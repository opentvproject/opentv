/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import app.opentv.core.AppSettings.CategorySortOrder

/**
 * The category sort + whitelist rules, shared by the Live rail and the Movies/Shows chips. Pure
 * functions over any item type (a live [ChannelsViewModel.CategoryGroup] or a raw VOD category),
 * so the rules are unit-tested once rather than per screen.
 */
object CategoryFiltering {

    /** [CategorySortOrder.DEFAULT] keeps the provider's own order; the others sort case-insensitively. */
    fun <T> sort(items: List<T>, order: CategorySortOrder, label: (T) -> String): List<T> = when (order) {
        CategorySortOrder.DEFAULT -> items
        CategorySortOrder.NAME_ASC -> items.sortedBy { label(it).lowercase() }
        CategorySortOrder.NAME_DESC -> items.sortedByDescending { label(it).lowercase() }
    }

    /**
     * Keeps only whitelisted items. An empty whitelist means "show everything". So does a whitelist
     * that names none of these items (it was saved against another provider's categories) — a
     * whitelist from elsewhere should never blank out the whole rail.
     */
    fun <T> applyWhitelist(items: List<T>, whitelist: Set<String>, key: (T) -> String): List<T> {
        if (whitelist.isEmpty()) return items
        val kept = items.filter { key(it) in whitelist }
        return kept.ifEmpty { items }
    }

    /** The keys [applyWhitelist] would drop — what the guide must also hide from "All channels". */
    fun <T> excludedKeys(items: List<T>, whitelist: Set<String>, key: (T) -> String): Set<String> {
        val kept = applyWhitelist(items, whitelist, key).mapTo(HashSet(), key)
        return items.mapNotNullTo(HashSet()) { key(it).takeUnless { k -> k in kept } }
    }

    /**
     * Folds a selection made over the categories currently on screen ([scopeKeys] — one provider's,
     * say) back into the saved whitelist. Keys outside the scope are left as they were, so trimming
     * one provider's rail doesn't touch another's. Ticking everything collapses back to the empty
     * "show all" whitelist when nothing else is pinned, so categories a provider adds later appear.
     */
    fun mergeWhitelist(saved: Set<String>, scopeKeys: Set<String>, selected: Set<String>): Set<String> {
        val outside = saved - scopeKeys
        if (selected.containsAll(scopeKeys) && outside.isEmpty()) return emptySet()
        // A previously empty whitelist meant "everything", so nothing outside the scope is pinned.
        return outside + (selected intersect scopeKeys)
    }
}

/**
 * A Movies/Shows category's whitelist key. Category ids are only unique within one provider, so
 * the source id is part of it.
 */
fun vodCategoryKey(category: app.opentv.data.model.Category): String = "${category.sourceId}:${category.id}"
