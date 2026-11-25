/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.diagnostics

import app.opentv.diagnostics.DiagnosticsVerdict.mbps

/** Where a "Fix my streams" run is up to. */
data class FixStep(val stage: Stage, val channel: String = "", val done: Int = 0, val total: Int = 0) {
    enum class Stage { INTERNET, PROVIDERS, COPIES }
}

/**
 * What one press of "Fix my streams" found and did.
 *
 * The run measures the three things that can be wrong in the order they depend on each other — the
 * connection, then each provider's server, then the copies of the channels this device actually
 * watches — and the report boils all of it down to the one sentence a viewer wanted in the first
 * place. A pure value, so that sentence can be tested without a network.
 */
data class FixReport(
    val atMillis: Long,
    /** False when the speed test could not reach the internet at all. */
    val online: Boolean,
    val internetBps: Long,
    /** Providers whose server did not answer. */
    val providersDown: List<String> = emptyList(),
    val providers: Int = 0,
    val channels: Int = 0,
    val copies: Int = 0,
    /** Channels that will now open on a different copy than they would have before the run. */
    val improved: List<String> = emptyList(),
    /** Channels with no copy that keeps up: nothing the app can pick will fix these. */
    val hopeless: List<String> = emptyList(),
) {
    enum class Outcome { OFFLINE, PROVIDERS_DOWN, INTERNET_SLOW, NOTHING_WATCHED, IMPROVED, NO_GOOD_COPY, ALL_GOOD }

    val outcome: Outcome
        get() = when {
            !online -> Outcome.OFFLINE
            providers > 0 && providersDown.size == providers -> Outcome.PROVIDERS_DOWN
            SpeedGuide.verdict(internetBps, SpeedGuide.Tier.HD) != SpeedGuide.Verdict.PERFECT -> Outcome.INTERNET_SLOW
            channels == 0 -> Outcome.NOTHING_WATCHED
            improved.isNotEmpty() -> Outcome.IMPROVED
            hopeless.isNotEmpty() -> Outcome.NO_GOOD_COPY
            else -> Outcome.ALL_GOOD
        }

    val severity: Severity
        get() = when (outcome) {
            Outcome.OFFLINE, Outcome.PROVIDERS_DOWN, Outcome.INTERNET_SLOW -> Severity.BAD
            Outcome.NO_GOOD_COPY -> Severity.WARN
            Outcome.NOTHING_WATCHED -> Severity.INFO
            Outcome.IMPROVED, Outcome.ALL_GOOD -> if (hopeless.isEmpty() && providersDown.isEmpty()) Severity.GOOD else Severity.WARN
        }

    /** The whole run in one sentence or two. */
    fun sentence(): String {
        val speed = mbps(internetBps)
        val down = providersDown.takeIf { it.isNotEmpty() }?.let {
            " ${names(it)} did not answer, which is on the provider's side — try again later."
        }.orEmpty()
        val stuck = hopeless.takeIf { it.isNotEmpty() }?.let {
            " ${names(it)} ${if (it.size == 1) "has" else "have"} no copy that keeps up right now: that is the " +
                "provider's server, not your connection."
        }.orEmpty()
        return when (outcome) {
            Outcome.OFFLINE ->
                "This device cannot reach the internet, so nothing will stream until that is fixed. Check its " +
                    "network cable or Wi-Fi."
            Outcome.PROVIDERS_DOWN ->
                "Your internet is fine ($speed), but ${names(providersDown)} did not answer. That is on the " +
                    "provider's side; nothing in this house will fix it. Try again later."
            Outcome.INTERNET_SLOW -> {
                val best = SpeedGuide.best(internetBps)
                "Your internet connection is the limit: at $speed " +
                    (if (best == null) "even standard definition will struggle" else "only standard definition plays with room to spare") +
                    ", whatever the provider does. Lower-quality versions of a channel will play more smoothly."
            }
            Outcome.NOTHING_WATCHED ->
                "Your internet ($speed) and your provider both look healthy. Watch a few channels, then run this " +
                    "again so there is something to tune.$down"
            Outcome.IMPROVED ->
                "Fixed: ${names(improved)} will now open on a steadier copy. Your internet is fine ($speed).$stuck$down"
            Outcome.NO_GOOD_COPY ->
                "Your internet is fine ($speed).$stuck$down"
            Outcome.ALL_GOOD ->
                "Nothing needed fixing: your internet is fine ($speed) and the " +
                    (if (channels == 1) "channel you watch most already opens" else "$channels channels you watch most already open") +
                    " on ${if (channels == 1) "its" else "their"} strongest copy.$down"
        }
    }

    private fun names(list: List<String>): String = when {
        list.size <= 1 -> list.firstOrNull().orEmpty()
        list.size <= MAX_NAMED -> list.dropLast(1).joinToString(", ") + " and " + list.last()
        else -> list.take(MAX_NAMED).joinToString(", ") + " and ${list.size - MAX_NAMED} more"
    }

    private companion object {
        const val MAX_NAMED = 3
    }
}

