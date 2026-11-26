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
                streamUrl = streamUrl,
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
                    streamUrl = r.streamUrl,
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
            favouriteChannels = graph.catalogRepository.favouriteChannelUrls(),
            hiddenChannels = graph.catalogRepository.hiddenChannelUrls(),
            favouriteMovies = graph.catalogRepository.favouriteMovieUrls(),
            favouriteSeries = graph.catalogRepository.favouriteSeriesIds(),
            hiddenCategories = graph.settings.hiddenCategories.value.toList(),
            userHiddenLiveCategories = graph.settings.userHiddenLiveCategories.value.toList(),
            liveCategoryOrder = graph.settings.liveCategoryOrder.value,
            userHiddenMovieCategories = graph.settings.userHiddenMovieCategories.value.toList(),
            movieCategoryOrder = graph.settings.movieCategoryOrder.value,
            userHiddenSeriesCategories = graph.settings.userHiddenSeriesCategories.value.toList(),
            seriesCategoryOrder = graph.settings.seriesCategoryOrder.value,
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

        // Curation — additive union, keyed by stable identifiers. Count only genuinely new keys
        // (ones we hadn't already curated) so the "Synced N" line means "new to this device".
        val localFavChannels = graph.catalogRepository.favouriteChannelUrls().toHashSet()
        val localHiddenChannels = graph.catalogRepository.hiddenChannelUrls().toHashSet()
        val localFavMovies = graph.catalogRepository.favouriteMovieUrls().toHashSet()
        val localFavSeries = graph.catalogRepository.favouriteSeriesIds().toHashSet()

        var favourites = 0
        var hidden = 0
        for (url in bundle.favouriteChannels) {
            graph.catalogRepository.markChannelFavouriteByUrl(url)
            if (localFavChannels.add(url)) favourites++
        }
        for (url in bundle.hiddenChannels) {
            graph.catalogRepository.markChannelHiddenByUrl(url)
            if (localHiddenChannels.add(url)) hidden++
        }
        for (url in bundle.favouriteMovies) {
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
        if (bundle.userHiddenLiveCategories.isNotEmpty()) {
            graph.settings.setUserHiddenLiveCategories(
                graph.settings.userHiddenLiveCategories.value + bundle.userHiddenLiveCategories,
            )
        }
        if (bundle.liveCategoryOrder.isNotEmpty()) {
            val merged = (bundle.liveCategoryOrder + graph.settings.liveCategoryOrder.value).distinct()
            graph.settings.setLiveCategoryOrder(merged)
        }
        if (bundle.userHiddenMovieCategories.isNotEmpty()) {
            graph.settings.setUserHiddenMovieCategories(
                graph.settings.userHiddenMovieCategories.value + bundle.userHiddenMovieCategories,
            )
        }
        if (bundle.movieCategoryOrder.isNotEmpty()) {
            val merged = (bundle.movieCategoryOrder + graph.settings.movieCategoryOrder.value).distinct()
            graph.settings.setMovieCategoryOrder(merged)
        }
        if (bundle.userHiddenSeriesCategories.isNotEmpty()) {
            graph.settings.setUserHiddenSeriesCategories(
                graph.settings.userHiddenSeriesCategories.value + bundle.userHiddenSeriesCategories,
            )
        }
        if (bundle.seriesCategoryOrder.isNotEmpty()) {
            val merged = (bundle.seriesCategoryOrder + graph.settings.seriesCategoryOrder.value).distinct()
            graph.settings.setSeriesCategoryOrder(merged)
        }

        // NAS recordings — insert any smb:// recording we don't already have, keyed by its locator.
        // Never touch an existing row; a peer's copy and ours are the same file on the same NAS.
        var recordings = 0
        for (rec in bundle.recordings) {
            if (!SmbClient.isSmb(rec.filePath)) continue
            if (graph.recordingRepository.byFilePath(rec.filePath) != null) continue
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
                        streamUrl = rec.streamUrl,
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
            val localId = when (item.kind) {
                "movie" -> graph.catalogRepository.movieByStreamUrl(item.streamUrl)?.id
                "ep" -> graph.catalogRepository.episodeByStreamUrl(item.streamUrl)?.id
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

    private suspend fun profileIdFor(name: String): Long? {
        graph.profiles.byName(name)?.let { return it.id }
        return runCatching { graph.profiles.insert(Profile(name = name, createdAtMillis = 0)) }.getOrNull()
    }
}
