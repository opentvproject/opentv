/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.diagnostics

import java.net.Socket
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** What the device is connected through right now. Stamped on every measurement. */
data class NetworkState(val online: Boolean, val vpn: Boolean, val transport: String) {
    companion object {
        fun read(context: Context): NetworkState {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
                ?: return NetworkState(online = false, vpn = false, transport = "")
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
            return NetworkState(
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                transport = transport,
            )
        }
    }
}

/**
 * Active measurements: the user's internet in general, the route to a provider's portal, and one
 * stream in particular.
 *
 * The three are measured separately on purpose. "It buffers" has three different owners — the
 * house's connection, the provider's server, and the path between them — and the only way to say
 * which is to have a number for each. A fast internet test next to a slow stream test is the
 * evidence that the provider (or the ISP's treatment of it) is the bottleneck.
 */
class NetworkProbe(private val context: Context, baseClient: OkHttpClient) {

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Panels start a channel on demand, and a cold one can take most of twenty seconds to send
        // its first byte. Giving up sooner reports a stream as dead that would have played.
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        // Its own pool, emptied before each test, so every measurement pays for its own lookup and
        // connection instead of riding one the player (or the last test) left open.
        .connectionPool(okhttp3.ConnectionPool(2, 30, TimeUnit.SECONDS))
        .followRedirects(true)
        .followSslRedirects(true)
        .cache(null)
        .build()

    /**
     * The internet baseline, against a neutral endpoint that has nothing to do with any provider.
     * [onProgress] receives the running download rate in bits per second.
     */
    suspend fun internet(onProgress: (Long) -> Unit = {}): ProbeResult = withContext(Dispatchers.IO) {
        val net = NetworkState.read(context)
        val base = ProbeResult(
            kind = ProbeResult.KIND_INTERNET, label = "Internet", host = BASELINE_HOST,
            atMillis = System.currentTimeMillis(), ok = false, vpn = net.vpn, transport = net.transport,
        )
        try {
            // The first request pays for DNS, TCP and TLS; the rest reuse the connection, so their
            // round trips are latency and nothing else.
            client.connectionPool.evictAll()
            val cold = Timings()
            call(cold, "$BASELINE_URL?bytes=0").use { it.body?.bytes() }
            // Round trips are timed as bare TCP handshakes, not HTTP requests: a request's time
            // includes however long the server took to answer, which swings by 50 ms and more on
            // a perfect line and used to be reported as an unsteady connection.
            val address = InetAddress.getByName(BASELINE_HOST)
            val rtts = (1..LATENCY_SAMPLES).map {
                val start = System.nanoTime()
                Socket().use { it.connect(InetSocketAddress(address, 443), LATENCY_TIMEOUT_MILLIS) }
                (System.nanoTime() - start) / 1_000_000
            }
            val jitter = rtts.zipWithNext { a, b -> abs(a - b) }.average().toLong()

            // Download in fixed chunks until the time budget is spent, so a slow line is not made
            // to sit through a file sized for a fast one.
            val meter = RateMeter(DOWNLOAD_BUDGET_MILLIS, DOWNLOAD_WARMUP_MILLIS, onProgress)
            while (!meter.done) {
                call(Timings(), "$BASELINE_URL?bytes=$DOWNLOAD_CHUNK_BYTES").use { meter.drain(it) }
            }
            base.copy(
                ok = true,
                dnsMillis = cold.dnsMillis, connectMillis = cold.connectMillis, tlsMillis = cold.tlsMillis,
                ttfbMillis = cold.ttfbMillis,
                latencyMillis = rtts.sorted()[rtts.size / 2], jitterMillis = jitter,
                throughputBps = meter.bitsPerSecond, bytes = meter.totalBytes,
            )
        } catch (e: Exception) {
            base.copy(error = describe(e))
        }
    }

