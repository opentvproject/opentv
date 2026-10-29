/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.model.StreamKind
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Subtitle and category preferences: each setter must emit on its flow straight away and survive a
 * restart. "Restart" is a second [AppSettings] built over the same prefs file, since the shared
 * singleton would only ever hand back the in-memory values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppSettingsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun freshSettings(): AppSettings =
        AppSettings::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context)

    @Test
    fun `subtitle defaults are white, 20sp, outlined, no box`() {
        val s = freshSettings()

        assertThat(s.subtitleTextSizeSp.value).isEqualTo(20)
        assertThat(s.subtitleTextColor.value).isEqualTo(AppSettings.SubtitleColor.WHITE)
        assertThat(s.subtitleBackground.value).isEqualTo(AppSettings.SubtitleBackground.NONE)
        assertThat(s.subtitleEdge.value).isEqualTo(AppSettings.SubtitleEdge.OUTLINE)
    }

    @Test
    fun `subtitle style emits immediately and survives a restart`() {
        val s = freshSettings()
        s.setSubtitleTextSizeSp(26)
        s.setSubtitleTextColor(AppSettings.SubtitleColor.YELLOW)
        s.setSubtitleBackground(AppSettings.SubtitleBackground.SOLID)
        s.setSubtitleEdge(AppSettings.SubtitleEdge.DROP_SHADOW)

        assertThat(s.subtitleTextSizeSp.value).isEqualTo(26)
        assertThat(s.subtitleTextColor.value).isEqualTo(AppSettings.SubtitleColor.YELLOW)

        val restarted = freshSettings()
        assertThat(restarted.subtitleTextSizeSp.value).isEqualTo(26)
        assertThat(restarted.subtitleTextColor.value).isEqualTo(AppSettings.SubtitleColor.YELLOW)
        assertThat(restarted.subtitleBackground.value).isEqualTo(AppSettings.SubtitleBackground.SOLID)
        assertThat(restarted.subtitleEdge.value).isEqualTo(AppSettings.SubtitleEdge.DROP_SHADOW)
    }

    @Test
    fun `subtitle size is clamped to the supported range`() {
        val s = freshSettings()

        s.setSubtitleTextSizeSp(4)
        assertThat(s.subtitleTextSizeSp.value).isEqualTo(AppSettings.SUBTITLE_SIZE_MIN)
        s.setSubtitleTextSizeSp(99)
        assertThat(s.subtitleTextSizeSp.value).isEqualTo(AppSettings.SUBTITLE_SIZE_MAX)
    }

    @Test
    fun `an unknown stored value falls back to the default`() {
        context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE).edit()
            .putString("subtitle_text_color", "MAGENTA")
            .putString("category_sort_order", "BY_VIBES")
            .commit()

        val s = freshSettings()
        assertThat(s.subtitleTextColor.value).isEqualTo(AppSettings.SubtitleColor.WHITE)
        assertThat(s.categorySortOrder.value).isEqualTo(AppSettings.CategorySortOrder.DEFAULT)
    }

    @Test
    fun `category sort order persists`() {
        freshSettings().setCategorySortOrder(AppSettings.CategorySortOrder.NAME_DESC)

        assertThat(freshSettings().categorySortOrder.value).isEqualTo(AppSettings.CategorySortOrder.NAME_DESC)
    }

    @Test
    fun `category whitelists are kept per content type`() {
        val s = freshSettings()
        s.setVisibleCategoryKeys(StreamKind.LIVE, setOf("news", "sport"))

        assertThat(s.visibleCategoryKeys(StreamKind.LIVE).value).containsExactly("news", "sport")
        assertThat(s.visibleCategoryKeys(StreamKind.MOVIE).value).isEmpty()

        val restarted = freshSettings()
        assertThat(restarted.visibleCategoryKeys(StreamKind.LIVE).value).containsExactly("news", "sport")
        assertThat(restarted.visibleCategoryKeys(StreamKind.SERIES).value).isEmpty()
    }
}
