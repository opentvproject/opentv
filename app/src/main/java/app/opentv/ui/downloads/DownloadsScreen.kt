/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.downloads

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Download
import app.opentv.data.model.DownloadStatus
import app.opentv.data.model.Recording
import app.opentv.data.work.Downloads
import app.opentv.recording.RecordingStorage
import app.opentv.ui.recordings.RecordingsScreen
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)

    val downloads: StateFlow<List<Download>> =
        graph.downloadRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(download: Download) {
        viewModelScope.launch {
            RecordingStorage.delete(getApplication(), graph.settings, download.filePath)
            graph.downloadRepository.delete(download.id)
        }
    }

    /** Re-queue a failed download (clears the old row + partial file, starts fresh). */
    fun retry(download: Download) {
        viewModelScope.launch {
            Downloads.start(
                getApplication(), download.mediaKey, download.title, download.posterUrl,
                download.streamUrl, download.sourceId, null,
                year = download.year, genre = download.genre, plot = download.plot,
                seriesTitle = download.seriesTitle, season = download.season,
                episodeNumber = download.episodeNumber,
            )
        }
    }
}

private enum class Section { FILMS, SHOWS, RECORDINGS }

/**
 * The unified library: everything you've saved on this device — downloaded Films (grouped by genre),
 * downloaded TV Shows (grouped by series), and your Recordings — behind one tab.
 */
@Composable
fun LibraryScreen(
    onPlay: (mediaKey: String, url: String, title: String) -> Unit,
    onPlayRecording: (Recording) -> Unit,
) {
    val viewModel: LibraryViewModel = viewModel()
    val downloads by viewModel.downloads.collectAsState()
    var section by remember { mutableStateOf(Section.FILMS) }

    val films = downloads.filter { it.mediaKey.startsWith("movie:") }
    val episodes = downloads.filter { it.mediaKey.startsWith("ep:") }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        Text(stringResource(R.string.nav_library), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = section == Section.FILMS,
                onClick = { section = Section.FILMS },
                label = { Text(stringResource(R.string.library_films)) },
            )
            FilterChip(
                selected = section == Section.SHOWS,
                onClick = { section = Section.SHOWS },
                label = { Text(stringResource(R.string.library_shows)) },
            )
            FilterChip(
                selected = section == Section.RECORDINGS,
                onClick = { section = Section.RECORDINGS },
                label = { Text(stringResource(R.string.library_recordings)) },
            )
        }
        Spacer(Modifier.height(16.dp))

        when (section) {
            Section.FILMS -> FilmsSection(films, onPlay, viewModel)
            Section.SHOWS -> ShowsSection(episodes, onPlay, viewModel)
            Section.RECORDINGS -> RecordingsScreen(onPlay = onPlayRecording)
        }
    }
}

@Composable
private fun FilmsSection(
    films: List<Download>,
    onPlay: (String, String, String) -> Unit,
    viewModel: LibraryViewModel,
) {
    if (films.isEmpty()) {
        EmptyState(stringResource(R.string.library_empty_films))
        return
    }
    // Group by the first genre token — "Comedy, Drama" files under Comedy — with "Other" last.
    val byGenre = films.groupBy { genreOf(it) }
        .toSortedMap(compareBy({ it == "Other" }, { it }))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        byGenre.forEach { (genre, rows) ->
            item(key = "g-$genre") { SectionHeader(genre) }
            items(rows, key = { it.id }) { d ->
                DownloadRow(
                    download = d,
                    onPlay = { onPlay(d.mediaKey, Downloads.playbackUrl(d.filePath), d.title) },
                    onRetry = { viewModel.retry(d) },
                    onDelete = { viewModel.delete(d) },
                )
            }
        }
    }
}

@Composable
private fun ShowsSection(
    episodes: List<Download>,
    onPlay: (String, String, String) -> Unit,
    viewModel: LibraryViewModel,
) {
    if (episodes.isEmpty()) {
        EmptyState(stringResource(R.string.library_empty_shows))
        return
    }
    val byShow = episodes.groupBy { it.seriesTitle?.takeIf { s -> s.isNotBlank() } ?: it.title }
        .toSortedMap()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        byShow.forEach { (show, eps) ->
            item(key = "s-$show") { SectionHeader(show) }
            val ordered = eps.sortedWith(compareBy({ it.season ?: 0 }, { it.episodeNumber ?: 0 }))
            items(ordered, key = { it.id }) { d ->
                DownloadRow(
                    download = d,
                    onPlay = { onPlay(d.mediaKey, Downloads.playbackUrl(d.filePath), d.title) },
                    onRetry = { viewModel.retry(d) },
                    onDelete = { viewModel.delete(d) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DownloadRow(
    download: Download,
    onPlay: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val poster = download.posterPath?.let { java.io.File(it) } ?: download.posterUrl
        if (poster != null) {
            AsyncImage(
                model = poster,
                contentDescription = null,
                modifier = Modifier.size(width = 40.dp, height = 56.dp).clip(RoundedCornerShape(6.dp)),
            )
        } else {
            Box(
                Modifier.size(width = 40.dp, height = 56.dp).clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                download.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                statusLine(download),
                style = MaterialTheme.typography.bodySmall,
                color = statusColor(download.status),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (download.status == DownloadStatus.DOWNLOADING || download.status == DownloadStatus.QUEUED) {
                Spacer(Modifier.height(6.dp))
                if (download.totalBytes > 0) {
                    val progress = (download.downloadedBytes.toFloat() / download.totalBytes).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        if (download.status == DownloadStatus.COMPLETED) {
            ActionButton(Icons.Filled.PlayArrow, stringResource(R.string.common_play), onPlay)
            Spacer(Modifier.width(8.dp))
        }
        if (download.status == DownloadStatus.FAILED) {
            ActionButton(Icons.Filled.Refresh, stringResource(R.string.vod_download_retry), onRetry)
            Spacer(Modifier.width(8.dp))
        }
        ActionButton(Icons.Filled.Delete, stringResource(R.string.common_delete), onDelete)
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Icon(icon, contentDescription = label, tint = fg)
    }
}

private fun genreOf(d: Download): String =
    d.genre?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: "Other"

@Composable
private fun statusLine(d: Download): String {
    val parts = buildList {
        d.year?.let { add(it.toString()) }
        when (d.status) {
            DownloadStatus.COMPLETED -> {
                add(stringResource(R.string.library_downloaded_on, formatDate(d.createdAtMillis)))
                add(formatSize(if (d.totalBytes > 0) d.totalBytes else d.downloadedBytes))
            }
            DownloadStatus.DOWNLOADING -> {
                val pct = if (d.totalBytes > 0) ((d.downloadedBytes * 100L) / d.totalBytes).toInt() else 0
                add(stringResource(R.string.dl_status_downloading, pct, formatSize(d.downloadedBytes)))
            }
            DownloadStatus.QUEUED -> add(stringResource(R.string.dl_status_queued))
            DownloadStatus.FAILED -> add(stringResource(R.string.dl_status_failed, d.error ?: stringResource(R.string.rec_error_unknown)))
        }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun statusColor(status: DownloadStatus): Color = when (status) {
    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> MaterialTheme.colorScheme.primary
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
}

private fun formatDate(millis: Long): String {
    if (millis <= 0) return ""
    return java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(millis))
}
