/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.diagnostics

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import app.opentv.player.PlaybackErrors
import app.opentv.player.PlayerController
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The running picture of the stream that is on screen, refreshed about once a second. */
data class LiveStats(
    val channelId: Long,
    val host: String,
    val container: String,
    val width: Int,
    val height: Int,
    /** What the stream needs. 0 until it can be told. */
    val bitrateBps: Long,
    /** What is arriving from the provider right now. */
    val receivedBps: Long,
    /** What the link to this server can carry, when measurable (HLS). 0 otherwise. */
    val linkBps: Long,
    val bufferMillis: Long,
    /**
     * Seconds of video delivered per second of real time, as a percentage. A live stream needs
     * 100 to keep playing; anything sustained below that is a stall waiting to happen. -1 while
     * there is not yet enough history to say.
     */
    val deliveryPercent: Int,
    val startupMillis: Long,
    val watchedMillis: Long,
    val stallCount: Int,
    val stallMillis: Long,
    val reconnects: Int,
    val droppedFrames: Int,
    val backwardJumps: Int,
    val streamGaps: Int,
    val lastError: String?,
    val vpn: Boolean,
    val transport: String,
    val grade: HealthGrade,
    /** True once this stream has misbehaved enough that offering another copy is a kindness. */
    val struggling: Boolean,
    /** Still starting up: there is no picture yet, so no verdict on the stream either. */
    val tuning: Boolean = false,
)

/**
 * Watches the live player and writes down what the stream actually did.
 *
 * It only observes: nothing here changes how anything plays. It listens to the player's own events
 * (state changes, errors, discontinuities) for *what* went wrong, and counts bytes off the network
 * for *how much* was arriving at the time — the second is what separates "the provider stopped
 * sending" from "the box could not decode fast enough", which look identical on screen.
 *
 * All player callbacks arrive on the main thread; only the byte counter is touched from loader
 * threads, hence the one atomic.
 */
