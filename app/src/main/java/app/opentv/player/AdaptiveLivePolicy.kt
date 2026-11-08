/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

/**
 * Conservative per-channel live tuning for bursty HLS feeds.
 *
 * A channel that still rebuffers moves one step farther from the live edge, and the caller
 * persists that learned value for its next tune. The cap prevents a broken feed from accumulating
 * unbounded delay.
 */
internal object AdaptiveLivePolicy {
    const val DEFAULT_TARGET_MILLIS = 30_000L
    const val REBUFFER_STEP_MILLIS = 5_000L
    const val MAX_TARGET_MILLIS = 60_000L

    fun appliesTo(isLive: Boolean, url: String): Boolean =
        isLive && url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    fun targetFor(savedTargetMillis: Long?): Long =
        savedTargetMillis
            ?.coerceIn(DEFAULT_TARGET_MILLIS, MAX_TARGET_MILLIS)
            ?: DEFAULT_TARGET_MILLIS

    fun targetAfterRebuffer(currentTargetMillis: Long): Long =
        (targetFor(currentTargetMillis) + REBUFFER_STEP_MILLIS)
            .coerceAtMost(MAX_TARGET_MILLIS)
}
