/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The domain models double as Room entities.
 *
 * A separate entity layer with mappers is the textbook answer, but for an app this size it
 * roughly doubles the code for no behavioural gain, and every extra layer is one more thing
 * a first-time contributor has to understand before they can fix a bug. If OpenTV ever grows
 * a second storage backend, split them then.
 */

/** How a source delivers its catalogue. */
enum class SourceKind {
    /** Xtream Codes / "player API" panel: username + password + host. */
    XTREAM,

    /** A plain M3U/M3U8 playlist URL, optionally with a separate XMLTV EPG URL. */
    M3U,

    /**
     * Stalker / Ministra portal (MAG-box middleware): identified by a MAC address rather than a
     * login, with a token handshake and per-play `create_link` stream resolution. Its channels
     * carry a [Channel.cmd] resolved to a real URL on demand at play time.
     */
    STALKER,
}

/** Live-stream container the panel is asked for. Xtream panels serve one or both. */
enum class LiveStreamFormat(val extension: String) {
    /** HLS — `.../<id>.m3u8`. ExoPlayer's HLS path. The historical default. */
    HLS("m3u8"),

    /** Raw MPEG-TS — `.../<id>.ts`. Some panels only serve this; more universally compatible. */
    MPEG_TS("ts"),
}

/**
 * A configured provider. Local use needs no account or OpenTV server. Optional cloud
 * copies are encrypted on-device; see docs/CLOUD_SYNC.md.
 */
@Entity(tableName = "sources")
data class Source(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: SourceKind,
    /** Base URL including scheme and port, e.g. `http://example.com:8080`. No trailing slash. */
    val url: String,
    val username: String? = null,
    val password: String? = null,
    /**
     * Stalker/Ministra MAC address, e.g. `00:1A:79:xx:xx:xx` — the portal's identity and credential
     * (it takes the place of username/password). Null for Xtream/M3U.
     */
    val macAddress: String? = null,
    /** Optional explicit XMLTV URL. For Xtream this is derived if left null. */
    val epgUrl: String? = null,
    /**
     * Some panels reject requests whose User-Agent they do not recognise, which is the
     * usual cause of a blanket 403 on every stream. Overridable per source.
     */
    val userAgent: String = DEFAULT_USER_AGENT,
    /**
     * Live-stream container to request from an Xtream panel. Defaults to HLS, which is what the
     * app has always used; a panel that only serves raw MPEG-TS needs this set to [LiveStreamFormat.MPEG_TS].
     * Ignored for M3U sources, whose channel URLs come straight from the playlist.
     */
    val liveFormat: LiveStreamFormat = LiveStreamFormat.HLS,
    val enabled: Boolean = true,
    val lastCatalogSyncMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val sortIndex: Int = 0,
) {
    companion object {
        const val DEFAULT_USER_AGENT: String = "OpenTV/0.1 (Android)"
    }
}

enum class StreamKind { LIVE, MOVIE, SERIES }

@Entity(
    tableName = "categories",
    primaryKeys = ["sourceId", "id", "kind"],
)
data class Category(
    val id: String,
    val sourceId: Long,
    val name: String,
    val kind: StreamKind,
    val sortIndex: Int = 0,
)

