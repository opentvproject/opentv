/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Category
import app.opentv.data.model.StreamKind
import app.opentv.ui.foldLiveCategories
import app.opentv.ui.isVodCategoryHidden
import app.opentv.ui.reorderVisibleCategories
import app.opentv.ui.sortLiveCategories
import app.opentv.ui.sortVodCategories
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CategoryCustomizationTest {

    @Test
    fun `reorders live categories according to user custom order`() {
        val raw = listOf(
            Category(id = "1", sourceId = 1L, name = "UK| General HD", kind = StreamKind.LIVE),
            Category(id = "2", sourceId = 1L, name = "UK| Sports HD", kind = StreamKind.LIVE),
            Category(id = "3", sourceId = 1L, name = "UK| News HD", kind = StreamKind.LIVE),
            Category(id = "4", sourceId = 1L, name = "UK| Movies HD", kind = StreamKind.LIVE),
        )
        val folded = foldLiveCategories(raw)

        // User wants News first, then Movies, then Sports
        val userOrder = listOf("news", "movies", "sports")
        val sorted = sortLiveCategories(folded, userOrder)

        val keys = sorted.map { it.key }
        assertThat(keys).containsExactly("news", "movies", "sports", "general").inOrder()
    }

    @Test
    fun `hiding live category filters it and gathers underlying ids`() {
        val raw = listOf(
            Category(id = "10", sourceId = 1L, name = "UK| General SD", kind = StreamKind.LIVE),
            Category(id = "11", sourceId = 1L, name = "UK| General HD", kind = StreamKind.LIVE),
            Category(id = "20", sourceId = 1L, name = "UK| Sports HD", kind = StreamKind.LIVE),
            Category(id = "30", sourceId = 1L, name = "Adult Channels", kind = StreamKind.LIVE),
        )
        val folded = foldLiveCategories(raw)

        val hiddenLiveKeys = setOf("general")
        val adultHiddenKeys = setOf("adultchannels")

        // In guide: both user-hidden and adult-hidden are filtered
        val visible = folded.filter { it.key !in hiddenLiveKeys && it.key !in adultHiddenKeys }
        assertThat(visible.map { it.key }).containsExactly("sports")

        // Underlying IDs for hidden categories to filter channels
        val hiddenIds = folded
            .filter { it.key in hiddenLiveKeys || it.key in adultHiddenKeys }
            .flatMap { it.ids }
            .toSet()
        assertThat(hiddenIds).containsExactly("10", "11", "30")
    }

    @Test
    fun `reorders and hides VOD categories`() {
        val vodCategories = listOf(
            Category(id = "action", sourceId = 1L, name = "Action", kind = StreamKind.MOVIE, sortIndex = 0),
            Category(id = "comedy", sourceId = 1L, name = "Comedy", kind = StreamKind.MOVIE, sortIndex = 1),
            Category(id = "drama", sourceId = 1L, name = "Drama", kind = StreamKind.MOVIE, sortIndex = 2),
            Category(id = "horror", sourceId = 1L, name = "Horror", kind = StreamKind.MOVIE, sortIndex = 3),
        )

        val hiddenVod = setOf("horror")
        val customOrder = listOf("drama", "action", "comedy")

        val visible = vodCategories.filterNot { isVodCategoryHidden(it, hiddenVod) }
        val sorted = sortVodCategories(visible, customOrder)

        assertThat(sorted.map { it.id }).containsExactly("drama", "action", "comedy").inOrder()
    }

    @Test
    fun `reorders and hides Shows series categories`() {
        val seriesCategories = listOf(
            Category(id = "s_doc", sourceId = 1L, name = "Documentary", kind = StreamKind.SERIES, sortIndex = 0),
            Category(id = "s_sci", sourceId = 1L, name = "Sci-Fi", kind = StreamKind.SERIES, sortIndex = 1),
            Category(id = "s_ani", sourceId = 1L, name = "Animation", kind = StreamKind.SERIES, sortIndex = 2),
        )

        val hiddenSeries = setOf("s_ani")
        val customOrder = listOf("s_sci", "s_doc")

        val visible = seriesCategories.filterNot { isVodCategoryHidden(it, hiddenSeries) }
        val sorted = sortVodCategories(visible, customOrder)

        assertThat(sorted.map { it.id }).containsExactly("s_sci", "s_doc").inOrder()
    }

    @Test
    fun `moving category up and down shifts order correctly`() {
        val initial = listOf("catA", "catB", "catC", "catD")
        val movedUp = reorderVisibleCategories(initial, emptyList(), "catC", -1)
        assertThat(movedUp).containsExactly("catA", "catC", "catB", "catD").inOrder()

        val movedDown = reorderVisibleCategories(movedUp.orEmpty(), emptyList(), "catA", 1)
        assertThat(movedDown).containsExactly("catC", "catA", "catB", "catD").inOrder()
    }

    @Test
    fun `reordering a filtered source preserves categories from other sources`() {
        val visible = listOf("source-1:news", "source-1:sports")
        val saved = listOf("source-2:movies", "source-1:news", "source-1:sports", "source-3:shows")

        val reordered = reorderVisibleCategories(visible, saved, "source-1:sports", -1)

        assertThat(reordered).containsExactly(
            "source-2:movies",
            "source-1:sports",
            "source-1:news",
            "source-3:shows",
        ).inOrder()
    }
}
