/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import app.opentv.R
import app.opentv.core.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * A foreground service that captures live streams to storage. Recording has to keep going when the
 * user backs out of the player, opens another app, or the screen sleeps — a foreground service
 * with a wake lock is what keeps the process (and the socket) alive to do that.
 *
 * Each recording is one coroutine copying bytes from the stream to the chosen [RecordingStorage]
 * sink. Stopping cancels the coroutine and the HTTP call so a blocking read unblocks at once.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captures = ConcurrentHashMap<Long, Capture>()
    private var wakeLock: PowerManager.WakeLock? = null

    private class Capture(@Volatile var call: Call?, val job: Job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                if (id > 0 && !captures.containsKey(id)) startCapture(id)
            }
            ACTION_STOP -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                stopCapture(id)
            }
            ACTION_STOP_ALL -> captures.keys.toList().forEach { stopCapture(it) }
        }
        if (captures.isEmpty() && intent?.action != ACTION_START) stopSelfSafely()
        return START_NOT_STICKY
    }

    private fun startCapture(id: Long) {
        acquireWakeLock()
        val job = scope.launch { runCapture(id) }
        captures[id] = Capture(null, job)
        updateNotification()
    }

    private suspend fun runCapture(id: Long) {
        val graph = ServiceLocator.get(applicationContext)
        val repo = graph.recordingRepository
        val recording = repo.byId(id)
        if (recording == null) {
            finish(id)
            return
        }

        var total = 0L
        var error: String? = null
        var sink: RecordingStorage.Sink? = null
        try {
            repo.markStarted(id, System.currentTimeMillis())
            // Announce the live capture so the player can watch this file while it grows.
            RecordingLiveState.markActive(id, recording.filePath)
            sink = RecordingStorage.openSink(applicationContext, graph.settings, recording.filePath)
            // A USB (SAF) sink only learns its real content:// URI when the document is created —
            // persist it onto the row so the finished recording plays back and deletes correctly.
            sink.resolvedLocator?.let { actual ->
                if (actual != recording.filePath) {
                    repo.setFilePath(id, actual)
                    RecordingLiveState.updatePath(id, actual)
                }
            }

            val buffer = ByteArray(BUFFER_BYTES)
            var lastReport = 0L
            var deadReconnects = 0

            // Reconnect loop. Real IPTV `.ts` live endpoints commonly serve a rolling ~30s window and
            // then close the socket, expecting the client to re-request (ExoPlayer does this on its own
            // during playback). A single read-to-EOF therefore captures only ~30s — the exact bug. So
            // when the connection drops we reopen it and keep appending to the same file, until the
            // scheduled end, a user stop, or the stream is genuinely gone (several empty reconnects).
            val scheduledEnd = recording.scheduledEndMillis
            while (kotlin.coroutines.coroutineContext[Job]?.isActive == true) {
                if (scheduledEnd > 0 && System.currentTimeMillis() >= scheduledEnd) break

                val request = Request.Builder()
                    .url(recording.streamUrl)
                    .header("User-Agent", recording.userAgent)
                    .build()
                val call = graph.streamingHttpClient.newCall(request)
                captures[id]?.call = call

                val bytesThisLeg = try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                        val input = response.body?.byteStream() ?: throw java.io.IOException("Empty response")
                        var leg = 0L
                        while (kotlin.coroutines.coroutineContext[Job]?.isActive == true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            sink.output.write(buffer, 0, read)
                            total += read
                            leg += read
                            if (scheduledEnd > 0 && System.currentTimeMillis() >= scheduledEnd) break
                            if (total - lastReport >= SIZE_REPORT_BYTES) {
                                lastReport = total
                                repo.setSize(id, total)
                            }
                        }
                        leg
                    }
                } catch (t: kotlinx.coroutines.CancellationException) {
                    throw t
                } catch (t: java.io.IOException) {
                    // A dropped connection isn't a failure — reconnect and carry on.
                    Log.i(TAG, "Recording $id connection dropped (${t.message}); reconnecting")
                    0L
                }

                // Nothing arrived this leg: count it, and give up only after several in a row so a
                // truly dead stream can't spin forever. A leg that delivered bytes resets the counter.
                if (bytesThisLeg <= 0L) {
                    if (++deadReconnects >= MAX_DEAD_RECONNECTS) {
                        if (total == 0L) error = getString(R.string.rec_error_nothing)
                        break
                    }
                    kotlinx.coroutines.delay(RECONNECT_BACKOFF_MILLIS)
                } else {
                    deadReconnects = 0
                }
            }
        } catch (t: Throwable) {
            // A cancelled stop is a clean finish, not an error.
            if (t is kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Recording $id stopped by user")
            } else {
                error = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "Recording $id failed", t)
            }
        } finally {
            // A user stop cancels this coroutine. Cleanup must outlive that cancellation or Room's
            // suspended status write is skipped, leaving a phantom RECORDING row after capture ends.
            RecordingFinalizer.run {
                // The file is now at its final size — anyone watching it should see the true end.
                RecordingLiveState.markInactive(id)
                runCatching { sink?.close() }
                val ok = total > 0 && error == null
                runCatching {
                    repo.markFinished(
                        id = id,
                        ok = ok,
                        atMillis = System.currentTimeMillis(),
                        bytes = total,
                        error = if (ok) null else (error ?: getString(R.string.rec_error_nothing)),
                    )
                }.onFailure { throwable ->
                    Log.e(TAG, "Recording $id could not persist final status", throwable)
                }
                finish(id)
            }
        }
    }

    private fun stopCapture(id: Long) {
        val capture = captures[id] ?: return
        runCatching { capture.call?.cancel() }
        capture.job.cancel()
    }

    /** Called from the capture coroutine's finally to retire it and, if it was the last, stop. */
    private fun finish(id: Long) {
        captures.remove(id)
        if (captures.isEmpty()) {
            stopSelfSafely()
        } else {
            updateNotification()
        }
    }

    private fun stopSelfSafely() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    /**
     * The user swiped OpenTV out of recents. That must NOT kill an in-progress recording — the
     * whole point of a foreground capture is that it keeps going when you leave the app. So while
     * any capture is still running we deliberately do not [stopSelf]; we re-assert foreground so
     * the OS keeps the process (and its wake lock) alive, and let each capture retire the service
     * itself when it finishes. Only when nothing is recording do we tidy up and stop.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (captures.isEmpty()) {
            stopSelfSafely()
            return
        }
        Log.i(TAG, "Task removed with ${captures.size} active recording(s) — keeping capture alive")
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(captures.size)) }
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ---- Wake lock ---------------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenTV:recording").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    // ---- Notification ------------------------------------------------------------------------

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(captures.size))
    }

    private fun buildNotification(count: Int): Notification {
        val text = when (count) {
            0, 1 -> getString(R.string.rec_notification_one)
            else -> getString(R.string.rec_notification_many, count)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenTV")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_opentv_logo)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.rec_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = getString(R.string.rec_channel_desc) },
                )
            }
        }
    }

    companion object {
        private const val TAG = "OpenTV"
        private const val CHANNEL_ID = "recordings"
        private const val NOTIFICATION_ID = 4210
        private const val BUFFER_BYTES = 64 * 1024
        private const val SIZE_REPORT_BYTES = 4L * 1024 * 1024
        private const val MAX_WAKE_MILLIS = 6L * 60 * 60 * 1000 // 6h safety cap
        // Providers close a live .ts socket every ~30s; reconnect and keep appending. Give up only
        // after this many consecutive reconnects deliver nothing (the stream is genuinely gone).
        private const val MAX_DEAD_RECONNECTS = 5
        private const val RECONNECT_BACKOFF_MILLIS = 1500L

        private const val ACTION_START = "app.opentv.recording.START"
        private const val ACTION_STOP = "app.opentv.recording.STOP"
        private const val ACTION_STOP_ALL = "app.opentv.recording.STOP_ALL"
        private const val EXTRA_ID = "recordingId"

        fun start(context: Context, recordingId: Long) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ID, recordingId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context, recordingId: Long) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_ID, recordingId)
            }
            context.startService(intent)
        }
    }
}
