/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.util.Log
import app.opentv.core.AppSettings
import app.opentv.data.db.CategoryDao
import app.opentv.data.db.ChannelDao
import app.opentv.data.db.EpisodeDao
import app.opentv.data.db.MovieDao
import app.opentv.data.db.PlaybackPositionDao
import app.opentv.data.db.SeriesDao
import app.opentv.data.db.SourceDao
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Episode
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.parser.M3uParser
import app.opentv.data.parser.VodTitleCleaner
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.TmdbClient
import app.opentv.data.remote.XtreamApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Keeps one channel per distinct quality signature, preserving order (so "best first" is
 * preserved when the caller has already sorted by rank).
 *
 * Two streams with the same rank AND the same label are treated as the same quality — one is
 * kept, the rest dropped. This is what collapses "RAW / RAW" and "HD / HD" duplicates down to
 * a single option, and turns a falsely-multi-quality channel back into a single one.
 */
internal fun distinctByQuality(channels: List<app.opentv.data.model.Channel>): List<app.opentv.data.model.Channel> {
    val seen = HashSet<String>()
    return channels.filter { seen.add("${it.qualityRank}|${it.qualityLabel.lowercase()}") }
}

/** A home/detail row: a genre label and the titles under it. Generic so movies and series share it. */
@androidx.compose.runtime.Immutable
data class GenreGroup<T>(val genre: String, val items: List<T>)

/**
 * One library entry a person is credited in — a movie or a series — for the Person screen's mixed
 * poster grid. A thin wrapper so the screen can render one grid yet still route a click to the right
 * detail page (movie vs series) without a second lookup.
 */
sealed interface PersonTitle {
    data class MovieItem(val movie: Movie) : PersonTitle
    data class SeriesItem(val series: Series) : PersonTitle
}

/** One quality variant of a film, with the quality parsed from its name by [ChannelNameNormalizer]. */
data class MovieVariant(val movie: Movie, val qualityLabel: String, val qualityRank: Int)

/** One logical film with its switchable quality tiers, best first — the VOD analogue of a channel group. */
@androidx.compose.runtime.Immutable
data class MovieVariantGroup(
    /** Best-quality variant: what a row shows and plays by default. */
    val primary: Movie,
    /** Every variant (including [primary]), best quality first. */
    val variants: List<MovieVariant>,
) {
    val hasMultipleQualities: Boolean get() = variants.size > 1
}

/** A standalone 4-digit release year (19xx/20xx) as it appears inside a VOD title. */
private val VOD_YEAR = Regex("""\b(19|20)\d{2}\b""")

/**
 * Collapses obvious quality variants of the same film — "The Godfather 1972 HD" and
 * "The Godfather 4K" — into one entry with switchable tiers, the VOD analogue of a channel's
 * quality group. Pure and in-memory: movies carry no stored groupKey, so the key is computed here
 * from the normalised, year-stripped name plus the year (the field, else the one embedded in the
 * name). Group order follows first appearance; variants within a group are best-quality first.
 *
 * It folds only genuine quality variants: edition tokens [ChannelNameNormalizer] does not know
 * (IMAX, EXTENDED, 3D) stay in the name and keep those cuts separate — intended. Two same-named
 * films that both lack any year will merge, an accepted edge for a best-effort collapse.
 */
internal fun collapseMovieVariants(movies: List<Movie>): List<MovieVariantGroup> {
    val groups = LinkedHashMap<String, MutableList<MovieVariant>>()
    for (movie in movies) {
        val embeddedYear = VOD_YEAR.find(movie.name)?.value?.toIntOrNull()
        val bareName = VOD_YEAR.replace(movie.name, " ")
        val normalized = ChannelNameNormalizer.normalize(bareName)
        val year = movie.year ?: embeddedYear
        val key = normalized.groupKey + "|" + (year?.toString() ?: "")
        groups.getOrPut(key) { mutableListOf() }
            .add(MovieVariant(movie, normalized.qualityLabel, normalized.qualityRank))
    }
    return groups.values.map { variants ->
        val ordered = variants.sortedWith(
            compareByDescending<MovieVariant> { it.qualityRank }.thenBy { it.movie.name },
        )
        MovieVariantGroup(primary = ordered.first().movie, variants = ordered)
    }
}

/**
 * Channels, movies and series.
 *
 * Same principle as [EpgRepository]: a refresh that fails must never leave the user with less
 * than they started with. Catalogue writes go through [ChannelDao.replaceCatalogue], which
 * merges rather than wipes and carries favourites, hidden flags and manual ordering across.
 */
