/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import app.opentv.core.AppSettings
import app.opentv.core.BufferPreset
import app.opentv.core.DecoderPreference
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The playback knobs, and that they survive being set.
 *
 * Both are read by the player when a playback session opens, so what matters is that the
 * value on disk is the one the next session will see — including the first one after an
 * upgrade, where the preference has never been written.
 *
 * The two halves are tested apart on purpose. The live value and the stored value are
 * written by different code paths, and `apply()` writes the file on the next main-loop pass,
 * so a test that wrote one and read the other inside a single pass would be testing the
 * scheduler rather than the setting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PlaybackSettingsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun stored(): SharedPreferences =
        context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        stored().edit().clear().commit()
    }

    @Test
    fun `an install that never chose a buffer gets the default`() {
        assertThat(AppSettings.savedBufferPreset(context)).isEqualTo(BufferPreset.DEFAULT)
    }

    @Test
    fun `the chosen buffer is the live value`() {
        val settings = AppSettings.get(context)

        settings.setBufferPreset(BufferPreset.EXTRA_LARGE)
        assertThat(settings.bufferPreset.value).isEqualTo(BufferPreset.EXTRA_LARGE)

        settings.setBufferPreset(BufferPreset.SMALL)
        assertThat(settings.bufferPreset.value).isEqualTo(BufferPreset.SMALL)
    }

    @Test
    fun `the chosen buffer is what the next session reads`() {
        stored().edit().putString("buffer_preset", BufferPreset.LARGE.name).commit()

        assertThat(AppSettings.savedBufferPreset(context)).isEqualTo(BufferPreset.LARGE)
    }

    @Test
    fun `a corrupt stored buffer falls back to the default`() {
        stored().edit().putString("buffer_preset", "A_PRESET_FROM_THE_FUTURE").commit()

        assertThat(AppSettings.savedBufferPreset(context)).isEqualTo(BufferPreset.DEFAULT)
    }

    @Test
    fun `the decoder preference is the live value`() {
        val settings = AppSettings.get(context)

        settings.setDecoderPreference(DecoderPreference.SOFTWARE_FIRST)
        assertThat(settings.decoderPreference.value).isEqualTo(DecoderPreference.SOFTWARE_FIRST)

        settings.setDecoderPreference(DecoderPreference.AUTO)
        assertThat(settings.decoderPreference.value).isEqualTo(DecoderPreference.AUTO)
    }

    @Test
    fun `the decoder preference is what the next session reads`() {
        assertThat(AppSettings.savedDecoderPreference(context)).isEqualTo(DecoderPreference.AUTO)

        stored().edit().putString("decoder_preference", DecoderPreference.SOFTWARE_FIRST.name).commit()
        assertThat(AppSettings.savedDecoderPreference(context)).isEqualTo(DecoderPreference.SOFTWARE_FIRST)
    }

    @Test
    fun `a corrupt stored decoder falls back to the platform's`() {
        stored().edit().putString("decoder_preference", "QUANTUM").commit()

        assertThat(AppSettings.savedDecoderPreference(context)).isEqualTo(DecoderPreference.AUTO)
    }
}
