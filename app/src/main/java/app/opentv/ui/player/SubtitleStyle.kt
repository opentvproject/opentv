/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import android.util.TypedValue
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.AppSettings.SubtitleBackground
import app.opentv.core.AppSettings.SubtitleColor
import app.opentv.core.AppSettings.SubtitleEdge

/** The user's caption look, as one value the players can hand to their [SubtitleView]. */
data class SubtitleStyle(
    val sizeSp: Int,
    val color: SubtitleColor,
    val background: SubtitleBackground,
    val edge: SubtitleEdge,
)

/** The saved [SubtitleStyle], recomposing whenever any part of it changes. */
@Composable
fun collectSubtitleStyle(settings: AppSettings): SubtitleStyle {
    val size by settings.subtitleTextSizeSp.collectAsState()
    val color by settings.subtitleTextColor.collectAsState()
    val background by settings.subtitleBackground.collectAsState()
    val edge by settings.subtitleEdge.collectAsState()
    return SubtitleStyle(size, color, background, edge)
}

/**
 * Applies [style] to a player's caption view. Cheap and safe to call on every recomposition, so a
 * change made in [SubtitleStyleDialog] shows on the playing video at once, with no re-buffer.
 *
 * Embedded font sizes are switched off so the chosen size actually wins; embedded positioning and
 * styling stay on, since broadcast captions use them to sit clear of on-screen graphics.
 */
@OptIn(UnstableApi::class)
fun SubtitleView.applySubtitleStyle(style: SubtitleStyle) {
    setApplyEmbeddedFontSizes(false)
    setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, style.sizeSp.toFloat())
    setStyle(
        CaptionStyleCompat(
            style.color.argb,
            style.background.argb,
            android.graphics.Color.TRANSPARENT, // window colour: no box around the whole cue block
            when (style.edge) {
                SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                SubtitleEdge.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
            },
            android.graphics.Color.BLACK,
            null, // default typeface
        ),
    )
}

/**
 * Size, colour, background and edge for captions, with a live sample line. Every change is saved
 * as it's made, so when this opens over a playing video the real captions restyle behind it too.
 * Built from stepper buttons and chips rather than sliders so it works as well on a remote as a touch screen.
 */
@Composable
fun SubtitleStyleDialog(settings: AppSettings, onDismiss: () -> Unit) {
    val style = collectSubtitleStyle(settings)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(
                stringResource(R.string.subtitle_style_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            SubtitlePreview(style)
            Spacer(Modifier.height(16.dp))

            SectionLabel(stringResource(R.string.subtitle_style_size))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StyleChip(stringResource(R.string.subtitle_style_smaller), false) {
                    settings.setSubtitleTextSizeSp(style.sizeSp - SIZE_STEP)
                }
                Text(
                    stringResource(R.string.subtitle_style_size_value, style.sizeSp),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(88.dp),
                )
                StyleChip(stringResource(R.string.subtitle_style_larger), false) {
                    settings.setSubtitleTextSizeSp(style.sizeSp + SIZE_STEP)
                }
            }
            Spacer(Modifier.height(14.dp))

            SectionLabel(stringResource(R.string.subtitle_style_color))
            ChoiceRow(
                options = listOf(
                    SubtitleColor.WHITE to R.string.subtitle_color_white,
                    SubtitleColor.YELLOW to R.string.subtitle_color_yellow,
                    SubtitleColor.CYAN to R.string.subtitle_color_cyan,
                    SubtitleColor.GREEN to R.string.subtitle_color_green,
                ),
                selected = style.color,
                onSelect = settings::setSubtitleTextColor,
            )
            Spacer(Modifier.height(14.dp))

            SectionLabel(stringResource(R.string.subtitle_style_background))
            ChoiceRow(
                options = listOf(
                    SubtitleBackground.NONE to R.string.subtitle_bg_none,
                    SubtitleBackground.TRANSLUCENT to R.string.subtitle_bg_translucent,
                    SubtitleBackground.SOLID to R.string.subtitle_bg_solid,
                ),
                selected = style.background,
                onSelect = settings::setSubtitleBackground,
            )
            Spacer(Modifier.height(14.dp))

            SectionLabel(stringResource(R.string.subtitle_style_edge))
            ChoiceRow(
                options = listOf(
                    SubtitleEdge.OUTLINE to R.string.subtitle_edge_outline,
                    SubtitleEdge.DROP_SHADOW to R.string.subtitle_edge_shadow,
                    SubtitleEdge.NONE to R.string.subtitle_edge_none,
                ),
                selected = style.edge,
                onSelect = settings::setSubtitleEdge,
            )
            Spacer(Modifier.height(20.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = {
                    settings.setSubtitleTextSizeSp(AppSettings.SUBTITLE_SIZE_DEFAULT)
                    settings.setSubtitleTextColor(SubtitleColor.WHITE)
                    settings.setSubtitleBackground(SubtitleBackground.NONE)
                    settings.setSubtitleEdge(SubtitleEdge.OUTLINE)
                }) { Text(stringResource(R.string.subtitle_style_reset)) }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
            }
        }
    }
}

/**
 * A sample caption over a mock picture, drawn in Compose to match what the [SubtitleView] renders:
 * the same colour, size and background box, with the outline drawn as a stroke under the fill.
 */
@Composable
private fun SubtitlePreview(style: SubtitleStyle) {
    val text = stringResource(R.string.subtitle_style_preview)
    val base = TextStyle(fontSize = style.sizeSp.sp, textAlign = TextAlign.Center)
    val fill = Color(style.color.argb)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3A6073), Color(0xFF16222A), Color(0xFF8E9EAB)))),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .padding(12.dp)
                .background(Color(style.background.argb))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            if (style.edge == SubtitleEdge.OUTLINE) {
                Text(text, style = base.copy(color = Color.Black, drawStyle = Stroke(width = 6f)))
            }
            Text(
                text,
                style = base.copy(
                    color = fill,
                    shadow = if (style.edge == SubtitleEdge.DROP_SHADOW) {
                        Shadow(Color.Black, offset = Offset(3f, 3f), blurRadius = 4f)
                    } else {
                        null
                    },
                ),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun <T> ChoiceRow(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            item { StyleChip(stringResource(label), value == selected) { onSelect(value) } }
        }
    }
}

@Composable
private fun StyleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .then(
                if (selected && !focused) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private const val SIZE_STEP = 2