@OptIn(UnstableApi::class)
class StreamHealthRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Receives each finished session worth keeping. Must not suspend the caller. */
    private val onSessionFinished: (StreamSession) -> Unit,
) : AnalyticsListener, TransferListener {

    private val _live = MutableStateFlow<LiveStats?>(null)
    val live: StateFlow<LiveStats?> = _live.asStateFlow()

    private var player: ExoPlayer? = null
    private var session: Session? = null
    private var ticker: Job? = null
    private val networkBytes = AtomicLong()

    private class Session(
        val request: PlayerController.Request,
        val host: String,
        val container: String,
        val net: NetworkState,
        val startedAtWall: Long,
        val requestedAt: Long,
    ) {
        var firstReadyAt = 0L
        var startupMillis = -1L
        var stallStartedAt = 0L
        var stallCount = 0
        var stallMillis = 0L
        val stallTimes = ArrayDeque<Long>()
        var reconnects = 0
        val reconnectTimes = ArrayDeque<Long>()
        var awaitingRestart = false
        var liveOffsetBeforeRestart = C.TIME_UNSET
        var lastLiveOffset = C.TIME_UNSET
        var seekPending = false
        var loadErrors = 0
        var fatalErrors = 0
        var failed = false
        var lastError: String? = null
        var droppedFrames = 0
        var backwardJumps = 0
        var streamGaps = 0
        var declaredBitrate = 0L
        var observedBitrate = 0L
        /** Time the picture has actually been moving, and when that was last added up. */
        var playedMillis = 0L
        var lastSampleAt = 0L
        var width = 0
        var height = 0
        val linkEstimates = ArrayList<Long>()

        // Rolling windows for the once-a-second sampler: (elapsedRealtime, value).
        val byteSamples = ArrayDeque<Pair<Long, Long>>()
        val mediaSamples = ArrayDeque<Pair<Long, Long>>()
        var anchorBytes = -1L
        var anchorMediaMillis = 0L
        var receivedBps = 0L
        var deliveryPercent = -1
        var bufferMillis = 0L
    }

    fun attach(player: ExoPlayer) {
        this.player = player
        player.addAnalyticsListener(this)
    }

    /**
     * Called by the controller the moment it actually asks the player for a stream (after any
     * surfing debounce). [restart] marks the controller's own silent recovery from an error — that
     * is the same viewing session carrying on, and is counted as a reconnect, not a new tune.
     */
    fun onPlayRequested(request: PlayerController.Request, restart: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val existing = session
        if (restart && existing != null && existing.request.url == request.url) {
            existing.reconnects++
            existing.reconnectTimes.addLast(now)
            existing.awaitingRestart = true
            existing.liveOffsetBeforeRestart = existing.lastLiveOffset
            return
        }
        finish()
        if (!request.isLive) return
        val uri = runCatching { Uri.parse(request.url) }.getOrNull()
        session = Session(
            request = request,
            host = uri?.host.orEmpty(),
            container = containerOf(uri?.path.orEmpty()),
            net = NetworkState.read(context),
            startedAtWall = System.currentTimeMillis(),
            requestedAt = now,
        )
        networkBytes.set(0)
        startTicker()
    }

    /** The player was stopped or is going away: close out whatever was on. */
    fun onStopped() {
        finish()
        ticker?.cancel()
        ticker = null
        _live.value = null
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(SAMPLE_MILLIS)
                sample()
            }
        }
    }

    private fun sample() {
        val s = session ?: return
        val p = player ?: return
        val now = SystemClock.elapsedRealtime()

        val bytes = networkBytes.get()
        s.byteSamples.addLast(now to bytes)
        while (s.byteSamples.size > RATE_WINDOW_SAMPLES) s.byteSamples.removeFirst()
        s.receivedBps = rate(s.byteSamples) * 8

        s.bufferMillis = p.totalBufferedDuration
        s.lastLiveOffset = p.currentLiveOffset
        val loading = p.playWhenReady &&
            (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
        // How much programme has arrived so far: what has been shown plus what is waiting. Not
        // bufferedPosition — on a live stream that is measured inside a window that slides along
        // with the broadcast, so it barely moves however much is delivered.
        if (s.lastSampleAt != 0L && p.isPlaying) s.playedMillis += now - s.lastSampleAt
        s.lastSampleAt = now
        val mediaMillis = s.playedMillis + s.bufferMillis
        if (!loading || s.bufferMillis > FULL_BUFFER_MILLIS) {
            // Paused, or the buffer is as full as it is allowed to get: the player has stopped
            // asking for more, so a slow arrival rate here would be its doing, not the provider's.
            s.mediaSamples.clear()
            s.deliveryPercent = -1
        } else {
            if (s.mediaSamples.isNotEmpty() && mediaMillis < s.mediaSamples.last().second) s.mediaSamples.clear()
            s.mediaSamples.addLast(now to mediaMillis)
            while (s.mediaSamples.size > DELIVERY_WINDOW_SAMPLES) s.mediaSamples.removeFirst()
            val span = s.mediaSamples.last().first - s.mediaSamples.first().first
            s.deliveryPercent = if (span < DELIVERY_MIN_SPAN_MILLIS) -1 else {
                val media = s.mediaSamples.last().second - s.mediaSamples.first().second
                (media * 100 / span).toInt().coerceIn(0, 999)
            }
        }

        // Bytes in over media time in: the bitrate the stream really runs at, for containers that
        // do not declare one (which is every raw MPEG-TS stream).
        if (s.firstReadyAt != 0L) {
            if (s.anchorBytes < 0 || mediaMillis < s.anchorMediaMillis) {
                s.anchorBytes = bytes
                s.anchorMediaMillis = mediaMillis
            } else {
                val media = mediaMillis - s.anchorMediaMillis
                if (media >= BITRATE_MIN_MEDIA_MILLIS) s.observedBitrate = (bytes - s.anchorBytes) * 8_000 / media
            }
        }

        while (s.stallTimes.isNotEmpty() && now - s.stallTimes.first() > STRUGGLE_STALL_WINDOW_MILLIS) s.stallTimes.removeFirst()
        while (s.reconnectTimes.isNotEmpty() && now - s.reconnectTimes.first() > STRUGGLE_RECONNECT_WINDOW_MILLIS) {
            s.reconnectTimes.removeFirst()
        }
        publish(s, now)
    }

    private fun publish(s: Session, now: Long) {
        val snapshot = snapshot(s, now)
        val stallingFor = if (s.stallStartedAt != 0L) now - s.stallStartedAt else 0L
        val struggling = s.failed ||
            s.stallTimes.size >= STRUGGLE_STALLS ||
            stallingFor >= STRUGGLE_STALL_MILLIS ||
            s.reconnectTimes.size >= STRUGGLE_RECONNECTS ||
            (s.firstReadyAt == 0L && now - s.requestedAt >= STRUGGLE_START_MILLIS)
        _live.value = LiveStats(
            channelId = s.request.channelId,
            host = s.host,
            container = s.container,
            width = s.width,
            height = s.height,
            bitrateBps = snapshot.bitrateBps,
            receivedBps = s.receivedBps,
            linkBps = snapshot.throughputBps,
            bufferMillis = s.bufferMillis,
            deliveryPercent = s.deliveryPercent,
            startupMillis = s.startupMillis,
            watchedMillis = snapshot.watchedMillis,
            stallCount = s.stallCount,
            stallMillis = snapshot.stallMillis,
            reconnects = s.reconnects,
            droppedFrames = s.droppedFrames,
            backwardJumps = s.backwardJumps,
            streamGaps = s.streamGaps,
            lastError = s.lastError,
            vpn = s.net.vpn,
            transport = s.net.transport,
            grade = when {
                struggling -> HealthGrade.POOR
                // Still tuning: nothing has gone wrong yet, and nothing has gone right either.
                s.firstReadyAt == 0L -> HealthGrade.FAIR
                else -> gradeOf(HealthSummary.of(listOf(snapshot)).score())
            },
            struggling = struggling,
            tuning = !struggling && s.firstReadyAt == 0L,
        )
    }

    private fun snapshot(s: Session, now: Long): StreamSession {
        val openStall = if (s.stallStartedAt != 0L) now - s.stallStartedAt else 0L
        return StreamSession(
            channelId = s.request.channelId,
            sourceId = s.request.sourceId,
            groupKey = s.request.groupKey,
            channelName = s.request.title,
            host = s.host,
            container = s.container,
            startedAtMillis = s.startedAtWall,
            watchedMillis = if (s.firstReadyAt == 0L) 0 else now - s.firstReadyAt,
            startupMillis = s.startupMillis,
            stallCount = s.stallCount,
            stallMillis = s.stallMillis + openStall,
            reconnects = s.reconnects,
            loadErrors = s.loadErrors,
            fatalErrors = s.fatalErrors,
            lastError = s.lastError,
            droppedFrames = s.droppedFrames,
            bitrateBps = if (s.declaredBitrate > 0) s.declaredBitrate else s.observedBitrate,
            throughputBps = if (s.linkEstimates.isEmpty()) 0 else s.linkEstimates.sorted()[s.linkEstimates.size / 2],
            width = s.width,
            height = s.height,
            backwardJumps = s.backwardJumps,
            streamGaps = s.streamGaps,
            vpn = s.net.vpn,
            transport = s.net.transport,
        )
    }

    private fun finish() {
        val s = session ?: return
        session = null
        val now = SystemClock.elapsedRealtime()
        val result = snapshot(s, now)
        // Channels merely passed through while surfing say nothing about stream quality; a stream
        // that was given a fair wait and still showed no picture says a great deal.
        val worthKeeping = when {
            s.request.channelId == 0L -> false
            s.firstReadyAt != 0L -> result.watchedMillis >= MIN_SESSION_MILLIS || s.fatalErrors > 0
            else -> now - s.requestedAt >= MIN_FAILED_START_MILLIS
        }
        if (worthKeeping) onSessionFinished(result)
    }

    // ---- Player events ---------------------------------------------------------------------

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val s = session ?: return
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Player.STATE_READY -> {
                if (s.firstReadyAt == 0L) {
                    s.firstReadyAt = now
                    s.startupMillis = now - s.requestedAt
                }
                if (s.stallStartedAt != 0L) {
                    s.stallMillis += now - s.stallStartedAt
                    s.stallStartedAt = 0L
                }
                if (s.awaitingRestart) {
                    // Where the picture resumed, relative to the live edge, against where it was
                    // before the drop. Further behind means the viewer is now re-watching.
                    val before = s.liveOffsetBeforeRestart
                    val after = player?.currentLiveOffset ?: C.TIME_UNSET
                    if (before != C.TIME_UNSET && after != C.TIME_UNSET && after - before > JUMP_BACK_MILLIS) {
                        s.backwardJumps++
                    }
                    s.awaitingRestart = false
                }
                s.seekPending = false
                s.failed = false
            }
            Player.STATE_BUFFERING -> {
                val rebuffer = s.firstReadyAt != 0L && s.stallStartedAt == 0L &&
                    player?.playWhenReady == true && !s.seekPending
                if (rebuffer) {
                    s.stallStartedAt = now
                    // The spinner during a reconnect is already counted as that reconnect.
                    if (!s.awaitingRestart) {
                        s.stallCount++
                        s.stallTimes.addLast(now)
                    }
                }
            }
            Player.STATE_ENDED -> {
                // A live channel has no end. The server closed the stream and nothing will bring
                // it back without a re-tune.
                s.fatalErrors++
                s.failed = true
                s.lastError = "The server ended the stream"
            }
            else -> Unit
        }
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        val s = session ?: return
        s.fatalErrors++
        s.failed = true
        s.lastError = PlaybackErrors.describe(error)
        // The picture is gone from here until the next READY: that is stalled time.
        if (s.firstReadyAt != 0L && s.stallStartedAt == 0L) s.stallStartedAt = SystemClock.elapsedRealtime()
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        val s = session ?: return
        if (wasCanceled) return
        s.loadErrors++
        s.lastError = NetworkProbe.describe(error)
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        val s = session ?: return
        when (reason) {
            Player.DISCONTINUITY_REASON_SEEK, Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> s.seekPending = true
            Player.DISCONTINUITY_REASON_INTERNAL, Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> {
                val sameWindow = oldPosition.mediaItemIndex == newPosition.mediaItemIndex
                if (sameWindow && oldPosition.positionMs - newPosition.positionMs > JUMP_BACK_MILLIS) s.backwardJumps++
            }
            else -> Unit
        }
    }

    /**
     * The audio track's timestamps skipped: a chunk of the stream never arrived, or the provider's
     * encoder restarted. Heard as a click or a moment of silence, and the classic sign of a source
     * that is damaged before it ever reaches this network.
     */
    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        if (audioSinkError is AudioSink.UnexpectedDiscontinuityException) session?.let { it.streamGaps++ }
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        session?.let { it.droppedFrames += droppedFrames }
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        val s = session ?: return
        if (format.bitrate > 0) s.declaredBitrate = format.bitrate.toLong()
        if (format.width > 0) s.width = format.width
        if (format.height > 0) s.height = format.height
    }

    override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
        val s = session ?: return
        if (videoSize.width > 0) s.width = videoSize.width
        if (videoSize.height > 0) s.height = videoSize.height
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        val s = session ?: return
        // Only segmented streams are fetched faster than real time, so only there does the
        // estimate describe the link rather than the server's pacing.
        if (s.container != CONTAINER_HLS || totalBytesLoaded <= 0 || bitrateEstimate <= 0) return
        s.linkEstimates += bitrateEstimate
        if (s.linkEstimates.size > MAX_LINK_ESTIMATES) s.linkEstimates.removeAt(0)
    }

    // ---- Byte counting (loader threads) ----------------------------------------------------

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (isNetwork) networkBytes.addAndGet(bytesTransferred.toLong())
    }

    companion object {
        const val CONTAINER_HLS = "hls"
        const val CONTAINER_TS = "ts"

        fun containerOf(path: String): String = when {
            path.contains(".m3u8", ignoreCase = true) -> CONTAINER_HLS
            path.endsWith(".ts", ignoreCase = true) || path.endsWith(".mpegts", ignoreCase = true) -> CONTAINER_TS
            else -> "other"
        }

        /** Bytes per second across a window of (time, cumulative bytes) samples. */
        private fun rate(samples: ArrayDeque<Pair<Long, Long>>): Long {
            if (samples.size < 2) return 0
            val millis = samples.last().first - samples.first().first
            return if (millis <= 0) 0 else (samples.last().second - samples.first().second) * 1000 / millis
        }

        private const val SAMPLE_MILLIS = 1_000L
        // Long enough to span a couple of HLS segments, which arrive in bursts with silence between.
        private const val RATE_WINDOW_SAMPLES = 12
        private const val DELIVERY_WINDOW_SAMPLES = 30
        private const val DELIVERY_MIN_SPAN_MILLIS = 15_000L
        private const val FULL_BUFFER_MILLIS = 45_000L
        private const val BITRATE_MIN_MEDIA_MILLIS = 4_000L
        private const val MAX_LINK_ESTIMATES = 60
        private const val JUMP_BACK_MILLIS = 1_500L

        private const val MIN_SESSION_MILLIS = 10_000L
        private const val MIN_FAILED_START_MILLIS = 8_000L

        // When a stream counts as "struggling": two stalls inside a minute and a half, one long
        // one, two provider drops inside three minutes, or no picture after a fair wait.
        private const val STRUGGLE_STALLS = 2
        private const val STRUGGLE_STALL_WINDOW_MILLIS = 90_000L
        private const val STRUGGLE_STALL_MILLIS = 7_000L
        private const val STRUGGLE_RECONNECTS = 2
        private const val STRUGGLE_RECONNECT_WINDOW_MILLIS = 180_000L
        private const val STRUGGLE_START_MILLIS = 15_000L
    }
}
