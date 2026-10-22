/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.content.Context
import app.opentv.core.AppSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * How much guide the app keeps.
 *
 * The number the user picks is a ceiling, not a promise: a provider only serves what its
 * own archive holds. What the app must not do is silently keep less than it was asked
 * for, or ask the panel for more than it is going to keep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class GuideHistoryTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun stored() =
        context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        stored().edit().clear().commit()
    }

    @Test
    fun `a day is what an install gets by default`() {
        assertThat(AppSettings.savedGuideHistory(context)).isEqualTo(AppSettings.GuideHistory.ONE_DAY)
        assertThat(AppSettings.GuideHistory.ONE_DAY.days).isEqualTo(1)
    }

    @Test
    fun `the choices are a day, three and seven`() {
        assertThat(AppSettings.GuideHistory.THREE_DAYS.days).isEqualTo(3)
        assertThat(AppSettings.GuideHistory.SEVEN_DAYS.days).isEqualTo(7)
    }

    @Test
    fun `the chosen history is the live value and what the next sync reads`() {
        val settings = AppSettings.get(context)

        settings.setGuideHistory(AppSettings.GuideHistory.SEVEN_DAYS)
        assertThat(settings.guideHistory.value).isEqualTo(AppSettings.GuideHistory.SEVEN_DAYS)

        stored().edit().putString("guide_history", AppSettings.GuideHistory.THREE_DAYS.name).commit()
        assertThat(AppSettings.savedGuideHistory(context)).isEqualTo(AppSettings.GuideHistory.THREE_DAYS)
    }

    @Test
    fun `a corrupt stored value falls back to a day`() {
        stored().edit().putString("guide_history", "AN_EON_FROM_NOW").commit()

        assertThat(AppSettings.savedGuideHistory(context)).isEqualTo(AppSettings.GuideHistory.ONE_DAY)
    }
}
