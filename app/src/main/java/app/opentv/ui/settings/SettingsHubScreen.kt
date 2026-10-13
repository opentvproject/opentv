/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.opentv.R

/**
 * The one settings entry point. Every other settings surface is reached from here, so there is
 * a single place to look instead of icons scattered across the guide. Rows that aren't built
 * yet simply aren't shown — no dead ends.
 */
@Composable
fun SettingsHubScreen(
    onOpenProviders: () -> Unit,
    onOpenAddons: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenChannels: () -> Unit,
    onOpenWebManager: () -> Unit,
    onOpenDisplay: () -> Unit,
    onOpenParental: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenCloudSync: () -> Unit,
    onOpenRecordings: () -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
) {
    val entries = listOf(
        HubEntry(Icons.Filled.Dns, stringResource(R.string.settings_providers_title), stringResource(R.string.settings_providers_subtitle), onOpenProviders),
        HubEntry(Icons.Filled.Extension, stringResource(R.string.settings_addons_title), stringResource(R.string.settings_addons_subtitle), onOpenAddons),
        HubEntry(Icons.Filled.LiveTv, stringResource(R.string.settings_guide_title), stringResource(R.string.settings_guide_subtitle), onOpenGuide),
        HubEntry(Icons.Filled.GridView, stringResource(R.string.common_channels), stringResource(R.string.settings_channels_subtitle), onOpenChannels),
        HubEntry(Icons.Filled.PhoneAndroid, stringResource(R.string.settings_webmanager_title), stringResource(R.string.settings_webmanager_subtitle), onOpenWebManager),
        HubEntry(Icons.Filled.Tune, stringResource(R.string.settings_display_title), stringResource(R.string.settings_display_subtitle), onOpenDisplay),
        HubEntry(Icons.Filled.Storage, stringResource(R.string.settings_recording_title), stringResource(R.string.settings_recording_subtitle), onOpenRecordings),
        HubEntry(Icons.Filled.Lock, stringResource(R.string.settings_parental_title), stringResource(R.string.settings_parental_subtitle), onOpenParental),
        HubEntry(Icons.Filled.Sync, stringResource(R.string.cloud_title), stringResource(R.string.cloud_subtitle), onOpenCloudSync),
        HubEntry(Icons.Filled.Sync, stringResource(R.string.settings_sync_title), stringResource(R.string.settings_sync_subtitle), onOpenSync),
        HubEntry(Icons.Filled.Info, stringResource(R.string.settings_about_title), stringResource(R.string.settings_about_subtitle), onOpenAbout),
    )

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            HubTextButton(stringResource(R.string.common_done), onBack)
        }

        Spacer(Modifier.height(20.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.widthIn(max = 760.dp),
        ) {
            items(entries) { entry -> HubRow(entry) }
        }
    }
}

private data class HubEntry(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
)

@Composable
private fun HubRow(entry: HubEntry) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                else Modifier,
            )
            .clickable(onClick = entry.onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            entry.icon,
            contentDescription = null,
            tint = if (focused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (focused) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                entry.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (focused) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun HubTextButton(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}
