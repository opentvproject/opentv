/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Source
import app.opentv.data.model.shownName
import app.opentv.ui.ChannelsViewModel
import kotlinx.coroutines.launch

/**
 * The phone layout's stand-in for the category rail: one horizontally-scrolling chip row holding
 * the provider switch (with more than one source), Favourites, All, each category, and Manage.
 */
@Composable
internal fun CompactCategoryChips(
    sources: List<Source>,
    selectedSource: Long?,
    categories: List<ChannelsViewModel.CategoryGroup>,
    selectedCategory: String?,
    favouritesOnly: Boolean,
    canManage: Boolean,
    viewModel: ChannelsViewModel,
    onManage: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sources.size > 1) {
            item(key = "src-all") {
                TouchChip(stringResource(R.string.channels_manager_all_sources), selectedSource == null) {
                    viewModel.selectSource(null)
                }
            }
            items(sources, key = { "src-${it.id}" }) { source ->
                TouchChip(source.name, selectedSource == source.id) { viewModel.selectSource(source.id) }
            }
        }
        item(key = "favs") {
            TouchChip(stringResource(R.string.guide_favourites), favouritesOnly, viewModel::selectFavourites)
        }
        item(key = "all") {
            TouchChip(stringResource(R.string.guide_all_channels), !favouritesOnly && selectedCategory == null) {
                viewModel.selectCategory(null)
            }
        }
        items(categories, key = { it.key }) { group ->
            TouchChip(group.label, !favouritesOnly && selectedCategory == group.key) {
                viewModel.selectCategory(group.key)
            }
        }
        if (canManage) {
            item(key = "manage") { TouchChip(stringResource(R.string.categories_manage_short), false, onManage) }
        }
    }
}

/**
 * The phone channel list: [ChannelList] (tap watches, long-press opens the channel menu) with an
 * A–Z strip down the right edge. Tap or drag along the strip to jump to the first channel under
 * that letter, the contacts-app pattern for scrolling a few thousand rows by thumb.
 */
@Composable
internal fun CompactChannelList(
    rows: List<ChannelsViewModel.Row>,
    onWatch: (ChannelsViewModel.Row) -> Unit,
    onMenu: (ChannelsViewModel.Row) -> Unit,
    onToggleFavourite: (ChannelsViewModel.Row) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Letter → first row index under it, in list order. Only letters that actually occur.
    val index = remember(rows) {
        val firstRow = LinkedHashMap<Char, Int>()
        rows.forEachIndexed { i, row -> firstRow.putIfAbsent(indexLetter(row.primary.shownName), i) }
        firstRow.toSortedMap(compareBy { if (it == '#') Char.MAX_VALUE else it })
    }
    // A short list scrolls fine by hand, and a strip of one or two letters is noise.
    val showIndex = rows.size > INDEX_MIN_ROWS && index.size > 1

    Box(modifier) {
        ChannelList(
            rows = rows,
            selectedKey = null,
            onSelectRow = onWatch,
            onFocusRow = {},
            onToggleFavourite = onToggleFavourite,
            onLongPressRow = onMenu,
            state = listState,
            modifier = Modifier.padding(start = 8.dp, end = if (showIndex) 28.dp else 8.dp),
        )
        if (showIndex) {
            AlphabetIndex(
                letters = index.keys.toList(),
                onLetter = { letter ->
                    index[letter]?.let { target -> scope.launch { listState.scrollToItem(target) } }
                },
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(28.dp),
            )
        }
    }
}

@Composable
private fun AlphabetIndex(letters: List<Char>, onLetter: (Char) -> Unit, modifier: Modifier = Modifier) {
    var active by remember { mutableStateOf<Char?>(null) }
    fun pick(y: Float, height: Int) {
        if (height <= 0) return
        val letter = letters[(y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)]
        if (letter != active) {
            active = letter
            onLetter(letter)
        }
    }
    Column(
        modifier
            .padding(vertical = 8.dp)
            .pointerInput(letters) {
                detectTapGestures(onPress = { pick(it.y, size.height); tryAwaitRelease(); active = null })
            }
            .pointerInput(letters) {
                detectVerticalDragGestures(
                    onDragStart = { pick(it.y, size.height) },
                    onDragEnd = { active = null },
                    onDragCancel = { active = null },
                    onVerticalDrag = { change, _ -> change.consume(); pick(change.position.y, size.height) },
                )
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { letter ->
            Text(
                letter.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (letter == active) FontWeight.Bold else FontWeight.Normal,
                color = if (letter == active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TouchChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            // 48dp: the minimum comfortable touch target.
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The A–Z strip letter a channel files under: its first letter, or '#' for digits and symbols. */
internal fun indexLetter(name: String): Char {
    val c = name.trimStart().firstOrNull()?.uppercaseChar() ?: return '#'
    return if (c in 'A'..'Z') c else '#'
}

private const val INDEX_MIN_ROWS = 30
