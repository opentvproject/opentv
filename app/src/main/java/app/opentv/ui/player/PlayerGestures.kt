/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.findActivity
import kotlinx.coroutines.delay

/**
 * The touch layer over the video, for phones and tablets: tap toggles the controls, double-tap
 * on the left or right third skips back or forward [SEEK_STEP_MILLIS], and a vertical swipe
 * changes brightness on the left half and volume on the right. A remote never sends pointer
 * events, so on a TV this layer is inert and the d-pad handling is untouched.
 *
 * [onSeekBy] returns false when the stream can't seek (most live channels); the double-tap then
 * does nothing rather than flashing a skip that didn't happen. Brightness is a window override
 * for this screen only and is handed back to the system when the player closes.
 */
@Composable
fun PlayerGestureLayer(
    onToggleControls: () -> Unit,
    onSeekBy: (Long) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val window = remember { context.findActivity()?.window }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val toggle by rememberUpdatedState(onToggleControls)
    val seek by rememberUpdatedState(onSeekBy)

    var indicator by remember { mutableStateOf<GestureIndicator?>(null) }
    var dragging by remember { mutableStateOf(false) }

    // Fade the indicator a beat after the last change, but never mid-swipe.
    LaunchedEffect(indicator, dragging) {
        if (indicator != null && !dragging) {
            delay(INDICATOR_LINGER_MILLIS)
            indicator = null
        }
    }

    DisposableEffect(window) {
        onDispose {
            window?.let { w ->
                w.attributes = w.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    fun currentBrightness(): Float {
        val override = window?.attributes?.screenBrightness ?: -1f
        if (override >= 0f) return override
        return runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f)
    }

    fun setBrightness(level: Float) {
        val w = window ?: return
        // A floor above zero: 0 turns some panels fully off, which reads as the app crashing.
        w.attributes = w.attributes.apply { screenBrightness = level.coerceIn(0.01f, 1f) }
    }

    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { toggle() },
                    onDoubleTap = { offset ->
                        val third = size.width / 3f
                        when {
                            offset.x < third ->
                                if (seek(-SEEK_STEP_MILLIS)) indicator = GestureIndicator.SeekBack
                            offset.x > size.width - third ->
                                if (seek(SEEK_STEP_MILLIS)) indicator = GestureIndicator.SeekForward
                            else -> toggle()
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                var adjustingVolume = false
                var level = 0f
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        adjustingVolume = offset.x >= size.width / 2f
                        level = if (adjustingVolume) {
                            audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                        } else {
                            currentBrightness()
                        }
                        dragging = true
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        // A swipe across ~3/4 of the height covers the full range.
                        level = (level - dragAmount / (size.height * 0.75f)).coerceIn(0f, 1f)
                        indicator = if (adjustingVolume) {
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(level * maxVolume), 0)
                            GestureIndicator.Volume(level)
                        } else {
                            setBrightness(level)
                            GestureIndicator.Brightness(level)
                        }
                    },
                )
            },
    ) {
        indicator?.let { GestureIndicatorBadge(it, Modifier.align(Alignment.Center)) }
    }
}

private sealed interface GestureIndicator {
    data class Brightness(val level: Float) : GestureIndicator
    data class Volume(val level: Float) : GestureIndicator
    data object SeekBack : GestureIndicator
    data object SeekForward : GestureIndicator
}

@Composable
private fun GestureIndicatorBadge(indicator: GestureIndicator, modifier: Modifier = Modifier) {
    val (label, level) = when (indicator) {
        is GestureIndicator.Brightness -> stringResource(R.string.player_gesture_brightness) to indicator.level
        is GestureIndicator.Volume -> stringResource(R.string.player_gesture_volume) to indicator.level
        GestureIndicator.SeekBack -> stringResource(R.string.player_gesture_seek_back) to null
        GestureIndicator.SeekForward -> stringResource(R.string.player_gesture_seek_forward) to null
    }
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium)
        if (level != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { level },
                modifier = Modifier.width(140.dp).height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = 0.25f),
            )
            Spacer(Modifier.height(4.dp))
            Text("${Math.round(level * 100)}%", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

private const val SEEK_STEP_MILLIS = 10_000L
private const val INDICATOR_LINGER_MILLIS = 700L
