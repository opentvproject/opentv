/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.opentv.core.findActivity

/** Below this width the TV layout (side rail, time grid, side-by-side panes) no longer fits. */
const val COMPACT_WIDTH_DP = 600

/**
 * True on a narrow window — a phone held upright, or a slim split-screen pane. Screens use it to
 * swap their ten-foot layout for a single-column, touch-first one. A TV, a tablet and a phone on
 * its side all stay on the wide layout, so nothing changes for a remote.
 */
@Composable
fun isCompactWidth(): Boolean = LocalConfiguration.current.screenWidthDp < COMPACT_WIDTH_DP

/**
 * Turns a phone on its side for as long as a full-screen player is on screen, and hands rotation
 * back when it leaves — video is 16:9, and a portrait player wastes two thirds of the display.
 * Either landscape way up, following the sensor. Tablets and TVs are left alone: a tablet is fine
 * to watch in whichever way it is being held.
 */
@Composable
fun LandscapeOnPhones() {
    val activity = LocalContext.current.findActivity() ?: return
    val isPhone = LocalConfiguration.current.smallestScreenWidthDp < COMPACT_WIDTH_DP
    DisposableEffect(activity, isPhone) {
        if (isPhone) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (isPhone) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }
}