    /** How long the provider's own server takes to answer at all — DNS, connect, TLS, first byte. */
    suspend fun portal(sourceId: Long, name: String, url: String, userAgent: String): ProbeResult =
        withContext(Dispatchers.IO) {
            val net = NetworkState.read(context)
            val base = ProbeResult(
                kind = ProbeResult.KIND_PORTAL, sourceId = sourceId, label = name,
                host = url.toHttpUrlOrNull()?.host.orEmpty(),
                atMillis = System.currentTimeMillis(), ok = false, vpn = net.vpn, transport = net.transport,
            )
            try {
                client.connectionPool.evictAll()
                val t = Timings()
                // Headers are all that is needed; the body (possibly a 40,000-line playlist) is
                // dropped unread when the response closes.
                call(t, url, userAgent).use { response ->
                    base.copy(
                        // Any answer at all proves the route works; a 4xx on the bare portal URL is
                        // normal and says nothing about whether streams will play.
                        ok = true, httpCode = response.code, host = response.request.url.host,
                        dnsMillis = t.dnsMillis, connectMillis = t.connectMillis, tlsMillis = t.tlsMillis,
                        ttfbMillis = t.ttfbMillis,
                    )
                }
            } catch (e: Exception) {
                base.copy(error = describe(e))
            }
        }