/** One provider's week, next to the week before it. */
data class ProviderReport(
    val sourceId: Long,
    val name: String,
    val week: HealthSummary,
    val previous: HealthSummary,
) {
    enum class Trend { BETTER, SAME, WORSE }

    /** A school-report letter, A to E; null until there is enough viewing to mark. */
    val letter: Char?
        get() = if (!week.confident) null else when (week.score()) {
            in 90..100 -> 'A'
            in 80..89 -> 'B'
            in 65..79 -> 'C'
            in 50..64 -> 'D'
            else -> 'E'
        }

    /** How this week compares with the last; null when either week is too thin to compare. */
    val trend: Trend?
        get() {
            if (!week.confident || !previous.confident) return null
            val change = week.score() - previous.score()
            return when {
                change >= ReportCard.NOTICEABLE -> Trend.BETTER
                change <= -ReportCard.NOTICEABLE -> Trend.WORSE
                else -> Trend.SAME
            }
        }
}

/**
 * The weekly report card: each provider marked on what was actually watched from it — buffering,
 * how fast channels start, how often the connection dropped — and set against the week before, so
 * "has it got worse?" has an answer that is not a feeling.
 */
object ReportCard {
    const val WEEK_MILLIS = 7 * 24 * 3_600_000L
    internal const val NOTICEABLE = 8

    /**
     * @param sessions everything watched in the last two weeks, any order
     * @param sourceNames the enabled providers, by source id
     */
    fun of(sessions: List<StreamSession>, sourceNames: Map<Long, String>, nowMillis: Long): List<ProviderReport> {
        val weekStart = nowMillis - WEEK_MILLIS
        val previousStart = weekStart - WEEK_MILLIS
        return sourceNames.map { (id, name) ->
            val own = sessions.filter { it.sourceId == id }
            ProviderReport(
                sourceId = id,
                name = name,
                week = HealthSummary.of(own.filter { it.startedAtMillis >= weekStart }),
                previous = HealthSummary.of(own.filter { it.startedAtMillis in previousStart until weekStart }),
            )
        }.sortedWith(compareByDescending<ProviderReport> { it.letter != null }.thenByDescending { it.week.score() })
    }

    /** The one line above the table, or null when nothing has been watched enough to mark. */
    fun headline(reports: List<ProviderReport>): String? {
        val marked = reports.filter { it.letter != null }
        val best = marked.firstOrNull() ?: return null
        if (marked.size >= 2) {
            val gap = best.week.score() - marked[1].week.score()
            return if (gap >= NOTICEABLE) "${best.name} was your steadiest provider this week."
            else "Your providers were about as steady as each other this week."
        }
        return when (best.trend) {
            ProviderReport.Trend.BETTER -> "${best.name} played better this week than last."
            ProviderReport.Trend.WORSE -> "${best.name} played worse this week than last."
            ProviderReport.Trend.SAME -> "${best.name} played about the same as last week."
            null -> when (best.letter) {
                'A', 'B' -> "${best.name} played well this week."
                'C' -> "${best.name} was patchy this week."
                else -> "${best.name} struggled this week."
            }
        }
    }
}
