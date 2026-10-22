/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.StreamKind
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Client for the Xtream Codes "player API".
 *
 * ## Why this is hand-rolled instead of using typed `@Serializable` models
 *
 * Xtream panels are not a standard. Different builds return the same field as a number, a
 * quoted number, an empty string, `null`, or omit it entirely — and `stream_id` in particular
 * flips between `12345` and `"12345"` depending on the panel. Strict deserialisation into a
 * data class means one unusual field aborts the parse of the *entire* catalogue, which the
 * user experiences as "the app won't load my channels" with no way to diagnose it.
 *
 * So we read [JsonElement] defensively and coerce. Every accessor below tolerates the wrong
 * type and returns null rather than throwing. Ugly; correct.
 */
class XtreamApi(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    class XtreamException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Verifies credentials and returns the account's expiry, if the panel reports one. */
    suspend fun authenticate(source: Source): AccountInfo = withContext(Dispatchers.IO) {
        val body = getJson(source, action = null).jsonObject
        val userInfo = body["user_info"]?.jsonObjectOrNull
            ?: throw XtreamException("The server did not return account information.")

        val status = userInfo["status"].asStringOrNull
        if (status != null && !status.equals("Active", ignoreCase = true)) {
            throw XtreamException("This account is $status.")
        }
        if (userInfo["auth"].asIntOrNull == 0) {
            throw XtreamException("Username or password rejected by the server.")
        }

        AccountInfo(
            username = userInfo["username"].asStringOrNull,
            expiryMillis = userInfo["exp_date"].asLongOrNull?.times(1000),
            maxConnections = userInfo["max_connections"].asIntOrNull,
            activeConnections = userInfo["active_cons"].asIntOrNull,
            timezone = body["server_info"]?.jsonObjectOrNull?.get("timezone").asStringOrNull,
        )
    }

    suspend fun liveCategories(source: Source): List<Category> =
        categories(source, "get_live_categories", StreamKind.LIVE)

    suspend fun movieCategories(source: Source): List<Category> =
        categories(source, "get_vod_categories", StreamKind.MOVIE)

    suspend fun seriesCategories(source: Source): List<Category> =
        categories(source, "get_series_categories", StreamKind.SERIES)

    private suspend fun categories(
        source: Source,
        action: String,
        kind: StreamKind,
    ): List<Category> = withContext(Dispatchers.IO) {
        getJson(source, action).arrayOrEmpty.mapIndexedNotNull { index, element ->
            val obj = element.jsonObjectOrNull ?: return@mapIndexedNotNull null
            val id = obj["category_id"].asStringOrNull ?: return@mapIndexedNotNull null
            Category(
                id = id,
                sourceId = source.id,
                name = obj["category_name"].asStringOrNull ?: id,
                kind = kind,
                sortIndex = index,
            )
        }
    }

    suspend fun liveStreams(source: Source): List<Channel> = withContext(Dispatchers.IO) {
        getJson(source, "get_live_streams").arrayOrEmpty.mapIndexedNotNull { index, element ->
            val obj = element.jsonObjectOrNull ?: return@mapIndexedNotNull null
            val streamId = obj["stream_id"].asStringOrNull ?: return@mapIndexedNotNull null
            val name = obj["name"].asStringOrNull ?: return@mapIndexedNotNull null
            Channel(
                sourceId = source.id,
                streamId = streamId,
                name = name,
                categoryId = obj["category_id"].asStringOrNull,
                logoUrl = obj["stream_icon"].asStringOrNull?.takeIf { it.isNotBlank() },
                // epg_channel_id is what joins to XMLTV; it is frequently blank, in which
                // case the channel simply has no guide rather than the guide being broken.
                epgChannelId = obj["epg_channel_id"].asStringOrNull?.takeIf { it.isNotBlank() },
                tvArchive = obj["tv_archive"].asIntOrNull == 1,
                tvArchiveDays = obj["tv_archive_duration"].asIntOrNull ?: 0,
                number = obj["num"].asIntOrNull,
                streamUrl = liveStreamUrl(source, streamId),
                sortIndex = obj["num"].asIntOrNull ?: index,
            )
        }
    }

    suspend fun movies(source: Source): List<Movie> = withContext(Dispatchers.IO) {
        getJson(source, "get_vod_streams").arrayOrEmpty.mapNotNull { element ->
            val obj = element.jsonObjectOrNull ?: return@mapNotNull null
            val streamId = obj["stream_id"].asStringOrNull ?: return@mapNotNull null
            val name = obj["name"].asStringOrNull ?: return@mapNotNull null
            val extension = obj["container_extension"].asStringOrNull?.takeIf { it.isNotBlank() }
            Movie(
                sourceId = source.id,
                streamId = streamId,
                name = name,
                categoryId = obj["category_id"].asStringOrNull,
                posterUrl = obj["stream_icon"].asStringOrNull?.takeIf { it.isNotBlank() },
                rating = obj["rating"].asDoubleOrNull,
                year = obj["year"].asIntOrNull,
                plot = obj["plot"].asStringOrNull,
                durationSeconds = obj["duration_secs"].asIntOrNull,
                containerExtension = extension,
                streamUrl = vodStreamUrl(source, streamId, extension),
                addedMillis = obj["added"].asLongOrNull?.times(1000) ?: 0L,
                // Rich metadata is best-effort here: the streams list carries it on some panels
                // and not others. Whatever is missing is back-filled from get_vod_info on the
                // first detail open. See asBackdropUrl for the array-or-string handling.
                backdropUrl = obj.asBackdropUrl("movie_image", "cover_big"),
                cast = obj["cast"].asStringOrNull ?: obj["actors"].asStringOrNull,
                director = obj["director"].asStringOrNull,
                genre = obj["genre"].asStringOrNull,
                tmdbId = obj["tmdb_id"].asStringOrNull ?: obj["tmdb"].asStringOrNull,
            )
        }
    }

    suspend fun series(source: Source): List<Series> = withContext(Dispatchers.IO) {
        getJson(source, "get_series").arrayOrEmpty.mapNotNull { element ->
            val obj = element.jsonObjectOrNull ?: return@mapNotNull null
            val seriesId = obj["series_id"].asStringOrNull ?: return@mapNotNull null
            val name = obj["name"].asStringOrNull ?: return@mapNotNull null
            Series(
                sourceId = source.id,
                seriesId = seriesId,
                name = name,
                categoryId = obj["category_id"].asStringOrNull,
                posterUrl = obj["cover"].asStringOrNull?.takeIf { it.isNotBlank() },
                rating = obj["rating"].asDoubleOrNull,
                year = obj["year"].asIntOrNull ?: obj["releaseDate"].asStringOrNull?.take(4)?.toIntOrNull(),
                plot = obj["plot"].asStringOrNull,
                addedMillis = obj["last_modified"].asLongOrNull?.times(1000) ?: 0L,
                // get_series carries most of this inline on the majority of panels; anything
                // missing is back-filled from get_series_info on the first detail open.
                backdropUrl = obj.asBackdropUrl("cover_big", "cover"),
                cast = obj["cast"].asStringOrNull ?: obj["actors"].asStringOrNull,
                genre = obj["genre"].asStringOrNull,
                tmdbId = obj["tmdb_id"].asStringOrNull ?: obj["tmdb"].asStringOrNull,
            )
        }
    }

    /**
     * Episode list for one series.
     *
     * `episodes` is an object keyed by season number whose values are arrays — except on some
     * panels, where it is an array of arrays, and on others where a season with a single
     * episode collapses to a bare object. All three shapes are handled.
     */
    suspend fun episodes(source: Source, seriesId: String): List<Episode> =
        withContext(Dispatchers.IO) {
            val body = getJson(source, "get_series_info") { it.addQueryParameter("series_id", seriesId) }
            val episodesNode = body.jsonObjectOrNull?.get("episodes") ?: return@withContext emptyList()

            val seasonBuckets: List<Pair<Int?, JsonElement>> = when (episodesNode) {
                is JsonObject -> episodesNode.entries.map { it.key.toIntOrNull() to it.value }
                is JsonArray -> episodesNode.mapIndexed { index, value -> index to value }
                else -> emptyList()
            }

            seasonBuckets.flatMap { (seasonHint, bucket) ->
                val items: List<JsonElement> = when (bucket) {
                    is JsonArray -> bucket.toList()
                    is JsonObject -> listOf(bucket)
                    else -> emptyList()
                }
                items.mapNotNull { element ->
                    val obj = element.jsonObjectOrNull ?: return@mapNotNull null
                    val episodeId = obj["id"].asStringOrNull ?: return@mapNotNull null
                    val info = obj["info"].jsonObjectOrNull
                    val extension = obj["container_extension"].asStringOrNull
                        ?.takeIf { it.isNotBlank() }
                    Episode(
                        sourceId = source.id,
                        seriesId = seriesId,
                        episodeId = episodeId,
                        season = obj["season"].asIntOrNull ?: seasonHint ?: 1,
                        episodeNumber = obj["episode_num"].asIntOrNull ?: 0,
                        title = obj["title"].asStringOrNull ?: "Episode",
                        plot = info?.get("plot").asStringOrNull,
                        durationSeconds = info?.get("duration_secs").asIntOrNull,
                        stillUrl = info?.get("movie_image").asStringOrNull?.takeIf { it.isNotBlank() },
                        streamUrl = seriesStreamUrl(source, episodeId, extension),
                    )
                }
            }
        }

    /**
     * Rich metadata for a single movie, from `get_vod_info`'s `info` object.
     *
     * The streams list ([movies]) is deliberately lightweight; the heavy fields (backdrop art,
     * cast, director, genre) live behind this per-title call, which the detail screen makes lazily
     * on open. Every field is best-effort — a panel that omits `info` entirely yields null, not an
     * error, so the caller can fall back to whatever the list already had.
     */
    suspend fun movieInfo(source: Source, streamId: String): VodInfo? = withContext(Dispatchers.IO) {
        val body = runCatching {
            getJson(source, "get_vod_info") { it.addQueryParameter("vod_id", streamId) }
        }.getOrNull() ?: return@withContext null
        val info = body.jsonObjectOrNull?.get("info").jsonObjectOrNull ?: return@withContext null
        VodInfo(
            backdropUrl = info.asBackdropUrl("movie_image", "cover_big"),
            cast = info["cast"].asStringOrNull ?: info["actors"].asStringOrNull,
            director = info["director"].asStringOrNull,
            genre = info["genre"].asStringOrNull,
            tmdbId = info["tmdb_id"].asStringOrNull ?: info["tmdb"].asStringOrNull,
            plot = info["plot"].asStringOrNull ?: info["description"].asStringOrNull,
            rating = info["rating"].asDoubleOrNull,
            year = info["year"].asIntOrNull ?: info["releasedate"].asStringOrNull?.take(4)?.toIntOrNull(),
            durationSeconds = info["duration_secs"].asIntOrNull,
        )
    }

    /**
     * Rich metadata for a single series, from `get_series_info`'s `info` object. Same contract as
     * [movieInfo]; series carry no director. This shares the `get_series_info` endpoint with
     * [episodes] but is a separate call — the detail screen fetches episodes and info independently,
     * and most series are never opened, so paying for both only when needed is the point.
     */
    suspend fun seriesInfo(source: Source, seriesId: String): SeriesInfo? = withContext(Dispatchers.IO) {
        val body = runCatching {
            getJson(source, "get_series_info") { it.addQueryParameter("series_id", seriesId) }
        }.getOrNull() ?: return@withContext null
        val info = body.jsonObjectOrNull?.get("info").jsonObjectOrNull ?: return@withContext null
        SeriesInfo(
            backdropUrl = info.asBackdropUrl("cover_big", "cover"),
            cast = info["cast"].asStringOrNull ?: info["actors"].asStringOrNull,
            genre = info["genre"].asStringOrNull,
            tmdbId = info["tmdb_id"].asStringOrNull ?: info["tmdb"].asStringOrNull,
            plot = info["plot"].asStringOrNull ?: info["description"].asStringOrNull,
            rating = info["rating"].asDoubleOrNull,
            year = info["year"].asIntOrNull ?: info["releaseDate"].asStringOrNull?.take(4)?.toIntOrNull(),
        )
    }

    /**
     * Opens the XMLTV guide as a stream. The caller must close it.
     *
     * [prevDays] and [nextDays] are how much guide to ask the panel for. The panel's own
     * default is one day either way, which is less than the app's guide window shows, so
     * they are asked for explicitly rather than left to chance.
     */
    suspend fun openEpgStream(
        source: Source,
        prevDays: Int? = null,
        nextDays: Int? = null,
    ): InputStream = withContext(Dispatchers.IO) {
        val url = source.epgUrl?.toHttpUrlOrNull() ?: xmltvUrl(source, prevDays, nextDays)
        val response = http.newCall(request(source, url)).execute()
        if (!response.isSuccessful) {
            response.close()
            throw XtreamException("Guide download failed (HTTP ${response.code}).")
        }
        response.body?.byteStream()
            ?: throw XtreamException("The server returned an empty guide.")
    }

    // ---- URL construction ----------------------------------------------------------------

    fun liveStreamUrl(source: Source, streamId: String): String =
        "${source.url}/live/${source.username}/${source.password}/$streamId.${source.liveFormat.extension}"

    fun vodStreamUrl(source: Source, streamId: String, extension: String?): String =
        "${source.url}/movie/${source.username}/${source.password}/$streamId.${extension ?: "mp4"}"

    fun seriesStreamUrl(source: Source, episodeId: String, extension: String?): String =
        "${source.url}/series/${source.username}/${source.password}/$episodeId.${extension ?: "mp4"}"

    /**
     * Catch-up / archive stream for a past programme. Uses the widely-supported path form
     * `/timeshift/{user}/{pass}/{durationMinutes}/{yyyy-MM-dd:HH-mm}/{streamId}.ts`, which returns a
     * seekable MPEG-TS just like a recording.
     *
     * The start time is written on the **provider's** clock, not the device's. The panel
     * stores its archive as `/tv_archive/{stream_id}/YYYY-MM-DD:HH-mm.ts` and looks that name
     * up against its own timezone, so a device in São Paulo asking a Paris panel for "19:00"
     * would be asking for a recording from three hours earlier — the wrong programme, or none
     * at all. The panel's zone comes from its `server_info` and is kept on the source; when
     * nothing says otherwise, the device's own zone is used, which is what the app did
     * before and is right whenever the two agree.
     */
    fun catchupUrl(source: Source, streamId: String, startUtcMillis: Long, durationMinutes: Int): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
            .apply { timeZone = zoneOf(source.timezone) }
            .format(java.util.Date(startUtcMillis))
        val minutes = durationMinutes.coerceAtLeast(1)
        return "${source.url}/timeshift/${source.username}/${source.password}/$minutes/$stamp/$streamId.ts"
    }

    companion object {
        /**
         * The zone a provider's clock runs on.
         *
         * Java's own list of zones is the only map there is from the name a panel reports to
         * an offset, so that is what we use. A name Java does not know falls back to the
         * device's zone rather than to UTC: guessing UTC would be worse than the old
         * behaviour, which at least agreed with the viewer whenever the provider was local.
         */
        fun zoneOf(id: String?): java.util.TimeZone {
            val name = id?.trim().orEmpty()
            if (name.isEmpty()) return java.util.TimeZone.getDefault()
            if (!java.util.TimeZone.getAvailableIDs().contains(name)) return java.util.TimeZone.getDefault()
            return java.util.TimeZone.getTimeZone(name)
        }
    }

    private fun xmltvUrl(source: Source, prevDays: Int?, nextDays: Int?): HttpUrl =
        baseUrl(source).newBuilder()
            .encodedPath("/xmltv.php")
            .addQueryParameter("username", source.username.orEmpty())
            .addQueryParameter("password", source.password.orEmpty())
            .apply {
                // Asked for explicitly, because a panel only answers for the window it
                // generated — and for another one it answers with an empty body and no
                // error. Null leaves the panel's own default alone.
                if (prevDays != null) addQueryParameter("prev_days", prevDays.coerceAtLeast(0).toString())
                if (nextDays != null) addQueryParameter("next_days", nextDays.coerceAtLeast(0).toString())
            }
            .build()

    private fun baseUrl(source: Source): HttpUrl =
        source.url.toHttpUrlOrNull()
            ?: throw XtreamException("\"${source.url}\" is not a valid server address.")

    // ---- Plumbing ------------------------------------------------------------------------

    private fun getJson(
        source: Source,
        action: String?,
        extra: (HttpUrl.Builder) -> Unit = {},
    ): JsonElement {
        val builder = baseUrl(source).newBuilder()
            .encodedPath("/player_api.php")
            .addQueryParameter("username", source.username.orEmpty())
            .addQueryParameter("password", source.password.orEmpty())
        if (action != null) builder.addQueryParameter("action", action)
        extra(builder)

        http.newCall(request(source, builder.build())).execute().use { response ->
            if (!response.isSuccessful) {
                // Only a 404 is worth a second request: it is the one code that means two
                // different things, and only the address itself can tell them apart.
                val rootStatus = if (response.code == 404) probeAddress(source) else null
                throw XtreamException(describeHttpFailure(response.code, rootStatus))
            }
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) throw XtreamException("The server returned an empty response.")
            return try {
                json.parseToJsonElement(text)
            } catch (e: Exception) {
                // Panels behind a captive portal or Cloudflare return HTML here. Saying
                // "not valid JSON" is useless to a user; say what it probably means.
                throw XtreamException(
                    "The server replied with something that is not a valid catalogue. " +
                        "Check the address and port are correct.",
                    e,
                )
            }
        }
    }

    /**
     * Asks the bare address how it is doing, so a 404 can be told apart from a dead provider.
     *
     * A CDN in front of a panel that has gone down answers 404 for everything, which looks
     * exactly like a wrong address. The root of the same host says which it is: a server
     * error there means the provider is down and there is nothing for the user to fix.
     *
     * Null when the address could not be reached at all.
     */
    private fun probeAddress(source: Source): Int? = runCatching {
        val url = source.url.toHttpUrlOrNull() ?: return null
        http.newCall(request(source, url)).execute().use { it.code }
    }.getOrNull()

    private fun request(source: Source, url: HttpUrl): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", source.userAgent)
            .build()

    /**
     * Turns a failed request into a sentence that says what to do about it.
     *
     * @param rootStatus what the bare address answered, when it was asked. Null means it
     *   could not be reached, or that nobody asked (only a 404 is worth asking about).
     */
    internal fun describeHttpFailure(code: Int, rootStatus: Int? = null): String = when (code) {
        401, 403 -> "The server refused the request (HTTP $code). The username or password " +
            "may be wrong, or the provider may be blocking this app's User-Agent — try " +
            "changing it in the source's advanced settings."
        404 -> when {
            rootStatus == null -> "No Xtream API at that address (HTTP 404), and the address " +
                "itself could not be reached. Check the URL and port — and if the address is " +
                "right, the provider is probably down."
            rootStatus >= 500 -> "The provider's server is not answering (HTTP $rootStatus on " +
                "the address itself, HTTP 404 on its API). The panel appears to be down — " +
                "nothing to fix here; try again later or ask your provider."
            else -> "The address answers (HTTP $rootStatus), but there is no Xtream API on it " +
                "(HTTP 404). Check the URL and port — and note that a plain M3U playlist " +
                "belongs in the \"M3U playlist URL\" tab, not here."
        }
        405 -> "The server rejected the request method (HTTP 405). This usually means the " +
            "address points at a plain playlist rather than an Xtream panel."
        429 -> "The server is rate-limiting this device (HTTP 429). Try again shortly."
        in 500..599 -> "The provider's server is having problems (HTTP $code)."
        else -> "The server returned HTTP $code."
    }

    data class AccountInfo(
        val username: String?,
        val expiryMillis: Long?,
        val maxConnections: Int?,
        val activeConnections: Int?,
        val timezone: String?,
    )

    /**
     * Rich per-movie metadata from `get_vod_info`. Every field is nullable — the caller merges it
     * over the (possibly sparse) row it already has, only filling blanks. `plot`/`rating`/`year`/
     * `durationSeconds` are included because `get_vod_info` often carries a fuller version than the
     * streams list did.
     */
    data class VodInfo(
        val backdropUrl: String?,
        val cast: String?,
        val director: String?,
        val genre: String?,
        val tmdbId: String?,
        val plot: String?,
        val rating: Double?,
        val year: Int?,
        val durationSeconds: Int?,
    )

    /** Rich per-series metadata from `get_series_info`. Series have no director. See [VodInfo]. */
    data class SeriesInfo(
        val backdropUrl: String?,
        val cast: String?,
        val genre: String?,
        val tmdbId: String?,
        val plot: String?,
        val rating: Double?,
        val year: Int?,
    )
}

