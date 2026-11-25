/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.diagnostics.DiagnosticsVerdict.mbps
import app.opentv.diagnostics.HealthGrade
import app.opentv.diagnostics.SpeedGuide
import app.opentv.ui.player.gradeColor

/**
 * "What can this speed play": one bar per picture quality. The bar fills as far as the speed
 * reaches, the tick is the bitrate that quality needs, and the end of the bar is that bitrate with
 * enough room to spare that it should never buffer. Fed the live figure while a test runs, so the
 * bars climb as the test does.
 */
@Composable
internal fun SpeedChart(speedBps: Long) {
    Column {
        val best = SpeedGuide.best(speedBps)
        Text(
            if (best != null) stringResource(R.string.speed_chart_headline, tierName(best))
            else stringResource(R.string.speed_chart_headline_none),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        SpeedGuide.Tier.entries.forEach { tier ->
            TierRow(tier, speedBps)
            Spacer(Modifier.height(6.dp))
        }
        Muted(stringResource(R.string.speed_chart_legend))
    }
}

@Composable
private fun TierRow(tier: SpeedGuide.Tier, speedBps: Long) {
    val verdict = SpeedGuide.verdict(speedBps, tier)
    val color = verdictColor(verdict)
    val fill by animateFloatAsState(SpeedGuide.fill(speedBps, tier), label = "speedFill")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(118.dp)) {
            Text(tierName(tier), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Muted(stringResource(R.string.speed_chart_needs, mbps(tier.needBps)))
        }
        Box(
            Modifier
                .weight(1f)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fill)
                    .clip(RoundedCornerShape(7.dp))
                    .background(color),
            )
            // The bare bitrate: left of this the picture cannot keep up at all.
            Row(Modifier.fillMaxHeight().fillMaxWidth()) {
                Spacer(Modifier.fillMaxWidth(SpeedGuide.NEED_MARK))
                Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)))
            }
        }
        Text(
            stringResource(
                when (verdict) {
                    SpeedGuide.Verdict.PERFECT -> R.string.speed_chart_perfect
                    SpeedGuide.Verdict.TIGHT -> R.string.speed_chart_tight
                    SpeedGuide.Verdict.TOO_SLOW -> R.string.speed_chart_too_slow
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(start = 12.dp).width(120.dp),
        )
    }
}

@Composable
private fun tierName(tier: SpeedGuide.Tier): String = stringResource(
    when (tier) {
        SpeedGuide.Tier.SD -> R.string.speed_chart_sd
        SpeedGuide.Tier.HD -> R.string.speed_chart_hd
        SpeedGuide.Tier.FULL_HD -> R.string.speed_chart_fhd
        SpeedGuide.Tier.UHD -> R.string.speed_chart_uhd
    },
)

private fun verdictColor(verdict: SpeedGuide.Verdict): Color = gradeColor(
    when (verdict) {
        SpeedGuide.Verdict.PERFECT -> HealthGrade.GOOD
        SpeedGuide.Verdict.TIGHT -> HealthGrade.FAIR
        SpeedGuide.Verdict.TOO_SLOW -> HealthGrade.POOR
    },
)
