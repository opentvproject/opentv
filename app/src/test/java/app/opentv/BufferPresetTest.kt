/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.BufferPreset
import org.junit.Assert.*
import org.junit.Test

class BufferPresetTest {
    @Test fun `default preserves existing playback timing`() {
        val preset = BufferPreset.DEFAULT
        assertEquals(15_000, preset.minBufferMillis)
        assertEquals(60_000, preset.maxBufferMillis)
        assertEquals(2_500, preset.playbackBufferMillis)
        assertEquals(5_000, preset.rebufferMillis)
    }

    @Test fun `presets meet load control constraints and remain bounded`() {
        BufferPreset.entries.forEach { preset ->
            assertTrue(preset.playbackBufferMillis > 0)
            assertTrue(preset.rebufferMillis >= preset.playbackBufferMillis)
            assertTrue(preset.minBufferMillis >= preset.playbackBufferMillis)
            assertTrue(preset.minBufferMillis >= preset.rebufferMillis)
            assertTrue(preset.maxBufferMillis >= preset.minBufferMillis)
            assertTrue(preset.maxBufferMillis <= 120_000)
        }
        val ordered = listOf(BufferPreset.SMALL, BufferPreset.MEDIUM, BufferPreset.DEFAULT,
            BufferPreset.LARGE, BufferPreset.EXTRA_LARGE)
        ordered.zipWithNext().forEach { (a, b) ->
            assertTrue(a.maxBufferMillis < b.maxBufferMillis)
        }
    }

    @Test fun `missing and unknown stored presets fall back safely`() {
        assertEquals(BufferPreset.DEFAULT, BufferPreset.fromStoredValue(null))
        assertEquals(BufferPreset.DEFAULT, BufferPreset.fromStoredValue(""))
        assertEquals(BufferPreset.DEFAULT, BufferPreset.fromStoredValue("FUTURE_PRESET"))
        BufferPreset.entries.forEach {
            assertEquals(it, BufferPreset.fromStoredValue(it.name))
        }
    }
}
