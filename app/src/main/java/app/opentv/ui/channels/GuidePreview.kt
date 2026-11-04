/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
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
 * guide carries one — the "info on the EPG" people ask for before they'll judge a guide. The
 * pane is intentionally display-only so remote focus stays in the channel rows below it.
 *
 * ## Inline video
 * When [previewPlayer] is non-null the highlighted channel plays, muted, inside the card. The
 * earlier version of this that locked up cheap boxes ran a *second* decoder behind the
 * full-screen one. The caller now supplies the process-level live player shared with full-screen:
 * navigation only moves its output between PlayerViews, so there is still one decoder and the
 * same-channel transition does not reopen the stream. Leaving live TV stops the shared session.
 * Low-end boxes can turn the preview off in settings, in which case [previewPlayer] is null and
 * this falls back to the logo. The logo stays behind the video as the shutter, so a buffering or
 * failed stream still shows something rather than a black hole.
 */
@OptIn(UnstableApi::class)
@Composable
fun GuidePreview(
    row: ChannelsViewModel.Row?,
    nowMillis: Long,
    previewPlayer: ExoPlayer?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(212.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // ---- Display-only logo / live-preview card -----------------------------------------
        Box(
            Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .focusProperties { canFocus = false },
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
                    modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            isFocusable = false
                            isFocusableInTouchMode = false
                            isClickable = false
                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
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
        }

        Spacer(Modifier.width(18.dp))

        // ---- Now / next detail ------------------------------------------------------------
        Column(Modifier.weight(1f).fillMaxHeight()) {
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
                    row.primary.shownName,
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
