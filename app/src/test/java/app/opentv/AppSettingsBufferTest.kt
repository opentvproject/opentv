/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.content.Context
import app.opentv.core.AppSettings
import app.opentv.core.BufferPreset
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppSettingsBufferTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `existing installs default safely without a saved preference`() {
        assertEquals(BufferPreset.DEFAULT, AppSettings.savedBufferPreset(context))
        assertEquals(BufferPreset.DEFAULT, AppSettings.get(context).bufferPreset.value)
    }

    @Test fun `selection updates flow and survives a fresh preference read`() {
        val settings = AppSettings.get(context)
        settings.setBufferPreset(BufferPreset.EXTRA_LARGE)
        assertEquals(BufferPreset.EXTRA_LARGE, settings.bufferPreset.value)
        assertEquals(BufferPreset.EXTRA_LARGE, AppSettings.savedBufferPreset(context))
        settings.setBufferPreset(BufferPreset.DEFAULT)
        assertEquals(BufferPreset.DEFAULT, settings.bufferPreset.value)
        assertEquals(BufferPreset.DEFAULT, AppSettings.savedBufferPreset(context))
    }

    @Test fun `unknown or corrupt persisted values use default`() {
        val prefs = context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("buffer_preset", "FUTURE_PRESET").commit()
        assertEquals(BufferPreset.DEFAULT, AppSettings.savedBufferPreset(context))
        prefs.edit().putInt("buffer_preset", -1).commit()
        assertEquals(BufferPreset.DEFAULT, AppSettings.savedBufferPreset(context))
    }
}
