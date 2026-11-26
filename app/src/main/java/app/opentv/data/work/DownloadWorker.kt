/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.DownloadStatus
import app.opentv.recording.RecordingStorage
import okhttp3.Request

/**
 * Downloads one VOD movie or episode for offline viewing.
 *
 * A VOD stream is an ordinary file over HTTP (unlike a live `.ts`, which serves a rolling window),
 * so this is a single read-to-EOF into a [RecordingStorage] sink — the same storage the recorder
 * writes to, so a download lands on whichever target the user chose (internal / USB / NAS) and plays
 * back through the same player. Runs as a foreground worker so a big film survives the app being
 * killed mid-transfer; progress is written to the row so the Downloads list shows a live bar.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        if (id < 0) return Result.success()
        val graph = ServiceLocator.get(applicationContext)
        val repo = graph.downloadRepository
        val download = repo.byId(id) ?: return Result.success()

        runCatching { setForeground(foregroundInfo(download.title, 0)) }
        repo.setStatus(id, DownloadStatus.DOWNLOADING)

        var total = 0L
        var sink: RecordingStorage.Sink? = null
        try {
            sink = RecordingStorage.openSink(applicationContext, graph.settings, download.filePath)
            // A USB (SAF) sink only learns its real content:// URI once the document is created —
            // persist it so the finished download plays back and deletes correctly.
            sink.resolvedLocator?.let { actual ->
                if (actual != download.filePath) repo.setFilePath(id, actual)
            }

            val request = Request.Builder()
                .url(download.streamUrl)
                .header("User-Agent", download.userAgent)
                .build()
            graph.streamingHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                val body = response.body ?: throw java.io.IOException("Empty response")
                val contentLength = body.contentLength()
                if (contentLength > 0) repo.setProgress(id, 0, contentLength)
                val input = body.byteStream()
                val buffer = ByteArray(BUFFER_BYTES)
                var lastReport = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink.output.write(buffer, 0, read)
                    total += read
                    if (total - lastReport >= REPORT_BYTES) {
                        lastReport = total
                        // Deleted from the Library mid-transfer: stop, rather than fill the drive
                        // with a file nothing points at any more.
                        if (repo.byId(id) == null) throw java.io.IOException("Download removed")
                        repo.setProgress(id, total, maxOf(contentLength, total))
                        val pct = if (contentLength > 0) ((total * 100L) / contentLength).toInt() else 0
                        runCatching { setForeground(foregroundInfo(download.title, pct)) }
                    }
                }
            }
            sink.output.flush()
        } catch (t: Throwable) {
            runCatching { sink?.close() }
            // Bin the partial file so a failed download doesn't leave junk on the drive.
            val locator = repo.byId(id)?.filePath ?: download.filePath
            runCatching { RecordingStorage.delete(applicationContext, graph.settings, locator) }
            Log.w(TAG, "Download $id failed", t)
            repo.setFailed(id, t.message ?: t.javaClass.simpleName)
            return Result.success()
        }
        runCatching { sink?.close() }
        if (total <= 0L) {
            repo.setFailed(id, "The provider returned an empty file.")
            return Result.success()
        }
        repo.setProgress(id, total, total)
        // Save the poster locally too, so the Library shows artwork even fully offline.
        runCatching {
            val poster = download.posterUrl
            if (!poster.isNullOrBlank()) {
                val dir = java.io.File(applicationContext.filesDir, "download-posters").apply { mkdirs() }
                val out = java.io.File(dir, "$id.jpg")
                graph.httpClient.newCall(Request.Builder().url(poster).build()).execute().use { resp ->
                    if (resp.isSuccessful) out.outputStream().use { fo -> resp.body?.byteStream()?.copyTo(fo) }
                }
                if (out.length() > 0) repo.setPosterPath(id, out.absolutePath)
            }
        }
        repo.setStatus(id, DownloadStatus.COMPLETED)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null, 0)

    private fun foregroundInfo(title: String?, pct: Int): ForegroundInfo {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (mgr != null && mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        ctx.getString(R.string.dl_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = ctx.getString(R.string.dl_channel_desc) },
                )
            }
        }
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.dl_notification_title))
            .setContentText(title ?: ctx.getString(R.string.dl_notification_title))
            .setSmallIcon(R.drawable.ic_opentv_logo)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, pct.coerceIn(0, 100), pct <= 0)
            .build()
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val TAG = "DownloadWorker"
        const val KEY_ID = "downloadId"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 4310
        private const val BUFFER_BYTES = 64 * 1024
        private const val QUEUE_NAME = "opentv-downloads"
        private const val REPORT_BYTES = 2L * 1024 * 1024

        /**
         * Enqueue the transfer for [downloadId] behind any already waiting. One at a time on purpose:
         * a whole season queued at once would otherwise open a connection per episode, and most
         * providers allow one or two. A re-tap can't double up — [Downloads.start] is idempotent.
         */
        fun enqueue(context: Context, downloadId: Long) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_ID to downloadId))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                QUEUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
