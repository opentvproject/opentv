/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.diagnostics.DiagnosticsVerdict
import app.opentv.diagnostics.HealthGrade
import app.opentv.diagnostics.HealthSummary
import app.opentv.diagnostics.HlsPlaylist
import app.opentv.diagnostics.ProbeResult
import app.opentv.diagnostics.Severity
import app.opentv.diagnostics.StreamMatcher
import app.opentv.diagnostics.StreamSession
import app.opentv.diagnostics.gradeOf
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamScoringTest {

    private fun session(
        channelId: Long = 1,
        sourceId: Long = 1,
        watchedMinutes: Int = 30,
        startupMillis: Long = 1_500,
        stalls: Int = 0,
        stallMillis: Long = 0,
        reconnects: Int = 0,
        gaps: Int = 0,
        vpn: Boolean = false,
    ) = StreamSession(
        channelId = channelId, sourceId = sourceId, groupKey = "discovery", channelName = "Discovery",
        host = "example.test", container = "ts", startedAtMillis = 0, watchedMillis = watchedMinutes * 60_000L,
        startupMillis = startupMillis, stallCount = stalls, stallMillis = stallMillis, reconnects = reconnects,
        loadErrors = 0, fatalErrors = 0, streamGaps = gaps, vpn = vpn,
    )

    @Test
    fun `a clean hour scores as healthy`() {
        val summary = HealthSummary.of(listOf(session(watchedMinutes = 60)))
        assertThat(summary.score()).isEqualTo(100)
        assertThat(gradeOf(summary.score())).isEqualTo(HealthGrade.GOOD)
        assertThat(summary.confident).isTrue()
    }

    @Test
    fun `nothing measured is neutral and not trusted`() {
        assertThat(HealthSummary.EMPTY.score()).isEqualTo(HealthSummary.NEUTRAL_SCORE)
        assertThat(HealthSummary.EMPTY.confident).isFalse()
    }

    @Test
    fun `constant buffering scores as poor`() {
        val summary = HealthSummary.of(listOf(session(stalls = 12, stallMillis = 120_000, reconnects = 4)))
        assertThat(gradeOf(summary.score())).isEqualTo(HealthGrade.POOR)
    }

    @Test
    fun `provider drops alone pull a stall-free stream down`() {
        val clean = HealthSummary.of(listOf(session()))
        val dropped = HealthSummary.of(listOf(session(reconnects = 3)))
        assertThat(dropped.score()).isLessThan(clean.score())
    }

    @Test
    fun `streams that never start are penalised and count as evidence`() {
        val summary = HealthSummary.of(listOf(session(watchedMinutes = 0, startupMillis = -1), session(watchedMinutes = 0, startupMillis = -1)))
        assertThat(summary.failedStarts).isEqualTo(2)
        assertThat(summary.avgStartupMillis).isEqualTo(-1)
        assertThat(summary.confident).isTrue()
        assertThat(summary.score()).isAtMost(55)
    }

    @Test
    fun `matcher prefers the copy with the better record`() {
        val current = StreamMatcher.Candidate(1, 1, qualityRank = 3, own = HealthSummary.of(listOf(session(stalls = 12, stallMillis = 120_000))), provider = HealthSummary.EMPTY)
        val proven = StreamMatcher.Candidate(2, 2, qualityRank = 2, own = HealthSummary.of(listOf(session(channelId = 2, sourceId = 2, watchedMinutes = 60))), provider = HealthSummary.EMPTY)
        val best = StreamMatcher.best(current, listOf(proven))
        assertThat(best).isNotNull()
        assertThat(best!!.candidate.channelId).isEqualTo(2)
        assertThat(best.measured).isTrue()
    }

    @Test
    fun `matcher does not send you to a copy known to be bad`() {
        val current = StreamMatcher.Candidate(1, 1, qualityRank = 3, own = HealthSummary.of(listOf(session(stalls = 6, stallMillis = 60_000))), provider = HealthSummary.EMPTY)
        val worse = StreamMatcher.Candidate(2, 2, qualityRank = 3, own = HealthSummary.of(listOf(session(channelId = 2, sourceId = 2, stalls = 30, stallMillis = 600_000, reconnects = 10))), provider = HealthSummary.EMPTY)
        assertThat(StreamMatcher.best(current, listOf(worse))).isNull()
    }

    @Test
    fun `hls master playlist yields variants`() {
        val playlist = HlsPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=2500000,RESOLUTION=1280x720
            mid/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000
            https://cdn.example.test/high/index.m3u8
            """.trimIndent(),
        )
        assertThat(playlist.segments).isEmpty()
        assertThat(playlist.variants.map { it.bandwidth }).containsExactly(2_500_000L, 6_000_000L).inOrder()
        assertThat(playlist.variants[1].uri).isEqualTo("https://cdn.example.test/high/index.m3u8")
    }

    @Test
    fun `hls media playlist yields segments with durations`() {
        val playlist = HlsPlaylist.parse("#EXTM3U\r\n#EXT-X-TARGETDURATION:6\r\n#EXTINF:6.006,\r\nseg1.ts\r\n#EXTINF:4,title\r\nseg2.ts\r\n")
        assertThat(playlist.variants).isEmpty()
        assertThat(playlist.segments.map { it.uri }).containsExactly("seg1.ts", "seg2.ts").inOrder()
        assertThat(playlist.segments[0].durationSeconds).isWithin(0.001).of(6.006)
        assertThat(playlist.segments[1].durationSeconds).isWithin(0.001).of(4.0)
    }

    @Test
    fun `verdict says nothing is measured when nothing is`() {
        val findings = DiagnosticsVerdict.findings(emptyList(), emptyList(), emptyMap())
        assertThat(findings).hasSize(1)
        assertThat(findings[0].severity).isEqualTo(Severity.INFO)
    }

    @Test
    fun `verdict blames the provider when the internet is fast and the provider buffers`() {
        val internet = ProbeResult(
            kind = ProbeResult.KIND_INTERNET, label = "Internet", host = "speed.test", atMillis = 1, ok = true,
            throughputBps = 80_000_000, latencyMillis = 15, jitterMillis = 2,
        )
        val sessions = listOf(session(watchedMinutes = 60, stalls = 20, stallMillis = 300_000, reconnects = 6))
        val findings = DiagnosticsVerdict.findings(sessions, listOf(internet), mapOf(1L to "FlakyTV"))
        assertThat(findings.first().severity).isAtLeast(Severity.WARN)
        assertThat(findings.joinToString(" ") { it.title + " " + it.detail }).contains("FlakyTV")
    }

    @Test
    fun `verdict reports streams arriving with pieces missing`() {
        val findings = DiagnosticsVerdict.findings(listOf(session(watchedMinutes = 30, gaps = 9)), emptyList(), mapOf(1L to "P"))
        assertThat(findings.map { it.title }).contains("Streams are arriving with pieces missing")
    }

    @Test
    fun `mbps formats small and large rates`() {
        assertThat(DiagnosticsVerdict.mbps(0)).isEqualTo("–")
        assertThat(DiagnosticsVerdict.mbps(4_500_000)).isEqualTo("4.5 Mbps")
        assertThat(DiagnosticsVerdict.mbps(86_000_000)).isEqualTo("86 Mbps")
    }
}
