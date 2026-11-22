/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Source
import app.opentv.data.model.StreamKind
import app.opentv.ui.CategoriesViewModel

/**
 * Settings screen to hide or reorder entire categories of TV channels, shows and VOD.
 */
@Composable
fun CategorySettingsScreen(
    onBack: () -> Unit,
    viewModel: CategoriesViewModel = viewModel(),
) {
    val selectedKind by viewModel.selectedKind.collectAsState()
    val selectedSource by viewModel.selectedSource.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val categories by viewModel.categories.collectAsState()

    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        // Top bar
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    stringResource(R.string.settings_categories_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    stringResource(R.string.settings_categories_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) {
                Text(stringResource(R.string.common_done))
            }
        }

        Spacer(Modifier.height(18.dp))

        // Kind selection tabs: TV Channels, Shows, VOD
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryKindTab(
                title = stringResource(R.string.nav_live_tv),
                icon = Icons.Filled.LiveTv,
                selected = selectedKind == StreamKind.LIVE,
                onClick = { viewModel.selectKind(StreamKind.LIVE) },
            )
            CategoryKindTab(
                title = stringResource(R.string.nav_shows),
                icon = Icons.Filled.Tv,
                selected = selectedKind == StreamKind.SERIES,
                onClick = { viewModel.selectKind(StreamKind.SERIES) },
            )
            CategoryKindTab(
                title = stringResource(R.string.nav_movies),
                icon = Icons.Filled.Movie,
                selected = selectedKind == StreamKind.MOVIE,
                onClick = { viewModel.selectKind(StreamKind.MOVIE) },
            )
        }

        // Provider filter if multiple sources configured
        if (sources.size > 1) {
            Spacer(Modifier.height(10.dp))
            LazyRow(
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    Text(
                        stringResource(R.string.channels_manager_source_header),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                item {
                    CategorySourceChip(
                        label = stringResource(R.string.channels_manager_all_sources),
                        selected = selectedSource == null,
                        onClick = { viewModel.selectSource(null) },
                    )
                }
                items(sources, key = { it.id }) { src ->
                    CategorySourceChip(
                        label = src.name,
                        selected = selectedSource == src.id,
                        onClick = { viewModel.selectSource(src.id) },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Action & Summary bar
        val hiddenCount = categories.count { it.isHidden }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.categories_count_summary, categories.size, hiddenCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { viewModel.showAll() },
                enabled = categories.isNotEmpty() && hiddenCount > 0,
            ) {
                Text(stringResource(R.string.categories_show_all))
            }
            TextButton(
                onClick = { viewModel.hideAll() },
                enabled = categories.isNotEmpty() && hiddenCount < categories.size,
            ) {
                Text(stringResource(R.string.categories_hide_all))
            }
            TextButton(
                onClick = { viewModel.resetOrder() },
                enabled = categories.isNotEmpty(),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.categories_reset_order))
            }
        }

        Spacer(Modifier.height(8.dp))

        // Category list
        if (categories.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .widthIn(max = 760.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.categories_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .widthIn(max = 760.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(categories, key = { _, item -> item.key }) { index, item ->
                    CategoryRow(
                        item = item,
                        index = index,
                        totalCount = categories.size,
                        onMoveUp = { viewModel.moveCategory(item.key, -1) },
                        onMoveDown = { viewModel.moveCategory(item.key, 1) },
                        onToggleHidden = { viewModel.toggleHidden(item.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryKindTab(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    focused -> MaterialTheme.colorScheme.primary
                    selected -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(20.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = when {
                focused -> MaterialTheme.colorScheme.onPrimary
                selected -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = when {
                focused -> MaterialTheme.colorScheme.onPrimary
                selected -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun CategorySourceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
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
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = fg,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun CategoryRow(
    item: CategoriesViewModel.CategoryItem,
    index: Int,
    totalCount: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleHidden: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                else Modifier,
            )
            .alpha(if (item.isHidden) 0.6f else 1f),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (focused) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Reorder controls: Up and Down buttons
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = index > 0,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.ArrowUpward,
                        contentDescription = stringResource(R.string.categories_move_up),
                        modifier = Modifier.size(20.dp),
                        tint = if (index > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = index < totalCount - 1,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = stringResource(R.string.categories_move_down),
                        modifier = Modifier.size(20.dp),
                        tint = if (index < totalCount - 1) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // Index tag
            Text(
                "#${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.width(36.dp),
            )

            // Category name and hidden status
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (item.isHidden) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (item.isHidden) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.isHidden) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.categories_hidden_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // Visibility switch
            Switch(
                checked = !item.isHidden,
                onCheckedChange = { onToggleHidden() },
            )
        }
    }
}
