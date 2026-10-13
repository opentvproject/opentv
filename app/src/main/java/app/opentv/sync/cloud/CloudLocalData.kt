/* SPDX-License-Identifier: GPL-3.0-or-later */
package app.opentv.sync.cloud

import androidx.room.withTransaction
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.data.model.*

data class CloudLocalItem(val table: String, val id: String, val item: CloudItem)

/** Maps only user-authored state. Catalogues, programmes, recordings, paths, and NAS secrets stay local. */
class CloudLocalData(
    private val graph: ServiceLocator.Graph,
    private val store: CloudStore,
    private val account: String,
    private val key: ByteArray,
) {
    private val db get() = graph.database
    private fun id(category: String, identity: String) = CloudCrypto.stableId(key, category, identity)
    private fun bound(category: String, localId: Long, identity: String): String =
        store.cloudId(account, category, "$localId") ?: id(category, identity).also { store.bind(account, category, "$localId", it) }
    private fun providerId(s: Source) = bound("source", s.id, "${s.kind}|${s.url}|${s.username.orEmpty()}")
    private fun profileId(p: Profile) = bound("profile", p.id, p.name)
    suspend fun gather(categories: Set<CloudCategory>): List<CloudLocalItem> = db.withTransaction {
        val result = mutableListOf<CloudLocalItem>()
        fun add(category: CloudCategory, identity: String, type: String, values: Map<String, String>, stable: String? = null) {
            if (category in categories) result += CloudLocalItem(category.table, stable ?: id(category.table, identity), CloudItem(type, values))
        }
        val sources = db.sources().all()
        val sourceIds = sources.associate { it.id to providerId(it) }
        for (s in sources) {
            if (s.kind == SourceKind.STALKER) continue // Short-lived portal/MAC authorization is deliberately local.
            add(if (s.kind == SourceKind.M3U) CloudCategory.PLAYLISTS else CloudCategory.PROVIDERS, "", "source",
                mapOf("name" to s.name, "kind" to s.kind.name, "url" to s.url, "username" to s.username.orEmpty(),
                    "password" to s.password.orEmpty(), "epg" to s.epgUrl.orEmpty(), "ua" to s.userAgent,
                    "format" to s.liveFormat.name, "enabled" to "${s.enabled}", "order" to "${s.sortIndex}"), sourceIds.getValue(s.id))
        }
        val profiles = graph.profiles.all()
        val profileIds = profiles.associate { it.id to profileId(it) }
        for (p in profiles) add(CloudCategory.PROFILES, "", "profile", mapOf("name" to p.name), profileIds.getValue(p.id))
        if (CloudCategory.FAVORITES in categories || CloudCategory.SETTINGS in categories) {
            for (c in db.channels().cloudCurated()) {
                val common = mapOf("url" to c.streamUrl)
                if (c.favourite) add(CloudCategory.FAVORITES, "channel|${c.streamUrl}", "channel", common)
                if (c.hidden) add(CloudCategory.FAVORITES, "hidden|${c.streamUrl}", "hidden", common)
                if (c.sortIndex != 0 || c.customName != null || c.epgOverrideId != null) add(CloudCategory.SETTINGS, "channel|${c.streamUrl}", "channel_settings",
                    common + mapOf("order" to "${c.sortIndex}", "name" to c.customName.orEmpty(), "epg" to c.epgOverrideId.orEmpty()))
            }
        }
        if (CloudCategory.FAVORITES in categories) {
            for (m in db.movies().cloudFavourites()) add(CloudCategory.FAVORITES, "movie|${m.streamUrl}", "movie", mapOf("url" to m.streamUrl))
            for (s in db.series().cloudFavourites()) {
                val provider = sourceIds[s.sourceId] ?: continue
                add(CloudCategory.FAVORITES, "series|$provider|${s.seriesId}", "series", mapOf("provider" to provider, "series" to s.seriesId))
            }
            for (category in graph.settings.hiddenCategories.value) add(CloudCategory.FAVORITES, "category|$category", "category", mapOf("key" to category))
        }
        if (CloudCategory.WATCH in categories) {
            for (p in graph.playbackPositions.all()) {
                val parts = p.mediaKey.split(':', limit = 2)
                if (parts.size != 2) continue
                val localId = parts[1].toLongOrNull() ?: continue
                val url = when (parts[0]) {
                    "movie" -> graph.catalogRepository.movie(localId)?.streamUrl
                    "ep" -> graph.catalogRepository.episode(localId)?.streamUrl
                    else -> null
                } ?: continue
                val profile = profileIds[p.profileId] ?: continue
                add(CloudCategory.WATCH, "$profile|${parts[0]}|$url", "position", mapOf("profile" to profile,
                    "profile_name" to profiles.first { it.id == p.profileId }.name, "kind" to parts[0], "url" to url,
                    "position" to "${p.positionMillis}", "duration" to "${p.durationMillis}", "updated" to "${p.updatedAtMillis}"))
            }
        }
        if (CloudCategory.SETTINGS in categories) {
            for ((name, value) in preferenceValues()) add(CloudCategory.SETTINGS, name, "preference", mapOf("key" to name, "value" to value))
            for (feed in db.epgFeeds().all().filter { it.providerSourceId == null }) {
                add(CloudCategory.SETTINGS, "", "epg_feed", mapOf("name" to feed.name, "url" to feed.url.orEmpty(), "enabled" to "${feed.enabled}", "builtin" to "${feed.builtIn}"),
                    bound("epg", feed.id, feed.url.orEmpty()))
            }
        }
        result
    }
    private fun preferenceValues(): Map<String, String> = graph.settings.run {
        mapOf("theme" to themeMode.value.name, "layout" to channelLayout.value.name, "subtitles" to "${subtitlesEnabled.value}",
            "preview_video" to "${guidePreviewVideo.value}", "preview_sound" to "${guidePreviewSound.value}",
            "resume_last" to "${resumeLastChannel.value}", "resize" to "${playerResizeMode.value}",
            "live" to "${liveEnabled.value}", "movies" to "${moviesEnabled.value}", "series" to "${seriesEnabled.value}")
    }
    private suspend fun mediaId(item: CloudItem): Long? = when (item.values["kind"]) {
        "movie" -> db.movies().byStreamUrl(item.values.getValue("url"))?.id
        "ep" -> db.episodes().byStreamUrl(item.values.getValue("url"))?.id
        else -> null
    }
    /** Absence from a downloaded catalogue is NOT an unfavorite/delete. Wait until the item exists. */
    suspend fun canDelete(item: CloudItem): Boolean = when (item.type) {
        "channel", "hidden", "channel_settings" -> db.channels().byStreamUrl(item.values.getValue("url")) != null
        "movie" -> db.movies().byStreamUrl(item.values.getValue("url")) != null
        "series" -> store.localId(account, "source", item.values.getValue("provider"))?.toLongOrNull()?.let {
            db.series().cloudByProvider(it, item.values.getValue("series")) != null
        } ?: false
        "position" -> mediaId(item) != null
        else -> true
    }
    suspend fun apply(record: CloudRecord, item: CloudItem, categories: Set<CloudCategory>): Boolean = db.withTransaction {
        val v = item.values
        when (item.type) {
            "source" -> {
                val kind = SourceKind.valueOf(v.getValue("kind"))
                require(kind != SourceKind.STALKER)
                val all = db.sources().all()
                val existing = store.localId(account, "source", record.id)?.toLongOrNull()?.let { local -> all.firstOrNull { it.id == local } }
                    ?: all.firstOrNull { it.kind == kind && it.url == v["url"] && it.username.orEmpty() == v["username"] }
                if (record.deleted) {
                    if (existing != null && existing.kind == kind) {
                        // A kind change moves the same stable identity between provider/playlist
                        // tables. An old-kind tombstone must not delete its already-applied successor.
                        db.channels().deleteForSource(existing.id); db.movies().deleteForSource(existing.id)
                        db.series().deleteForSource(existing.id); db.episodes().deleteForSource(existing.id)
                        db.categories().deleteForSource(existing.id); db.sources().delete(existing.id)
                    }
                } else {
                    val source = Source(id = existing?.id ?: 0, name = v.getValue("name"), kind = kind, url = v.getValue("url"),
                        username = v["username"]?.ifBlank { null }, password = v["password"]?.ifBlank { null }, epgUrl = v["epg"]?.ifBlank { null },
                        userAgent = v.getValue("ua"), liveFormat = LiveStreamFormat.valueOf(v.getValue("format")), enabled = v.getValue("enabled").toBooleanStrict(),
                        sortIndex = v.getValue("order").toInt(), lastCatalogSyncMillis = existing?.lastCatalogSyncMillis ?: 0)
                    val localId = graph.sourceRepository.save(source)
                    store.bind(account, "source", "$localId", record.id)
                }
            }
            "profile" -> {
                val all = graph.profiles.all()
                val existing = store.localId(account, "profile", record.id)?.toLongOrNull()?.let { local -> all.firstOrNull { it.id == local } }
                    ?: all.firstOrNull { it.name == v["name"] }
                if (record.deleted) {
                    if (existing != null) {
                        if (all.size <= 1) return@withTransaction false
                        graph.profiles.deletePositions(existing.id); graph.profiles.delete(existing.id)
                        if (graph.settings.activeProfileId.value == existing.id) graph.settings.setActiveProfile(all.first { it.id != existing.id }.id)
                    }
                } else {
                    require(v.getValue("name").isNotBlank())
                    val localId = existing?.id ?: graph.profiles.insert(Profile(name = v.getValue("name")))
                    if (existing != null && existing.name != v["name"]) graph.profiles.rename(localId, v.getValue("name"))
                    store.bind(account, "profile", "$localId", record.id)
                }
            }
            "channel", "hidden", "channel_settings" -> {
                val c = db.channels().byStreamUrl(v.getValue("url")) ?: return@withTransaction false
                when (item.type) {
                    "channel" -> db.channels().setFavourite(c.id, !record.deleted)
                    "hidden" -> db.channels().setHidden(c.id, !record.deleted)
                    else -> {
                        db.openHelper.writableDatabase.execSQL("UPDATE channels SET sortIndex=?,customName=?,epgOverrideId=? WHERE id=?", arrayOf(
                            if (record.deleted) 0 else v.getValue("order").toInt(),
                            if (record.deleted) null else v["name"]?.ifBlank { null }, if (record.deleted) null else v["epg"]?.ifBlank { null }, c.id))
                    }
                }
            }
            "movie" -> { val m = db.movies().byStreamUrl(v.getValue("url")) ?: return@withTransaction false; db.movies().setFavourite(m.id, !record.deleted) }
            "series" -> {
                val source = store.localId(account, "source", v.getValue("provider"))?.toLongOrNull() ?: return@withTransaction false
                val s = db.series().cloudByProvider(source, v.getValue("series")) ?: return@withTransaction false
                db.series().setFavourite(s.id, !record.deleted)
            }
            "category" -> {
                val old = graph.settings.hiddenCategories.value; val category = v.getValue("key")
                graph.settings.setHiddenCategories(if (record.deleted) old - category else old + category)
            }
            "position" -> {
                val profile = store.localId(account, "profile", v.getValue("profile"))?.toLongOrNull()
                    ?: graph.profiles.byName(v.getValue("profile_name"))?.id ?: return@withTransaction false
                if (graph.profiles.all().none { it.id == profile }) return@withTransaction false
                val media = mediaId(item) ?: return@withTransaction false
                val mediaKey = "${v.getValue("kind")}:$media"
                if (record.deleted) graph.playbackPositions.delete(profile, mediaKey)
                else graph.playbackPositions.upsert(PlaybackPosition(profile, mediaKey,
                    v.getValue("position").toLong().coerceAtLeast(0), v.getValue("duration").toLong().coerceAtLeast(0), v.getValue("updated").toLong()))
            }
            "preference" -> if (!record.deleted) applyPreference(v.getValue("key"), v.getValue("value"))
            "epg_feed" -> {
                val all = db.epgFeeds().all()
                val existing = store.localId(account, "epg", record.id)?.toLongOrNull()?.let { local -> all.firstOrNull { it.id == local } }
                    ?: all.firstOrNull { it.providerSourceId == null && it.url.orEmpty() == v["url"] }
                if (record.deleted) { if (existing != null && !existing.builtIn) db.epgFeeds().delete(existing.id) }
                else {
                    val feed = EpgFeed(id = existing?.id ?: 0, name = v.getValue("name"), url = v["url"]?.ifBlank { null },
                        builtIn = v.getValue("builtin").toBooleanStrict(), enabled = v.getValue("enabled").toBooleanStrict(), lastSyncMillis = existing?.lastSyncMillis ?: 0)
                    val localId = if (existing == null) db.epgFeeds().insert(feed) else { db.epgFeeds().update(feed); existing.id }
                    store.bind(account, "epg", "$localId", record.id)
                }
            }
            else -> error("Update OpenTV to read this cloud item")
        }
        true
    }
    private fun applyPreference(name: String, value: String) = graph.settings.run {
        when (name) {
            "theme" -> setThemeMode(AppSettings.ThemeMode.valueOf(value))
            "layout" -> setChannelLayout(AppSettings.ChannelLayout.valueOf(value))
            "subtitles" -> setSubtitlesEnabled(value.toBooleanStrict())
            "preview_video" -> setGuidePreviewVideo(value.toBooleanStrict())
            "preview_sound" -> setGuidePreviewSound(value.toBooleanStrict())
            "resume_last" -> setResumeLastChannel(value.toBooleanStrict())
            "resize" -> { require(value.toInt() in 0..2); setPlayerResizeMode(value.toInt()) }
            "live" -> setLiveEnabled(value.toBooleanStrict())
            "movies" -> setMoviesEnabled(value.toBooleanStrict())
            "series" -> setSeriesEnabled(value.toBooleanStrict())
            else -> error("Unsupported cloud preference")
        }
    }
}
