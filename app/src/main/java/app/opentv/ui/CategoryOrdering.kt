/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import app.opentv.data.model.Category
import app.opentv.data.parser.ChannelNameNormalizer

internal fun foldLiveCategories(raw: List<Category>): List<ChannelsViewModel.CategoryGroup> {
    val groups = LinkedHashMap<String, Pair<String, MutableList<String>>>()
    for (category in raw) {
        val normalized = ChannelNameNormalizer.normalize(category.name)
        val key = normalized.groupKey.ifEmpty { category.id }
        val entry = groups.getOrPut(key) { normalized.baseName to mutableListOf() }
        entry.second += category.id
    }
    return groups.map { (key, value) ->
        ChannelsViewModel.CategoryGroup(key, value.first, value.second)
    }
}

internal fun sortLiveCategories(
    groups: List<ChannelsViewModel.CategoryGroup>,
    order: List<String>,
): List<ChannelsViewModel.CategoryGroup> {
    if (order.isEmpty()) return groups
    val orderMap = order.withIndex().associate { it.value to it.index }
    return groups.sortedBy { orderMap[it.key] ?: Int.MAX_VALUE / 2 }
}

internal fun sortVodCategories(categories: List<Category>, order: List<String>): List<Category> {
    if (order.isEmpty()) return categories
    val orderMap = order.withIndex().associate { it.value to it.index }
    return categories.sortedWith(
        compareBy<Category> { category ->
            orderMap["${category.sourceId}:${category.id}"]
                ?: orderMap[category.id]
                ?: orderMap[category.name]
                ?: (Int.MAX_VALUE / 2 + category.sortIndex)
        }.thenBy { it.name },
    )
}

internal fun isVodCategoryHidden(category: Category, hidden: Set<String>): Boolean =
    "${category.sourceId}:${category.id}" in hidden || category.id in hidden || category.name in hidden

/** Reorders the visible subset without dropping saved entries hidden by the current source filter. */
internal fun reorderVisibleCategories(
    visibleKeys: List<String>,
    savedOrder: List<String>,
    key: String,
    delta: Int,
): List<String>? {
    val oldIndex = visibleKeys.indexOf(key)
    val newIndex = oldIndex + delta
    if (oldIndex < 0 || newIndex !in visibleKeys.indices) return null

    val reordered = visibleKeys.toMutableList()
    reordered.add(newIndex, reordered.removeAt(oldIndex))
    val reorderedKeys = reordered.toSet()
    val remaining = ArrayDeque(reordered)
    return buildList {
        for (savedKey in savedOrder) {
            if (savedKey in reorderedKeys && remaining.isNotEmpty()) add(remaining.removeFirst())
            else add(savedKey)
        }
        addAll(remaining)
    }
}