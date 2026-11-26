/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.work

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Download
import app.opentv.data.model.DownloadStatus
import app.opentv.data.model.Source
import app.opentv.recording.RecordingStorage
import app.opentv.recording.SmbClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Kicks off offline downloads and turns a finished download's stored locator back into a URL the
 * player can open. One small surface so the UI never has to know about storage targets or workers.
 */
object Downloads {

    /**
     * Queue a movie/episode for offline download to the user's recording storage. Returns the row id.
     * Idempotent per [mediaKey]: an existing in-flight or completed download is returned as-is; a
     * previously failed one is cleared and re-queued.
     */
    suspend fun start(
        context: Context,
        mediaKey: String,
        title: String,
        posterUrl: String?,
        streamUrl: String,
        sourceId: Long,
        containerExtension: String?,
        year: Int? = null,
        genre: String? = null,
        plot: String? = null,
        seriesTitle: String? = null,
        season: Int? = null,
        episodeNumber: Int? = null,
    ): Long {
        val graph = ServiceLocator.get(context.applicationContext)
        val repo = graph.downloadRepository

        repo.byMediaKey(mediaKey)?.let { existing ->
            if (existing.status != DownloadStatus.FAILED) return existing.id
            // A failed attempt: drop the stale row (and any partial file) and start clean.
            runCatching { RecordingStorage.delete(context.applicationContext, graph.settings, existing.filePath) }
            repo.delete(existing.id)
        }

        val ua = graph.sourceRepository.byId(sourceId)?.userAgent ?: Source.DEFAULT_USER_AGENT
        val ext = (containerExtension?.takeIf { it.isNotBlank() } ?: extFromUrl(streamUrl) ?: "mp4")
            .trimStart('.').lowercase()
        val filename = "${safeName(title)}.$ext"
        val locator = RecordingStorage.plannedLocator(context.applicationContext, graph.settings, filename)

        val id = repo.insert(
            Download(
                sourceId = sourceId,
                mediaKey = mediaKey,
                title = title,
                posterUrl = posterUrl,
                streamUrl = streamUrl,
                userAgent = ua,
                filePath = locator,
                status = DownloadStatus.QUEUED,
                createdAtMillis = System.currentTimeMillis(),
                year = year,
                genre = genre,
                plot = plot,
                seriesTitle = seriesTitle,
                season = season,
                episodeNumber = episodeNumber,
            ),
        )
        DownloadWorker.enqueue(context.applicationContext, id)
        return id
    }

    /**
     * How big the file behind [streamUrl] is, asked of the provider without downloading it: a HEAD,
     * then (many panels refuse HEAD) a one-byte ranged GET whose `Content-Range` carries the total.
     * Null when the provider won't say — the download still works, it just can't be sized up front.
     */
    suspend fun probeSize(context: Context, streamUrl: String, sourceId: Long): Long? =
        withContext(Dispatchers.IO) {
            val graph = ServiceLocator.get(context.applicationContext)
            val ua = graph.sourceRepository.byId(sourceId)?.userAgent ?: Source.DEFAULT_USER_AGENT
            val client = graph.streamingHttpClient.newBuilder()
                .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            fun request() = Request.Builder().url(streamUrl).header("User-Agent", ua)
            val viaHead = runCatching {
                client.newCall(request().head().build()).execute().use { r ->
                    r.header("Content-Length")?.toLongOrNull()?.takeIf { r.isSuccessful && it > 0 }
                }
            }.getOrNull()
            viaHead ?: runCatching {
                client.newCall(request().header("Range", "bytes=0-0").build()).execute().use { r ->
                    DownloadSizes.contentRangeTotal(r.header("Content-Range"))
                        // A server that ignores Range answers 200 with the whole file's length.
                        ?: r.header("Content-Length")?.toLongOrNull()?.takeIf { r.code == 200 && it > 1 }
                }
            }.getOrNull()
        }

    /**
     * Where a download started now would be saved: the storage chosen in Settings, and a short name
     * for it to show ("nas/video", the USB folder's name); blank for this device's own storage.
     */
    data class Destination(val target: AppSettings.RecordingTarget, val name: String)

    fun destination(context: Context): Destination {
        val settings = ServiceLocator.get(context.applicationContext).settings
        return when (val target = settings.recordingTarget.value) {
            AppSettings.RecordingTarget.SMB ->
                Destination(target, listOf(settings.smbHost.value, settings.smbShare.value).filter { it.isNotBlank() }.joinToString("/"))
            AppSettings.RecordingTarget.USB -> Destination(
                target,
                runCatching {
                    settings.usbTreeUri.value?.let { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name }
                }.getOrNull().orEmpty(),
            )
            AppSettings.RecordingTarget.INTERNAL -> Destination(target, "")
        }
    }

    /**
     * Free space where downloads land — this device, the NAS share or the USB drive. Null when it
     * can't be read (the NAS is off, the drive is unplugged); the download itself reports that.
     */
    suspend fun freeSpace(context: Context): Long? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val settings = ServiceLocator.get(app).settings
        runCatching {
            when (settings.recordingTarget.value) {
                AppSettings.RecordingTarget.INTERNAL -> RecordingStorage.internalDir(app).usableSpace
                AppSettings.RecordingTarget.SMB -> SmbClient.freeSpace(RecordingStorage.smbConfig(settings))
                AppSettings.RecordingTarget.USB -> {
                    val tree = Uri.parse(settings.usbTreeUri.value ?: return@runCatching null)
                    val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    app.contentResolver.openFileDescriptor(dir, "r")?.use { fd ->
                        val stat = Os.fstatvfs(fd.fileDescriptor)
                        stat.f_bavail * stat.f_frsize
                    }
                }
            }
        }.getOrNull()?.takeIf { it > 0 }
    }

    private const val PROBE_TIMEOUT_SECONDS = 8L

    /** The URL to play a finished download from: as-is for NAS/USB, a file:// URI for internal. */
    fun playbackUrl(locator: String): String = when {
        SmbClient.isSmb(locator) -> locator
        RecordingStorage.isContent(locator) -> locator
        else -> Uri.fromFile(File(locator)).toString()
    }

    private fun safeName(s: String): String =
        s.replace(Regex("[^A-Za-z0-9 _.-]"), "_").take(80).trim().ifBlank { "Download" }

    private fun extFromUrl(url: String): String? =
        url.substringBefore('?').substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length in 2..4 }
}
