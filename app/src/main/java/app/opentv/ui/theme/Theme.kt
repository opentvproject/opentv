/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A deliberately dark, low-chroma palette.
 *
 * This is a living-room app: it is looked at in a dark room, from three metres away, often
 * for hours. Bright surfaces and saturated accents that read well on a phone in daylight are
 * actively unpleasant on a 55" panel at night, so everything here is anchored near-black with
 * a single restrained accent used only for focus and selection.
 */
private val Accent = Color(0xFF7C93FF)
private val AccentDim = Color(0xFF4B5DB8)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF0A0C14),
    primaryContainer = AccentDim,
    onPrimaryContainer = Color(0xFFE6EAFF),
    secondary = Color(0xFF9AA3C0),
    background = Color(0xFF07080C),
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF0D0F16),
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF171A24),
    onSurfaceVariant = Color(0xFFA8AEC0),
    outline = Color(0xFF2A2F3D),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF1A0505),
)

/**
 * Light mode for anyone who wants it. By default TV boxes stay dark (a white living-room
 * screen at night is nobody's friend) — that default lives at the call site, so a user who
 * explicitly picks Light in settings gets it on any device.
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF3B4FCC),
    onPrimary = Color.White,
    background = Color(0xFFFBFBFE),
    onBackground = Color(0xFF13141A),
    surface = Color.White,
    onSurface = Color(0xFF13141A),
    surfaceVariant = Color(0xFFEEF0F6),
    onSurfaceVariant = Color(0xFF4A4F60),
    outline = Color(0xFFD3D7E2),
)

/** Scaled up from the Material defaults: ten-foot viewing needs bigger text than a phone. */
private val OpenTvTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

/**
 * Material's focus layer is 10% opacity, which is close to invisible on a near-black surface,
 * and a projector or a bright room makes it worse. D-pad focus is the only cue a remote user
 * has, so it gets a much stronger layer. Pressed keeps the default so a click still reads as a
 * distinct step.
 */
@OptIn(ExperimentalMaterial3Api::class)
private val FocusRipple = RippleConfiguration(
    rippleAlpha = RippleAlpha(
        draggedAlpha = 0.16f,
        focusedAlpha = 0.35f,
        hoveredAlpha = 0.08f,
        pressedAlpha = 0.10f,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenTvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = OpenTvTypography,
    ) {
        CompositionLocalProvider(LocalRippleConfiguration provides FocusRipple, content = content)
    }
}