@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["sourceId", "streamId"], unique = true),
        Index(value = ["sourceId", "categoryId"]),
        Index(value = ["groupKey"]),
        Index(value = ["favourite"]),
    ],
)
data class Channel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    /** Provider's own stream id (Xtream) or the tvg-id/url hash (M3U). Stable per source. */
    val streamId: String,
    /** The provider's name, verbatim: `UK| BBC ONE FHD`. Kept for search and debugging. */
    val name: String,
    /** Cleaned display name: `BBC One`. What the UI shows. */
    val displayName: String = name,
    /**
     * Normalised join key: `bbcone`. Channels sharing a groupKey are quality variants of
     * one logical channel — shown as a single row, switchable during playback — and this is
     * also the key the EPG matcher joins on. See ChannelNameNormalizer.
     */
    val groupKey: String = "",
    /** Higher plays by default. 0 = the name said nothing about quality. */
    val qualityRank: Int = 0,
    /** `FHD`, `HD`, `RAW 60fps`… empty when unknown. */
    val qualityLabel: String = "",
    val categoryId: String?,
    val logoUrl: String?,
    /** Guide id supplied by the provider, when it bothers. Often blank. */
    val epgChannelId: String?,
    /** Guide id found by the name matcher. Recomputed after every guide sync. */
    val matchedEpgId: String? = null,
    /** Guide id chosen by the user by hand. Beats both of the above, survives resyncs. */
    val epgOverrideId: String? = null,
    /** Whether the provider offers catch-up/archive on this channel, and how many days back. */
    @ColumnInfo(defaultValue = "0") val tvArchive: Boolean = false,
    @ColumnInfo(defaultValue = "0") val tvArchiveDays: Int = 0,
    val number: Int?,
    /** Fully-resolved playback URL. For Stalker channels this is a placeholder; [cmd] is resolved
     *  to the real URL on demand at play time (see [SourceKind.STALKER]). */
    val streamUrl: String,
    /**
     * Stalker/Ministra play command (`ffmpeg http://…`, `auto …`, or a bare URL) that `create_link`
     * turns into a short-lived playable URL at tune time. Null for Xtream/M3U.
     */
    val cmd: String? = null,
    val favourite: Boolean = false,
    val hidden: Boolean = false,
    val sortIndex: Int = 0,
    /**
     * The user's manual rename, `null` until they set one. Beats [displayName] in the UI (see
     * [shownName]) and is carried across catalogue re-syncs, so a rename is not lost on refresh.
     */
    val customName: String? = null,
    /**
     * Stamp of the last catalogue sync that saw this channel.
     *
     * Used to prune channels the provider has dropped. The obvious alternative —
     * `DELETE ... WHERE streamId NOT IN (:allCurrentIds)` — breaks on real providers, because
     * SQLite caps bound variables (999 on older Android, 32766 on newer) and a large playlist
     * has 40,000 channels. A stamp comparison has no such limit.
     */
    val lastSeenMillis: Long = 0,
) {
    /**
     * The guide ids worth trying for this channel, most trustworthy first. The UI walks
     * them in order and uses the first that actually has programmes — a provider-supplied
     * id pointing at a guide the provider never ships must not shadow a working match.
     */
    val epgCandidates: List<String>
        get() = listOfNotNull(epgOverrideId, epgChannelId, matchedEpgId)
}

/** What the UI should show for a channel: the user's rename if set, else the cleaned display name. */
val Channel.shownName: String get() = customName?.takeIf { it.isNotBlank() } ?: displayName

/**
 * One guide feed: where XMLTV comes from.
 *
 * Three kinds share the table. Provider feeds are created automatically from each source's
 * panel. Built-in feeds are the curated free sources shipped with the app, off by default.
 * Custom feeds are URLs the user adds. All enabled feeds are downloaded and merged into a
 * single guide, and the matcher runs across the lot.
 */
@Entity(tableName = "epg_feeds")
data class EpgFeed(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Null for provider feeds — their URL is derived from the source's credentials. */
    val url: String? = null,
    /** Set when this feed belongs to a provider source. */
    val providerSourceId: Long? = null,
    /** Shipped with the app (as opposed to user-added). Used only for display. */
    val builtIn: Boolean = false,
    val enabled: Boolean = true,
    val lastSyncMillis: Long = 0,
    /** Human-readable outcome of the last sync attempt, shown in settings. */
    val lastResult: String = "",
)

/**
 * One `<channel>` element from a guide: the raw material for name matching.
 * (epgId, displayName) pairs, kept per feed so a removed feed takes its aliases with it.
 */
@Entity(
    tableName = "epg_channels",
    primaryKeys = ["feedId", "epgId"],
    indices = [Index(value = ["normalizedKey"])],
)
data class EpgChannelAlias(
    val feedId: Long,
    val epgId: String,
    val displayName: String,
    /** ChannelNameNormalizer.groupKeyOf(displayName), precomputed for the matcher. */
    val normalizedKey: String,
)

/**
 * One programme in the guide.
 *
 * Times are epoch millis in UTC. Rendering converts to the device zone at draw time;
 * we never store local time, which is the classic cause of a guide that is silently
 * an hour or two out.
 */
