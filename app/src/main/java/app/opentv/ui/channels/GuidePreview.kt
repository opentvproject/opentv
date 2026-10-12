/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import app.opentv.R
import app.opentv.data.model.shownName
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.opentv.ui.ChannelsViewModel
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The detail pane at the top of the guide.
 *
 * As focus moves down the channel list, [row] follows it and the pane shows that channel's
 * logo alongside what's on now, how far through it is, what's next, and a synopsis when the
 * guide carries one — the "info on the EPG" people ask for before they'll judge a guide.
 * Pressing it plays the channel full-screen.
 *
 * ## Inline video
 * When [previewPlayer] is non-null the highlighted channel plays, muted, inside the card. The
 * earlier version of this that locked up cheap boxes ran a *second* decoder behind the
 * full-screen one; here there is only ever this single, muted, debounced player — and the
 * caller ([HomeScreen]) stops it before handing off to full-screen and whenever the screen is
 * backgrounded, so the device never has two decoders alive at once. Low-end boxes can turn the
 * whole thing off in settings, in which case [previewPlayer] is null and this falls back to the
 * logo. The logo stays behind the video as the shutter, so a buffering or failed stream still
 * shows something rather than a black hole.
 */
@OptIn(UnstableApi::class)
@Composable
fun GuidePreview(
    row: ChannelsViewModel.Row?,
    nowMillis: Long,
    onWatch: () -> Unit,
    onRefresh: () -> Unit,
    onAddSource: () -> Unit,
    previewPlayer: ExoPlayer?,
    isRecording: Boolean = false,
    onRecord: () -> Unit = {},
    dayLabel: String = "",
    canGoPrevDay: Boolean = false,
    onPrevDay: () -> Unit = {},
    onNextDay: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(212.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // ---- Logo / "watch" card ----------------------------------------------------------
        Box(
            Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .clickable(onClick = onWatch),
            contentAlignment = Alignment.Center,
        ) {
            // Logo sits behind everything as the fallback / shutter.
            if (row != null) {
                AsyncImage(
                    model = row.primary.logoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(0.6f),
                )
            }

            // Live video on top of the logo when preview playback is on. The transparent
            // shutter means the logo behind shows through until the first frame arrives.
            if (previewPlayer != null && row != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            player = previewPlayer
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    update = { it.player = previewPlayer },
                )
            }

            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.guide_watch_label), color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(Modifier.width(18.dp))

        // ---- Now / next detail ------------------------------------------------------------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // ---- Day navigation (Sky Q-style): ‹ Today › ----------------------------
                // Only shown once the caller wires a label in; keeps older callers unchanged.
                if (dayLabel.isNotEmpty()) {
                    IconButton(onClick = onPrevDay, enabled = canGoPrevDay) {
                        Icon(
                            Icons.Default.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.guide_prev_day),
                            tint = if (canGoPrevDay) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        )
                    }
                    Text(
                        dayLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    IconButton(onClick = onNextDay) {
                        Icon(
                            Icons.Default.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.guide_next_day),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (row != null) {
                    IconButton(onClick = onRecord) {
                        // Red throughout — the record convention — but the glyph switches to a
                        // stop square while a recording is running, so a press visibly does
                        // something (● start → ■ stop) rather than looking inert.
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            contentDescription = if (isRecording) stringResource(R.string.guide_cd_stop_recording) else stringResource(R.string.guide_cd_record_now),
                            tint = Color(0xFFE53935),
                        )
                    }
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.guide_cd_refresh))
                }
                IconButton(onClick = onAddSource) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.guide_cd_add_source))
                }
            }

            if (row == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    Text(
                        stringResource(R.string.guide_highlight_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.shownName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (row.variants.size > 1) {
                    Text(
                        stringResource(R.string.guide_qualities_count, row.variants.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            val nowProg = row.now
            if (nowProg != null) {
                Text(
                    "${formatTime(nowProg.startUtcMillis)}–${formatTime(nowProg.endUtcMillis)}   ${nowProg.title}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { nowProg.progressAt(nowMillis) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
                nowProg.description?.takeIf { it.isNotBlank() }?.let { synopsis ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        synopsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    stringResource(R.string.guide_no_info_channel),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            row.next?.let { next ->
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.guide_next_prefix, formatTime(next.startUtcMillis), next.title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val previewTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(utcMillis: Long): String = previewTimeFormat.format(Date(utcMillis))
