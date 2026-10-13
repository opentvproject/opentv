package app.opentv.sync.cloud

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CloudCoordinatorTest {
    private val key = CloudCrypto.randomKey()
    private val user = "10000000-0000-4000-8000-000000000001"
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var origin: String
    private val records = mutableMapOf<Pair<String,String>, CloudRecord>()
    private var sequence = 0L
    private var offline = false
    private var setupFailure: String? = null
    private val databases = mutableListOf<OpenTvDatabase>()
    private val stores = mutableListOf<CloudStore>()
    private data class Device(val graph: ServiceLocator.Graph, val cloud: CloudSync, val store: CloudStore)
    @Before fun setup() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (offline) return MockResponse().setResponseCode(503)
                    if (request.path == setupFailure) return MockResponse().setResponseCode(503)
                    val body = if (request.body.size > 0) CloudReplica.json.parseToJsonElement(request.body.readUtf8()).jsonObject else buildJsonObject {}
                    val response = when (request.path) {
                        "/auth/v1/token?grant_type=password" -> {
                            val session = UUID.randomUUID().toString()
                            val claims = buildJsonObject { put("exp", System.currentTimeMillis()/1000+3600); put("session_id", session) }
                            buildJsonObject {
                                put("access_token", "header.${CloudCrypto.encode(claims.toString().toByteArray())}.signature")
                                put("refresh_token", "test-refresh")
                                put("user", buildJsonObject { put("id", user) })
                            }
                        }
                        "/rest/v1/rpc/sync_register_device", "/rest/v1/rpc/sync_create_keyring" -> JsonNull
                        "/rest/v1/sync_keyring?select=commitment" -> JsonArray(emptyList())
                        "/rest/v1/rpc/sync_authorize_device" -> JsonNull
                        "/rest/v1/rpc/sync_push" -> {
                            JsonArray(body.getValue("records").jsonArray.map { encoded ->
                                val candidate = CloudReplica.json.decodeFromJsonElement<CloudRecord>(encoded)
                                val identity = candidate.table to candidate.id
                                val old = records[identity]
                                if (old == null || candidate.newerThan(old)) records[identity] = candidate.copy(sequence = ++sequence)
                                CloudReplica.json.encodeToJsonElement(CloudRecord.serializer(), records.getValue(identity))
                            })
                        }
                        "/rest/v1/rpc/sync_pull" -> JsonArray(records.values.filter { it.sequence > body.getValue("after_sequence").jsonPrimitive.long }
                            .sortedBy { it.sequence }.take(200).map { CloudReplica.json.encodeToJsonElement(CloudRecord.serializer(), it) })
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody(response.toString())
                }
            }
            start()
        }
        origin = server.url("/").toString().trimEnd('/')
    }
    @After fun cleanup() { server.shutdown(); stores.forEach { it.close() }; databases.forEach { it.close() } }
    private fun device(): Device {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = UUID.randomUUID().toString()
        val database = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).allowMainThreadQueries().build().also { databases += it }
        val graph = ServiceLocator.Graph(context, database, http)
        val prefs = CloudPreferences(context, "test-$name").apply {
            configure(origin, "sb_publishable_test_public_key", name)
            setCategories(setOf(CloudCategory.PROVIDERS, CloudCategory.PLAYLISTS))
            setEnabled(true)
        }
        val secretValues = mutableMapOf<String,String>()
        val secrets = object : CloudSecrets {
            override fun get(name: String) = secretValues[name]
            override fun put(name: String, value: String?) { if (value == null) secretValues.remove(name) else secretValues[name] = value }
        }
        val api = SupabaseApi(origin, prefs.publishableKey, secrets, http)
        secrets.put(api.sessionStorage, CloudReplica.json.encodeToString(CloudSession.serializer(), CloudSession(
            "test-user-jwt", "test-refresh", System.currentTimeMillis()/1000+3600, user, UUID.randomUUID().toString())))
        secrets.put("key:$origin|$user", CloudCrypto.encode(key))
        val store = CloudStore(context, "journal-$name.db").also { stores += it }
        return Device(graph, CloudSync(context, graph, prefs, secrets, store, {}, {}), store)
    }
    @Test fun `complete coordinator synchronizes two Room devices and propagates offline deletion`() = runTest {
        val a = device(); val b = device()
        val id = a.graph.sourceRepository.save(Source(name = "Authorized", kind = SourceKind.XTREAM, url = "https://provider.test", username = "user", password = "private"))
        a.cloud.sync(); a.cloud.sync(); b.cloud.sync()
        assertEquals("private", b.graph.database.sources().all().single().password)
        val firstId = records.values.single().id
        a.graph.sourceRepository.save(a.graph.sourceRepository.byId(id)!!.copy(password = "updated", name = "Renamed"))
        a.cloud.sync(); b.cloud.sync()
        assertEquals("updated", b.graph.database.sources().all().single().password)
        assertEquals(firstId, records.values.single().id)
        offline = true
        a.graph.database.sources().delete(id)
        try { a.cloud.sync(); fail() } catch (_: CloudApiException) { }
        val outbox = a.store.entries("$origin|$user").single()
        assertTrue(outbox.pending); assertTrue(outbox.record.deleted)
        assertEquals(1, b.graph.database.sources().all().size)
        offline = false
        a.cloud.sync(); b.cloud.sync(); b.cloud.sync()
        assertTrue(b.graph.database.sources().all().isEmpty())
        assertTrue(records.values.single().deleted)
        assertFalse(a.store.entries("$origin|$user").single().pending)
    }
    @Test fun `initial cloud settings beat fresh local defaults and disabled sync is silent`() = runTest {
        val d = device(); d.cloud.preferences.setCategories(setOf(CloudCategory.SETTINGS))
        d.graph.settings.setThemeMode(AppSettings.ThemeMode.DARK)
        val replica = CloudReplica(key, "$origin|$user", UUID.randomUUID().toString())
        val item = CloudItem("preference", mapOf("key" to "theme", "value" to "LIGHT"))
        val table = CloudCategory.SETTINGS.table
        val record = replica.edit(table, CloudCrypto.stableId(key, table, "theme"), item, null, false, 100).record.copy(sequence = ++sequence)
        records[table to record.id] = record
        d.cloud.sync(); d.cloud.sync()
        assertEquals(AppSettings.ThemeMode.LIGHT, d.graph.settings.themeMode.value)
        assertEquals("LIGHT", replica.content(records.getValue(table to record.id)).values["value"])
        val requests = server.requestCount
        d.cloud.preferences.setEnabled(false); d.cloud.sync()
        assertEquals(requests, server.requestCount)
    }
    @Test fun `offline edit remains queued across failure and is sent on retry`() = runTest {
        val d = device()
        val id = d.graph.sourceRepository.save(Source(name = "First", kind = SourceKind.M3U, url = "https://playlist.test/live.m3u8"))
        d.cloud.sync(); d.cloud.sync()
        d.graph.sourceRepository.save(d.graph.sourceRepository.byId(id)!!.copy(name = "Offline edit"))
        offline = true
        try { d.cloud.sync(); fail() } catch (_: CloudApiException) { }
        assertTrue(d.store.entries("$origin|$user").single().pending)
        offline = false
        d.cloud.sync(); d.cloud.sync()
        assertEquals("Offline edit", CloudReplica(key, "$origin|$user", d.cloud.preferences.deviceId).content(records.values.single()).values["name"])
        assertEquals(1, records.size)
    }
    @Test fun `failed post-login setup clears session and allows a successful retry`() = runTest {
        for (stage in listOf("/rest/v1/rpc/sync_register_device", "/rest/v1/sync_keyring?select=commitment", "/rest/v1/rpc/sync_create_keyring")) {
            val d = device()
            d.cloud.preferences.setEnabled(false)
            setupFailure = stage
            try { d.cloud.signIn("test@example.test", "test-password", false); fail() }
            catch (e: CloudApiException) { assertEquals(503, e.status) }
            assertFalse(d.cloud.isSignedIn())
            setupFailure = null
            assertEquals("Signed in. Save your recovery key before enabling cloud sync.", d.cloud.signIn("test@example.test", "test-password", false))
            assertTrue(d.cloud.isSignedIn())
        }
    }
}
