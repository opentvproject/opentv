/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.AppSettings

/**
 * The interface mode, asked at the start: a television, or a phone/tablet.
 *
 * It is worth asking because the device cannot always be trusted to say which it is. A Google
 * TV stick that reports a phone profile is a real thing, and so is a phone driving a
 * wall-mounted screen — and the two want different things: a television is driven by a d-pad,
 * wants a category folder to open as a plain list and wants a way to blank the picture with
 * the programme still playing; a phone is driven by a finger and wants neither.
 *
 * The chips are ordinary [FilterChip]s, so they are focusable and a remote can reach them.
 */
@Composable
fun ModeSelector(
    mode: AppSettings.AppMode,
    onSelect: (AppSettings.AppMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            stringResource(R.string.onboarding_mode_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(
                selected = mode == AppSettings.AppMode.TV,
                onClick = { onSelect(AppSettings.AppMode.TV) },
                label = { Text(stringResource(R.string.settings_mode_tv)) },
            )
            FilterChip(
                selected = mode == AppSettings.AppMode.PHONE,
                onClick = { onSelect(AppSettings.AppMode.PHONE) },
                label = { Text(stringResource(R.string.settings_mode_phone)) },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.onboarding_mode_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
