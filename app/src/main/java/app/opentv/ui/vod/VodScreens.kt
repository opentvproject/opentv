/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.parser.displayTitle
import app.opentv.ui.VodViewModel
import coil.compose.AsyncImage

/**
 * Movies: a modern, row-based home — Continue Watching, Recommended, Recently added and a row per
 * genre, each a horizontally-scrolling shelf of poster cards. A category chip strip along the top
 * keeps whole-category browsing one press away without a permanent rail eating the width. Clicking
 * a film opens its detail page rather than playing straight away, the streaming-app convention.
 */
@Composable
fun MoviesScreen(
    onOpenMovie: (Movie) -> Unit,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
    onOpenSearch: () -> Unit,
    hasSources: Boolean,
    isSyncing: Boolean,
    viewModel: VodViewModel = viewModel(),
) {
    val categories by viewModel.movieCategories.collectAsState()
    val resume by viewModel.continueWatching.collectAsState()
    val recommended by viewModel.recommendedMovies.collectAsState()
    val recentlyAdded by viewModel.recentlyAddedMovies.collectAsState()
    val genreRows by viewModel.movieGenreRows.collectAsState()
    val categoryMovies by viewModel.movies.collectAsState()
    val vodLoading by viewModel.vodLoading.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val selectedSource by viewModel.selectedVodSource.collectAsState()

    // Pull the movie library the first time this tab is opened, not at login; refresh the computed
    // home rows (recommended, by-genre) on open too — cheap, and covers a library already on disk.
    LaunchedEffect(Unit) {
        if (hasSources) viewModel.ensureVodLoaded()
        viewModel.loadHomeFeeds()
    }

    // null = the curated home rows; a category id = that category's full grid.
    // Saveable so backing out of a title returns to the category grid, not the home rows.
    var browseCategory by rememberSaveable { mutableStateOf<String?>(null) }

    val hasContent = resume.isNotEmpty() || recommended.isNotEmpty() ||
        recentlyAdded.isNotEmpty() || genreRows.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        SearchAffordance(onOpenSearch)
        if (sources.size > 1) {
            ProviderChips(
                sources = sources,
                selected = selectedSource,
                onSelectAll = { browseCategory = null; viewModel.selectVodSource(null) },
                onSelectSource = { id -> browseCategory = null; viewModel.selectVodSource(id) },
            )
        }
        CategoryChips(
            entries = categories.map { it.id to it.name },
            selected = browseCategory,
            onSelectHome = { browseCategory = null },
            onSelectCategory = { id -> browseCategory = id; viewModel.selectMovieCategory(id) },
        )
        // Weighted so the shelves fill the space under the fixed search + chips header, exactly and
        // unambiguously — the same reason Live TV weights its guide grid.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                browseCategory != null -> MovieCategoryGrid(categoryMovies, viewModel, onOpenMovie)
                !hasContent -> when {
                    vodLoading || isSyncing -> LoadingVod(stringResource(R.string.vod_loading_movies))
                    hasSources -> EmptyVod(stringResource(R.string.vod_no_movies), stringResource(R.string.vod_no_movies_provider))
                    else -> EmptyVod(stringResource(R.string.vod_no_movies), stringResource(R.string.vod_no_movies_add))
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (resume.isNotEmpty()) item(key = "cw") { ContinueWatchingRow(resume, onResume) }
                    if (recommended.isNotEmpty()) item(key = "rec") {
                        MoviePosterRow(stringResource(R.string.vod_recommended), recommended, onOpenMovie)
                    }
                    if (recentlyAdded.isNotEmpty()) item(key = "recent") {
                        MoviePosterRow(stringResource(R.string.vod_recently_added), recentlyAdded, onOpenMovie)
                    }
                    items(genreRows, key = { "g:${it.genre}" }) { group ->
                        MoviePosterRow(group.genre, group.items, onOpenMovie)
                    }
                }
            }
        }
    }
}

/**
 * Shows: the same row-based home as Movies (Continue Watching, Recently added, genre rows) with the
 * category chip strip for whole-category browsing. A show opens its detail page — where episodes are
 * fetched on demand, since pulling every episode of every series up front is what makes a first sync
 * take forever.
 */
