/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * The three layouts the shell adapts between, on the Material window-size breakpoints:
 * [COMPACT] (< 600dp: a phone held upright) gets a bottom bar and a single touch-first column;
 * [MEDIUM] (600–840dp: foldables, small tablets) keeps the side rail with a narrower category list;
 * [EXPANDED] (≥ 840dp, and every TV whatever it reports) is the original d-pad, TiviMate-style layout.
 */
enum class WindowWidth { COMPACT, MEDIUM, EXPANDED }

/** The current [WindowWidth]. Re-read on rotation or fold, since the configuration changes. */
@Composable
fun currentWindowWidth(isTelevision: Boolean): WindowWidth {
    // A TV is always laid out for the 10-foot d-pad UI, even a box that reports a small dp width.
    if (isTelevision) return WindowWidth.EXPANDED
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp < 600 -> WindowWidth.COMPACT
        widthDp < 840 -> WindowWidth.MEDIUM
        else -> WindowWidth.EXPANDED
    }
}