@Entity(
    tableName = "programmes",
    indices = [
        Index(value = ["feedId", "epgChannelId", "startUtcMillis"], unique = true),
        Index(value = ["endUtcMillis"]),
        Index(value = ["epgChannelId"]),
    ],
)
data class Programme(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The [EpgFeed] this came from. */
    val feedId: Long,
    val epgChannelId: String,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val iconUrl: String? = null,
) {
    val durationMillis: Long get() = endUtcMillis - startUtcMillis

    fun isLiveAt(nowUtcMillis: Long): Boolean =
        nowUtcMillis in startUtcMillis until endUtcMillis

    fun progressAt(nowUtcMillis: Long): Float {
        if (durationMillis <= 0L) return 0f
        return ((nowUtcMillis - startUtcMillis).toFloat() / durationMillis).coerceIn(0f, 1f)
    }
}

@Entity(
    tableName = "movies",
    indices = [
        Index(value = ["sourceId", "streamId"], unique = true),
        Index(value = ["sourceId", "categoryId"]),
    ],
)
data class Movie(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val streamId: String,
    val name: String,
    val categoryId: String?,
    val posterUrl: String?,
    val rating: Double?,
    val year: Int?,
    val plot: String?,
    val durationSeconds: Int?,
    val containerExtension: String?,
    val streamUrl: String,
    val favourite: Boolean = false,
    val addedMillis: Long = 0,
    /**
     * Richer metadata for the Netflix-style detail/home surfaces, pulled from the provider's own
     * Xtream `get_vod_info`. All nullable and best-effort: a panel that omits them just yields a
     * plainer card. Populated opportunistically at sync time and back-filled on first detail open.
     */
    /** Wide 16:9 art for hero/detail headers (Xtream `backdrop_path`, first URL of the array). */
    val backdropUrl: String? = null,
    /** Billed cast, provider-formatted (usually comma-separated). */
    val cast: String? = null,
    /** Genre(s) as the provider labels them; often comma- or pipe-separated (see repo grouping). */
    val genre: String? = null,
    /** TMDB id, stored only — resolved/used by a later agent. Coerced to String (panels vary). */
    val tmdbId: String? = null,
    /** Director(s), provider-formatted. Movies only. */
    val director: String? = null,
)

@Entity(
    tableName = "series",
    indices = [
        Index(value = ["sourceId", "seriesId"], unique = true),
        Index(value = ["sourceId", "categoryId"]),
    ],
)
data class Series(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val seriesId: String,
    val name: String,
    val categoryId: String?,
    val posterUrl: String?,
    val rating: Double?,
    val year: Int?,
    val plot: String?,
    val favourite: Boolean = false,
    val addedMillis: Long = 0,
    /**
     * Richer metadata for the Netflix-style detail/home surfaces, from the provider's own Xtream
     * `get_series_info`. Nullable and best-effort. Series carry no `director` (episodes vary), so
     * that field is on [Movie] only. Populated at sync time and back-filled on first detail open.
     */
    /** Wide 16:9 art for hero/detail headers (Xtream `backdrop_path`, first URL of the array). */
    val backdropUrl: String? = null,
    /** Billed cast, provider-formatted (usually comma-separated). */
    val cast: String? = null,
    /** Genre(s) as the provider labels them; often comma- or pipe-separated (see repo grouping). */
    val genre: String? = null,
    /** TMDB id, stored only — resolved/used by a later agent. Coerced to String (panels vary). */
    val tmdbId: String? = null,
)

@Entity(
    tableName = "episodes",
    indices = [
        Index(value = ["sourceId", "episodeId"], unique = true),
        Index(value = ["sourceId", "seriesId"]),
    ],
)
data class Episode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val seriesId: String,
    val episodeId: String,
    val season: Int,
    val episodeNumber: Int,
    val title: String,
    val plot: String?,
    val durationSeconds: Int?,
    val stillUrl: String?,
    val streamUrl: String,
)

/**
 * Resume position for a movie or episode, per profile. Live TV is never resumed.
 *
 * Keyed by (profileId, mediaKey) so two people's progress through the same film stay separate —
 * the whole point of profiles.
 */
@Entity(tableName = "playback_positions", primaryKeys = ["profileId", "mediaKey"])
data class PlaybackPosition(
    val profileId: Long,
    val mediaKey: String,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAtMillis: Long,
) {
    /** Treat the last 5% as "finished" so we do not resume two seconds before the credits. */
    val isFinished: Boolean
        get() = durationMillis > 0 && positionMillis.toDouble() / durationMillis > 0.95
}