@Composable
fun SeriesScreen(
    onOpenSeries: (Series) -> Unit,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
    onOpenSearch: () -> Unit,
    hasSources: Boolean,
    isSyncing: Boolean,
    viewModel: VodViewModel = viewModel(),
) {
    val categories by viewModel.seriesCategories.collectAsState()
    val resume by viewModel.continueWatching.collectAsState()
    val recentlyAdded by viewModel.recentlyAddedSeries.collectAsState()
    val genreRows by viewModel.seriesGenreRows.collectAsState()
    val categorySeries by viewModel.series.collectAsState()
    val vodLoading by viewModel.vodLoading.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val selectedSource by viewModel.selectedVodSource.collectAsState()

    LaunchedEffect(Unit) {
        if (hasSources) viewModel.ensureVodLoaded()
        viewModel.loadHomeFeeds()
    }

    // Saveable so backing out of a title returns to the category grid, not the home rows.
    var browseCategory by rememberSaveable { mutableStateOf<String?>(null) }

    val hasContent = resume.isNotEmpty() || recentlyAdded.isNotEmpty() || genreRows.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        SearchAffordance(onOpenSearch)
        if (sources.size > 1) {
            ProviderChips(
                sources = sources,
                selected = selectedSource,
                onSelectAll = { browseCategory = null; viewModel.selectVodSource(null) },
                onSelectSource = { id -> browseCategory = null; viewModel.selectVodSource(id) },
            )
        }
        CategoryChips(
            entries = categories.map { it.id to it.name },
            selected = browseCategory,
            onSelectHome = { browseCategory = null },
            onSelectCategory = { id -> browseCategory = id; viewModel.selectSeriesCategory(id) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                browseCategory != null -> SeriesCategoryGrid(categorySeries, onOpenSeries)
                !hasContent -> when {
                    vodLoading || isSyncing -> LoadingVod(stringResource(R.string.vod_loading_shows))
                    hasSources -> EmptyVod(stringResource(R.string.vod_no_shows), stringResource(R.string.vod_no_shows_provider))
                    else -> EmptyVod(stringResource(R.string.vod_no_shows), stringResource(R.string.vod_no_shows_add))
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (resume.isNotEmpty()) item(key = "cw") { ContinueWatchingRow(resume, onResume) }
                    if (recentlyAdded.isNotEmpty()) item(key = "recent") {
                        SeriesPosterRow(stringResource(R.string.vod_recently_added), recentlyAdded, onOpenSeries)
                    }
                    items(genreRows, key = { "g:${it.genre}" }) { group ->
                        SeriesPosterRow(group.genre, group.items, onOpenSeries)
                    }
                }
            }
        }
    }
}

// ---- Whole-category browse grids ---------------------------------------------------------------

/** One category's films as a poster grid. Quality variants collapse to one card, badged. */
@Composable
private fun MovieCategoryGrid(movies: List<Movie>, viewModel: VodViewModel, onOpenMovie: (Movie) -> Unit) {
    if (movies.isEmpty()) { LoadingVod(stringResource(R.string.vod_loading_movies)); return }
    val groups = remember(movies) { viewModel.collapseVariants(movies) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        gridItems(groups, key = { it.primary.id }) { group ->
            val quality = group.variants.firstOrNull()?.qualityLabel?.takeIf { it.isNotBlank() }
            val badge = quality
                ?: if (group.hasMultipleQualities) {
                    stringResource(R.string.guide_qualities_count, group.variants.size)
                } else {
                    null
                }
            PosterCard(
                title = group.primary.displayTitle,
                posterUrl = group.primary.posterUrl,
                subtitle = group.primary.year?.toString(),
                rating = group.primary.rating,
                qualityBadge = badge,
                onClick = { onOpenMovie(group.primary) },
            )
        }
    }
}

/** One category's shows as a poster grid. */
@Composable
private fun SeriesCategoryGrid(series: List<Series>, onOpenSeries: (Series) -> Unit) {
    if (series.isEmpty()) { LoadingVod(stringResource(R.string.vod_loading_shows)); return }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        gridItems(series, key = { it.id }) { item ->
            PosterCard(
                title = item.displayTitle,
                posterUrl = item.posterUrl,
                subtitle = item.year?.toString(),
                rating = item.rating,
                onClick = { onOpenSeries(item) },
            )
        }
    }
}

// ---- Shared shelves ----------------------------------------------------------------------------

/** A titled horizontal shelf of movie poster cards. Shared by the home and the detail's "more like this". */
@Composable
internal fun MoviePosterRow(title: String, movies: List<Movie>, onOpenMovie: (Movie) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(movies, key = { it.id }) { movie ->
                PosterCard(
                    title = movie.displayTitle,
                    posterUrl = movie.posterUrl,
                    subtitle = movie.year?.toString(),
                    rating = movie.rating,
                    onClick = { onOpenMovie(movie) },
                )
            }
        }
    }
}