class CatalogRepository(
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val positionDao: PlaybackPositionDao,
    private val api: XtreamApi,
    private val stalkerApi: StalkerApi,
    private val http: OkHttpClient,
    private val settings: AppSettings,
) {

    /** TMDB back-fill for VOD detail pages, gated on a user-supplied key. See [TmdbClient]. */
    private val tmdb = TmdbClient(http, settings)

    sealed interface SyncResult {
        data class Success(
            val channelCount: Int,
            val movieCount: Int,
            val seriesCount: Int,
        ) : SyncResult

        data class Failed(val reason: String, val cause: Throwable?) : SyncResult
    }

    fun observeChannels(sourceId: Long? = null, categoryId: String? = null): Flow<List<Channel>> =
        channelDao.observe(sourceId, categoryId)

    fun observeChannelsIn(categoryIds: List<String>): Flow<List<Channel>> =
        channelDao.observeInCategories(categoryIds)

    /**
     * Channels in a set of categories INCLUDING hidden ones, optionally scoped to one source — the
     * channel manager's browse feed. Separate from [observeChannelsIn] (which drops `hidden` rows
     * for the guide) because the manager must show hidden channels so they can be un-hidden.
     */
    fun observeChannelsInIncludingHidden(sourceId: Long?, categoryIds: List<String>): Flow<List<Channel>> =
        channelDao.observeInCategoriesIncludingHidden(sourceId, categoryIds)

    fun observeFavouriteChannels(): Flow<List<Channel>> = channelDao.observeFavourites()

    /** Reactive number of visible channels on disk — the UI uses this to tell "guide still
     * building" (channels exist) apart from "nothing loaded" (a failed or empty sync). */
    fun observeChannelCount(): Flow<Int> = channelDao.observeVisibleCount()

    fun observeCategories(kind: StreamKind): Flow<List<Category>> = categoryDao.observe(kind)

    fun observeMovies(categoryId: String? = null): Flow<List<Movie>> = movieDao.observe(categoryId)

    fun observeSeries(categoryId: String? = null): Flow<List<Series>> = seriesDao.observe(categoryId)

    fun observeEpisodes(sourceId: Long, seriesId: String): Flow<List<Episode>> =
        episodeDao.observeForSeries(sourceId, seriesId)

    fun searchChannels(query: String): Flow<List<Channel>> = channelDao.search(query)

    fun searchChannelsIncludingHidden(query: String): Flow<List<Channel>> =
        channelDao.searchIncludingHidden(query)

    fun searchMovies(query: String): Flow<List<Movie>> = movieDao.search(query)

    fun searchSeries(query: String): Flow<List<Series>> = seriesDao.search(query)

    suspend fun channel(id: Long): Channel? = channelDao.byId(id)

    suspend fun movie(id: Long): Movie? = movieDao.byId(id)

    /**
     * The IMDb id for a film, for Stremio add-on stream lookups. Resolved through the user's TMDB
     * key (using the provider's TMDB id when present, else a title+year search). Null without a key
     * or a match — the add-on feature stays inert rather than guessing.
     */
    suspend fun imdbIdFor(movie: Movie): String? = withContext(Dispatchers.IO) {
        tmdb.imdbId(title = movie.name, year = movie.year, isMovie = true, tmdbId = movie.tmdbId)
    }

    suspend fun episode(id: Long): Episode? = episodeDao.byId(id)

    suspend fun movieByStreamUrl(url: String): Movie? = movieDao.byStreamUrl(url)

    suspend fun episodeByStreamUrl(url: String): Episode? = episodeDao.byStreamUrl(url)

    suspend fun series(id: Long): app.opentv.data.model.Series? = seriesDao.byId(id)

    // ---- Netflix-style home feeds ---------------------------------------------------------------
    // All local: derived from the catalogue already on disk plus the active profile's watch history.
    // The plain catalogue rows (recently added) are Flows so they fill in live as a VOD sync lands;
    // the computed rows (recommended, by-genre, more-like-this) are one-shot suspend reads, cheap
    // enough to recompute on screen open over a few thousand titles (one in-memory pass each — a
    // per-genre LIKE query would multiply round-trips and match substrings). A later UI agent wraps
    // these into home rows and detail screens.

    /** "Recently Added" movies, newest first. Reactive. */
    fun recentlyAddedMovies(limit: Int = 30): Flow<List<Movie>> = movieDao.observeRecentlyAdded(limit)

    /** "Recently Added" series, newest first. Reactive. */
    fun recentlyAddedSeries(limit: Int = 30): Flow<List<Series>> = seriesDao.observeRecentlyAdded(limit)

    /** Every movie, newest first — for a caller that wants to build its own groupings. */
    suspend fun allMovies(): List<Movie> = withContext(Dispatchers.IO) { movieDao.all() }

    /** Every series, newest first. */
    suspend fun allSeries(): List<Series> = withContext(Dispatchers.IO) { seriesDao.all() }

    /** How many movies / series are on disk — a cheap COUNT the home screen uses to tell "the
     *  library grew" from "unchanged since last open" without loading every row. */
    suspend fun movieCount(): Int = withContext(Dispatchers.IO) { movieDao.count() }
    suspend fun seriesCount(): Int = withContext(Dispatchers.IO) { seriesDao.count() }

    /**
     * Movies grouped by genre for the by-genre home rows: the [maxGenres] biggest genres, each with
     * up to [perGenre] titles (newest first). A movie appears under every genre it lists — provider
     * genre strings are frequently multi-valued ("Action, Thriller" / "Action|Thriller"), so they
     * are split on comma and pipe (see [splitGenres]).
     */
    suspend fun moviesByGenre(maxGenres: Int = 12, perGenre: Int = 30): List<GenreGroup<Movie>> =
        withContext(Dispatchers.IO) { groupByGenre(movieDao.all(), Movie::genre, maxGenres, perGenre) }

    /** Series grouped by genre for the by-genre home rows. See [moviesByGenre]. */
    suspend fun seriesByGenre(maxGenres: Int = 12, perGenre: Int = 30): List<GenreGroup<Series>> =
        withContext(Dispatchers.IO) { groupByGenre(seriesDao.all(), Series::genre, maxGenres, perGenre) }

    /**
     * Movies grouped by genre from an ALREADY-LOADED list — the single-scan path the home screen
     * uses. [VodViewModel.loadHomeFeeds] reads [allMovies] once and hands that one list to this, to
     * [recommendedMoviesFrom], etc., so opening Movies scans the (20k-title) table once instead of
     * once per row. Pure grouping, off the main thread. See [moviesByGenre] for the scanning variant.
     */
    suspend fun moviesByGenreFrom(all: List<Movie>, maxGenres: Int = 12, perGenre: Int = 30): List<GenreGroup<Movie>> =
        withContext(Dispatchers.Default) { groupByGenre(all, Movie::genre, maxGenres, perGenre) }

    /** Series grouped by genre from an already-loaded list — the single-scan path. See [moviesByGenreFrom]. */
    suspend fun seriesByGenreFrom(all: List<Series>, maxGenres: Int = 12, perGenre: Int = 30): List<GenreGroup<Series>> =
        withContext(Dispatchers.Default) { groupByGenre(all, Series::genre, maxGenres, perGenre) }

    /**
     * "Recommended for you" — a simple, explainable genre-affinity heuristic, no ML.
     *
     * Tallies the genres of the movies this profile has watched or resumed, then returns the
     * highest-scoring UNWATCHED movies, where a movie's score is how many of its genres the profile
     * favours (ties broken by rating, then recency). With no usable history — a fresh profile, or
     * only watched movies that carry no genre — it falls back to top-rated, then recently-added.
     */
    suspend fun recommendedMovies(profileId: Long, limit: Int = 30): List<Movie> =
        withContext(Dispatchers.IO) { recommendFrom(movieDao.all(), watchedMovieIds(profileId), limit) }

    /**
     * "Recommended for you" from an ALREADY-LOADED movie list — the single-scan path (see
     * [recommendedMovies]). Only the profile's watch history is read from disk here; the movie
     * library is the caller's [allMovies] list, shared with the genre rows so the home screen scans
     * the (20k-title) table once for the whole home feed rather than once per row.
     */
    suspend fun recommendedMoviesFrom(all: List<Movie>, profileId: Long, limit: Int = 30): List<Movie> =
        withContext(Dispatchers.IO) { recommendFrom(all, watchedMovieIds(profileId), limit) }

    /** The movie ids this profile has watched or resumed — the input to the affinity heuristic. */
    private suspend fun watchedMovieIds(profileId: Long): Set<Long> =
        positionDao.forProfile(profileId).mapNotNull { movieIdFromMediaKey(it.mediaKey) }.toSet()

    /** The pure genre-affinity ranking pass shared by [recommendedMovies] and [recommendedMoviesFrom]. */
    private fun recommendFrom(all: List<Movie>, watchedMovieIds: Set<Long>, limit: Int): List<Movie> {
        val byId = all.associateBy { it.id }

        val affinity = HashMap<String, Int>()
        for (id in watchedMovieIds) {
            val watched = byId[id] ?: continue
            for (genre in splitGenres(watched.genre)) affinity[genre] = (affinity[genre] ?: 0) + 1
        }

        val unwatched = all.filter { it.id !in watchedMovieIds }

        fun topRatedFallback(): List<Movie> = unwatched
            .sortedWith(compareByDescending<Movie> { it.rating ?: -1.0 }.thenByDescending { it.addedMillis })
            .take(limit)

        if (affinity.isEmpty()) return topRatedFallback()

        val ranked = unwatched
            .map { it to genreScore(it.genre, affinity) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Movie, Int>> { it.second }
                    .thenByDescending { it.first.rating ?: -1.0 }
                    .thenByDescending { it.first.addedMillis },
            )
            .map { it.first }
            .take(limit)

        return ranked.ifEmpty { topRatedFallback() }
    }

    /**
     * "More Like This" for a movie: other titles sharing a genre, most genres in common first,
     * same-source titles preferred, the film itself excluded. Falls back to other titles in the
     * same source/category when the movie has no genre metadata to match on.
     */
    suspend fun moreLikeThis(movie: Movie, limit: Int = 20): List<Movie> = withContext(Dispatchers.IO) {
        val genres = splitGenres(movie.genre).toSet()
        if (genres.isEmpty()) {
            return@withContext movieDao.similarByCategory(movie.sourceId, movie.categoryId, movie.id, limit)
        }
        movieDao.all().asSequence()
            .filter { it.id != movie.id }
            .map { it to sharedGenreCount(it.genre, genres) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Movie, Int>> { it.second }
                    .thenByDescending { it.first.sourceId == movie.sourceId }
                    .thenByDescending { it.first.rating ?: -1.0 },
            )
            .map { it.first }
            .take(limit)
            .toList()
    }

    // ---- Related by person (Plex-style "click an actor / director") ----------------------------

    /** Movies a person is billed in, best-rated first. Blank query yields nothing. */
    suspend fun moviesWithActor(name: String, limit: Int = 40): List<Movie> = withContext(Dispatchers.IO) {
        name.trim().takeIf { it.isNotEmpty() }?.let { movieDao.moviesWithActor(it, limit) }.orEmpty()
    }

    /** Movies a person directed, best-rated first. */
    suspend fun moviesByDirector(name: String, limit: Int = 40): List<Movie> = withContext(Dispatchers.IO) {
        name.trim().takeIf { it.isNotEmpty() }?.let { movieDao.moviesByDirector(it, limit) }.orEmpty()
    }

    /** Series a person is billed in, best-rated first. */
    suspend fun seriesWithActor(name: String, limit: Int = 40): List<Series> = withContext(Dispatchers.IO) {
        name.trim().takeIf { it.isNotEmpty() }?.let { seriesDao.seriesWithActor(it, limit) }.orEmpty()
    }

    /**
     * Everything in the library featuring a person — the data behind the Person screen. Merges movies
     * they act in, movies they directed and series they act in, de-duplicating movies that credit the
     * same person as both actor and director. Movies (best-rated first) lead, then series.
     */
    suspend fun titlesWithPerson(name: String, perKind: Int = 40): List<PersonTitle> =
        withContext(Dispatchers.IO) {
            val query = name.trim()
            if (query.isEmpty()) return@withContext emptyList()

            val movies = LinkedHashMap<Long, Movie>()
            for (m in movieDao.moviesWithActor(query, perKind)) movies[m.id] = m
            for (m in movieDao.moviesByDirector(query, perKind)) movies.putIfAbsent(m.id, m)

            val orderedMovies = movies.values.sortedWith(
                compareByDescending<Movie> { it.rating ?: -1.0 }.thenByDescending { it.addedMillis },
            )
            val series = seriesDao.seriesWithActor(query, perKind)

            buildList(orderedMovies.size + series.size) {
                orderedMovies.forEach { add(PersonTitle.MovieItem(it)) }
                series.forEach { add(PersonTitle.SeriesItem(it)) }
            }
        }

    /** "More Like This" for a series. See [moreLikeThis]. */
    suspend fun moreLikeThisSeries(series: Series, limit: Int = 20): List<Series> = withContext(Dispatchers.IO) {
        val genres = splitGenres(series.genre).toSet()
        if (genres.isEmpty()) {
            return@withContext seriesDao.similarByCategory(series.sourceId, series.categoryId, series.id, limit)
        }
        seriesDao.all().asSequence()
            .filter { it.id != series.id }
            .map { it to sharedGenreCount(it.genre, genres) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Series, Int>> { it.second }
                    .thenByDescending { it.first.sourceId == series.sourceId }
                    .thenByDescending { it.first.rating ?: -1.0 },
            )
            .map { it.first }
            .take(limit)
            .toList()
    }

    /**
     * Loads a movie and, if its detail fields are still bare, back-fills them from the provider's
     * `get_vod_info` and persists the merged row before returning it. Safe to call whenever a detail
     * screen opens: it runs off the main thread, only touches the network when something is missing,
     * tolerates any failure (returning the row unchanged), and — because it copies the stored row —
     * preserves the favourite flag. Non-Xtream sources are returned as-is.
     */
    suspend fun movieDetail(id: Long): Movie? = withContext(Dispatchers.IO) {
        val movie = movieDao.byId(id) ?: return@withContext null
        var result = movie
        val source = sourceDao.byId(movie.sourceId)

        // 1) Provider back-fill from get_vod_info, for a still-bare row on an Xtream source.
        if (!result.isEnriched && source?.kind == SourceKind.XTREAM) {
            val info = runCatching { api.movieInfo(source, result.streamId) }.getOrNull()
            if (info != null) {
                result = result.copy(
                    backdropUrl = result.backdropUrl ?: info.backdropUrl,
                    cast = result.cast ?: info.cast,
                    director = result.director ?: info.director,
                    genre = result.genre ?: info.genre,
                    tmdbId = result.tmdbId ?: info.tmdbId,
                    plot = result.plot ?: info.plot,
                    rating = result.rating ?: info.rating,
                    year = result.year ?: info.year,
                    durationSeconds = result.durationSeconds ?: info.durationSeconds,
                )
            }
        }

        // 2) TMDB fallback for whatever the provider still left blank — only when the user set a
        //    key, and only while a headline visual field is missing (so it stops re-fetching once
        //    filled). This is the "match Plex" back-fill for posters, backdrops, synopsis and cast.
        if (tmdb.isConfigured() && (result.backdropUrl == null || result.plot == null || result.cast == null)) {
            val meta = runCatching {
                tmdb.movieMeta(VodTitleCleaner.clean(result.name), result.year, result.tmdbId)
            }.getOrNull()
            if (meta != null) {
                result = result.copy(
                    posterUrl = result.posterUrl ?: meta.posterUrl,
                    backdropUrl = result.backdropUrl ?: meta.backdropUrl,
                    plot = result.plot ?: meta.overview,
                    cast = result.cast ?: meta.cast,
                    director = result.director ?: meta.director,
                    genre = result.genre ?: meta.genre,
                    tmdbId = result.tmdbId ?: meta.tmdbId,
                    rating = result.rating ?: meta.rating,
                    year = result.year ?: meta.year,
                )
            }
        }

        if (result != movie) movieDao.upsertAll(listOf(result))
        result
    }

    /** Loads a series and lazily back-fills its detail fields from `get_series_info`. See [movieDetail]. */
    suspend fun seriesDetail(id: Long): Series? = withContext(Dispatchers.IO) {
        val series = seriesDao.byId(id) ?: return@withContext null
        var result = series
        val source = sourceDao.byId(series.sourceId)

        // 1) Provider back-fill from get_series_info, for a still-bare row on an Xtream source.
        if (!result.isEnriched && source?.kind == SourceKind.XTREAM) {
            val info = runCatching { api.seriesInfo(source, result.seriesId) }.getOrNull()
            if (info != null) {
                result = result.copy(
                    backdropUrl = result.backdropUrl ?: info.backdropUrl,
                    cast = result.cast ?: info.cast,
                    genre = result.genre ?: info.genre,
                    tmdbId = result.tmdbId ?: info.tmdbId,
                    plot = result.plot ?: info.plot,
                    rating = result.rating ?: info.rating,
                    year = result.year ?: info.year,
                )
            }
        }

        // 2) TMDB fallback for anything still missing (see [movieDetail]); TMDB has no director for TV.
        if (tmdb.isConfigured() && (result.backdropUrl == null || result.plot == null || result.cast == null)) {
            val meta = runCatching {
                tmdb.seriesMeta(VodTitleCleaner.clean(result.name), result.year, result.tmdbId)
            }.getOrNull()
            if (meta != null) {
                result = result.copy(
                    posterUrl = result.posterUrl ?: meta.posterUrl,
                    backdropUrl = result.backdropUrl ?: meta.backdropUrl,
                    plot = result.plot ?: meta.overview,
                    cast = result.cast ?: meta.cast,
                    genre = result.genre ?: meta.genre,
                    tmdbId = result.tmdbId ?: meta.tmdbId,
                    rating = result.rating ?: meta.rating,
                    year = result.year ?: meta.year,
                )
            }
        }

        if (result != series) seriesDao.upsertAll(listOf(result))
        result
    }

    /**
     * Collapses a list of movies (e.g. one category's titles) into logical films with switchable
     * quality tiers — see [collapseMovieVariants]. Pure; hand it whatever list the UI is about to
     * show. Kept here as the discoverable entry point for the VOD UI.
     */
    fun collapseVariants(movies: List<Movie>): List<MovieVariantGroup> = collapseMovieVariants(movies)

    // -- feed helpers --

    /** A movie counts as "already enriched" once any headline detail field is set, so [movieDetail]
     *  re-fetches only truly-bare rows. A movie the provider has no metadata for re-fetches each open;
     *  acceptable, and it self-limits the moment anything comes back. */
    private val Movie.isEnriched: Boolean
        get() = backdropUrl != null || cast != null || genre != null || director != null

    private val Series.isEnriched: Boolean
        get() = backdropUrl != null || cast != null || genre != null

    /** Splits a provider genre string ("Action, Thriller" / "Action|Thriller") into clean genres. */
    private fun splitGenres(raw: String?): List<String> =
        raw?.split(',', '|')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()

    private fun <T> groupByGenre(
        items: List<T>,
        genreOf: (T) -> String?,
        maxGenres: Int,
        perGenre: Int,
    ): List<GenreGroup<T>> {
        val buckets = LinkedHashMap<String, MutableList<T>>()
        for (item in items) {
            for (genre in splitGenres(genreOf(item))) {
                buckets.getOrPut(genre) { mutableListOf() }.add(item)
            }
        }
        return buckets.entries
            .sortedByDescending { it.value.size }
            .take(maxGenres)
            .map { GenreGroup(it.key, it.value.take(perGenre)) }
    }

    /** How strongly a title matches a profile's genre affinity: sum of its genres' tallies. */
    private fun genreScore(genre: String?, affinity: Map<String, Int>): Int =
        splitGenres(genre).sumOf { affinity[it] ?: 0 }

    /** How many of a title's genres are in the wanted set — the More-Like-This overlap. */
    private fun sharedGenreCount(genre: String?, wanted: Set<String>): Int =
        splitGenres(genre).count { it in wanted }

    /** Extracts a movie's local id from a playback-position mediaKey ("movie:42" → 42). */
    private fun movieIdFromMediaKey(mediaKey: String): Long? {
        val parts = mediaKey.split(":", limit = 2)
        return if (parts.size == 2 && parts[0] == "movie") parts[1].toLongOrNull() else null
    }

    suspend fun setChannelFavourite(id: Long, favourite: Boolean) =
        channelDao.setFavourite(id, favourite)

    suspend fun setChannelHidden(id: Long, hidden: Boolean) = channelDao.setHidden(id, hidden)

    /** Sets (or clears, on blank) a channel's manual rename. Trimmed; a blank name clears it. */
    suspend fun setChannelCustomName(id: Long, name: String?) =
        channelDao.setCustomName(id, name?.trim()?.takeIf { it.isNotBlank() })

    suspend fun setChannelSortIndex(id: Long, sortIndex: Int) = channelDao.setSortIndex(id, sortIndex)

    // --- Web channel manager: one-shot reads for the socket-thread server ------------------------
    // The manager server handles one HTTP request at a time off the UI thread and wants plain
    // lists, not Flows. These are read-only pass-throughs; every mutation still goes through the
    // suspend setters above (setChannelHidden/Favourite/CustomName/SortIndex).

    /** Enabled providers, for the manager's source selector. */
    suspend fun enabledSources(): List<Source> = sourceDao.enabled()

    /** Every live category (raw, un-folded), for the manager's category list. */
    suspend fun liveCategories(): List<Category> = categoryDao.allByKind(StreamKind.LIVE)

    /** Channel counts per (source, category) — the number shown next to each category. */
    suspend fun categoryChannelCounts(): List<app.opentv.data.db.CategoryChannelCount> =
        channelDao.channelCountsByCategory()

    /** One category's channels, hidden included, optionally scoped to a single source. */
    suspend fun channelsInCategoryForManager(sourceId: Long?, categoryId: String): List<Channel> =
        channelDao.channelsInCategoryIncludingHidden(sourceId, categoryId)

    suspend fun setMovieFavourite(id: Long, favourite: Boolean) =
        movieDao.setFavourite(id, favourite)

    // --- Sync helpers: read/apply curation by stable stream URL ---
    suspend fun favouriteChannelUrls(): List<String> = channelDao.favouriteUrls()
    suspend fun hiddenChannelUrls(): List<String> = channelDao.hiddenUrls()
    suspend fun markChannelFavouriteByUrl(url: String) = channelDao.markFavouriteByUrl(url)
    suspend fun markChannelHiddenByUrl(url: String) = channelDao.markHiddenByUrl(url)
    suspend fun favouriteMovieUrls(): List<String> = movieDao.favouriteUrls()
    suspend fun markMovieFavouriteByUrl(url: String) = movieDao.markFavouriteByUrl(url)
    suspend fun favouriteSeriesIds(): List<String> = seriesDao.favouriteSeriesIds()
    suspend fun markSeriesFavouriteBySeriesId(seriesId: String) =
        seriesDao.markFavouriteBySeriesId(seriesId)

    /** Series episodes are fetched lazily — panels are slow and most series are never opened. */
    suspend fun ensureEpisodes(source: Source, seriesId: String) {
        if (source.kind != SourceKind.XTREAM) return
        runCatching { api.episodes(source, seriesId) }
            .onSuccess { if (it.isNotEmpty()) episodeDao.upsertAll(it) }
            .onFailure { Log.w(TAG, "Episode fetch failed for series $seriesId", it) }
    }

    /**
     * Full catalogue: live channels, then movies and series. Used by the periodic worker and by a
     * manual refresh, where there's no user staring at a spinner. The initial add takes the faster
     * [syncLive] + background [syncVod] path instead, so the guide appears without waiting for a
     * 40,000-title VOD list.
     */
    suspend fun sync(source: Source, nowUtcMillis: Long): SyncResult = withContext(Dispatchers.IO) {
        // Skip fetching a content type the user has switched off — that's the whole speed-up.
        // Live is gated here (not in syncLive) so onboarding's direct syncLive still loads channels.
        // Already-synced rows are left untouched: turning a type back on and refreshing restores it.
        val live =
            if (settings.liveEnabled.value) syncLive(source, nowUtcMillis)
            else SyncResult.Success(0, 0, 0)
        if (live is SyncResult.Success && source.kind == SourceKind.XTREAM) {
            runCatching { syncXtreamVod(source, nowUtcMillis) }
                .onFailure { Log.w(TAG, "VOD sync failed for source ${source.id}", it) }
        }
        live
    }

    /** Live channels only — the fast path so the guide can show before VOD and the guide load. */
    suspend fun syncLive(source: Source, nowUtcMillis: Long): SyncResult = withContext(Dispatchers.IO) {
        try {
            when (source.kind) {
                SourceKind.XTREAM -> syncXtreamLive(source, nowUtcMillis)
                SourceKind.M3U -> syncM3u(source, nowUtcMillis)
                SourceKind.STALKER -> syncStalkerLive(source, nowUtcMillis)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Catalogue sync failed for source ${source.id}", e)
            SyncResult.Failed(e.message ?: "The catalogue could not be downloaded.", e)
        }
    }

    /** Movies + series — best-effort, meant to run in the background so a huge VOD list never
     * blocks live TV. Silent on failure: an account with no VOD is normal, not an error. */
    suspend fun syncVod(source: Source, nowUtcMillis: Long) = withContext(Dispatchers.IO) {
        runCatching { if (source.kind == SourceKind.XTREAM) syncXtreamVod(source, nowUtcMillis) }
            .onFailure { Log.w(TAG, "VOD sync failed for source ${source.id}", it) }
    }

    private suspend fun syncXtreamLive(source: Source, nowUtcMillis: Long): SyncResult {
        // Authenticate first so a wrong password produces a clear message rather than
        // four separate confusing failures further down.
        api.authenticate(source)

        val liveCategories = api.liveCategories(source)
        val channels = api.liveStreams(source)
        if (channels.isEmpty()) {
            return SyncResult.Failed(
                "The server returned no channels. The account may have no package assigned.",
                null,
            )
        }

        categoryDao.upsertAll(liveCategories)
        val categoryNames = liveCategories.associate { it.id to it.name }
        channelDao.replaceCatalogue(source.id, normalized(channels, categoryNames), nowUtcMillis)
        sourceDao.markCatalogSynced(source.id, nowUtcMillis)
        return SyncResult.Success(channels.size, 0, 0)
    }

    /**
     * Stalker/Ministra live sync. Mirrors [syncXtreamLive] but the channels it stores carry a
     * [Channel.cmd] instead of a directly-playable URL — [resolvePlaybackUrl] mints the real URL at
     * play time. The handshake happens inside [StalkerApi] on first call; a bad MAC or URL throws
     * here with a clear message, caught by [syncLive].
     */
    private suspend fun syncStalkerLive(source: Source, nowUtcMillis: Long): SyncResult {
        val liveCategories = stalkerApi.liveCategories(source)
        val channels = stalkerApi.liveChannels(source)
        if (channels.isEmpty()) {
            return SyncResult.Failed(
                "The portal returned no channels. The MAC may not be authorised, or its package is empty.",
                null,
            )
        }
        categoryDao.upsertAll(liveCategories)
        val categoryNames = liveCategories.associate { it.id to it.name }
        channelDao.replaceCatalogue(source.id, normalized(channels, categoryNames), nowUtcMillis)
        sourceDao.markCatalogSynced(source.id, nowUtcMillis)
        return SyncResult.Success(channels.size, 0, 0)
    }

    /**
     * The URL to actually feed the player for [channel]. Xtream/M3U channels already carry a playable
     * [Channel.streamUrl]; a Stalker channel's real URL is short-lived, so it's minted now via
     * create_link from the channel's [Channel.cmd]. Falls back to the stored streamUrl if resolution
     * fails, so the player surfaces an error rather than silently doing nothing.
     */
    suspend fun resolvePlaybackUrl(channel: Channel, source: Source?): String {
        if (source?.kind != SourceKind.STALKER) return channel.streamUrl
        val cmd = channel.cmd?.takeIf { it.isNotBlank() } ?: return channel.streamUrl
        return runCatching { stalkerApi.createLink(source, cmd) }.getOrNull() ?: channel.streamUrl
    }

    private suspend fun syncXtreamVod(source: Source, nowUtcMillis: Long) {
        // VOD is optional: plenty of accounts have live TV only, and a 404 on get_vod_streams
        // must not cost the user their channel list. Movies and series are gated independently so
        // a user who only turned off, say, Series still gets their movie library refreshed.
        val moviesOn = settings.moviesEnabled.value
        val seriesOn = settings.seriesEnabled.value
        if (!moviesOn && !seriesOn) return

        val movieCategories =
            if (moviesOn) runCatching { api.movieCategories(source) }.getOrDefault(emptyList())
            else emptyList()
        val movies =
            if (moviesOn) runCatching { api.movies(source) }.getOrDefault(emptyList())
            else emptyList()
        val seriesCategories =
            if (seriesOn) runCatching { api.seriesCategories(source) }.getOrDefault(emptyList())
            else emptyList()
        val series =
            if (seriesOn) runCatching { api.series(source) }.getOrDefault(emptyList())
            else emptyList()

        if (movieCategories.isNotEmpty() || seriesCategories.isNotEmpty()) {
            categoryDao.upsertAll(movieCategories + seriesCategories)
        }
        if (movies.isNotEmpty()) movieDao.upsertAll(movies)
        if (series.isNotEmpty()) seriesDao.upsertAll(series)
    }

    private suspend fun syncM3u(source: Source, nowUtcMillis: Long): SyncResult {
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", source.userAgent)
            .build()

        val parsed = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return SyncResult.Failed("Playlist download failed (HTTP ${response.code}).", null)
            }
            val stream = response.body?.byteStream()
                ?: return SyncResult.Failed("The playlist was empty.", null)
            M3uParser.parse(stream, source.id)
        }

        if (parsed.channels.isEmpty()) {
            return SyncResult.Failed(
                "No channels found in that playlist. Check the URL points at an M3U file.",
                null,
            )
        }

        // Synthesise categories from group-title so the UI has something to group by.
        val categories = parsed.channels
            .mapNotNull { it.categoryId }
            .distinct()
            .sorted()
            .mapIndexed { index, name ->
                Category(
                    id = name,
                    sourceId = source.id,
                    name = name,
                    kind = StreamKind.LIVE,
                    sortIndex = index,
                )
            }

        categoryDao.upsertAll(categories)
        val categoryNames = categories.associate { it.id to it.name }
        channelDao.replaceCatalogue(source.id, normalized(parsed.channels, categoryNames), nowUtcMillis)

        // If the playlist declared its own guide URL and the user did not set one, adopt it.
        if (source.epgUrl.isNullOrBlank() && !parsed.declaredEpgUrl.isNullOrBlank()) {
            sourceDao.update(source.copy(epgUrl = parsed.declaredEpgUrl))
        }

        sourceDao.markCatalogSynced(source.id, nowUtcMillis)
        return SyncResult.Success(parsed.channels.size, 0, 0)
    }

    /**
     * Stamps every channel with its normalised identity before it is stored.
     *
     * This one pass powers both headline features: [Channel.groupKey] folds quality
     * variants of a channel into a single row and is the join key for EPG matching, and
     * [Channel.displayName] is the cleaned name the UI shows instead of `UK| BBC ONE FHD`.
     */
    private fun normalized(
        channels: List<Channel>,
        categoryNames: Map<String, String>,
    ): List<Channel> {
        val stamped = channels.map { channel ->
            val n = ChannelNameNormalizer.normalize(channel.name)
            var rank = n.qualityRank
            var label = n.qualityLabel
            if (label.isEmpty() || rank == 0) {
                // Some providers put the quality in the CATEGORY, not the channel:
                // 'UK| GENERAL HD/RAW' and 'UK| GENERAL hevc' holding identically named
                // channels. Without this, the player's switch is four buttons all
                // reading 'Standard' — grouped correctly, labelled uselessly.
                val categoryName = channel.categoryId?.let { categoryNames[it] }
                if (categoryName != null) {
                    val c = ChannelNameNormalizer.normalize(categoryName)
                    if (label.isEmpty()) label = c.qualityLabel
                    if (rank == 0) rank = c.qualityRank
                }
            }
            channel.copy(
                displayName = n.baseName,
                groupKey = n.groupKey,
                qualityRank = rank,
                qualityLabel = label,
                // Providers ship decorative separator rows ('#### UK GENERAL ####') as
                // channels. They are headings, not channels — hide them on import.
                hidden = channel.hidden || isSeparatorRow(channel.name),
            )
        }

        return stamped
    }

    /** Decorative list headings: starts AND ends with a run of banner characters. */
    private fun isSeparatorRow(rawName: String): Boolean {
        val t = rawName.trim()
        return t.length >= 6 &&
            t.take(3).all { it in SEPARATOR_CHARS } &&
            t.takeLast(3).all { it in SEPARATOR_CHARS }
    }

    /**
     * Re-cleans every stored channel with the current normaliser, no network needed.
     *
     * displayName, groupKey, quality and separator-hiding are computed at import time, so a
     * change to the normaliser (a new superscript char, a new junk pattern) does not reach
     * channels already in the database until the next full catalogue sync — which can be
     * hours away. This runs the same pass over existing rows locally, so a code fix shows up
     * on the next launch instead of the next sync. Bump [NORMALIZER_VERSION] to trigger it.
     */
    suspend fun renormalizeAll(): Int = withContext(Dispatchers.IO) {
        val existing = channelDao.allForMatching()
        if (existing.isEmpty()) return@withContext 0

        val bySource = existing.groupBy { it.sourceId }
        var changed = 0
        for ((_, channels) in bySource) {
            val names = channelCategoryNames(channels)
            val renamed = normalized(channels, names)
            // Only write rows that actually changed, to keep the write small.
            val diff = renamed.filterIndexed { i, c ->
                val old = channels[i]
                c.displayName != old.displayName || c.groupKey != old.groupKey ||
                    c.qualityLabel != old.qualityLabel || c.qualityRank != old.qualityRank ||
                    c.hidden != old.hidden
            }
            if (diff.isNotEmpty()) {
                diff.chunked(500).forEach { channelDao.upsertAll(it) }
                changed += diff.size
            }
        }
        Log.i(TAG, "Re-normalised $changed channels with the current normaliser")
        changed
    }

    /** Best-effort category-id → name map for a set of channels. */
    private suspend fun channelCategoryNames(channels: List<Channel>): Map<String, String> {
        val ids = channels.mapNotNull { it.categoryId }.toSet()
        if (ids.isEmpty()) return emptyMap()
        return categoryDao.namesFor(ids).associate { it.id to it.name }
    }

    /**
     * The switchable quality variants of a channel, best first, ONE per distinct quality.
     *
     * A provider often lists the same stream in several categories — PRIME American Crimes
     * shows up twice, both RAW. Those are not "qualities"; offering a switch between two
     * identical (and sometimes one dead) feeds is worse than useless. So variants are
     * de-duplicated by their quality signature: only genuinely different qualities survive,
     * and a channel that is really single-quality gets no switch at all.
     */
    suspend fun variants(channel: Channel): List<Channel> {
        if (settings.playlistMode.value == AppSettings.PlaylistMode.ORIGINAL) return listOf(channel)
        if (channel.groupKey.isEmpty()) return listOf(channel)
        val all = channelDao.variantsInGroup(channel.groupKey)
        return distinctByQuality(all).ifEmpty { listOf(channel) }
    }

    /**
     * The record menu's "record from which provider" options: one channel per source that carries
     * this logical channel, each that source's best-quality copy, the current source first. With two
     * providers this lets a recording run on one account while the user keeps watching on the other —
     * the only real way around a provider's single-connection limit (which even TiviMate can't dodge).
     */
    suspend fun recordSourceOptions(channel: Channel): List<Channel> = withContext(Dispatchers.IO) {
        if (channel.groupKey.isEmpty()) return@withContext listOf(channel)
        channelDao.variantsInGroup(channel.groupKey)
            .groupBy { it.sourceId }
            .map { (_, chans) -> chans.maxByOrNull { it.qualityRank } ?: chans.first() }
            .sortedByDescending { it.sourceId == channel.sourceId }
    }

    /**
     * Switches an Xtream source's live-stream container (HLS ↔ MPEG-TS) and rewrites its live
     * channels' playback URLs in place, so the change takes effect immediately without a re-sync.
     *
     * Xtream only: an M3U source's channel URLs come straight from its playlist and must not be
     * rebuilt from credentials. The `channels` table is live-only (movies and series have their own
     * tables), so every row here is a live channel whose URL derives from [XtreamApi.liveStreamUrl].
     */
    suspend fun setLiveFormat(sourceId: Long, format: LiveStreamFormat) = withContext(Dispatchers.IO) {
        val source = sourceDao.byId(sourceId) ?: return@withContext
        if (source.kind != SourceKind.XTREAM || source.liveFormat == format) return@withContext
        val updated = source.copy(liveFormat = format)
        sourceDao.update(updated)
        // Rebuild each live channel's URL for the new container.
        channelDao.forSource(sourceId).forEach { channel ->
            val newUrl = api.liveStreamUrl(updated, channel.streamId)
            if (newUrl != channel.streamUrl) channelDao.updateStreamUrl(channel.id, newUrl)
        }
    }

    suspend fun deleteSource(sourceId: Long) = withContext(Dispatchers.IO) {
        channelDao.deleteForSource(sourceId)
        categoryDao.deleteForSource(sourceId)
        movieDao.deleteForSource(sourceId)
        seriesDao.deleteForSource(sourceId)
        episodeDao.deleteForSource(sourceId)
        sourceDao.delete(sourceId)
    }

    companion object {
        private const val TAG = "CatalogRepository"
        val SEPARATOR_CHARS = setOf('#', '*', '=', '~', '-', '_', '•', '█', '▓', '|')

        /**
         * Bump this whenever the normaliser changes in a way that should re-process
         * already-imported channels. The app compares it against a stored value on launch
         * and runs [renormalizeAll] once when it moves.
         */
        const val NORMALIZER_VERSION = 2
    }
}
