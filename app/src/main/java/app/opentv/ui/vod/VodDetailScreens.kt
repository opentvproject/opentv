/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.DownloadStatus
import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.StremioStream
import app.opentv.data.parser.displayTitle
import app.opentv.data.work.Downloads
import app.opentv.ui.VodViewModel
import coil.compose.AsyncImage
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A film's cinematic detail page: a backdrop hero with title, meta and Watch/Resume + favourite
 * over a gradient scrim, then synopsis, cast and director, and a "more like this" shelf. Opening it
 * enriches the row from the provider (backdrop/cast/genre) if those fields are still bare, so a card
 * that was plain in a grid fills out here.
 */
@Composable
fun MovieDetailScreen(
    movieId: Long,
    onPlay: (Movie) -> Unit,
    onPlayUrl: (key: String, url: String, title: String) -> Unit,
    onDownload: (Movie) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onOpenPerson: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: VodViewModel = viewModel(),
) {
    BackHandler { onBack() }
    var movie by remember(movieId) { mutableStateOf<Movie?>(null) }
    var moreLike by remember(movieId) { mutableStateOf<List<Movie>>(emptyList()) }
    var resumeExists by remember(movieId) { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    val hasAddons by viewModel.hasAddons.collectAsState()
    val scope = rememberCoroutineScope()
    // null = the add-on picker is closed; a (possibly empty) list = show it. Separate flag for the spinner.
    var addonStreams by remember(movieId) { mutableStateOf<List<StremioStream>?>(null) }
    var addonLoading by remember(movieId) { mutableStateOf(false) }
    val downloadState by viewModel.downloadFlow("movie:$movieId").collectAsState(initial = null)

    LaunchedEffect(movieId) {
        val loaded = viewModel.movieDetail(movieId)
        movie = loaded
        if (loaded != null) {
            resumeExists = viewModel.resumePosition("movie:${loaded.id}")?.let { !it.isFinished } == true
            moreLike = viewModel.moreLikeThis(loaded)
        }
    }

    val m = movie ?: run { LoadingDetail(); return }

    // Land focus on the primary action once the page is up, so a d-pad has somewhere to be.
    LaunchedEffect(m.id) {
        delay(60)
        runCatching { playFocus.requestFocus() }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            DetailBackdrop(title = m.displayTitle, backdropUrl = m.backdropUrl, posterUrl = m.posterUrl, meta = movieMeta(m)) {
                DetailButton(
                    icon = Icons.Filled.PlayArrow,
                    label = stringResource(if (resumeExists) R.string.vod_resume else R.string.vod_watch_now),
                    primary = true,
                    modifier = Modifier.focusRequester(playFocus),
                ) { onPlay(m) }
                val dl = downloadState
                DetailButton(
                    icon = when {
                        dl?.status == DownloadStatus.COMPLETED -> Icons.Filled.DownloadDone
                        dl?.status == DownloadStatus.DOWNLOADING || dl?.status == DownloadStatus.QUEUED -> Icons.Filled.Downloading
                        else -> Icons.Filled.Download
                    },
                    label = when {
                        dl?.status == DownloadStatus.COMPLETED -> stringResource(R.string.vod_downloaded)
                        dl?.status == DownloadStatus.DOWNLOADING -> stringResource(
                            R.string.vod_downloading_pct,
                            if (dl.totalBytes > 0) ((dl.downloadedBytes * 100L) / dl.totalBytes).toInt() else 0,
                        )
                        dl?.status == DownloadStatus.QUEUED -> stringResource(R.string.vod_download_queued)
                        else -> stringResource(R.string.vod_download)
                    },
                ) {
                    when {
                        dl?.status == DownloadStatus.COMPLETED ->
                            onPlayUrl("movie:${m.id}", Downloads.playbackUrl(dl.filePath), m.displayTitle)
                        dl?.status == DownloadStatus.DOWNLOADING || dl?.status == DownloadStatus.QUEUED -> Unit
                        else -> onDownload(m)
                    }
                }
                DetailButton(
                    icon = if (m.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    label = stringResource(if (m.favourite) R.string.common_remove_favourite else R.string.common_favourite),
                ) {
                    viewModel.toggleMovieFavourite(m)
                    movie = m.copy(favourite = !m.favourite)
                }
                // Only when the user has add-ons set up — queries them for this film's streams.
                if (hasAddons) {
                    DetailButton(
                        icon = Icons.Filled.Extension,
                        label = stringResource(R.string.vod_addon_sources),
                    ) {
                        addonLoading = true
                        scope.launch {
                            addonStreams = viewModel.addonStreams(m)
                            addonLoading = false
                        }
                    }
                }
            }
        }
        item(key = "info") {
            DetailInfo(
                plot = m.plot,
                cast = m.cast,
                director = m.director,
                genre = m.genre,
                onOpenPerson = onOpenPerson,
            )
        }
        if (moreLike.isNotEmpty()) {
            item(key = "more") {
                Spacer(Modifier.height(4.dp))
                MoviePosterRow(stringResource(R.string.vod_more_like_this), moreLike, onOpenMovie)
            }
        }
    }

    if (addonLoading || addonStreams != null) {
        AddonStreamPicker(
            loading = addonLoading,
            streams = addonStreams.orEmpty(),
            onPick = { stream ->
                addonStreams = null
                onPlayUrl("movie:${m.id}", stream.url, m.displayTitle)
            },
            onDismiss = { addonStreams = null; addonLoading = false },
        )
    }
}

/**
 * The picker shown when the user taps "Add-on sources" on a film: a spinner while streams are
 * fetched, then the list each add-on returned (already filtered to directly-playable URLs). Picking
 * one plays it through the normal VOD player.
 */
@Composable
private fun AddonStreamPicker(
    loading: Boolean,
    streams: List<StremioStream>,
    onPick: (StremioStream) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
        ) {
            Text(
                stringResource(R.string.vod_addon_pick_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(22.dp).width(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.vod_addon_loading), style = MaterialTheme.typography.bodyMedium)
                }
                streams.isEmpty() -> Text(
                    stringResource(R.string.vod_addon_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.height(360.dp),
                ) {
                    items(streams, key = { it.url }) { stream -> AddonStreamRow(stream, onPick) }
                }
            }
        }
    }
}

