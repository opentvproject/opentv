/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import android.util.Log
import androidx.media3.common.MimeTypes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Local LAN HTTP upload server for external subtitles when the device (such as Android TV / Fire TV)
 * has no file picker app installed. Mirrors [PairingServer]'s local-only security model.
 */
class SubtitleUploadServer(
    private val scope: CoroutineScope,
    private val targetDir: File,
) {
    data class Session(
        val url: String,
        val host: String,
        val port: Int,
    ) {
        val shortUrl: String get() = "$host:$port"
    }

    sealed interface State {
        data object Idle : State
        data class Listening(val session: Session) : State
        data class Received(val file: File, val displayName: String, val mimeType: String) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var token: String = ""
    @Volatile private var running = false

    fun start() {
        if (running) return
        val host = lanAddress()
        if (host == null) {
            _state.value = State.Failed("No local network interface found.")
            return
        }

        val random = SecureRandom()
        token = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        val socket = try {
            ServerSocket(0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open subtitle upload socket", e)
            _state.value = State.Failed("Could not start local subtitle upload server.")
            return
        }

        serverSocket = socket
        running = true
        _state.value = State.Listening(
            Session(
                url = "http://$host:${socket.localPort}/?t=$token",
                host = host,
                port = socket.localPort,
            ),
        )

        thread(name = "opentv-sub-upload", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                runCatching { handle(client) }
                    .onFailure { Log.w(TAG, "Subtitle upload request failed", it) }
                runCatching { client.close() }
            }
        }

        scope.launch(Dispatchers.Default) {
            delay(TimeUnit.MINUTES.toMillis(SESSION_TIMEOUT_MINUTES))
            if (running && _state.value is State.Listening) {
                stop()
                _state.value = State.Failed("Upload session timed out.")
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        token = ""
        if (_state.value is State.Listening) _state.value = State.Idle
    }

    private fun handle(client: Socket) {
        client.soTimeout = SOCKET_TIMEOUT_MILLIS
        val output = client.getOutputStream()

        if (!PairingHttp.isPrivateAddress(client.inetAddress?.hostAddress)) {
            output.write(PairingHttp.httpResponse("403 Forbidden", "text/plain", "Local network only."))
            return
        }

        val input = client.getInputStream()
        val requestLine = readAsciiLine(input) ?: return
        val (method, path, query) = PairingHttp.parseRequestLine(requestLine) ?: return

        val headers = HashMap<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            PairingHttp.parseHeader(line)?.let { (name, value) -> headers[name] = value }
        }

        val supplied = query["t"] ?: ""
        if (token.isEmpty() || !PairingHttp.secretsMatch(supplied, token)) {
            output.write(PairingHttp.httpResponse("404 Not Found", "text/plain", "Not found."))
            return
        }

        when {
            method == "GET" -> {
                output.write(PairingHttp.httpResponse("200 OK", "text/html", uploadPageHtml(token)))
            }
            method == "POST" && path == "/upload" -> {
                val rawLength = headers["content-length"]?.toIntOrNull()
                if (rawLength == null || rawLength <= 0 || rawLength > MAX_UPLOAD_BYTES) {
                    output.write(
                        PairingHttp.httpResponse(
                            "400 Bad Request",
                            "text/plain",
                            "Subtitle file is empty or too large (max 5 MB).",
                        ),
                    )
                    return
                }
                val bytes = readExactBytes(input, rawLength)
                if (bytes == null) {
                    output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Incomplete upload."))
                    return
                }
                val rawName = query["name"]?.takeIf { it.isNotBlank() } ?: "subtitle.srt"
                val safeName = sanitizeSubtitleFileName(rawName)
                val mimeType = subtitleMimeTypeForName(safeName, headers["content-type"])
                if (mimeType == null) {
                    output.write(
                        PairingHttp.httpResponse(
                            "415 Unsupported Media Type",
                            "text/plain",
                            "Unsupported subtitle format. Choose SRT, VTT, SSA, ASS, or TTML.",
                        ),
                    )
                    return
                }
                targetDir.mkdirs()
                val outFile = File(targetDir, "upload_${System.currentTimeMillis()}_$safeName")
                outFile.writeBytes(bytes)
                output.write(PairingHttp.httpResponse("200 OK", "text/plain", "OK"))
                output.flush()

                _state.value = State.Received(outFile, safeName, mimeType)
                running = false
                runCatching { serverSocket?.close() }
                serverSocket = null
                token = ""
            }
            else -> {
                output.write(PairingHttp.httpResponse("405 Method Not Allowed", "text/plain", "No."))
            }
        }
    }

    private fun lanAddress(): String? {
        val candidates = mutableListOf<InetAddress>()
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.inetAddresses) {
                    if (address.isLoopbackAddress) continue
                    if (address.hostAddress?.contains(':') == true) continue
                    if (PairingHttp.isPrivateAddress(address.hostAddress)) candidates += address
                }
            }
        }
        return candidates
            .sortedBy { it.hostAddress?.startsWith("169.254") == true }
            .firstOrNull()
            ?.hostAddress
    }

    companion object {
        private const val TAG = "SubtitleUploadServer"
        private const val SESSION_TIMEOUT_MINUTES = 5L
        private const val SOCKET_TIMEOUT_MILLIS = 30_000
        private const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024
        private const val MAX_LINE_BYTES = 8 * 1024

        internal fun readAsciiLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (buf.size() < MAX_LINE_BYTES) {
                val b = input.read()
                if (b == -1) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
                if (b == '\n'.code) break
                if (b != '\r'.code) buf.write(b)
            }
            return buf.toString(Charsets.UTF_8.name())
        }

        internal fun readExactBytes(input: InputStream, length: Int): ByteArray? {
            val result = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(result, offset, length - offset)
                if (read <= 0) return null
                offset += read
            }
            return result
        }

        internal fun sanitizeSubtitleFileName(rawName: String): String {
            val base = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
            val cleaned = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return cleaned.ifBlank { "subtitle.srt" }
        }

        fun subtitleMimeTypeForName(displayName: String?, providerType: String? = null): String? {
            val name = displayName.orEmpty().substringBefore('?').substringBefore('#')
                .lowercase(Locale.ROOT)
            val type = providerType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            return when {
                name.endsWith(".srt") || type in setOf("application/x-subrip", "application/subrip") ->
                    MimeTypes.APPLICATION_SUBRIP
                name.endsWith(".vtt") || type == MimeTypes.TEXT_VTT -> MimeTypes.TEXT_VTT
                name.endsWith(".ass") || name.endsWith(".ssa") || type == MimeTypes.TEXT_SSA ->
                    MimeTypes.TEXT_SSA
                name.endsWith(".ttml") || name.endsWith(".xml") || type == MimeTypes.APPLICATION_TTML ->
                    MimeTypes.APPLICATION_TTML
                else -> null
            }
        }

        private fun uploadPageHtml(token: String): String = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>OpenTV — Upload Subtitle</title>
              <style>
                body { font-family: system-ui, sans-serif; background: #10141c; color: #f1f5f9;
                       max-width: 460px; margin: 40px auto; padding: 0 20px; }
                .card { background: #1a2130; border-radius: 14px; padding: 24px; }
                h1 { font-size: 1.3rem; margin: 0 0 8px; }
                p { color: #94a3b8; font-size: 0.95rem; line-height: 1.4; }
                input[type=file] { width: 100%; margin: 16px 0; color: #cbd5e1; }
                button { width: 100%; padding: 12px; border: 0; border-radius: 10px;
                         background: #3b82f6; color: #fff; font-size: 1rem; font-weight: 600; }
                #status { margin-top: 14px; font-size: 0.95rem; }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>Send subtitle to TV</h1>
                <p>Choose an SRT, VTT, SSA, ASS or TTML subtitle file on this device.</p>
                <input id="file" type="file" accept=".srt,.vtt,.ass,.ssa,.ttml,.xml,text/*">
                <button id="send" type="button">Upload subtitle</button>
                <div id="status"></div>
              </div>
              <script>
                const btn = document.getElementById('send');
                const input = document.getElementById('file');
                const status = document.getElementById('status');
                btn.onclick = async () => {
                  const f = input.files[0];
                  if (!f) { status.textContent = 'Please choose a subtitle file first.'; return; }
                  btn.disabled = true;
                  status.textContent = 'Uploading…';
                  try {
                    const url = '/upload?t=' + encodeURIComponent('$token') + '&name=' + encodeURIComponent(f.name);
                    const res = await fetch(url, { method: 'POST', body: f });
                    if (res.ok) {
                      status.textContent = 'Sent to your TV!';
                    } else {
                      status.textContent = await res.text();
                      btn.disabled = false;
                    }
                  } catch (e) {
                    status.textContent = 'Upload failed: ' + e.message;
                    btn.disabled = false;
                  }
                };
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
