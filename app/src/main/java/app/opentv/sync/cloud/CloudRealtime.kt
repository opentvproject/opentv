/* SPDX-License-Identifier: GPL-3.0-or-later */
package app.opentv.sync.cloud

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.atomic.AtomicLong

/** Foreground hints only. Durable reconciliation, not WebSocket delivery, decides what is applied. */
class CloudRealtime(
    private val api: SupabaseApi,
    private val session: CloudSession,
    private val scope: CoroutineScope,
    private val changed: () -> Unit,
) : WebSocketListener() {
    val disconnected = CompletableDeferred<Unit>()
    private var socket: WebSocket? = null
    private var heartbeat: Job? = null
    private var hint: Job? = null
    private val ref = AtomicLong()
    private val topic = "realtime:opentv:${session.userId}"
    fun connect() {
        val url = "${api.url}/realtime/v1/websocket".toHttpUrl().newBuilder()
            .addQueryParameter("apikey", api.publishableKey).addQueryParameter("vsn", "1.0.0").build()
        socket = api.http.newWebSocket(Request.Builder().url(url).build(), this)
    }
    override fun onOpen(webSocket: WebSocket, response: Response) {
        send(webSocket, topic, "phx_join", buildJsonObject {
            put("access_token", session.accessToken)
            put("config", buildJsonObject {
                put("broadcast", buildJsonObject { put("self", false); put("ack", false) })
                put("presence", buildJsonObject { put("key", "") })
                put("postgres_changes", JsonArray((CloudCategory.entries.map { it.table } + "sync_devices").map { table ->
                    buildJsonObject { put("event", "*"); put("schema", "public"); put("table", table); put("filter", "user_id=eq.${session.userId}") }
                }))
            })
        })
        heartbeat = scope.launch { while (isActive) { delay(25_000); send(webSocket, "phoenix", "heartbeat", buildJsonObject {}) } }
        // Reconnect fills any gap through the outbox/inbox cursor.
        changed()
    }
    private fun send(ws: WebSocket, topic: String, event: String, payload: JsonObject) = ws.send(buildJsonObject {
        put("topic", topic); put("event", event); put("payload", payload); put("ref", ref.incrementAndGet().toString())
    }.toString())
    @Synchronized override fun onMessage(webSocket: WebSocket, text: String) {
        val event = runCatching { CloudReplica.json.parseToJsonElement(text).jsonObject["event"]?.jsonPrimitive?.content }.getOrNull()
        if (event == "postgres_changes") {
            hint?.cancel(); hint = scope.launch { delay(1000); changed() }
        } else if (event == "phx_error" || event == "phx_close") close()
    }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { heartbeat?.cancel(); hint?.cancel(); disconnected.complete(Unit) }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { heartbeat?.cancel(); hint?.cancel(); disconnected.complete(Unit) }
    fun close() { disconnected.complete(Unit); heartbeat?.cancel(); hint?.cancel(); socket?.close(1000, ""); socket = null }
}
