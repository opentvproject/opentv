/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.AppSettings.CategorySortOrder
import app.opentv.ui.CategoryFiltering
import app.opentv.ui.channels.indexLetter
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CategoryFilteringTest {

    private data class Cat(val key: String, val label: String)

    private val cats = listOf(Cat("uk", "UK General"), Cat("sport", "sport"), Cat("news", "News"))

    @Test
    fun `default order keeps the provider's order`() {
        assertThat(CategoryFiltering.sort(cats, CategorySortOrder.DEFAULT) { it.label }).isEqualTo(cats)
    }

    @Test
    fun `name sorts ignore case in both directions`() {
        val asc = CategoryFiltering.sort(cats, CategorySortOrder.NAME_ASC) { it.label }.map { it.key }
        val desc = CategoryFiltering.sort(cats, CategorySortOrder.NAME_DESC) { it.label }.map { it.key }

        assertThat(asc).containsExactly("news", "sport", "uk").inOrder()
        assertThat(desc).containsExactly("uk", "sport", "news").inOrder()
    }

    @Test
    fun `an empty whitelist shows everything`() {
        assertThat(CategoryFiltering.applyWhitelist(cats, emptySet()) { it.key }).isEqualTo(cats)
    }

    @Test
    fun `a whitelist keeps only its categories, in their existing order`() {
        val kept = CategoryFiltering.applyWhitelist(cats, setOf("news", "uk")) { it.key }

        assertThat(kept.map { it.key }).containsExactly("uk", "news").inOrder()
        assertThat(CategoryFiltering.excludedKeys(cats, setOf("news", "uk")) { it.key }).containsExactly("sport")
    }

    @Test
    fun `a whitelist naming none of these categories never blanks the rail`() {
        val other = setOf("someone-elses-category")

        assertThat(CategoryFiltering.applyWhitelist(cats, other) { it.key }).isEqualTo(cats)
        assertThat(CategoryFiltering.excludedKeys(cats, other) { it.key }).isEmpty()
    }

    @Test
    fun `ticking every category collapses back to show-all`() {
        val scope = setOf("uk", "sport", "news")

        assertThat(CategoryFiltering.mergeWhitelist(setOf("uk"), scope, scope)).isEmpty()
    }

    @Test
    fun `saving one provider's ticks leaves another provider's keys alone`() {
        val merged = CategoryFiltering.mergeWhitelist(
            saved = setOf("1:a", "2:x"),
            scopeKeys = setOf("1:a", "1:b"),
            selected = setOf("1:b"),
        )

        assertThat(merged).containsExactly("2:x", "1:b")
    }

    @Test
    fun `trimming from show-all pins just the ticked categories`() {
        val merged = CategoryFiltering.mergeWhitelist(emptySet(), setOf("uk", "sport", "news"), setOf("news"))

        assertThat(merged).containsExactly("news")
    }

    @Test
    fun `channels file under their first letter, everything else under hash`() {
        assertThat(indexLetter("bbc one")).isEqualTo('B')
        assertThat(indexLetter("  Sky")).isEqualTo('S')
        assertThat(indexLetter("5 Star")).isEqualTo('#')
        assertThat(indexLetter("")).isEqualTo('#')
    }
}
