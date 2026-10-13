package app.opentv.sync.cloud

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SupabaseApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SupabaseApi
    private val user = "10000000-0000-4000-8000-000000000001"
    private val sessionId = "20000000-0000-4000-8000-000000000001"
    private val values = mutableMapOf<String, String>()
    private val vault = object : CloudSecrets {
        override fun get(name: String) = values[name]
        override fun put(name: String, value: String?) { if (value == null) values.remove(name) else values[name] = value }
    }
    @Before fun setup() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        api = SupabaseApi(server.url("/").toString(), "sb_publishable_test_public_key", vault, http)
    }
    @After fun cleanup() { server.shutdown() }
    private fun response(expiration: Long, refresh: String = "rotated-refresh"): JsonObject = buildJsonObject {
        val claims = buildJsonObject { put("sub", user); put("exp", expiration); put("session_id", sessionId) }
        put("access_token", "header.${CloudCrypto.encode(claims.toString().toByteArray())}.signature")
        put("refresh_token", refresh); put("user", buildJsonObject { put("id", user) })
    }
    private fun persist(expiration: Long) {
        val payload = response(expiration, "old-refresh")
        val session = CloudSession(payload.getValue("access_token").jsonPrimitive.content, "old-refresh", expiration, user, sessionId)
        vault.put(api.sessionStorage, CloudReplica.json.encodeToString(CloudSession.serializer(), session))
    }
    @Test fun `expired sessions refresh once and durably save rotated token`() = runTest {
        persist(1)
        server.enqueue(MockResponse().setBody(response(System.currentTimeMillis()/1000+3600).toString()))
        val sessions = List(4) { async { api.session() } }.awaitAll()
        assertTrue(sessions.all { it.refreshToken == "rotated-refresh" })
        assertEquals("rotated-refresh", api.storedSession()!!.refreshToken)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=refresh_token", request.path)
        assertEquals("sb_publishable_test_public_key", request.getHeader("apikey"))
        assertNull(request.getHeader("Authorization"))
    }
    @Test fun `failed refresh retains saved session and queued content`() = runTest {
        persist(1)
        server.enqueue(MockResponse().setResponseCode(503))
        try { api.session(); fail() } catch (e: CloudApiException) { assertEquals(503, e.status) }
        assertEquals("old-refresh", api.storedSession()!!.refreshToken)
    }
    @Test fun `unauthorized access refreshes then retries with user JWT`() = runTest {
        persist(System.currentTimeMillis()/1000+3600)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(response(System.currentTimeMillis()/1000+3600).toString()))
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals(JsonArray(emptyList()), api.request("rest/v1/sync_devices"))
        assertTrue(server.takeRequest().getHeader("Authorization")!!.startsWith("Bearer header."))
        assertEquals("/auth/v1/token?grant_type=refresh_token", server.takeRequest().path)
        assertTrue(server.takeRequest().getHeader("Authorization")!!.startsWith("Bearer header."))
    }
    @Test fun `signout clears local secrets when remote logout fails`() = runTest {
        persist(System.currentTimeMillis()/1000+3600)
        server.enqueue(MockResponse().setResponseCode(503))
        api.signOut()
        assertNull(api.storedSession())
    }
    @Test fun `signout sends the expired stored token once without refreshing`() = runTest {
        persist(1)
        val token = api.storedSession()!!.accessToken
        server.enqueue(MockResponse().setResponseCode(401))
        api.signOut()
        assertNull(api.storedSession())
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/auth/v1/logout?scope=local", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
    }
    @Test fun `signout with no session does not contact the backend`() = runTest {
        api.signOut()
        assertNull(api.storedSession())
        assertEquals(0, server.requestCount)
    }
    @Test fun `backend cannot redirect credentials to another host`() = runTest {
        persist(System.currentTimeMillis()/1000+3600)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.invalid/"))
        try { api.request("rest/v1/sync_devices"); fail() } catch (e: CloudApiException) { assertEquals(302, e.status) }
        assertEquals(1, server.requestCount)
    }
    @Test fun `rejects cleartext privileged keys and URLs containing credentials or paths`() {
        for (bad in listOf("http://example.test", "https://user:password@example.test", "https://example.test/rest/v1", "https://example.test?token=x")) {
            assertThrows(IllegalArgumentException::class.java) { SupabaseApi(bad, "sb_publishable_test_public_key", vault, OkHttpClient()) }
        }
        for (bad in listOf("sb_secret_privileged", "eyJ.service_role.jwt")) {
            assertThrows(IllegalArgumentException::class.java) { SupabaseApi("https://example.test", bad, vault, OkHttpClient()) }
        }
    }
}
