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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.data.model.Category
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.parser.displayTitle
import app.opentv.ui.VodViewModel
import coil.compose.AsyncImage

/**
 * Movies: a row-based home — Continue Watching, Recommended, Recently added and a row per genre.
 * The left rail provides provider and category navigation; the content pane remains dedicated to
 * search, shelves, or the selected category grid.
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
    val resume by viewModel.continueWatchingMovies.collectAsState()
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
    var browseCategory by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(categories, browseCategory) {
        if (browseCategory != null && categories.isNotEmpty() && categories.none { "${it.sourceId}:${it.id}" == browseCategory }) {
            browseCategory = null
        }
    }

    val hasContent = resume.isNotEmpty() || recommended.isNotEmpty() ||
        recentlyAdded.isNotEmpty() || genreRows.isNotEmpty()

    Row(Modifier.fillMaxSize()) {
        VodCategoryRail(
            title = stringResource(R.string.nav_movies),
            allLabel = stringResource(R.string.vod_all),
            categories = categories,
            selectedCategory = browseCategory,
            sources = sources,
            selectedSource = selectedSource,
            onSelectAll = {
                browseCategory = null
                viewModel.selectMovieCategory(null)
            },
            onSelectCategory = { category ->
                browseCategory = "${category.sourceId}:${category.id}"
                viewModel.selectVodSource(category.sourceId)
                viewModel.selectMovieCategory(category.id)
            },
            onSelectAllSources = { browseCategory = null; viewModel.selectVodSource(null) },
            onSelectSource = { id -> browseCategory = null; viewModel.selectVodSource(id) },
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            SearchAffordance(onOpenSearch)
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
}

/**
 * Shows: the same left-rail navigation and row-based home as Movies. A show opens its detail page,
 * where episodes are fetched on demand instead of during the initial catalogue sync.
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
    val resume by viewModel.continueWatchingEpisodes.collectAsState()
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

    var browseCategory by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(categories, browseCategory) {
        if (browseCategory != null && categories.isNotEmpty() && categories.none { "${it.sourceId}:${it.id}" == browseCategory }) {
            browseCategory = null
        }
    }

    val hasContent = resume.isNotEmpty() || recentlyAdded.isNotEmpty() || genreRows.isNotEmpty()

    Row(Modifier.fillMaxSize()) {
        VodCategoryRail(
            title = stringResource(R.string.nav_shows),
            allLabel = stringResource(R.string.vod_all),
            categories = categories,
            selectedCategory = browseCategory,
            sources = sources,
            selectedSource = selectedSource,
            onSelectAll = {
                browseCategory = null
                viewModel.selectSeriesCategory(null)
            },
            onSelectCategory = { category ->
                browseCategory = "${category.sourceId}:${category.id}"
                viewModel.selectVodSource(category.sourceId)
                viewModel.selectSeriesCategory(category.id)
            },
            onSelectAllSources = { browseCategory = null; viewModel.selectVodSource(null) },
            onSelectSource = { id -> browseCategory = null; viewModel.selectVodSource(id) },
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            SearchAffordance(onOpenSearch)
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
}

/** Profile-specific watch history and favourites, split across movies and shows. */
@Composable
fun MyViewScreen(
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
    viewModel: VodViewModel = viewModel(),
) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val moviesEnabled by settings.moviesEnabled.collectAsState()
    val seriesEnabled by settings.seriesEnabled.collectAsState()
    val history by viewModel.watchHistory.collectAsState()
    val rawFavMovies by viewModel.favouriteMovies.collectAsState()
    val rawFavSeries by viewModel.favouriteSeries.collectAsState()

    val continueMovies = if (moviesEnabled) history.filter { !it.isWatched && it.movie != null } else emptyList()
    val continueShows = if (seriesEnabled) history.filter { !it.isWatched && it.series != null } else emptyList()
    val watchedMovies = if (moviesEnabled) history.filter { it.isWatched && it.movie != null } else emptyList()
    val watchedShows = if (seriesEnabled) history.filter { it.isWatched && it.series != null } else emptyList()
    val favMovies = if (moviesEnabled) rawFavMovies else emptyList()
    val favSeries = if (seriesEnabled) rawFavSeries else emptyList()
    val hasHistory = continueMovies.isNotEmpty() || continueShows.isNotEmpty() ||
        watchedMovies.isNotEmpty() || watchedShows.isNotEmpty()
    val hasFavourites = favMovies.isNotEmpty() || favSeries.isNotEmpty()
    var confirmClearAll by remember { mutableStateOf(false) }

    if (!hasHistory && !hasFavourites) {
        EmptyVod(
            title = stringResource(R.string.nav_my_view),
            body = stringResource(R.string.my_view_empty),
        )
        return
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text(stringResource(R.string.my_view_clear_all_title)) },
            text = { Text(stringResource(R.string.my_view_clear_all_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    viewModel.clearWatchHistory()
                }) { Text(stringResource(R.string.my_view_clear_all_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "my-view-title") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.nav_my_view), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                if (hasHistory) {
                    IconButton(onClick = { confirmClearAll = true }) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            contentDescription = stringResource(R.string.my_view_clear_all_confirm),
                        )
                    }
                }
            }
        }
        if (continueMovies.isNotEmpty() || continueShows.isNotEmpty()) {
            item(key = "continue-header") { SectionHeader(stringResource(R.string.my_view_continue_watching)) }
            if (continueMovies.isNotEmpty()) {
                item(key = "continue-movies") {
                    HistoryPosterRow(stringResource(R.string.my_view_movies), continueMovies, false, viewModel::clearHistoryItem) { item ->
                        onResume(item.mediaKey, item.streamUrl, item.resumeTitle)
                    }
                }
            }
            if (continueShows.isNotEmpty()) {
                item(key = "continue-shows") {
                    HistoryPosterRow(stringResource(R.string.my_view_shows), continueShows, false, viewModel::clearHistoryItem) { item ->
                        onResume(item.mediaKey, item.streamUrl, item.resumeTitle)
                    }
                }
            }
        }
        if (hasFavourites) {
            item(key = "favourites-header") { SectionHeader(stringResource(R.string.my_view_favourites)) }
            if (favMovies.isNotEmpty()) {
                item(key = "favourites-movies") {
                    MoviePosterRow(stringResource(R.string.my_view_movies), favMovies, onOpenMovie)
                }
            }
            if (favSeries.isNotEmpty()) {
                item(key = "favourites-shows") {
                    SeriesPosterRow(stringResource(R.string.my_view_shows), favSeries, onOpenSeries)
                }
            }
        }
        if (watchedMovies.isNotEmpty() || watchedShows.isNotEmpty()) {
            item(key = "watched-header") { SectionHeader(stringResource(R.string.my_view_watched)) }
            if (watchedMovies.isNotEmpty()) {
                item(key = "watched-movies") {
                    HistoryPosterRow(stringResource(R.string.my_view_movies), watchedMovies, true, viewModel::clearHistoryItem) { item ->
                        item.movie?.let(onOpenMovie)
                    }
                }
            }
            if (watchedShows.isNotEmpty()) {
                item(key = "watched-shows") {
                    HistoryPosterRow(stringResource(R.string.my_view_shows), watchedShows, true, viewModel::clearHistoryItem) { item ->
                        item.series?.let(onOpenSeries)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryPosterRow(
    title: String,
    items: List<VodViewModel.HistoryItem>,
    watched: Boolean,
    onClear: (VodViewModel.HistoryItem) -> Unit,
    onClick: (VodViewModel.HistoryItem) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.mediaKey }) { item ->
                Row(
                    Modifier.focusGroup(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PosterCard(
                        title = item.title,
                        posterUrl = item.posterUrl,
                        subtitle = item.subtitle,
                        progress = if (watched) null else item.progress,
                        onClick = { onClick(item) },
                    )
                    var clearFocused by remember(item.mediaKey) { mutableStateOf(false) }
                    IconButton(
                        onClick = { onClear(item) },
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(48.dp)
                            .onFocusChanged { clearFocused = it.isFocused }
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (clearFocused) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .then(
                                if (clearFocused) {
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(8.dp))
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.my_view_clear_item, item.title),
                            tint = if (clearFocused) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                        )
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

// ---- VOD category rail -------------------------------------------------------------------------

@Composable
private fun VodCategoryRail(
    title: String,
    allLabel: String,
    categories: List<Category>,
    selectedCategory: String?,
    sources: List<Source>,
    selectedSource: Long?,
    onSelectAll: () -> Unit,
    onSelectCategory: (Category) -> Unit,
    onSelectAllSources: () -> Unit,
    onSelectSource: (Long) -> Unit,
) {
    Column(
        Modifier
            .width(220.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (sources.size > 1) {
                item(key = "provider-header") {
                    Text(
                        stringResource(R.string.channels_manager_source_header),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                item(key = "provider-all") {
                    VodRailEntry(
                        label = stringResource(R.string.channels_manager_all_sources),
                        selected = selectedSource == null,
                        onClick = onSelectAllSources,
                    )
                }
                items(sources, key = { "src-${it.id}" }) { source ->
                    VodRailEntry(
                        label = source.name,
                        selected = selectedSource == source.id,
                        onClick = { onSelectSource(source.id) },
                    )
                }
                item(key = "provider-divider") { Spacer(Modifier.height(10.dp)) }
            }
            item(key = "all") {
                VodRailEntry(label = allLabel, selected = selectedCategory == null, onClick = onSelectAll)
            }
            items(categories, key = { "${it.sourceId}:${it.id}" }) { category ->
                val categoryKey = "${category.sourceId}:${category.id}"
                val sourceName = sources.firstOrNull { it.id == category.sourceId }?.name
                val label = if (selectedSource == null && sources.size > 1 && sourceName != null) {
                    "$sourceName · ${category.name}"
                } else {
                    category.name
                }
                VodRailEntry(label = label, selected = selectedCategory == categoryKey) {
                    onSelectCategory(category)
                }
            }
        }
    }
}

@Composable
private fun VodRailEntry(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
