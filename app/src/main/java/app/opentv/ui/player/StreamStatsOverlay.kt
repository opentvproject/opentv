/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.diagnostics.DiagnosticsVerdict
import app.opentv.diagnostics.HealthGrade
import app.opentv.diagnostics.LiveStats
import app.opentv.diagnostics.StreamHealthRecorder
import app.opentv.diagnostics.StreamSuggestion
import java.util.Locale

internal fun gradeColor(grade: HealthGrade): Color = when (grade) {
    HealthGrade.GOOD -> Color(0xFF43A047)
    HealthGrade.FAIR -> Color(0xFFFFB300)
    HealthGrade.POOR -> Color(0xFFE53935)
}

@Composable
internal fun gradeLabel(grade: HealthGrade): String = stringResource(
    when (grade) {
        HealthGrade.GOOD -> R.string.diag_grade_good
        HealthGrade.FAIR -> R.string.diag_grade_fair
        HealthGrade.POOR -> R.string.diag_grade_poor
    },
)

@Composable
internal fun transportLabel(transport: String, vpn: Boolean): String {
    val base = when (transport) {
        "wifi" -> stringResource(R.string.diag_transport_wifi)
        "ethernet" -> stringResource(R.string.diag_transport_ethernet)
        "cellular" -> stringResource(R.string.diag_transport_cellular)
        else -> stringResource(R.string.diag_transport_other)
    }
    return if (vpn) base + " · " + stringResource(R.string.diag_vpn_on) else base
}

private fun seconds(millis: Long): String = String.format(Locale.US, "%.1f s", millis / 1000.0)

/**
 * The live stream-health readout: what the stream needs, what is arriving, and what has gone wrong
 * since it started. Display only — it takes no focus and no key presses, so it can stay up while
 * the viewer carries on using the remote as normal.
 */
@Composable
fun StreamStatsOverlay(stats: LiveStats, modifier: Modifier = Modifier) {
    Column(
        modifier
            .widthIn(max = 380.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (stats.tuning) Color(0xFF9E9E9E) else gradeColor(stats.grade)))
            Spacer(Modifier.width(8.dp))
            Text(
                if (stats.tuning) stringResource(R.string.diag_grade_tuning) else gradeLabel(stats.grade),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))

        val format = when (stats.container) {
            StreamHealthRecorder.CONTAINER_HLS -> "HLS"
            StreamHealthRecorder.CONTAINER_TS -> "MPEG-TS"
            else -> ""
        }
        val picture = if (stats.width > 0 && stats.height > 0) "${stats.width}×${stats.height}" else ""
        StatRow(
            stringResource(R.string.diag_stat_server),
            listOf(stats.host, format, picture).filter { it.isNotBlank() }.joinToString(" · "),
        )
        StatRow(stringResource(R.string.diag_stat_needs), DiagnosticsVerdict.mbps(stats.bitrateBps))
        StatRow(
            stringResource(R.string.diag_stat_arriving),
            DiagnosticsVerdict.mbps(stats.receivedBps) +
                if (stats.linkBps > 0) "  (" + stringResource(R.string.diag_stat_link, DiagnosticsVerdict.mbps(stats.linkBps)) + ")" else "",
            // Arriving slower than the stream runs is the stall, seconds before it shows.
            warn = stats.bitrateBps > 0 && stats.receivedBps in 1 until stats.bitrateBps * 9 / 10 && stats.bufferMillis < 5_000,
        )
        StatRow(
            stringResource(R.string.diag_stat_delivery),
            if (stats.deliveryPercent < 0) "–" else "${stats.deliveryPercent} %",
            warn = stats.deliveryPercent in 0..94,
        )
        StatRow(stringResource(R.string.diag_stat_buffer), seconds(stats.bufferMillis), warn = stats.bufferMillis < 2_000)
        StatRow(
            stringResource(R.string.diag_stat_startup),
            if (stats.startupMillis < 0) "–" else seconds(stats.startupMillis),
        )
        StatRow(
            stringResource(R.string.diag_stat_stalls),
            if (stats.stallCount == 0 && stats.stallMillis == 0L) "0" else "${stats.stallCount}  (${seconds(stats.stallMillis)})",
            warn = stats.stallCount > 0,
        )
        StatRow(
            stringResource(R.string.diag_stat_reconnects),
            if (stats.backwardJumps > 0) {
                "${stats.reconnects}  (" + stringResource(R.string.diag_stat_replays, stats.backwardJumps) + ")"
            } else {
                stats.reconnects.toString()
            },
            warn = stats.reconnects > 0,
        )
        StatRow(stringResource(R.string.diag_stat_dropped), stats.droppedFrames.toString())
        StatRow(stringResource(R.string.diag_stat_gaps), stats.streamGaps.toString(), warn = stats.streamGaps > 0)
        StatRow(stringResource(R.string.diag_stat_network), transportLabel(stats.transport, stats.vpn))
        stats.lastError?.let { StatRow(stringResource(R.string.diag_stat_last_error), it, warn = true) }
    }
}

@Composable
private fun StatRow(label: String, value: String, warn: Boolean = false) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.62f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(112.dp),
        )
        Text(
            value,
            color = if (warn) Color(0xFFFFB300) else Color.White,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Offered when the stream on screen keeps stalling and another copy of the same channel is likely
 * to do better. Deliberately not focusable: it must never pull the d-pad away from whatever the
 * viewer was doing. OK accepts it (handled by the player's key handler), Back dismisses it, and it
 * leaves by itself after a few seconds.
 */
@Composable
fun StreamSuggestionCard(suggestion: StreamSuggestion, onAccept: () -> Unit, modifier: Modifier = Modifier) {
    val copy = listOf(suggestion.channel.name, suggestion.sourceName).filter { it.isNotBlank() }.joinToString(" · ")
    NoticeCard(
        title = stringResource(R.string.diag_suggest_title),
        body = if (copy.isBlank()) {
            stringResource(R.string.diag_suggest_body_plain)
        } else if (suggestion.measured) {
            stringResource(R.string.diag_suggest_body_measured, copy, suggestion.score)
        } else {
            stringResource(R.string.diag_suggest_body, copy)
        },
        hint = stringResource(R.string.diag_suggest_hint),
        modifier = modifier.pointerInput(Unit) { detectTapGestures { onAccept() } },
    )
}

/** Confirms a switch the app made by itself, so a changed picture is never a mystery. */
@Composable
fun StreamSwitchedCard(copy: String, modifier: Modifier = Modifier, canUndo: Boolean = false, onUndo: () -> Unit = {}) {
    NoticeCard(
        title = stringResource(R.string.diag_switched_title),
        body = if (copy.isBlank()) stringResource(R.string.diag_switched_body_plain) else stringResource(R.string.diag_switched_body, copy),
        hint = if (canUndo) stringResource(R.string.diag_switched_undo) else null,
        modifier = if (canUndo) modifier.pointerInput(Unit) { detectTapGestures { onUndo() } } else modifier,
    )
}

@Composable
private fun NoticeCard(title: String, body: String, hint: String?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .widthIn(max = 520.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.86f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(body, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
            if (hint != null) {
                Spacer(Modifier.height(6.dp))
                Text(hint, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
