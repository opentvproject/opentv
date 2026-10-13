/* SPDX-License-Identifier: GPL-3.0-or-later */
package app.opentv.sync.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class CloudSession(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: Long,
    val userId: String,
    val sessionId: String,
)

class CloudApiException(val status: Int) : Exception(when (status) {
    400 -> "Check your email, password, and backend configuration."
    401 -> "Your session expired. Sign in again; your local data is safe."
    403 -> "This device is unlinked or backend access is denied. Sign in again on an authorized device."
    409 -> "This account already has an encryption key. Pair a device or enter your recovery key."
    429 -> "Too many requests. Wait a few minutes and try again."
    else -> "The cloud service is unavailable. Changes will retry when it is reachable."
})

/** Supported GoTrue and PostgREST APIs. No request logging, redirects, or privileged API keys. */
class SupabaseApi(
    val url: String,
    val publishableKey: String,
    private val vault: CloudSecrets,
    baseClient: OkHttpClient,
) {
    val http = baseClient.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(45, TimeUnit.SECONDS).build()
    private val json = CloudReplica.json
    private val sessionMutex = Mutex()
    val sessionStorage = "session:${CloudCrypto.hash(url.toByteArray())}"
    init {
        val parsed = url.toHttpUrl()
        require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.encodedPath == "/" && parsed.query == null && parsed.fragment == null) {
            "Use the HTTPS Supabase project origin, without a path or credentials."
        }
        require(publishableKey.startsWith("sb_publishable_") && publishableKey.length in 25..256) { "Use a publishable key (sb_publishable_), never a secret or service-role key." }
    }
    fun storedSession(): CloudSession? = vault.get(sessionStorage)?.let { json.decodeFromString(it) }
    private fun persist(session: CloudSession?) = vault.put(sessionStorage, session?.let { json.encodeToString(CloudSession.serializer(), it) })
    private fun readSession(response: JsonObject): CloudSession {
        val token = response.getValue("access_token").jsonPrimitive.content
        // Claims are used for local namespaces only. Supabase verifies the signature for authorization.
        val claims = json.parseToJsonElement(String(CloudCrypto.decode(token.split('.')[1]), Charsets.UTF_8)).jsonObject
        return CloudSession(token, response.getValue("refresh_token").jsonPrimitive.content,
            claims.getValue("exp").jsonPrimitive.long, response.getValue("user").jsonObject.getValue("id").jsonPrimitive.content,
            claims.getValue("session_id").jsonPrimitive.content)
    }
    suspend fun signIn(email: String, password: String, create: Boolean): Boolean = sessionMutex.withLock {
        val response = raw(if (create) "auth/v1/signup" else "auth/v1/token?grant_type=password", "POST",
            buildJsonObject { put("email", email.trim()); put("password", password) }, null).jsonObject
        if (response["access_token"] == null) false else { persist(readSession(response)); true }
    }
    suspend fun session(forceRefresh: Boolean = false): CloudSession = sessionMutex.withLock {
        val current = storedSession() ?: throw CloudApiException(401)
        if (!forceRefresh && current.expiresAt > System.currentTimeMillis() / 1000 + 90) return@withLock current
        // Persist rotated refresh token BEFORE making another request. A network failure never clears it.
        val refreshed = readSession(raw("auth/v1/token?grant_type=refresh_token", "POST",
            buildJsonObject { put("refresh_token", current.refreshToken) }, null).jsonObject)
        require(refreshed.userId == current.userId && refreshed.sessionId == current.sessionId)
        persist(refreshed); refreshed
    }
    suspend fun signOut() {
        try {
            storedSession()?.let { raw("auth/v1/logout?scope=local", "POST", buildJsonObject {}, it.accessToken) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Remote logout is best-effort; local cleanup is unconditional. */ }
        finally { persist(null) }
    }
    fun clearSession() = persist(null)
    suspend fun request(path: String, method: String = "GET", body: JsonElement? = null): JsonElement {
        val current = session()
        return try { raw(path, method, body, current.accessToken) }
        catch (e: CloudApiException) {
            if (e.status != 401) throw e
            raw(path, method, body, session(forceRefresh = true).accessToken)
        }
    }
    suspend fun rpc(name: String, body: JsonElement) = request("rest/v1/rpc/$name", "POST", body)
    private suspend fun raw(path: String, method: String, body: JsonElement?, token: String?): JsonElement = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url("${url.trimEnd('/')}/$path").header("apikey", publishableKey)
        if (token != null) builder.header("Authorization", "Bearer $token")
        builder.method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
        try {
            http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw CloudApiException(response.code)
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
            }
        } catch (e: CancellationException) { throw e }
    }
}