/** A titled horizontal shelf of series poster cards. */
@Composable
internal fun SeriesPosterRow(title: String, series: List<Series>, onOpenSeries: (Series) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(series, key = { it.id }) { item ->
                PosterCard(
                    title = item.displayTitle,
                    posterUrl = item.posterUrl,
                    subtitle = item.year?.toString(),
                    rating = item.rating,
                    onClick = { onOpenSeries(item) },
                )
            }
        }
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
    )
}

/**
 * The reusable poster card: art, title and an optional year, with a rating chip, a quality badge and
 * a resume progress bar drawn over the art where the data is there. The focused card scales up and
 * gains a primary border — the app's established focus cue — and, being focusable, the lazy row
 * brings it into view on its own.
 */
@Composable
internal fun PosterCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    rating: Double? = null,
    qualityBadge: String? = null,
    progress: Float? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "posterScale")
    Column(
        modifier
            .width(POSTER_WIDTH)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    else Modifier,
                ),
        ) {
            AsyncImage(
                model = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            rating?.takeIf { it > 0.0 }?.let {
                Badge(
                    text = "★ ${formatRating(it)}",
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
            }
            qualityBadge?.let {
                Badge(
                    text = it,
                    highlight = true,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
            progress?.let {
                LinearProgressIndicator(
                    progress = { it },
                    modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** A small rounded chip drawn over poster art — a rating or a quality label. */
@Composable
private fun Badge(text: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val bg = if (highlight) MaterialTheme.colorScheme.primary
    else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.66f)
    val fg = if (highlight) MaterialTheme.colorScheme.onPrimary else androidx.compose.ui.graphics.Color.White
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---- Continue watching -------------------------------------------------------------------------

@Composable
internal fun ContinueWatchingRow(
    items: List<VodViewModel.ResumeItem>,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.vod_continue_watching))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.mediaKey }) { item ->
                ResumeCard(item) { onResume(item.mediaKey, item.streamUrl, item.title) }
            }
        }
    }
}

/** A landscape resume thumbnail with a progress fill — a movie or an episode part-way through. */
@Composable
private fun ResumeCard(item: VodViewModel.ResumeItem, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "resumeScale")
    Column(
        Modifier
            .width(190.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(107.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                    else Modifier,
                ),
        ) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomStart),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- Category chips ----------------------------------------------------------------------------

/**
 * A horizontal chip strip along the top of the home: an "All" chip returns to the curated rows, and
 * each following chip opens that category's full grid. Keeps whole-category browsing reachable on a
 * d-pad without a permanent side rail taking the width.
 */
@Composable
private fun CategoryChips(
    entries: List<Pair<String, String>>,
    selected: String?,
    onSelectHome: () -> Unit,
    onSelectCategory: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "label") {
            Text(
                stringResource(R.string.vod_categories),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        item(key = "all") { Chip(stringResource(R.string.vod_all), selected == null, onSelectHome) }
        items(entries, key = { it.first }) { (id, name) ->
            Chip(name, selected == id) { onSelectCategory(id) }
        }
    }
}

/**
 * A provider filter above the category chips, shown only when more than one source is configured —
 * pick a provider to browse just its Movies/Shows categories, or "All sources" to fold them.
 */
@Composable
private fun ProviderChips(
    sources: List<Source>,
    selected: Long?,
    onSelectAll: () -> Unit,
    onSelectSource: (Long) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "plabel") {
            Text(
                stringResource(R.string.channels_manager_source_header),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        item(key = "pall") {
            Chip(stringResource(R.string.channels_manager_all_sources), selected == null, onSelectAll)
        }
        items(sources, key = { it.id }) { source ->
            Chip(source.name, selected == source.id) { onSelectSource(source.id) }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

// ---- Search / loading / empty ------------------------------------------------------------------

/**
 * The search entry at the top of the Movies and Shows home. The rail's global search already covers
 * movies and series; this makes it reachable without leaving the tab. Focusable for d-pad on TV and
 * tappable on touch, it just opens the existing search screen.
 */
@Composable
private fun SearchAffordance(onOpenSearch: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onOpenSearch)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (focused) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(Icons.Filled.Search, contentDescription = null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.vod_search_hint),
            style = MaterialTheme.typography.titleMedium,
            color = tint,
        )
    }
}

@Composable
private fun EmptyVod(title: String, body: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingVod(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.vod_large_provider_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Poster shelf card width; the grid uses an adaptive min size close to this. */
private val POSTER_WIDTH = 140.dp

/** Rating to one decimal place, locale-independent (the "★" is drawn beside it). */
internal fun formatRating(rating: Double): String = String.format(java.util.Locale.US, "%.1f", rating)
