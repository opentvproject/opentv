/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import app.opentv.core.ServiceLocator
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Profile
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.recording.SmbClient
import kotlinx.serialization.json.Json

/**
 * Reads local state into a [SyncBundle] and applies a received one. The single place that knows
 * how OpenTV's tables map onto the portable, cross-device bundle, so the LAN server/client and the
 * NAS sync all agree on what travels and how it merges.
 *
 * Merge policy (see [SyncBundle]): watch positions are two-way newest-wins; curation (profiles,
 * favourites, hidden channels, hidden categories) and NAS recordings are applied **additively** —
 * we spread what a peer has without deleting anything locally. That keeps a sync from ever wiping a
 * favourite you set on one box, un-hiding the structural separator rows the importer hides, or
 * dropping a recording that only one device knows about.
 */
class SyncEngine(private val graph: ServiceLocator.Graph) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** What a single [apply] actually changed — summed across peers for the NAS "Synced N" line. */
    data class MergeResult(
        val positions: Int = 0,
        val favourites: Int = 0,
        val hidden: Int = 0,
        val recordings: Int = 0,
    ) {
        val total: Int get() = positions + favourites + hidden + recordings

        operator fun plus(other: MergeResult): MergeResult = MergeResult(
            positions = positions + other.positions,
            favourites = favourites + other.favourites,
            hidden = hidden + other.hidden,
            recordings = recordings + other.recordings,
        )
    }

    suspend fun gather(): SyncBundle {
        val profiles = graph.profiles.all()
        val names = profiles.associate { it.id to it.name }
        val positions = graph.playbackPositions.all().mapNotNull { pos ->
            val profileName = names[pos.profileId] ?: return@mapNotNull null
            val parts = pos.mediaKey.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val id = parts[1].toLongOrNull() ?: return@mapNotNull null
            val streamUrl = when (parts[0]) {
                "movie" -> graph.catalogRepository.movie(id)?.streamUrl
                "ep" -> graph.catalogRepository.episode(id)?.streamUrl
                else -> null
            } ?: return@mapNotNull null
            SyncPosition(
                profile = profileName,
                // Hashed, never raw: an Xtream URL carries the account's user and password.
                streamUrl = SyncKeys.key(streamUrl),
                kind = parts[0],
                positionMillis = pos.positionMillis,
                durationMillis = pos.durationMillis,
                updatedAtMillis = pos.updatedAtMillis,
            )
        }
        // Only finished recordings that live on the shared NAS travel — a device-local (file://)
        // capture can't be opened from another box, so sharing its metadata would just create a
        // library row that fails to play everywhere but here.
        val recordings = graph.recordingRepository.all()
            .filter { it.status == RecordingStatus.COMPLETED && SmbClient.isSmb(it.filePath) }
            .map { r ->
                SyncRecording(
                    channelName = r.channelName,
                    logoUrl = r.logoUrl,
                    title = r.title,
                    description = r.description,
                    filePath = r.filePath,
                    streamUrl = SyncKeys.key(r.streamUrl),
                    userAgent = r.userAgent,
                    scheduledStartMillis = r.scheduledStartMillis,
                    scheduledEndMillis = r.scheduledEndMillis,
                    startedAtMillis = r.startedAtMillis,
                    endedAtMillis = r.endedAtMillis,
                    sizeBytes = r.sizeBytes,
                )
            }
        return SyncBundle(
            generatedAtMillis = System.currentTimeMillis(),
            profiles = profiles.map { it.name },
            positions = positions,
            // Curation is keyed by URL on both sides, so it is keyed by [SyncKeys.key] on the wire
            // for the same reason as the positions above.
            favouriteChannels = graph.catalogRepository.favouriteChannelUrls().map(SyncKeys::key),
            hiddenChannels = graph.catalogRepository.hiddenChannelUrls().map(SyncKeys::key),
            favouriteMovies = graph.catalogRepository.favouriteMovieUrls().map(SyncKeys::key),
            favouriteSeries = graph.catalogRepository.favouriteSeriesIds(),
            hiddenCategories = graph.settings.hiddenCategories.value.toList(),
            recordings = recordings,
        )
    }

    fun encode(bundle: SyncBundle): String = json.encodeToString(SyncBundle.serializer(), bundle)

    fun decode(bundleJson: String): SyncBundle =
        json.decodeFromString(SyncBundle.serializer(), bundleJson)

    /** Applies a received bundle. Returns what changed, so callers can report "Synced N items". */
    suspend fun apply(bundle: SyncBundle): MergeResult {
        // Profiles — add any the peer has that we're missing. Never remove (that would delete a
        // local profile's watch history).
        for (name in bundle.profiles) {
            if (name.isNotBlank() && graph.profiles.byName(name) == null) {
                runCatching { graph.profiles.insert(Profile(name = name, createdAtMillis = 0)) }
            }
        }

        // Every URL-keyed item arrives as a [SyncKeys.key]; the only place a real URL now exists is
        // this device's own catalogue. Build just the slice of it this bundle references.
        val urlIndex = buildUrlIndex(bundle)

        // Curation — additive union, keyed by stable identifiers. Count only genuinely new keys
        // (ones we hadn't already curated) so the "Synced N" line means "new to this device".
        val localFavChannels = graph.catalogRepository.favouriteChannelUrls().toHashSet()
        val localHiddenChannels = graph.catalogRepository.hiddenChannelUrls().toHashSet()
        val localFavMovies = graph.catalogRepository.favouriteMovieUrls().toHashSet()
        val localFavSeries = graph.catalogRepository.favouriteSeriesIds().toHashSet()

        var favourites = 0
        var hidden = 0
        for (key in bundle.favouriteChannels) {
            val url = resolveUrl(key, urlIndex, bundle.version) ?: continue
            graph.catalogRepository.markChannelFavouriteByUrl(url)
            if (localFavChannels.add(url)) favourites++
        }
        for (key in bundle.hiddenChannels) {
            val url = resolveUrl(key, urlIndex, bundle.version) ?: continue
            graph.catalogRepository.markChannelHiddenByUrl(url)
            if (localHiddenChannels.add(url)) hidden++
        }
        for (key in bundle.favouriteMovies) {
            val url = resolveUrl(key, urlIndex, bundle.version) ?: continue
            graph.catalogRepository.markMovieFavouriteByUrl(url)
            if (localFavMovies.add(url)) favourites++
        }
        for (seriesId in bundle.favouriteSeries) {
            graph.catalogRepository.markSeriesFavouriteBySeriesId(seriesId)
            if (localFavSeries.add(seriesId)) favourites++
        }
        if (bundle.hiddenCategories.isNotEmpty()) {
            graph.settings.setHiddenCategories(
                graph.settings.hiddenCategories.value + bundle.hiddenCategories,
            )
        }

        // NAS recordings — insert any smb:// recording we don't already have, keyed by its locator.
        // Never touch an existing row; a peer's copy and ours are the same file on the same NAS.
        var recordings = 0
        for (rec in bundle.recordings) {
            if (!SmbClient.isSmb(rec.filePath)) continue
            if (graph.recordingRepository.byFilePath(rec.filePath) != null) continue
            // Playback comes from filePath; the stream URL only names the original line, so an
            // unresolvable key is fine to store empty rather than refusing the whole recording.
            val recordingStreamUrl = resolveUrl(rec.streamUrl, urlIndex, bundle.version).orEmpty()
            val inserted = runCatching {
                graph.recordingRepository.insert(
                    Recording(
                        // Ids from the source device mean nothing here; the row is self-contained.
                        channelId = 0,
                        sourceId = 0,
                        channelName = rec.channelName,
                        logoUrl = rec.logoUrl,
                        title = rec.title,
                        description = rec.description,
                        filePath = rec.filePath,
                        streamUrl = recordingStreamUrl,
                        userAgent = rec.userAgent,
                        scheduledStartMillis = rec.scheduledStartMillis,
                        scheduledEndMillis = rec.scheduledEndMillis,
                        startedAtMillis = rec.startedAtMillis,
                        endedAtMillis = rec.endedAtMillis,
                        status = RecordingStatus.COMPLETED,
                        sizeBytes = rec.sizeBytes,
                    ),
                )
            }.isSuccess
            if (inserted) recordings++
        }

        // Watch positions — two-way, newest wins.
        var positions = 0
        for (item in bundle.positions) {
            val profileId = profileIdFor(item.profile) ?: continue
            val url = resolveUrl(item.streamUrl, urlIndex, bundle.version) ?: continue
            val localId = when (item.kind) {
                "movie" -> graph.catalogRepository.movieByStreamUrl(url)?.id
                "ep" -> graph.catalogRepository.episodeByStreamUrl(url)?.id
                else -> null
            } ?: continue
            val mediaKey = "${item.kind}:$localId"
            val existing = graph.playbackPositions.get(profileId, mediaKey)
            if (existing == null || item.updatedAtMillis > existing.updatedAtMillis) {
                graph.playbackPositions.upsert(
                    PlaybackPosition(
                        profileId = profileId,
                        mediaKey = mediaKey,
                        positionMillis = item.positionMillis,
                        durationMillis = item.durationMillis,
                        updatedAtMillis = item.updatedAtMillis,
                    ),
                )
                positions++
            }
        }
        return MergeResult(
            positions = positions,
            favourites = favourites,
            hidden = hidden,
            recordings = recordings,
        )
    }

    /**
     * Resolves a bundle key to this device's own URL.
     *
     * `null` when a v4 key matches nothing here (the item simply is not in our catalogue). A
     * pre-v4 bundle carries the URL itself, so that is accepted as-is — an older build on the same
     * wifi keeps working, and this device is the one that had to change.
     */
    private fun resolveUrl(key: String, index: Map<String, String>, bundleVersion: Int): String? {
        if (key.isEmpty()) return null
        index[key]?.let { return it }
        return if (bundleVersion < 4 && key.startsWith("http", ignoreCase = true)) key else null
    }

    /**
     * `SyncKeys.key(url) -> url` for the catalogue slices this bundle actually references.
     *
     * Built per sync rather than stored: a hash column would need a schema migration for data
     * that only ever exists for the duration of one merge. Cost is one indexed pass over the
     * affected tables, and only the tables the peer mentioned.
     */
    private suspend fun buildUrlIndex(bundle: SyncBundle): Map<String, String> {
        val needChannels = bundle.favouriteChannels.isNotEmpty() ||
            bundle.hiddenChannels.isNotEmpty() ||
            bundle.recordings.isNotEmpty()
        val needMovies = bundle.favouriteMovies.isNotEmpty() ||
            bundle.positions.any { it.kind == "movie" }
        val needEpisodes = bundle.positions.any { it.kind == "ep" }
        if (!needChannels && !needMovies && !needEpisodes) return emptyMap()

        val index = HashMap<String, String>()
        if (needChannels) {
            for (c in graph.database.channels().allForMatching()) {
                index[SyncKeys.key(c.streamUrl)] = c.streamUrl
            }
        }
        if (needMovies) {
            for (m in graph.database.movies().all()) {
                index[SyncKeys.key(m.streamUrl)] = m.streamUrl
            }
        }
        if (needEpisodes) {
            for (e in graph.database.episodes().all()) {
                index[SyncKeys.key(e.streamUrl)] = e.streamUrl
            }
        }
        return index
    }

    private suspend fun profileIdFor(name: String): Long? {
        graph.profiles.byName(name)?.let { return it.id }
        return runCatching { graph.profiles.insert(Profile(name = name, createdAtMillis = 0)) }.getOrNull()
    }
}