/** A local viewing profile — just a name. No account, no password; it lives on this device. */
@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMillis: Long = 0,
)

/** Where a recording is in its lifecycle. */
enum class RecordingStatus {
    /** Booked from the guide, waiting for its start time. */
    SCHEDULED,

    /** Capturing right now. */
    RECORDING,

    /** Finished and playable. */
    COMPLETED,

    /** Stopped early or hit an error; the file may be partial or missing. */
    FAILED,
}

/**
 * One recording — scheduled, in progress, or finished.
 *
 * The channel name, logo and stream URL are snapshotted at record time so the library still
 * reads correctly even if the provider later drops or renames the channel. The captured file is
 * a raw MPEG-TS (`.ts`) written straight from the live stream; it plays back through the same
 * player as VOD, with full seeking once complete.
 */
@Entity(
    tableName = "recordings",
    indices = [Index(value = ["status"]), Index(value = ["channelId"])],
)
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The channel row this came from (may since have been re-synced away). */
    val channelId: Long,
    val sourceId: Long,
    /** Channel name at record time — the library shows this, not a live lookup. */
    val channelName: String,
    val logoUrl: String? = null,
    /** Programme title when tied to a guide entry; otherwise the channel name. */
    val title: String,
    val description: String? = null,
    /**
     * Where the captured `.ts` lives: an absolute filesystem path (internal), an `smb://` locator
     * (NAS), or a `content://` document URI (USB via SAF). [RecordingStorage] reads it back.
     */
    val filePath: String,
    /** The stream URL being captured. */
    val streamUrl: String,
    /** User-Agent to capture with (some panels only serve a UA they recognise). */
    val userAgent: String = Source.DEFAULT_USER_AGENT,
    /** Planned window, for scheduled recordings (0 when started on the spot). */
    val scheduledStartMillis: Long = 0,
    val scheduledEndMillis: Long = 0,
    val startedAtMillis: Long = 0,
    val endedAtMillis: Long = 0,
    val status: RecordingStatus,
    val sizeBytes: Long = 0,
    /** Set when this recording was booked by a series-link rule (for de-dup). */
    val seriesRuleId: Long? = null,
    /** The viewing profile this recording is for (null = unassigned / everyone). */
    val profileId: Long? = null,
    /** Human-readable reason when [status] is FAILED. */
    val error: String? = null,
) {
    /** Best available length: actual if finished, else the planned window. */
    val durationMillis: Long
        get() = when {
            endedAtMillis > startedAtMillis -> endedAtMillis - startedAtMillis
            scheduledEndMillis > scheduledStartMillis -> scheduledEndMillis - scheduledStartMillis
            else -> 0
        }
}

/**
 * A series-link rule: record every future airing of a programme on a channel.
 *
 * Xtream/XMLTV rarely give a stable series id, so matching is by normalised title on a specific
 * channel. A periodic scan turns matching upcoming programmes into scheduled recordings.
 */
@Entity(tableName = "series_rules", indices = [Index(value = ["channelId"])])
data class SeriesRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val channelName: String,
    /** Normalised programme title to match upcoming airings against. */
    val titleKey: String,
    /** The title as shown to the user. */
    val title: String,
    val createdAtMillis: Long = 0,
    val enabled: Boolean = true,
)

/**
 * A one-off reminder for a future programme.
 *
 * At the programme's start time an exact alarm fires a notification; tapping it jumps to the
 * channel. With [autoTune] the fired notification is full-screen so a living-room box switches
 * over on its own. A reminder is identified for de-dup by (channelId, startUtcMillis) — the same
 * (channel, slot) can only be reminded once. Everything is snapshotted so the reminder still
 * reads correctly even if the guide later shifts.
 */
@Entity(
    tableName = "reminders",
    indices = [Index(value = ["startUtcMillis"]), Index(value = ["channelId"])],
)
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val channelName: String,
    val logoUrl: String? = null,
    /** The programme title being reminded about. */
    val title: String,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    /** Switch to the channel automatically at start (full-screen notification), not just notify. */
    val autoTune: Boolean = false,
    val createdAtMillis: Long = 0,
    /** Set once the alarm has fired, so a boot re-arm skips it and the list can grey it out. */
    val fired: Boolean = false,
)
