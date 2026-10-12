/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.shownName
import app.opentv.ui.ChannelsViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Channel manager: browse your channels by category and, for each, favourite it or hide it from
 * the guide.
 *
 * Two panes. LEFT is Search plus a scrollable category list (with a source filter when more than
 * one provider is configured, so a second source can be kept separate rather than merged). RIGHT
 * is the channels in the selected category — each a logo, name, favourite star and a show/hide
 * switch. Hidden channels appear here too, greyed, so they can be brought back; the guide never
 * shows them, but the manager shows everything.
 *
 * The right pane is always scoped to one category (and optionally one source): a flat list of a
 * 20,000-channel provider is unscrollable on a remote, so browsing is by category by design.
 */
@Composable
fun ChannelManagerScreen(
    onBack: () -> Unit,
    viewModel: ChannelsViewModel = viewModel(),
) {
    val sources by viewModel.sources.collectAsState()
    val categoryGroups by viewModel.managerCategoryGroups.collectAsState()
    val selectedSource by viewModel.managerSelectedSource.collectAsState()
    val selectedCategory by viewModel.managerSelectedCategory.collectAsState()
    val browseRows by viewModel.managerRows.collectAsState()
    val offCategories by viewModel.offCategories.collectAsState()
    // A logical category counts as switched off once every provider category folded into it is.
    fun isOff(group: ChannelsViewModel.CategoryGroup) =
        group.categoryKeys.isNotEmpty() && group.categoryKeys.all { it in offCategories }

    // Search is one option among browse-by-category: the "Search" rail entry flips the right pane
    // to the original keyboard-driven search, which is kept intact rather than thrown away.
    var searchMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val searchResults by viewModel.managerResults.collectAsState()
    LaunchedEffect(query) { viewModel.setManagerQuery(query) }

    // Focus handoff between the panes, mirroring HomeScreen's rail: the active left entry (the
    // selected category, or Search) carries this requester, so LEFT from a channel row lands
    // straight back on it. The rail is always visible, so if the requester's node is scrolled out
    // this returns false and ordinary left-navigation still reaches a visible entry.
    val railFocusRequester = remember { FocusRequester() }
    val onExitLeftToRail: () -> Boolean = {
        runCatching { railFocusRequester.requestFocus() }.isSuccess
    }

    BackHandler { onBack() }

    // Open on the first category so channels show straight away — the whole complaint was that the
    // manager never displayed any. Re-runs when the category set changes (e.g. after switching
    // source, which clears the selection) so a newly-chosen source immediately shows its channels.
    LaunchedEffect(categoryGroups, searchMode) {
        if (!searchMode && selectedCategory == null && categoryGroups.isNotEmpty()) {
            viewModel.selectManagerCategory(categoryGroups.first().key)
        }
    }
    // One-time: drop focus onto the rail once there is something to focus. Guarded so an incidental
    // category re-emit later (a background sync) never yanks focus out of the channel list.
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(categoryGroups, searchMode) {
        if (!initialFocusDone && !searchMode && categoryGroups.isNotEmpty()) {
            delay(80)
            runCatching { railFocusRequester.requestFocus() }
            initialFocusDone = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar: title + Done (Back also exits via BackHandler). Kept out of the two panes so
        // pressing RIGHT from a category lands in the channel list, not on this button.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.channels_manager_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // ---- LEFT pane: Search, an optional source filter, and the category list ----------
            LazyColumn(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    RailEntry(
                        label = stringResource(R.string.nav_search),
                        selected = searchMode,
                        onClick = { searchMode = true },
                        modifier = if (searchMode) Modifier.focusRequester(railFocusRequester) else Modifier,
                    )
                }

                // Source filter — only when there's more than one provider, so a second source can
                // be viewed on its own instead of merged into everything else.
                if (sources.size > 1) {
                    item { SectionLabel(stringResource(R.string.channels_manager_source_header)) }
                    item {
                        RailEntry(
                            label = stringResource(R.string.channels_manager_all_sources),
                            selected = selectedSource == null,
                            onClick = { searchMode = false; viewModel.selectManagerSource(null) },
                        )
                    }
                    items(sources, key = { "src-${it.id}" }) { source ->
                        RailEntry(
                            label = source.name,
                            selected = selectedSource == source.id,
                            onClick = { searchMode = false; viewModel.selectManagerSource(source.id) },
                        )
                    }
                }

                item { SectionLabel(stringResource(R.string.channels_manager_categories_header)) }
                items(categoryGroups, key = { "cat-${it.key}" }) { group ->
                    val groupSelected = !searchMode && selectedCategory == group.key
                    RailEntry(
                        label = if (isOff(group)) stringResource(R.string.channels_category_off_suffix, group.label) else group.label,
                        selected = groupSelected,
                        dimmed = isOff(group),
                        onClick = { searchMode = false; viewModel.selectManagerCategory(group.key) },
                        modifier = if (groupSelected) Modifier.focusRequester(railFocusRequester) else Modifier,
                    )
                }
            }

            // ---- RIGHT pane: the search UI, or the selected category's channels ---------------
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                if (searchMode) {
                    SearchPane(
                        query = query,
                        results = searchResults,
                        onKey = { if (query.length < 40) query += it },
                        onSpace = { if (query.length < 40) query += " " },
                        onBackspace = { query = query.dropLast(1) },
                        onClear = { query = "" },
                        onToggleHidden = { row, hidden -> viewModel.setRowHidden(row, !hidden) },
                        onToggleFavourite = { viewModel.toggleFavourite(it) },
                    )
                } else {
                    val group = categoryGroups.firstOrNull { it.key == selectedCategory }
                    if (group != null) {
                        val off = isOff(group)
                        CategorySwitch(
                            label = group.label,
                            off = off,
                            onToggle = { viewModel.setCategoryOff(group, !off) },
                        )
                    }
                    BrowsePane(
                        hasCategories = categoryGroups.isNotEmpty(),
                        categorySelected = selectedCategory != null,
                        rows = browseRows,
                        onExitLeft = onExitLeftToRail,
                        onToggleHidden = { row, hidden -> viewModel.setRowHidden(row, !hidden) },
                        onToggleFavourite = { viewModel.toggleFavourite(it) },
                    )
                }
            }
        }
    }
}

