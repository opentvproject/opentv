package app.opentv.sync.cloud

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CloudRealtimeTest {
    @Test fun `closed and failed connections cancel a queued change hint`() = runTest {
        val secrets = object : CloudSecrets {
            override fun get(name: String): String? = null
            override fun put(name: String, value: String?) = Unit
        }
        val api = SupabaseApi("https://example.test", "sb_publishable_test_public_key", secrets, OkHttpClient())
        val session = CloudSession("test-jwt", "test-refresh", 1, "test-user", "test-session")
        val socket = object : WebSocket {
            override fun request() = Request.Builder().url("https://example.test").build()
            override fun queueSize() = 0L
            override fun send(text: String) = true
            override fun send(bytes: ByteString) = true
            override fun close(code: Int, reason: String?) = true
            override fun cancel() = Unit
        }
        for (failed in listOf(false, true)) {
            var changes = 0
            val realtime = CloudRealtime(api, session, this) { changes++ }
            realtime.onMessage(socket, "{\"event\":\"postgres_changes\"}")
            runCurrent()
            if (failed) realtime.onFailure(socket, java.io.IOException("test failure"), null)
            else realtime.onClosed(socket, 1000, "test close")
            advanceTimeBy(1001); runCurrent()
            assertTrue(realtime.disconnected.isCompleted)
            assertEquals(0, changes)
        }
    }
}
