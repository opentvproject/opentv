/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Channel
import app.opentv.data.work.OptimiseWorker
import app.opentv.diagnostics.CopyPicker
import app.opentv.diagnostics.DiagnosticsRepository
import app.opentv.diagnostics.SpeedGuide
import app.opentv.diagnostics.StreamStrength
import app.opentv.diagnostics.StrengthGrade
import app.opentv.diagnostics.ProbeResult
import app.opentv.diagnostics.StreamTestRow
import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import org.junit.Test

class CopyPickerTest {

    private fun copy(id: Long, rank: Int, name: String = "Sky Sports", sourceId: Long = 1) = Channel(
        id = id, sourceId = sourceId, streamId = "$id", name = name, groupKey = "skysports",
        qualityRank = rank, categoryId = null, logoUrl = null, epgChannelId = null, number = 0, streamUrl = "http://example.test/$id",
    )

    private fun tested(id: Long, ratio: Double, failures: Int = 0, measured: Boolean = true) = StreamStrength(
        channelId = id, sourceId = 1, groupKey = "skysports", ratio = ratio, measured = measured,
        failures = failures, bitrateBps = 5_000_000, throughputBps = (5_000_000 * ratio).toLong(), testedAtMillis = 1,
    )

    private val uhd = copy(1, 40)
    private val fhd = copy(2, 30)
    private val hd = copy(3, 20)
    private val sd = copy(4, 10)
    private val all = listOf(uhd, fhd, hd, sd)

    private fun pick(from: Channel, vararg rows: StreamStrength) =
        DiagnosticsRepository.preferredAmong(from, all, rows.associateBy { it.channelId })

    @Test
    fun `nothing measured leaves the channel alone`() {
        assertThat(pick(uhd)).isEqualTo(uhd)
        assertThat(pick(hd)).isEqualTo(hd)
    }

    @Test
    fun `a copy that cannot keep up gives way to the best one that can`() {
        assertThat(pick(uhd, tested(1, 0.6), tested(2, 0.9), tested(3, 2.4), tested(4, 5.0))).isEqualTo(hd)
    }

    @Test
    fun `an untested copy is not skipped on suspicion`() {
        // 4K is slow, FHD has never been tried: FHD gets the benefit of the doubt over HD.
        assertThat(pick(uhd, tested(1, 0.6), tested(3, 2.4))).isEqualTo(fhd)
    }

    @Test
    fun `a strong copy is kept`() {
        assertThat(pick(fhd, tested(2, 1.9), tested(3, 4.0))).isEqualTo(fhd)
    }

    @Test
    fun `a proven better picture is taken`() {
        assertThat(pick(hd, tested(2, 2.2), tested(3, 3.0))).isEqualTo(fhd)
    }

    @Test
    fun `an unproven better picture is not`() {
        assertThat(pick(hd, tested(2, 1.2), tested(3, 3.0))).isEqualTo(hd)
    }

    @Test
    fun `when every copy is weak the least bad one plays`() {
        assertThat(pick(uhd, tested(1, 0.3), tested(2, 0.5), tested(3, 0.8), tested(4, 0.7))).isEqualTo(hd)
    }

    @Test
    fun `a copy that did not open is graded dead and passed over`() {
        assertThat(tested(1, 0.4, failures = 1).grade).isEqualTo(StrengthGrade.DEAD)
        assertThat(pick(uhd, tested(1, 0.4, failures = 1), tested(2, 1.8))).isEqualTo(fhd)
    }

    @Test
    fun `a stream that plays but cannot be measured is good enough to stay on`() {
        val raw = tested(1, 1.0, measured = false)
        assertThat(raw.grade).isEqualTo(StrengthGrade.PLAYS)
        assertThat(pick(uhd, raw, tested(3, 3.0))).isEqualTo(uhd)
    }

