/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.work

import android.content.Context
import android.net.Uri
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Download
import app.opentv.data.model.DownloadStatus
import app.opentv.data.model.Source
import app.opentv.recording.RecordingStorage
import app.opentv.recording.SmbClient
import java.io.File

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
