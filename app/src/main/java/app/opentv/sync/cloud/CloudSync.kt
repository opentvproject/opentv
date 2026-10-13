/* SPDX-License-Identifier: GPL-3.0-or-later */
package app.opentv.sync.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import okhttp3.HttpUrl.Companion.toHttpUrl
import app.opentv.core.ServiceLocator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.security.KeyPair
import java.util.UUID

/** One coordinator per process. UI, local observation, Realtime, and workers share the same mutex. */
class CloudSync(
    private val context: Context,
    private val graph: ServiceLocator.Graph,
    val preferences: CloudPreferences = CloudPreferences(context),
    private val vault: CloudSecrets = CloudVault(context),
    private val store: CloudStore = CloudStore(context),
    private val enqueue: () -> Unit = { CloudSyncWorker.enqueue(context) },
    private val refreshCatalog: () -> Unit = { app.opentv.data.work.SyncWorker.refreshNow(context) },
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var realtime: CloudRealtime? = null
    private var observer: InvalidationTracker.Observer? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var scan: Job? = null
    private var started = false
    private val foreground = MutableStateFlow(false)
    fun setForeground(value: Boolean) { foreground.value = value }
    private var pairKeys: KeyPair? = null
    private var pairId: String? = null
    private var pairSecret: ByteArray? = null
    private var pairCipher: String? = null
    private var pairContext: String? = null
    private var cachedApi: SupabaseApi? = null
    @Synchronized fun api(): SupabaseApi {
        val current = cachedApi
        if (current != null && current.url == preferences.url && current.publishableKey == preferences.publishableKey) return current
        return SupabaseApi(preferences.url, preferences.publishableKey, vault, graph.httpClient).also { cachedApi = it }
    }
    private fun account(session: CloudSession) = "${preferences.url}|${session.userId}"
    private fun master(session: CloudSession): ByteArray? = vault.get("key:${account(session)}")?.let(CloudCrypto::decode)
    private fun keyCommitment(session: CloudSession, key: ByteArray) = CloudCrypto.hash(key + account(session).toByteArray())
    fun isSignedIn() = runCatching { api().storedSession() != null }.getOrDefault(false)
    fun hasKey() = runCatching { api().storedSession()?.let(::master) != null }.getOrDefault(false)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            preferences.enabled.collectLatest { enabled ->
                stopRealtime()
                if (enabled) {
                    installObservers()
                    CloudSyncWorker.schedule(context)
                    enqueue()
                    localChanged()
                } else {
                    uninstallObservers()
                    CloudSyncWorker.cancel(context)
                    preferences.status.value = "Cloud sync is off"
                }
            }
        }
        scope.launch {
            combine(preferences.enabled, foreground) { enabled, visible -> enabled && visible }.collectLatest { active ->
                stopRealtime()
                if (!active) return@collectLatest
                var backoff = 1000L
                while (isActive) {
                    try {
                        val api = api(); val session = api.session()
                        val connection = CloudRealtime(api, session, scope) { enqueue() }
                        realtime = connection; connection.connect()
                        val refreshIn = ((session.expiresAt - System.currentTimeMillis() / 1000 - 90) * 1000).coerceIn(1000, 1_800_000)
                        withTimeoutOrNull(refreshIn) { connection.disconnected.await() }
                        stopRealtime()
                        delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { stopRealtime(); delay(60_000) }
                }
            }
        }
    }
    private fun stopRealtime() { realtime?.close(); realtime = null }
    private fun installObservers() {
        if (observer != null) return
        observer = object : InvalidationTracker.Observer("sources", "channels", "movies", "series", "episodes", "profiles", "playback_positions", "epg_feeds") {
            override fun onInvalidated(tables: Set<String>) = localChanged()
        }.also { graph.database.invalidationTracker.addObserver(it) }
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> localChanged() }.also {
            context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(it)
        }
    }
    private fun uninstallObservers() {
        observer?.let { graph.database.invalidationTracker.removeObserver(it) }; observer = null
        prefsListener?.let { context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(it) }; prefsListener = null
    }
    @Synchronized fun localChanged() {
        if (!preferences.enabled.value) return
        scan?.cancel()
        scan = scope.launch {
            delay(750)
            try {
                val changed = mutex.withLock {
                    if (!preferences.enabled.value) return@withLock false
                    val session = api().storedSession() ?: return@withLock false
                    val key = master(session) ?: return@withLock false
                    if (!store.initialized(account(session))) return@withLock false
                    capture(session, key) || store.entries(account(session)).any { it.pending && CloudCategory.fromTable(it.record.table) in preferences.categories() }
                }
                if (changed) enqueue()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { preferences.status.value = "Could not queue cloud changes. Open Cloud Sync and check your session." }
        }
    }
    private suspend fun capture(session: CloudSession, key: ByteArray): Boolean {
        val namespace = account(session)
        val replica = CloudReplica(key, namespace, preferences.deviceId)
        val local = CloudLocalData(graph, store, namespace, key)
        val selected = preferences.categories()
        val entries = store.entries(namespace).associateBy { it.record.table to it.record.id }
        val snapshot = local.gather(selected).associateBy { it.table to it.id }
        var changed = false
        // Persist each operation before network activity; retries resend the exact encrypted envelope.
        for ((identity, item) in snapshot) {
            val existing = entries[identity]
            if (existing != null && !existing.applied && existing.baseline == null) continue
            val digest = replica.digest(item.item)
            if (existing == null || existing.baseline != digest || (existing.applied && existing.record.deleted) || (existing.pending && existing.record.writer != preferences.deviceId)) {
                store.save(namespace, replica.edit(item.table, item.id, item.item, existing, false, System.currentTimeMillis()))
                changed = true
            }
        }
        for ((identity, existing) in entries) {
            if (CloudCategory.fromTable(existing.record.table) !in selected || (!existing.applied && existing.baseline == null) || existing.record.deleted || identity in snapshot) continue
            val old = replica.content(existing.record)
            if (!local.canDelete(old)) continue
            store.save(namespace, replica.edit(existing.record.table, existing.record.id, old, existing, true, System.currentTimeMillis()))
            changed = true
        }
        return changed
    }
    suspend fun sync() = withContext(Dispatchers.IO) { mutex.withLock {
        if (!preferences.enabled.value) return@withLock
        preferences.status.value = "Synchronizing…"
        val api = api(); val session = api.session()
        val key = master(session) ?: error("Pair a device or enter your recovery key before enabling sync.")
        val namespace = account(session)
        val replica = CloudReplica(key, namespace, preferences.deviceId)
        val local = CloudLocalData(graph, store, namespace, key)
        if (store.initialized(namespace)) capture(session, key)
        // The backend validates the signed session_id, not a client-provided device ID alone.
        api.rpc("sync_authorize_device", buildJsonObject { put("device", preferences.deviceId) })
        val selected = preferences.categories()
        for (batch in store.entries(namespace).filter { it.pending && CloudCategory.fromTable(it.record.table) in selected }.chunked(100)) {
            api.rpc("sync_push", buildJsonObject {
                put("device", preferences.deviceId)
                put("records", JsonArray(batch.map { CloudReplica.json.encodeToJsonElement(CloudRecord.serializer(), it.record) }))
            })
            // Do not clear pending here: the pull acknowledges the actual server winner, including stale retries.
        }
        var cursor = store.cursor(namespace)
        while (true) {
            val page = api.rpc("sync_pull", buildJsonObject { put("device", preferences.deviceId); put("after_sequence", cursor) }).jsonArray
            if (page.isEmpty()) break
            if (store.initialized(namespace)) capture(session, key)
            val known = store.entries(namespace).associateBy { it.record.table to it.record.id }
            val incoming = page.map { CloudReplica.json.decodeFromJsonElement<CloudRecord>(it) }
            // Journal and cursor commit atomically. Materialization may retry after process death.
            store.transaction {
                for (record in incoming) store.save(namespace, replica.merge(known[record.table to record.id], record))
                cursor = incoming.maxOf { it.sequence }; store.cursor(namespace, cursor)
            }
        }
        // A newer local operation may have beaten an already-consumed remote row. Fetch exact winners
        // through push response so an offline loser never remains pending forever.
        val pending = store.entries(namespace).filter { it.pending && CloudCategory.fromTable(it.record.table) in selected }
        for (batch in pending.chunked(100)) {
            val winners = api.rpc("sync_push", buildJsonObject {
                put("device", preferences.deviceId); put("records", JsonArray(batch.map { CloudReplica.json.encodeToJsonElement(CloudRecord.serializer(), it.record) }))
            }).jsonArray
            val known = store.entries(namespace).associateBy { it.record.table to it.record.id }
            store.transaction { for (winner in winners) {
                val record = CloudReplica.json.decodeFromJsonElement<CloudRecord>(winner)
                store.save(namespace, replica.merge(known[record.table to record.id], record))
            } }
        }
        val materialized = graph.database.withTransaction {
            // Capture once while Room excludes competing writes. Network I/O has already ended.
            if (store.initialized(namespace)) capture(session, key)
            val ordered = store.entries(namespace).sortedBy { when (CloudCategory.fromTable(it.record.table)) {
                CloudCategory.PROVIDERS, CloudCategory.PLAYLISTS -> 0; CloudCategory.PROFILES -> 1; else -> 2
            } }
            val applied = mutableListOf<Pair<CloudEntry, CloudItem>>()
            for (entry in ordered) {
                if (CloudCategory.fromTable(entry.record.table) !in selected || entry.applied) continue
                val item = replica.content(entry.record)
                if (local.apply(entry.record, item, selected)) applied += entry to item
            }
            applied
        }
        // Acknowledge only AFTER Room commits. A crash before this point replays idempotent
        // materialization; the journal can never falsely report a rolled-back write as applied.
        store.transaction {
            for ((entry, item) in materialized) store.save(namespace, entry.copy(applied = true,
                baseline = if (entry.record.deleted) null else replica.digest(item)))
        }
        val catalogChanged = materialized.any { it.first.record.table in listOf("sync_providers", "sync_playlists") }
        store.cursor(namespace, cursor)
        store.markInitialized(namespace)
        capture(session, key)
        preferences.success(); preferences.status.value = "Up to date"
        if (catalogChanged) refreshCatalog()
        if (store.entries(namespace).any { it.pending && CloudCategory.fromTable(it.record.table) in selected }) enqueue()
    } }
    suspend fun configure(url: String, key: String, nickname: String) = withContext(Dispatchers.IO) { mutex.withLock {
        check(!isSignedIn())
        SupabaseApi(url.trim().trimEnd('/'), key.trim(), vault, graph.httpClient)
        preferences.configure(url.trim().toHttpUrl().toString().trimEnd('/'), key, nickname.ifBlank { android.os.Build.MODEL })
    } }
    suspend fun rename(nickname: String) = withContext(Dispatchers.IO) { mutex.withLock {
        require(nickname.isNotBlank() && nickname.length <= 80)
        api().rpc("sync_register_device", buildJsonObject { put("device", preferences.deviceId); put("nickname", nickname) })
        preferences.prefs.edit().putString("nickname", nickname).apply()
    } }
    suspend fun signIn(email: String, password: String, create: Boolean): String = withContext(Dispatchers.IO) { mutex.withLock {
        check(!preferences.enabled.value)
        val api = api()
        if (!api.signIn(email, password, create)) return@withLock "Check your email to confirm your account, then sign in."
        try {
            preferences.newDevice()
            api.rpc("sync_register_device", buildJsonObject { put("device", preferences.deviceId); put("nickname", preferences.nickname) })
            val session = api.session()
            val ring = api.request("rest/v1/sync_keyring?select=commitment").jsonArray
            if (ring.isEmpty()) {
                val key = CloudCrypto.randomKey()
                // Save first, so interruption between registration and local persistence cannot orphan a key.
                vault.put("key:${account(session)}", CloudCrypto.encode(key))
                api.rpc("sync_create_keyring", buildJsonObject { put("commitment", keyCommitment(session, key)) })
            } else master(session)?.let { verifyKey(api, session, it) }
            if (hasKey()) "Signed in. Save your recovery key before enabling cloud sync." else "Signed in. Pair with an existing device or enter your recovery key."
        } catch (e: Exception) {
            api.clearSession()
            throw e
        }
    } }
    private suspend fun verifyKey(api: SupabaseApi, session: CloudSession, key: ByteArray) {
        require(key.size == 32)
        val ring = api.request("rest/v1/sync_keyring?select=commitment").jsonArray.single().jsonObject.getValue("commitment").jsonPrimitive.content
        require(ring == keyCommitment(session, key)) { "That recovery key belongs to another account or backend." }
    }
    suspend fun recover(recovery: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val api = api(); val session = api.session(); val key = CloudCrypto.decode(recovery.trim())
        verifyKey(api, session, key); vault.put("key:${account(session)}", CloudCrypto.encode(key))
    } }
    suspend fun recoveryKey(): String = withContext(Dispatchers.IO) { mutex.withLock {
        CloudCrypto.encode(master(api().session()) ?: error("No encryption key is available on this device."))
    } }
    suspend fun devices(): JsonArray = withContext(Dispatchers.IO) { api().request("rest/v1/sync_devices?select=id,nickname,revoked_at,last_seen_at&order=created_at").jsonArray }
    suspend fun unlink(device: String) = withContext(Dispatchers.IO) { mutex.withLock {
        api().rpc("sync_unlink_device", buildJsonObject { put("device", device) })
        if (device == preferences.deviceId) {
            val session = api().storedSession()
            preferences.setEnabled(false); api().clearSession(); session?.let { vault.put("key:${account(it)}", null) }
        }
    } }
    suspend fun signOut() = withContext(Dispatchers.IO) { mutex.withLock {
        preferences.setEnabled(false)
        val api = api(); val session = api.storedSession()
        try { if (session != null) api.rpc("sync_unlink_device", buildJsonObject { put("device", preferences.deviceId) }) }
        finally {
            try { api.signOut() } finally { session?.let { vault.put("key:${account(it)}", null) }; clearPairing() }
        }
    } }
    suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        if (enabled) {
            val api = api(); val session = api.session()
            val key = master(session) ?: error("Pair a device or enter your recovery key first.")
            verifyKey(api, session, key)
        }
        preferences.setEnabled(enabled)
        if (enabled) enqueue()
    } }
    suspend fun beginPairing(): String = withContext(Dispatchers.IO) { mutex.withLock {
        clearPairing()
        pairKeys = CloudCrypto.pairingKeys()
        val response = api().rpc("sync_begin_pairing", buildJsonObject {
            put("device", preferences.deviceId); put("public_key", CloudCrypto.encode(pairKeys!!.public.encoded))
        }).jsonObject
        pairId = response.getValue("id").jsonPrimitive.content
        response.getValue("code").jsonPrimitive.content
    } }
    suspend fun approvePairing(code: String): String = withContext(Dispatchers.IO) { mutex.withLock {
        clearPairing()
        val api = api(); val session = api.session()
        val request = api.rpc("sync_find_pairing", buildJsonObject { put("code", code.trim().uppercase()) }).jsonObject
        val id = request.getValue("id").jsonPrimitive.content
        val peer = request.getValue("public_key").jsonPrimitive.content
        val keys = CloudCrypto.pairingKeys(); val ownPublic = CloudCrypto.encode(keys.public.encoded)
        val context = "${account(session)}|$id|$peer|$ownPublic"
        val secret = CloudCrypto.pairingSecret(keys, peer, context)
        val encrypted = CloudCrypto.seal(secret, CloudCrypto.encode(master(session) ?: error("This device has no encryption key.")), context)
        api.rpc("sync_approve_pairing", buildJsonObject { put("pairing", id); put("public_key", ownPublic); put("wrapped_key", encrypted) })
        CloudCrypto.verification(secret).also { secret.fill(0) }
    } }
    suspend fun pollPairing(): String? = withContext(Dispatchers.IO) { mutex.withLock {
        val id = pairId ?: return@withLock null
        val api = api(); val session = api.session()
        val response = api.rpc("sync_read_pairing", buildJsonObject { put("pairing", id) }).jsonObject
        val wrapped = response["wrapped_key"]?.jsonPrimitive?.contentOrNull ?: return@withLock null
        val peer = response.getValue("approver_public_key").jsonPrimitive.content
        val keys = pairKeys ?: error("Start pairing again.")
        val context = "${account(session)}|$id|${CloudCrypto.encode(keys.public.encoded)}|$peer"
        pairSecret?.fill(0)
        pairSecret = CloudCrypto.pairingSecret(keys, peer, context); pairCipher = wrapped; pairContext = context
        CloudCrypto.verification(pairSecret!!)
    } }
    suspend fun confirmPairing() = withContext(Dispatchers.IO) { mutex.withLock {
        val api = api(); val session = api.session()
        val key = CloudCrypto.decode(CloudCrypto.open(pairSecret ?: error("Wait for pairing approval."), pairCipher!!, pairContext!!))
        verifyKey(api, session, key)
        api.rpc("sync_consume_pairing", buildJsonObject { put("pairing", pairId!!) })
        vault.put("key:${account(session)}", CloudCrypto.encode(key)); key.fill(0); clearPairing()
    } }
    fun cancelPairing() { scope.launch { mutex.withLock { clearPairing() } } }
    private fun clearPairing() { pairSecret?.fill(0); pairSecret = null; pairKeys = null; pairId = null; pairCipher = null; pairContext = null }
}
