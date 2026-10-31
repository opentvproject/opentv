/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.player.PlayerController

/**
 * The loading / failure layer drawn on top of a video surface.
 *
 * One component for every place a channel can be tuning — the full-screen player, the VOD player
 * and the guide's preview pane — so they all look and behave the same.
 *
 * - **Buffering** dims the frame (so a stale picture from the previous channel isn't mistaken for
 *   the new one) and shows a spinner with the channel name, or "Retrying (n of m)" while the
 *   controller is silently retrying.
 * - **Error** shows what went wrong. [onRetry] adds a Try again button; leave it null where the
 *   surface isn't focusable (the guide preview) and a one-line message is shown instead.
 *
 * Renders nothing for Idle/Playing.
 *
 * @param compact smaller type and no button, for small surfaces such as the guide preview.
 */
@Composable
fun PlaybackOverlay(
    state: PlayerController.State,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    maxRetries: Int = 2,
    onRetry: (() -> Unit)? = null,
) {
    // Only Buffering and Error have anything to show; collapse the rest to null so the cross-fade
    // runs between "loading" and "failed" and fades out cleanly once playback starts.
    val shown = when (state) {
        is PlayerController.State.Buffering, is PlayerController.State.Error -> state
        else -> null
    }

    AnimatedContent(
        targetState = shown,
        modifier = modifier,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        contentKey = { it?.let { s -> s::class } },
        label = "playbackOverlay",
    ) { s ->
        when (s) {
            is PlayerController.State.Buffering -> LoadingLayer(s, compact, maxRetries)
            is PlayerController.State.Error -> FailureLayer(s, compact, onRetry)
            else -> Spacer(Modifier)
        }
    }
}

@Composable
private fun LoadingLayer(
    state: PlayerController.State.Buffering,
    compact: Boolean,
    maxRetries: Int,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (compact) 0.55f else 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(if (compact) 8.dp else 32.dp),
        ) {
            CircularProgressIndicator(
                modifier = if (compact) Modifier.size(28.dp) else Modifier,
                strokeWidth = if (compact) 3.dp else 4.dp,
            )
            Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
            if (state.title.isNotEmpty()) {
                Text(
                    state.title,
                    color = Color.White,
                    style = if (compact) MaterialTheme.typography.labelMedium
                    else MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (state.retryAttempt > 0) {
                    stringResource(R.string.playback_retrying, state.retryAttempt.coerceAtMost(maxRetries), maxRetries)
                } else {
                    stringResource(R.string.playback_loading)
                },
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun FailureLayer(
    state: PlayerController.State.Error,
    compact: Boolean,
    onRetry: (() -> Unit)?,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        if (compact) {
            Text(
                stringResource(R.string.playback_failed_short),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(48.dp),
            ) {
                Text(
                    if (state.title.isNotEmpty()) stringResource(R.string.playback_failed_title, state.title)
                    else stringResource(R.string.playback_failed_short),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    state.message,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                )
                if (onRetry != null) {
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onRetry) { Text(stringResource(R.string.common_try_again)) }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.playback_failed_hint),
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