    @Test
    fun `another provider and the plus channel are never substituted`() {
        val plus = copy(5, 30, name = "Sky Sports+")
        val other = copy(6, 30, sourceId = 2)
        val strength = mapOf(1L to tested(1, 0.4), 5L to tested(5, 3.0), 6L to tested(6, 3.0), 4L to tested(4, 2.0))
        assertThat(DiagnosticsRepository.preferredAmong(uhd, listOf(uhd, plus, other, sd), strength)).isEqualTo(sd)
    }

    @Test
    fun `one bad night does not erase a good record`() {
        val blended = CopyPicker.blend(tested(1, 2.5), 1.0)
        assertThat(blended).isGreaterThan(1.5)
        assertThat(CopyPicker.blend(null, 0.8)).isEqualTo(0.8)
    }

    @Test
    fun `the nightly run is due at the next three o'clock`() {
        val evening = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 25, 22, 30, 0); set(Calendar.MILLISECOND, 0) }
        assertThat(OptimiseWorker.millisUntilNight(evening.timeInMillis)).isEqualTo(4 * 3_600_000L + 30 * 60_000L)
        val early = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 25, 1, 0, 0); set(Calendar.MILLISECOND, 0) }
        assertThat(OptimiseWorker.millisUntilNight(early.timeInMillis)).isEqualTo(2 * 3_600_000L)
    }

    @Test
    fun speedGuideGradesEachQualityWithHeadroom() {
        // 30 Mbps covers 4K's 25 but not with room to spare.
        assertThat(SpeedGuide.verdict(30_000_000, SpeedGuide.Tier.UHD)).isEqualTo(SpeedGuide.Verdict.TIGHT)
        assertThat(SpeedGuide.verdict(38_000_000, SpeedGuide.Tier.UHD)).isEqualTo(SpeedGuide.Verdict.PERFECT)
        assertThat(SpeedGuide.verdict(20_000_000, SpeedGuide.Tier.UHD)).isEqualTo(SpeedGuide.Verdict.TOO_SLOW)
        assertThat(SpeedGuide.best(30_000_000)).isEqualTo(SpeedGuide.Tier.FULL_HD)
        assertThat(SpeedGuide.best(2_000_000)).isEqualTo(null)
        assertThat(SpeedGuide.fill(1_000_000_000, SpeedGuide.Tier.UHD)).isEqualTo(1f)
    }

    private fun row(id: Long, rank: Int, mbpsNeeded: Int, mbpsArrives: Int, ok: Boolean = true) = StreamTestRow(
        copy(id, rank),
        "Acme",
        ProbeResult(
            kind = ProbeResult.KIND_STREAM, channelId = id, atMillis = 0, ok = ok,
            bitrateBps = mbpsNeeded * 1_000_000L, throughputBps = mbpsArrives * 1_000_000L,
        ),
    )

    @Test
    fun `the best tested copy is the best quality with room to spare, not the SD one`() {
        val sd = row(1, rank = 100, mbpsNeeded = 1, mbpsArrives = 50)
        val hd = row(2, rank = 200, mbpsNeeded = 4, mbpsArrives = 90)
        val uhd = row(3, rank = 400, mbpsNeeded = 25, mbpsArrives = 20)
        val dead = row(4, rank = 400, mbpsNeeded = 0, mbpsArrives = 0, ok = false)
        val ranked = listOf(sd, uhd, dead, hd).sortedWith(DiagnosticsRepository.TEST_ORDER)
        assertThat(ranked.map { it.channel.id }).containsExactly(2L, 1L, 3L, 4L).inOrder()
    }

    @Test
    fun `the score's bands match the grades`() {
        assertThat(CopyPicker.score(0.0)).isEqualTo(0)
        assertThat(CopyPicker.score(0.5)).isEqualTo(24)
        assertThat(CopyPicker.score(0.999)).isLessThan(50)
        assertThat(CopyPicker.score(CopyPicker.KEEPS_UP)).isEqualTo(50)
        assertThat(CopyPicker.score(1.49)).isLessThan(80)
        assertThat(CopyPicker.score(CopyPicker.STRONG)).isEqualTo(80)
        assertThat(CopyPicker.score(3.0)).isEqualTo(100)
        assertThat(CopyPicker.score(40.0)).isEqualTo(100)
    }
}
