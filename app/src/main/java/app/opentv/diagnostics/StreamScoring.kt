/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.diagnostics

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A stream's track record over a set of sessions, boiled down to the numbers that predict buffering. */
data class HealthSummary(
    val sessions: Int,
    val watchedMillis: Long,
    val stallCount: Int,
    val stallMillis: Long,
    val reconnects: Int,
    val fatalErrors: Int,
    /** Sessions that never produced a picture. */
    val failedStarts: Int,
    /** Mean request-to-first-frame over the sessions that did start; -1 when none did. */
    val avgStartupMillis: Long,
    val backwardJumps: Int,
) {
    private val hours: Double get() = max(watchedMillis, MIN_WATCH_FOR_RATE_MILLIS) / 3_600_000.0

    val stallsPerHour: Double get() = stallCount / hours
    val reconnectsPerHour: Double get() = reconnects / hours

    /** Seconds of spinner for every hour on screen. */
    val stallSecondsPerHour: Double get() = stallMillis / 1000.0 / hours

    /** Fraction of on-screen time spent staring at a spinner. */
    val stallRatio: Double
        get() = if (watchedMillis <= 0) 0.0 else stallMillis.toDouble() / watchedMillis

    /** Whether there is enough viewing behind these numbers to trust them over a guess. */
    val confident: Boolean get() = watchedMillis >= CONFIDENT_WATCH_MILLIS || failedStarts >= 2

    /**
     * 0–100, higher is better. Each failure mode has a capped penalty so that one kind of
     * trouble cannot hide another: a stream that never stalls but is dropped by the provider every
     * ten minutes still scores badly.
     */
    fun score(): Int {
        if (sessions == 0) return NEUTRAL_SCORE
        var score = 100.0
        score -= min(35.0, stallsPerHour * 3.5)
        score -= min(25.0, stallRatio * 500.0)
        score -= min(20.0, reconnectsPerHour * 5.0)
        score -= 45.0 * failedStarts / sessions
        if (avgStartupMillis > SLOW_START_MILLIS) {
            score -= min(10.0, (avgStartupMillis - SLOW_START_MILLIS) / 600.0)
        }
        return score.roundToInt().coerceIn(0, 100)
    }

    companion object {
        /** What an unmeasured stream is assumed to be worth: decent, but below anything proven good. */
        const val NEUTRAL_SCORE = 65
        private const val MIN_WATCH_FOR_RATE_MILLIS = 5 * 60_000L
        private const val CONFIDENT_WATCH_MILLIS = 2 * 60_000L
        private const val SLOW_START_MILLIS = 3_000L

        val EMPTY = HealthSummary(0, 0, 0, 0, 0, 0, 0, -1, 0)

        fun of(sessions: List<StreamSession>): HealthSummary {
            if (sessions.isEmpty()) return EMPTY
            val started = sessions.filter { it.startupMillis >= 0 }
            return HealthSummary(
                sessions = sessions.size,
                watchedMillis = sessions.sumOf { it.watchedMillis },
                stallCount = sessions.sumOf { it.stallCount },
                stallMillis = sessions.sumOf { it.stallMillis },
                reconnects = sessions.sumOf { it.reconnects },
                fatalErrors = sessions.sumOf { it.fatalErrors },
                failedStarts = sessions.size - started.size,
                avgStartupMillis = if (started.isEmpty()) -1 else started.sumOf { it.startupMillis } / started.size,
                backwardJumps = sessions.sumOf { it.backwardJumps },
            )
        }
    }
}

enum class HealthGrade { GOOD, FAIR, POOR }

fun gradeOf(score: Int): HealthGrade = when {
    score >= 80 -> HealthGrade.GOOD
    score >= 55 -> HealthGrade.FAIR
    else -> HealthGrade.POOR
}

/**
 * Picks the copy of a channel most likely to play cleanly here.
 *
 * Providers list the same channel several times (SD/HD/FHD/4K) and a user with two providers has
 * it several times over. Which one plays best is not a property of the name: it depends on the
 * server behind it and the route from this house to that server. So the ranking leads with what was
 * measured, and only falls back to a stream's provider-wide record, and then to a neutral guess,
 * when this exact stream has not been watched enough to know.
 */
object StreamMatcher {

    data class Candidate(
        val channelId: Long,
        val sourceId: Long,
        val qualityRank: Int,
        /** This exact stream's record. */
        val own: HealthSummary = HealthSummary.EMPTY,
        /** Everything watched from the same provider — a proxy for the server and the route to it. */
        val provider: HealthSummary = HealthSummary.EMPTY,
        /** What a stream test made of this copy, when it has had one. */
        val strength: StrengthGrade? = null,
    )

    data class Ranked(val candidate: Candidate, val score: Int, val measured: Boolean)

    /**
     * Ranks [alternatives] for a viewer whose [current] stream is struggling, best first.
     *
     * Two nudges sit on top of the measured score. A copy on a *different* provider gets a lift,
     * because whatever is choking the current server probably is not choking an unrelated one. A
     * *lighter* quality gets a smaller lift and a heavier one a penalty: a stream that cannot keep
     * up at FHD will not be rescued by trying 4K.
     */
    fun rank(current: Candidate, alternatives: List<Candidate>): List<Ranked> =
        alternatives
            // A copy that will not play is not a rescue, whatever else is known about it.
            .filter { it.channelId != current.channelId && it.strength != StrengthGrade.DEAD }
            .map { c ->
                val measured = c.own.confident || c.strength != null
                var score = when {
                    c.own.confident -> c.own.score().toDouble()
                    // Lean an unmeasured stream toward its provider's record, but not all the way:
                    // one bad channel does not condemn a whole provider, nor one good one bless it.
                    c.provider.confident -> (c.provider.score() + HealthSummary.NEUTRAL_SCORE) / 2.0
                    else -> HealthSummary.NEUTRAL_SCORE.toDouble()
                }
                if (c.sourceId != current.sourceId) score += OTHER_PROVIDER_BONUS
                score += when {
                    c.qualityRank < current.qualityRank -> LIGHTER_BONUS
                    c.qualityRank > current.qualityRank -> -HEAVIER_PENALTY
                    else -> 0
                }
                // The test says how fast the copy arrives; nothing predicts buffering better.
                score += when (c.strength) {
                    StrengthGrade.STRONG -> TESTED_STRONG_BONUS
                    StrengthGrade.BORDERLINE -> -TESTED_BORDERLINE_PENALTY
                    StrengthGrade.TOO_SLOW -> -TESTED_SLOW_PENALTY
                    else -> 0
                }
                Ranked(c, score.roundToInt().coerceIn(0, 100), measured)
            }
            .sortedWith(compareByDescending<Ranked> { it.score }.thenByDescending { it.candidate.qualityRank })

    /**
     * The alternative worth offering, or null when nothing is likely to beat what is already on.
     * A candidate known to be poor is never offered — swapping one buffering stream for another
     * teaches the user to ignore the suggestion.
     */
    fun best(current: Candidate, alternatives: List<Candidate>): Ranked? =
        rank(current, alternatives).firstOrNull { it.score >= MIN_OFFER_SCORE }

    private const val OTHER_PROVIDER_BONUS = 10
    private const val LIGHTER_BONUS = 6
    private const val HEAVIER_PENALTY = 12
    private const val MIN_OFFER_SCORE = 55
    private const val TESTED_STRONG_BONUS = 12
    private const val TESTED_BORDERLINE_PENALTY = 8
    private const val TESTED_SLOW_PENALTY = 30
}
