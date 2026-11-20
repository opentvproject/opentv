/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import kotlinx.serialization.Serializable

/**
 * The state two OpenTV devices exchange when they sync.
 *
 * Nothing here is keyed by a local database id — those differ from one install to the next. An
 * item is identified by something stable for the same provider: a channel/movie/episode by its
 * **stream URL**, a series by its **provider series id**, a profile by its **name**, a category
 * by its **group key**, a NAS recording by its **smb:// locator**. So a bundle from one device
 * makes sense on another.
 *
 * ## URLs are hashed on the wire (bundle version 4)
 *
 * Every field documented as a "URL" actually carries [SyncKeys.key] of that URL — a truncated
 * SHA-256, never the URL itself. An Xtream URL is `http://host/live/USER/PASS/id.ts`, so shipping
 * it verbatim would put the provider's username and password on the LAN and into the peer's
 * storage, which is exactly what this bundle promises not to do. The receiver resolves each key
 * against its own catalogue, so matching still works and a peer's account never travels.
 *
 * Bundles with `version < 4` still decode: the strings in them are raw URLs, and [SyncEngine]
 * falls back to treating them as such, so an older device on the same wifi keeps working.
 *
 * Two merge policies travel together, matching how people actually use this:
 *  - **Watch positions** are two-way, newest-wins per item (you watch on any device, resume on
 *    all), using [SyncPosition.updatedAtMillis].
 *  - **Curation and recordings** are additive: favourites, hidden channels, hidden categories and
 *    NAS recordings from a peer are merged in without ever removing anything local. You tidy up on
 *    one device and the others adopt it, but a favourite set only on one box is never lost.
 *
 * Every field defaults to empty and the reader ignores unknown keys, so a positions-only device
 * and a full device interoperate without a flag day.
 */
@Serializable
data class SyncBundle(
    val version: Int = 4,
    val generatedAtMillis: Long = 0,
    /** Profile names the peer knows about. Others add any they are missing (add-only). */
    val profiles: List<String> = emptyList(),
    /** Two-way, newest-wins resume points. */
    val positions: List<SyncPosition> = emptyList(),
    /** Additive curation, keyed by the stable identifiers noted above. */
    val favouriteChannels: List<String> = emptyList(),
    val hiddenChannels: List<String> = emptyList(),
    val favouriteMovies: List<String> = emptyList(),
    /** Favourite series, keyed by the provider's own series id (series carry no stream URL). */
    val favouriteSeries: List<String> = emptyList(),
    val hiddenCategories: List<String> = emptyList(),
    /**
     * Recordings that live on the shared NAS (their [SyncRecording.filePath] is an `smb://`
     * locator) and so play on any device pointed at the same NAS. Device-local recordings are
     * never shared — they cannot be opened from another box.
     */
    val recordings: List<SyncRecording> = emptyList(),
)

@Serializable
data class SyncPosition(
    val profile: String,
    /** [SyncKeys.key] of the stream URL — see [SyncBundle] on why the URL itself never travels. */
    val streamUrl: String,
    /** "movie" or "ep" — which table to resolve [streamUrl] against on the receiving device. */
    val kind: String,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * A finished NAS recording, portable across devices. Everything needed to insert a playable
 * library row is snapshotted here; the receiving device opens [filePath] (an `smb://` locator)
 * straight off the shared NAS. De-duplicated on the receiver by that locator.
 */
@Serializable
data class SyncRecording(
    val channelName: String,
    val logoUrl: String? = null,
    val title: String,
    val description: String? = null,
    /** The `smb://host/share/…/file.ts` locator on the shared NAS. */
    val filePath: String,
    /**
     * [SyncKeys.key] of the source stream URL. Recordings play from [filePath]; this is only
     * kept so the receiving device can still name the original line. Empty when the peer hashed
     * a URL this device does not know.
     */
    val streamUrl: String,
    val userAgent: String,
    val scheduledStartMillis: Long = 0,
    val scheduledEndMillis: Long = 0,
    val startedAtMillis: Long = 0,
    val endedAtMillis: Long = 0,
    val sizeBytes: Long = 0,
)
