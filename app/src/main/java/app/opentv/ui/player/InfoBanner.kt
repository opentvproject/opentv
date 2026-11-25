/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Programme
import app.opentv.player.PlaybackQueue
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The channel banner that flashes up on every channel change, and expands into full programme
 * detail on the Info key.
 *
 * ## Why this exists separately from the control bar
 *
 * Zapping and operating the player are different jobs. The control bar is a row of focusable
 * buttons you steer with the d-pad; a channel banner is a read-only glance that must not steal
 * focus, because the very next key press is usually another channel change. So this draws no
 * focusable elements at all — the d-pad keeps zapping straight through it.
 *
 * ## Why it never waits for the stream
 *
 * Everything in the compact row ([item]) comes from the in-memory playback queue, so the banner
 * is on screen on the same frame as the key press — before the first video frame, and still there
 * if the stream never loads at all. That is what makes fast zapping possible: you navigate by the
 * banner, not by waiting for each picture to appear. Guide data arrives a moment later and fills
 * in underneath; its absence is shown honestly rather than held back behind a spinner.
 */
@Composable
internal fun InfoBanner(
    item: PlaybackQueue.Item,
    now: Programme?,
    next: Programme?,
    expanded: Boolean,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))))
            // Same inset treatment as the control bar: clear of the nav bar and cutout on a
            // phone, zero on a TV, with the gradient still running to the screen edge.
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .padding(horizontal = 28.dp, vertical = 20.dp),
    ) {
        // ---- Channel identity ------------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Fixed-width number slot so the logo and name hold the same position as the banner
            // is replaced on each zap — a jittering banner is unreadable when surfing fast.
            if (item.number != null) {
                Text(
                    item.number.toString(),
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.width(96.dp),
                )
            }
            if (item.logoUrl != null) {
                AsyncImage(
                    model = item.logoUrl,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(16.dp))
            }
            Text(
                item.name,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- What's on now ---------------------------------------------------------------
        if (now != null) {
            Text(
                "${formatClock(now.startUtcMillis)}–${formatClock(now.endUtcMillis)}   ${now.title}",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (expanded) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { now.progressAt(nowMillis) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )

            // Season/episode and genre, when the guide carries them. Only worth the line when
            // expanded — compact is for glancing while zapping.
            if (expanded) {
                val meta = listOfNotNull(episodeLabel(now), now.category?.takeIf { it.isNotBlank() })
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        meta.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val synopsis = now.description?.takeIf { it.isNotBlank() }
                if (synopsis != null) {
                    Spacer(Modifier.height(10.dp))
                    // Capped and scrollable: some feeds ship several hundred words of synopsis,
                    // and a banner that grows to fill the screen stops being a banner.
                    Box(Modifier.heightIn(max = 148.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            synopsis,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.88f),
                        )
                    }
                }
            }
        } else {
            Text(
                stringResource(R.string.guide_no_info),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }

        // ---- What's on next --------------------------------------------------------------
        if (next != null) {
            Spacer(Modifier.height(if (expanded) 14.dp else 10.dp))
            Text(
                stringResource(R.string.guide_next_prefix, formatClock(next.startUtcMillis), next.title),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                val nextSynopsis = next.description?.takeIf { it.isNotBlank() }
                if (nextSynopsis != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        nextSynopsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // A one-line nudge that there is more behind the Info key, shown only while compact and
        // only when there is actually something more to reveal.
        if (!expanded && now?.description?.isNotBlank() == true) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.player_info_hint),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1,
            )
        }
    }
}

/** `S2 E5`, `S2`, `E5`, or null when the guide gave neither. */
private fun episodeLabel(programme: Programme): String? {
    val parts = listOfNotNull(
        programme.season?.let { "S$it" },
        programme.episode?.let { "E$it" },
    )
    return parts.joinToString(" ").takeIf { it.isNotEmpty() }
}

private val bannerClockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatClock(utcMillis: Long): String = bannerClockFormat.format(Date(utcMillis))
