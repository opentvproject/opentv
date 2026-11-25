/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.diagnostics.CopyPicker
import app.opentv.diagnostics.DayPart
import app.opentv.diagnostics.FixReport
import app.opentv.diagnostics.PartStrength
import app.opentv.diagnostics.ProviderReport
import app.opentv.diagnostics.ReportCard
import app.opentv.diagnostics.Severity
import app.opentv.diagnostics.StreamSession
import app.opentv.diagnostics.StreamStrength
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FixReportTest {

    private val now = 100 * ReportCard.WEEK_MILLIS
    private val fast = 200_000_000L

    private fun session(sourceId: Long, daysAgo: Int, watchedMinutes: Int = 60, stalls: Int = 0, stallMillis: Long = 0) = StreamSession(
        channelId = 1, sourceId = sourceId, groupKey = "sports", channelName = "Sports",
        host = "example.test", container = "ts", startedAtMillis = now - daysAgo * 86_400_000L,
        watchedMillis = watchedMinutes * 60_000L, startupMillis = 1_500, stallCount = stalls, stallMillis = stallMillis,
        reconnects = 0, loadErrors = 0, fatalErrors = 0,
    )

    @Test
    fun `the day is cut into four parts`() {
        assertThat(DayPart.of(5)).isEqualTo(DayPart.NIGHT)
        assertThat(DayPart.of(6)).isEqualTo(DayPart.MORNING)
        assertThat(DayPart.of(12)).isEqualTo(DayPart.AFTERNOON)
        assertThat(DayPart.of(20)).isEqualTo(DayPart.EVENING)
        assertThat(DayPart.of(23)).isEqualTo(DayPart.NIGHT)
    }

    @Test
    fun `a copy is judged on how it does at this time of day`() {
        val overall = StreamStrength(channelId = 1, sourceId = 1, groupKey = "sports", ratio = 2.0, testedAtMillis = 0)
        val evening = PartStrength(channelId = 1, part = DayPart.EVENING.ordinal, ratio = 0.8, updatedAtMillis = 0)
        assertThat(CopyPicker.atPart(overall, null)).isEqualTo(overall)
        assertThat(CopyPicker.atPart(overall, evening).ratio).isEqualTo(0.8)
        // A copy that has stopped opening is dead whatever it managed last Tuesday evening.
        val dead = overall.copy(ratio = 0.0, failures = 2)
        assertThat(CopyPicker.atPart(dead, evening.copy(ratio = 2.0))).isEqualTo(dead)
    }

    @Test
    fun `no internet is said first and plainly`() {
        val report = FixReport(atMillis = 0, online = false, internetBps = 0)
        assertThat(report.outcome).isEqualTo(FixReport.Outcome.OFFLINE)
        assertThat(report.severity).isEqualTo(Severity.BAD)
        assertThat(report.sentence()).contains("cannot reach the internet")
    }

    @Test
    fun `a provider that does not answer is blamed, not the connection`() {
        val report = FixReport(atMillis = 0, online = true, internetBps = fast, providersDown = listOf("Acme"), providers = 1)
        assertThat(report.outcome).isEqualTo(FixReport.Outcome.PROVIDERS_DOWN)
        assertThat(report.sentence()).contains("Acme did not answer")
        assertThat(report.sentence()).contains("Your internet is fine")
    }

    @Test
    fun `a slow connection is the answer whatever the copies did`() {
        val report = FixReport(atMillis = 0, online = true, internetBps = 3_000_000, providers = 1, channels = 2, improved = listOf("Sports"))
        assertThat(report.outcome).isEqualTo(FixReport.Outcome.INTERNET_SLOW)
        assertThat(report.sentence()).contains("Your internet connection is the limit")
    }

    @Test
    fun `channels moved to a better copy are named`() {
        val report = FixReport(
            atMillis = 0, online = true, internetBps = fast, providers = 1, channels = 3, copies = 9,
            improved = listOf("Sports", "News"),
        )
        assertThat(report.outcome).isEqualTo(FixReport.Outcome.IMPROVED)
        assertThat(report.severity).isEqualTo(Severity.GOOD)
        assertThat(report.sentence()).startsWith("Fixed: Sports and News will now open on a steadier copy.")
    }

    @Test
    fun `a channel with no copy that keeps up is the provider's fault`() {
        val report = FixReport(atMillis = 0, online = true, internetBps = fast, providers = 1, channels = 1, hopeless = listOf("Sports"))
        assertThat(report.outcome).isEqualTo(FixReport.Outcome.NO_GOOD_COPY)
        assertThat(report.severity).isEqualTo(Severity.WARN)
        assertThat(report.sentence()).contains("Sports has no copy that keeps up")
    }

    @Test
    fun `nothing to fix says so`() {
        val report = FixReport(atMillis = 0, online = true, internetBps = fast, providers = 1, channels = 4, copies = 12)
        assertThat(report.outcome).isEqualTo(FixReport.Outcome.ALL_GOOD)
        assertThat(report.sentence()).startsWith("Nothing needed fixing")
        assertThat(FixReport(0, true, fast, providers = 1).outcome).isEqualTo(FixReport.Outcome.NOTHING_WATCHED)
    }

    @Test
    fun `a clean week is an A and an unwatched provider gets no mark`() {
        val reports = ReportCard.of(listOf(session(sourceId = 1, daysAgo = 1)), mapOf(1L to "Acme", 2L to "Other"), now)
        assertThat(reports.map { it.name }).containsExactly("Acme", "Other").inOrder()
        assertThat(reports[0].letter).isEqualTo('A')
        assertThat(reports[0].trend).isNull()
        assertThat(reports[1].letter).isNull()
        assertThat(ReportCard.headline(reports)).isEqualTo("Acme played well this week.")
    }

    @Test
    fun `this week is set against the one before`() {
        val sessions = listOf(
            session(sourceId = 1, daysAgo = 2, stalls = 30, stallMillis = 300_000),
            session(sourceId = 1, daysAgo = 9),
        )
        val report = ReportCard.of(sessions, mapOf(1L to "Acme"), now).single()
        assertThat(report.previous.sessions).isEqualTo(1)
        assertThat(report.trend).isEqualTo(ProviderReport.Trend.WORSE)
        assertThat(ReportCard.headline(listOf(report))).isEqualTo("Acme played worse this week than last.")
    }

    @Test
    fun `nothing watched means no headline`() {
        assertThat(ReportCard.headline(ReportCard.of(emptyList(), mapOf(1L to "Acme"), now))).isNull()
    }
}
