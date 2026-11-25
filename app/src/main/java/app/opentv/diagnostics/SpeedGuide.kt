/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.diagnostics

/**
 * Turns a measured connection speed into "what picture quality can this carry".
 *
 * The needs are the steady bitrates typical IPTV feeds run at, not the lower figures the big
 * streaming services quote for their own heavily compressed video. The verdict uses the same
 * headroom rule as stream strength ([CopyPicker.STRONG]): a line that only just matches the
 * bitrate plays until anything else in the house uses the connection.
 */
object SpeedGuide {
    enum class Tier(val needBps: Long) {
        SD(3_000_000),
        HD(5_000_000),
        FULL_HD(8_000_000),
        UHD(25_000_000),
    }

    enum class Verdict { PERFECT, TIGHT, TOO_SLOW }

    fun verdict(speedBps: Long, tier: Tier): Verdict {
        val ratio = speedBps.toDouble() / tier.needBps
        return when {
            ratio >= CopyPicker.STRONG -> Verdict.PERFECT
            ratio >= CopyPicker.KEEPS_UP -> Verdict.TIGHT
            else -> Verdict.TOO_SLOW
        }
    }

    /** How far along the bar the speed reaches: full at the "perfect" mark, [NEED_MARK] at the bare need. */
    fun fill(speedBps: Long, tier: Tier): Float =
        (speedBps.toDouble() / (tier.needBps * CopyPicker.STRONG)).coerceIn(0.0, 1.0).toFloat()

    /** Where on the bar the bare bitrate sits. */
    const val NEED_MARK = (CopyPicker.KEEPS_UP / CopyPicker.STRONG).toFloat()

    /** The best tier this speed carries with headroom, or null when even SD is not comfortable. */
    fun best(speedBps: Long): Tier? = Tier.entries.lastOrNull { verdict(speedBps, it) == Verdict.PERFECT }
}