@Composable
private fun AddonStreamRow(stream: StremioStream, onPick: (StremioStream) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onPick(stream) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stream.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stream.addonName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * A show's detail page: the same cinematic header (backdrop, title, meta, synopsis, cast, favourite)
 * above the episode list grouped by season, with a "more like this" shelf at the bottom. Episodes
 * are fetched on demand — pulling every episode of every series up front is what makes a first sync
 * take twenty minutes on a large provider, and most of it is never looked at.
 */
@Composable
fun SeriesDetailScreen(
    seriesId: Long,
    onPlayEpisode: (mediaKey: String, url: String, title: String) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenPerson: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: VodViewModel = viewModel(),
) {
    BackHandler { onBack() }
    var series by remember(seriesId) { mutableStateOf<Series?>(null) }
    var moreLike by remember(seriesId) { mutableStateOf<List<Series>>(emptyList()) }
    val favFocus = remember { FocusRequester() }

    LaunchedEffect(seriesId) {
        val loaded = viewModel.seriesDetail(seriesId)
        series = loaded
        if (loaded != null) {
            viewModel.loadEpisodes(loaded)
            moreLike = viewModel.moreLikeThisSeries(loaded)
        }
    }

    val s = series
    val episodes by (s?.let { viewModel.episodes(it) } ?: viewModel.noEpisodes)
        .collectAsState(initial = emptyList())

    if (s == null) { LoadingDetail(); return }

    LaunchedEffect(s.id) {
        delay(60)
        runCatching { favFocus.requestFocus() }
    }

    // Group episodes by season (seasons ascending, episodes ordered within each) — the classic
    // "episode-by-season" list.
    val seasons = remember(episodes) {
        episodes.groupBy { it.season }
            .mapValues { (_, eps) -> eps.sortedBy { it.episodeNumber } }
            .toSortedMap()
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            DetailBackdrop(title = s.displayTitle, backdropUrl = s.backdropUrl, posterUrl = s.posterUrl, meta = seriesMeta(s)) {
                DetailButton(
                    icon = if (s.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    label = stringResource(if (s.favourite) R.string.common_remove_favourite else R.string.common_favourite),
                    primary = true,
                    modifier = Modifier.focusRequester(favFocus),
                ) {
                    viewModel.toggleSeriesFavourite(s)
                    series = s.copy(favourite = !s.favourite)
                }
            }
        }
        item(key = "info") {
            DetailInfo(
                plot = s.plot,
                cast = s.cast,
                director = null,
                genre = s.genre,
                onOpenPerson = onOpenPerson,
            )
        }

        if (episodes.isEmpty()) {
            item(key = "eploading") {
                Text(
                    stringResource(R.string.vod_loading_episodes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        } else {
            seasons.forEach { (season, eps) ->
                item(key = "season:$season") {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader(stringResource(R.string.vod_season, season))
                }
                items(eps, key = { it.id }) { ep ->
                    EpisodeRow(ep, onPlayEpisode) { viewModel.downloadEpisode(ep, s) }
                }
            }
        }

        if (moreLike.isNotEmpty()) {
            item(key = "more") {
                Spacer(Modifier.height(12.dp))
                SeriesPosterRow(stringResource(R.string.vod_more_like_this), moreLike, onOpenSeries)
            }
        }
    }
}

// ---- Shared detail pieces ----------------------------------------------------------------------

/**
 * The hero header, Plex-style: the poster always shown as an elevated card on the left, with the
 * title, meta line and action buttons beside it, over a backdrop background that falls back to the
 * poster and then to a plain gradient.
 *
 * Built so the hero is **never blank**. Two independent failure modes are covered: a provider that
 * ships a non-null but dead `backdropUrl` (real on the user's box — a good poster, a blank blue
 * hero) now falls the *background* through backdrop → poster → gradient via Coil's `onError`; and
 * the poster *card* falls back to a titled gradient tile if its own URL is missing or dead. So even
 * a title with no backdrop at all and a broken poster still shows its name over the app gradient
 * beside an intentional-looking placeholder card, never an empty rectangle.
 */
@Composable
private fun DetailBackdrop(
    title: String,
    backdropUrl: String?,
    posterUrl: String?,
    meta: String,
    actions: @Composable RowScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(360.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // Background: try the backdrop, fall back to the poster on load error, then to the gradient
        // alone. Coil silently renders nothing on a failed load, so we drive the fallback from the
        // error callback rather than trusting a non-null URL to actually resolve.
        val bgCandidates = remember(backdropUrl, posterUrl) { listOfNotNull(backdropUrl, posterUrl).distinct() }
        var bgIndex by remember(bgCandidates) { mutableStateOf(0) }
        bgCandidates.getOrNull(bgIndex)?.let { model ->
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onError = { if (bgIndex < bgCandidates.lastIndex) bgIndex++ },
            )
        }
        // Scrim: keeps text readable over any art, and — with no art at all — is itself the tasteful
        // gradient the hero falls back to.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.20f),
                    0.5f to Color.Black.copy(alpha = 0.45f),
                    1f to Color.Black.copy(alpha = 0.92f),
                ),
            ),
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            HeroPosterCard(posterUrl = posterUrl, title = title)
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(meta, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f))
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

/**
 * The poster shown as an elevated, rounded card in the hero — the piece that guarantees the header
 * always has real art. When the poster URL is missing or fails to load, it falls back to a gradient
 * tile carrying the title's initial, so the card still reads as intentional rather than broken. Not
 * focusable by design: it is decoration, and the Watch/Resume and favourite buttons remain the
 * hero's d-pad targets.
 */
@Composable
private fun HeroPosterCard(posterUrl: String?, title: String) {
    var posterFailed by remember(posterUrl) { mutableStateOf(false) }
    Box(
        Modifier
            .width(128.dp)
            .height(192.dp)
            .shadow(12.dp, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (posterUrl != null && !posterFailed) {
            AsyncImage(
                model = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onError = { posterFailed = true },
            )
        } else {
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        1f to MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title.trim().take(1).uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * The body under the hero: a genre chip row, the synopsis, and Plex-style **clickable** Cast and
 * Director rows. Tapping a person opens the Person screen ([onOpenPerson]) — everything else in the
 * library featuring them. Each block appears only when the provider gave us something for it.
 *
 * Rows bleed to the screen edge (their own 24dp content padding) rather than sitting inside a padded
 * Column, so a long cast list scrolls cleanly to the edge on a d-pad instead of stopping short.
 */
@Composable
private fun DetailInfo(
    plot: String?,
    cast: String?,
    director: String?,
    genre: String?,
    onOpenPerson: (String) -> Unit,
) {
    val genres = remember(genre) { splitNames(genre, ',', '|', '/') }
    val castList = remember(cast) { splitNames(cast, ',') }
    val directors = remember(director) { splitNames(director, ',') }

    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        if (genres.isNotEmpty()) {
            ChipRow(genres.size, key = { genres[it] }) { GenreChip(genres[it]) }
            Spacer(Modifier.height(16.dp))
        }
        plot?.takeIf { it.isNotBlank() }?.let { LabeledBlock(stringResource(R.string.vod_synopsis), it) }
        if (castList.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionLabel(stringResource(R.string.vod_cast))
            Spacer(Modifier.height(8.dp))
            ChipRow(castList.size, key = { castList[it] }) {
                PersonChip(castList[it]) { onOpenPerson(castList[it]) }
            }
        }
        if (directors.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionLabel(stringResource(R.string.vod_director))
            Spacer(Modifier.height(8.dp))
            ChipRow(directors.size, key = { directors[it] }) {
                PersonChip(directors[it]) { onOpenPerson(directors[it]) }
            }
        }
    }
}

/** Splits a provider multi-value string (comma/pipe/slash) into trimmed, de-duplicated, non-blank names. */
private fun splitNames(raw: String?, vararg delimiters: Char): List<String> =
    raw?.split(*delimiters)
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.distinct()
        .orEmpty()

/** A section label ("Cast", "Director") aligned with the page's 24dp gutter. */
@Composable
private fun SectionLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

@Composable
private fun LabeledBlock(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A horizontal, edge-bleeding shelf of chips — genres or people. */
@Composable
private fun ChipRow(count: Int, key: (Int) -> Any, chip: @Composable (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(count, key = key) { chip(it) }
    }
}

/** A static genre pill. */
@Composable
private fun GenreChip(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/**
 * A focusable, clickable name chip — a cast member or the director. Focus mirrors the app's cue
 * (primary fill + border), and it is padded to a comfortable d-pad target. Clicking opens the
 * Person screen for that name.
 */
@Composable
private fun PersonChip(name: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val container = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Text(
        name,
        style = MaterialTheme.typography.titleSmall,
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(22.dp))
            .background(container)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** A focusable pill button for the hero — Watch/Resume and the favourite toggle. */
@Composable
private fun DetailButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val container = when {
        focused -> MaterialTheme.colorScheme.primary
        primary -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.White.copy(alpha = 0.16f)
    }
    val content = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        primary -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> Color.White
    }
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = content)
    }
}

/** One episode row: season/episode marker, title, and a focus highlight; plays on click. */
@Composable
private fun EpisodeRow(
    ep: Episode,
    onPlay: (mediaKey: String, url: String, title: String) -> Unit,
    onDownload: (Episode) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 3.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier,
            )
            .clickable {
                onPlay("ep:${ep.id}", ep.streamUrl, "S${ep.season}E${ep.episodeNumber} · ${ep.title}")
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "S${ep.season}E${ep.episodeNumber}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(72.dp),
        )
        Text(
            ep.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        EpisodeDownloadButton { onDownload(ep) }
    }
}

/** A trailing "download this episode" affordance; the row itself still plays on click. */
@Composable
private fun EpisodeDownloadButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.vod_download), tint = fg)
    }
}

@Composable
private fun LoadingDetail() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.vod_detail_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Meta lines --------------------------------------------------------------------------------

/** "2021  ·  ★ 7.8  ·  1h 52m" — each part dropped when the provider didn't give it. Genre is shown
 *  separately as its own chip row (see [DetailInfo]), so it stays off this line. */
private fun movieMeta(m: Movie): String = listOfNotNull(
    m.year?.toString(),
    m.rating?.takeIf { it > 0.0 }?.let { "★ ${formatRating(it)}" },
    m.durationSeconds?.takeIf { it > 0 }?.let { formatRuntime(it) },
).joinToString("  ·  ")

/** "2021  ·  ★ 8.1" — series carry no runtime; genre is its own chip row. */
private fun seriesMeta(s: Series): String = listOfNotNull(
    s.year?.toString(),
    s.rating?.takeIf { it > 0.0 }?.let { "★ ${formatRating(it)}" },
).joinToString("  ·  ")

/** Seconds to a compact "1h 52m" / "45m". */
private fun formatRuntime(seconds: Int): String {
    val totalMinutes = seconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
