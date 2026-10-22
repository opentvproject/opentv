/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/**
 * Forward-buffer targets for ordinary playback, in milliseconds.
 *
 * DEFAULT preserves OpenTV's existing tuning: larger than ExoPlayer's defaults, because IPTV
 * sources are much twitchier than a CDN, and a deeper buffer is the difference between a
 * momentary hiccup and a visible stall.
 *
 * The rest are for the two kinds of box this cannot know about in advance: one that is
 * short of memory and would rather start quickly, and one that is on a bad line and would
 * rather wait. These are *targets*, not guarantees — a stream's own live window and the
 * player's allocation limit can stop the reservoir filling all the way.
 *
 * The shape came from PR #17; the values are OpenTV's.
 */
enum class BufferPreset(
    val minBufferMillis: Int,
    val maxBufferMillis: Int,
    val playbackBufferMillis: Int,
    val rebufferMillis: Int,
) {
    DEFAULT(15_000, 60_000, 2_500, 5_000),
    SMALL(5_000, 15_000, 1_000, 2_000),
    MEDIUM(10_000, 30_000, 2_000, 4_000),
    LARGE(30_000, 90_000, 5_000, 10_000),
    EXTRA_LARGE(60_000, 120_000, 8_000, 15_000);

    companion object {
        fun fromStoredValue(value: String?): BufferPreset =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
