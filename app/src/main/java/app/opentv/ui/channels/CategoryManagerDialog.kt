/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.opentv.R
import app.opentv.core.AppSettings.CategorySortOrder

/** One row in [CategoryManagerDialog]: the whitelist key and the label shown for it. */
data class ManagedCategory(val key: String, val label: String)

/**
 * Pick which categories show, and in what order. Shared by Live TV (folded category groups) and
 * Movies/Shows (provider categories). The sort applies straight away; the ticks apply on Save.
 *
 * The filter box narrows the list, and Select all / Deselect all / Invert act on what the filter
 * leaves showing — so "filter 'UK', Select all" ticks just the UK categories. Save is disabled with
 * nothing ticked, because an empty whitelist means "show everything" and would undo the choice.
 */
@Composable
fun CategoryManagerDialog(
    categories: List<ManagedCategory>,
    whitelist: Set<String>,
    sortOrder: CategorySortOrder,
    onSortOrder: (CategorySortOrder) -> Unit,
    onSave: (scopeKeys: Set<String>, selected: Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val allKeys = remember(categories) { categories.mapTo(LinkedHashSet()) { it.key } }
    // Start from what is showing now: everything when the whitelist is empty (or names none of
    // these), otherwise just the whitelisted ones.
    var selected by remember(allKeys) {
        val pinned = allKeys intersect whitelist
        mutableStateOf<Set<String>>(if (pinned.isEmpty()) allKeys else pinned)
    }
    var filter by remember { mutableStateOf("") }
    val shown = remember(categories, filter) {
        val q = filter.trim()
        if (q.isEmpty()) categories else categories.filter { it.label.contains(q, ignoreCase = true) }
    }
    val shownKeys = shown.mapTo(HashSet()) { it.key }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
        ) {
            Text(
                stringResource(R.string.categories_manage_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            Text(
                stringResource(R.string.categories_sort_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    ToggleChip(stringResource(R.string.categories_sort_default), sortOrder == CategorySortOrder.DEFAULT) {
                        onSortOrder(CategorySortOrder.DEFAULT)
                    }
                }
                item {
                    ToggleChip(stringResource(R.string.categories_sort_az), sortOrder == CategorySortOrder.NAME_ASC) {
                        onSortOrder(CategorySortOrder.NAME_ASC)
                    }
                }
                item {
                    ToggleChip(stringResource(R.string.categories_sort_za), sortOrder == CategorySortOrder.NAME_DESC) {
                        onSortOrder(CategorySortOrder.NAME_DESC)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                label = { Text(stringResource(R.string.categories_filter_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    ToggleChip(stringResource(R.string.categories_select_all), false) { selected = selected + shownKeys }
                }
                item {
                    ToggleChip(stringResource(R.string.categories_deselect_all), false) { selected = selected - shownKeys }
                }
                item {
                    ToggleChip(stringResource(R.string.categories_invert), false) {
                        selected = (selected - shownKeys) + (shownKeys - selected)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.categories_shown_count, selected.size, allKeys.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                if (shown.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.categories_none_match),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                items(shown, key = { it.key }) { category ->
                    val checked = category.key in selected
                    CheckRow(category.label, checked) {
                        selected = if (checked) selected - category.key else selected + category.key
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                if (selected.isEmpty()) stringResource(R.string.categories_pick_one)
                else stringResource(R.string.categories_note),
                style = MaterialTheme.typography.bodySmall,
                color = if (selected.isEmpty()) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Spacer(Modifier.width(12.dp))
                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = { onSave(allKeys, selected); onDismiss() },
                ) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row is the click target; the box only shows state, so it isn't a second focus stop.
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