// ---- Defensive JSON accessors ------------------------------------------------------------
// Every one of these returns null rather than throwing. See the class doc for why.

private val JsonElement?.jsonObjectOrNull: JsonObject?
    get() = this as? JsonObject

private val JsonElement.arrayOrEmpty: List<JsonElement>
    get() = (this as? JsonArray)?.toList() ?: emptyList()

private val JsonElement?.asPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive

private val JsonElement?.asStringOrNull: String?
    get() = asPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotEmpty() && it != "null" }

/**
 * A field that is a plain URL string on some panels and a JSON array of URLs on others — which is
 * exactly how Xtream ships `backdrop_path`. Returns the first non-blank element of an array, or the
 * string itself otherwise. `asStringOrNull` already drops empties and literal "null".
 */
private val JsonElement?.firstStringOrNull: String?
    get() = when (this) {
        is JsonArray -> firstNotNullOfOrNull { it.asStringOrNull }
        else -> asStringOrNull
    }

/**
 * The best backdrop URL from an `info`/stream object: `backdrop_path` (array-aware) first, then the
 * given fallback keys (e.g. `movie_image`, `cover_big`) as plain strings. Keeps the array handling
 * in one place so [movies], [series], [movieInfo] and [seriesInfo] stay consistent.
 */
private fun JsonObject.asBackdropUrl(vararg fallbackKeys: String): String? {
    this["backdrop_path"].firstStringOrNull?.let { return it }
    this["backdrop"].firstStringOrNull?.let { return it }
    for (key in fallbackKeys) this[key].asStringOrNull?.takeIf { it.isNotBlank() }?.let { return it }
    return null
}

private val JsonElement?.asIntOrNull: Int?
    get() = asStringOrNull?.substringBefore('.')?.toIntOrNull()

private val JsonElement?.asLongOrNull: Long?
    get() = asStringOrNull?.substringBefore('.')?.toLongOrNull()

private val JsonElement?.asDoubleOrNull: Double?
    get() = asStringOrNull?.toDoubleOrNull()