/** The kept-intact keyboard-driven search: on-screen keyboard on the left, matches on the right. */
@Composable
private fun SearchPane(
    query: String,
    results: List<ChannelsViewModel.Row>,
    onKey: (String) -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onToggleHidden: (ChannelsViewModel.Row, Boolean) -> Unit,
    onToggleFavourite: (ChannelsViewModel.Row) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        OnScreenKeyboard(onKey = onKey, onSpace = onSpace, onBackspace = onBackspace, onClear = onClear)
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f).fillMaxSize()) {
            when {
                query.isBlank() -> Hint(stringResource(R.string.channels_manager_search_hint))
                query.trim().length < 2 -> Hint(stringResource(R.string.common_keep_typing))
                results.isEmpty() -> Hint(stringResource(R.string.channels_manager_no_match, query))
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(results, key = { it.key }) { row ->
                        val hidden = row.variants.all { it.hidden }
                        ManagerRow(
                            row = row,
                            hidden = hidden,
                            onToggleHidden = { onToggleHidden(row, hidden) },
                            onToggleFavourite = { onToggleFavourite(row) },
                        )
                    }
                }
            }
        }
    }
}

/** The selected category's channels — hidden ones included, greyed, so they can be un-hidden. */
@Composable
private fun BrowsePane(
    hasCategories: Boolean,
    categorySelected: Boolean,
    rows: List<ChannelsViewModel.Row>,
    onExitLeft: () -> Boolean,
    onToggleHidden: (ChannelsViewModel.Row, Boolean) -> Unit,
    onToggleFavourite: (ChannelsViewModel.Row) -> Unit,
) {
    when {
        !hasCategories -> Hint(stringResource(R.string.channels_manager_no_channels))
        !categorySelected -> Hint(stringResource(R.string.channels_manager_pick_category))
        rows.isEmpty() -> Hint(stringResource(R.string.channels_manager_empty_category))
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                val hidden = row.variants.all { it.hidden }
                ManagerRow(
                    row = row,
                    hidden = hidden,
                    onToggleHidden = { onToggleHidden(row, hidden) },
                    onToggleFavourite = { onToggleFavourite(row) },
                    // LEFT from the row's leftmost control (the star) hops back to the category
                    // rail; from the switch it first steps left to the star, as usual.
                    leftEdgeModifier = Modifier.onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) onExitLeft()
                        else false
                    },
                )
            }
        }
    }
}

@Composable
private fun RailEntry(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (dimmed && !focused) fg.copy(alpha = 0.5f) else fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/**
 * The whole-category switch above the channel list. Off = the category is decluttered: its
 * channels aren't loaded into Live TV, search or favourites at all (unlike hiding single channels,
 * this is for whole categories you never watch). The same switch exists on the phone/web manager.
 */
@Composable
private fun CategorySwitch(label: String, off: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (off) stringResource(R.string.channels_category_off_hint)
                else stringResource(R.string.channels_category_in_guide),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = !off, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ManagerRow(
    row: ChannelsViewModel.Row,
    hidden: Boolean,
    onToggleHidden: () -> Unit,
    onToggleFavourite: () -> Unit,
    leftEdgeModifier: Modifier = Modifier,
) {
    val alpha = if (hidden) 0.5f else 1f
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = row.primary.logoUrl,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.shownName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (hidden) stringResource(R.string.channels_hidden) else stringResource(R.string.channels_showing),
                style = MaterialTheme.typography.bodyMedium,
                color = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
        }

        // The star is the row's leftmost focusable, so it carries the LEFT-to-rail handler.
        IconButton(onClick = onToggleFavourite, modifier = leftEdgeModifier) {
            Icon(
                imageVector = if (row.primary.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = if (row.primary.favourite) stringResource(R.string.common_remove_favourite) else stringResource(R.string.common_favourite),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.channels_show), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            // Checked = visible; off = hidden. Reads the natural way: switch it off to hide.
            Switch(checked = !hidden, onCheckedChange = { onToggleHidden() })
        }
    }
}