    /**
     * Pulls a few seconds of one stream and reports how fast it arrived.
     *
     * For HLS the newest segments are downloaded flat out, which gives both the link's real speed
     * to that server and the bitrate the stream needs — the ratio is the headroom. A raw MPEG-TS
     * stream is paced by the server, so its arrival rate only says whether the server is keeping
     * up; the bitrate cannot be separated from it and is left unknown.
     *
     * This opens a real connection to the provider. Callers must not run it while something is
     * playing: most accounts allow one connection, and the probe would knock the picture off.
     */
    suspend fun stream(
        sourceId: Long,
        channelId: Long,
        label: String,
        url: String,
        userAgent: String,
    ): ProbeResult = withContext(Dispatchers.IO) {
        val net = NetworkState.read(context)
        val base = ProbeResult(
            kind = ProbeResult.KIND_STREAM, sourceId = sourceId, channelId = channelId, label = label,
            host = url.toHttpUrlOrNull()?.host.orEmpty(),
            atMillis = System.currentTimeMillis(), ok = false, vpn = net.vpn, transport = net.transport,
        )
        try {
            client.connectionPool.evictAll()
            val t = Timings()
            call(t, url, userAgent).use { response ->
                val timed = base.copy(
                    httpCode = response.code, host = response.request.url.host,
                    dnsMillis = t.dnsMillis, connectMillis = t.connectMillis, tlsMillis = t.tlsMillis,
                    ttfbMillis = t.ttfbMillis,
                )
                if (!response.isSuccessful) {
                    return@withContext timed.copy(error = "The server answered HTTP ${response.code}")
                }
                val body = response.body ?: return@withContext timed.copy(error = "Empty response")
                val isPlaylist = body.contentType()?.subtype?.contains("mpegurl", ignoreCase = true) == true ||
                    response.request.url.encodedPath.endsWith(".m3u8", ignoreCase = true) ||
                    runCatching { body.source().peek().readUtf8(7) == "#EXTM3U" }.getOrDefault(false)

                if (isPlaylist) {
                    val text = body.string()
                    probeHls(timed, response.request.url, text, userAgent)
                } else {
                    val meter = RateMeter(STREAM_BUDGET_MILLIS, warmupMillis = 0)
                    meter.drain(response, STREAM_MAX_BYTES)
                    if (meter.totalBytes == 0L) timed.copy(error = "Connected, but the server sent no video")
                    else timed.copy(ok = true, throughputBps = meter.bitsPerSecond, bytes = meter.totalBytes)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("OpenTVDiag", "Stream test of $label failed: ${e.javaClass.simpleName}: ${e.message}")
            base.copy(error = describe(e))
        }
    }

    private fun probeHls(base: ProbeResult, playlistUrl: HttpUrl, text: String, userAgent: String): ProbeResult {
        var url = playlistUrl
        var playlist = HlsPlaylist.parse(text)
        if (playlist.variants.isNotEmpty()) {
            // A master playlist: follow the heaviest rendition, the one the player will reach for.
            val variant = playlist.variants.maxByOrNull { it.bandwidth } ?: playlist.variants.first()
            url = url.resolve(variant.uri) ?: return base.copy(error = "Unreadable playlist")
            playlist = call(Timings(), url.toString(), userAgent).use { HlsPlaylist.parse(it.body?.string().orEmpty()) }
        }
        val segments = playlist.segments.takeLast(HLS_SEGMENTS)
        if (segments.isEmpty()) return base.copy(error = "The playlist lists no video segments")

        var bytes = 0L
        var nanos = 0L
        var mediaSeconds = 0.0
        for (segment in segments) {
            val segmentUrl = url.resolve(segment.uri) ?: continue
            val start = System.nanoTime()
            val got = call(Timings(), segmentUrl.toString(), userAgent).use { r ->
                if (!r.isSuccessful) throw IOException("Segment request answered HTTP ${r.code}")
                r.body?.source()?.let { source ->
                    var n = 0L
                    val sink = okio.Buffer()
                    while (true) {
                        val read = source.read(sink, 64 * 1024L)
                        if (read < 0) break
                        n += read
                        sink.clear()
                    }
                    n
                } ?: 0L
            }
            nanos += System.nanoTime() - start
            bytes += got
            mediaSeconds += segment.durationSeconds
            if (nanos > STREAM_BUDGET_MILLIS * 1_000_000) break
        }
        if (bytes == 0L || nanos == 0L) return base.copy(error = "Segments downloaded empty")
        return base.copy(
            ok = true, bytes = bytes,
            throughputBps = (bytes * 8 * 1_000_000_000.0 / nanos).toLong(),
            bitrateBps = if (mediaSeconds > 0) (bytes * 8 / mediaSeconds).toLong() else 0,
        )
    }

    private fun call(timings: Timings, url: String, userAgent: String = DEFAULT_USER_AGENT): Response =
        client.newBuilder().eventListener(timings).build()
            .newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
            .execute()

    /** Measures a download's rate, ignoring a warm-up period so TCP slow start does not drag it down. */
    private class RateMeter(
        private val budgetMillis: Long,
        private val warmupMillis: Long,
        private val onProgress: (Long) -> Unit = {},
    ) {
        private val startNanos = System.nanoTime()
        private var countedBytes = 0L
        private var countedSinceNanos = 0L
        private var lastProgressNanos = 0L
        var totalBytes = 0L
            private set
        var done = false
            private set

        val bitsPerSecond: Long
            get() {
                val nanos = System.nanoTime() - countedSinceNanos
                return if (countedSinceNanos == 0L || nanos <= 0) 0 else (countedBytes * 8 * 1_000_000_000.0 / nanos).toLong()
            }

        fun drain(response: Response, maxBytes: Long = Long.MAX_VALUE) {
            if (!response.isSuccessful) throw IOException("The server answered HTTP ${response.code}")
            val source = response.body?.source() ?: return
            val sink = okio.Buffer()
            while (!done) {
                val read = source.read(sink, 64 * 1024L)
                if (read < 0) break
                sink.clear()
                val now = System.nanoTime()
                val elapsedMillis = (now - startNanos) / 1_000_000
                totalBytes += read
                if (elapsedMillis >= warmupMillis) {
                    if (countedSinceNanos == 0L) countedSinceNanos = now else countedBytes += read
                }
                if (now - lastProgressNanos > 250_000_000) {
                    lastProgressNanos = now
                    onProgress(bitsPerSecond)
                }
                if (elapsedMillis >= budgetMillis || totalBytes >= maxBytes) done = true
            }
        }
    }

    /** Captures the phases of one HTTP exchange. With redirects, the first hop's setup is kept. */
    private class Timings : EventListener() {
        private var dnsStart = 0L
        private var connectStart = 0L
        private var tlsStart = 0L
        private var requestSent = 0L
        var dnsMillis = -1L; private set
        var connectMillis = -1L; private set
        var tlsMillis = -1L; private set
        var ttfbMillis = -1L; private set

        private fun since(start: Long) = (System.nanoTime() - start) / 1_000_000

        override fun dnsStart(call: Call, domainName: String) { dnsStart = System.nanoTime() }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            if (dnsMillis < 0) dnsMillis = since(dnsStart)
        }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectStart = System.nanoTime()
        }
        override fun secureConnectStart(call: Call) {
            // TCP is up at this point; what follows is the TLS handshake.
            if (connectMillis < 0) connectMillis = since(connectStart)
            tlsStart = System.nanoTime()
        }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            if (tlsMillis < 0) tlsMillis = since(tlsStart)
        }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            if (connectMillis < 0) connectMillis = since(connectStart)
        }
        override fun requestHeadersEnd(call: Call, request: Request) { requestSent = System.nanoTime() }
        override fun responseHeadersStart(call: Call) {
            if (requestSent != 0L) ttfbMillis = since(requestSent)
        }
    }

    companion object {
        const val BASELINE_HOST = "speed.cloudflare.com"
        private const val BASELINE_URL = "https://speed.cloudflare.com/__down"
        private const val DEFAULT_USER_AGENT = "OpenTV/0.1 (Android)"
        private const val LATENCY_SAMPLES = 6
        private const val LATENCY_TIMEOUT_MILLIS = 3_000
        private const val DOWNLOAD_CHUNK_BYTES = 25_000_000
        private const val DOWNLOAD_BUDGET_MILLIS = 8_000L
        private const val DOWNLOAD_WARMUP_MILLIS = 1_500L
        private const val STREAM_BUDGET_MILLIS = 6_000L
        private const val STREAM_MAX_BYTES = 24_000_000L
        private const val HLS_SEGMENTS = 3

        /** A network failure in words a viewer can act on, never a stack trace. */
        fun describe(error: Exception): String {
            // HTTP stacks wrap the real failure (IOException -> ExecutionException -> cause);
            // the outer layers only ever say that something went wrong.
            var e: Throwable = error
            while (true) {
                val cause = e.cause ?: break
                if (cause === e || (e !is java.util.concurrent.ExecutionException && e.javaClass != java.io.IOException::class.java)) break
                e = cause
            }
            return describeRoot(e)
        }

        private fun describeRoot(e: Throwable): String = when (e) {
            is java.net.UnknownHostException -> "The server's address could not be found (DNS)"
            is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "The server took too long to answer"
            is java.net.ConnectException -> "The server refused the connection"
            is javax.net.ssl.SSLException -> "A secure connection could not be set up"
            // OkHttp's wording for a 407 it has no proxy to answer. Panels send it when they turn a
            // request away, most often because the account's connection limit is already in use.
            is java.net.ProtocolException -> if (e.message.orEmpty().contains("407")) {
                "The provider's server refused the stream (HTTP 407) — usually its connection limit"
            } else {
                "The server sent a reply that makes no sense (${e.message?.take(60).orEmpty()})"
            }
            else -> e.message?.take(120) ?: e.javaClass.simpleName
        }
    }
}

/** Just enough HLS to find the newest segments. Pure, so it can be tested without a network. */
data class HlsPlaylist(val variants: List<Variant>, val segments: List<Segment>) {
    data class Variant(val bandwidth: Long, val uri: String)
    data class Segment(val durationSeconds: Double, val uri: String)

    companion object {
        private val BANDWIDTH = Regex("""[:,]BANDWIDTH=(\d+)""")

        fun parse(text: String): HlsPlaylist {
            val variants = mutableListOf<Variant>()
            val segments = mutableListOf<Segment>()
            var pendingBandwidth: Long? = null
            var pendingDuration: Double? = null
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("#EXT-X-STREAM-INF") ->
                        pendingBandwidth = BANDWIDTH.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    line.startsWith("#EXTINF:") ->
                        pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    line.startsWith("#") -> Unit
                    pendingBandwidth != null -> {
                        variants += Variant(pendingBandwidth!!, line)
                        pendingBandwidth = null
                    }
                    pendingDuration != null -> {
                        segments += Segment(pendingDuration!!, line)
                        pendingDuration = null
                    }
                }
            }
            return HlsPlaylist(variants, segments)
        }
    }
}
