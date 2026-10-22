/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.BufferPreset
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The buffer presets, and the constraints ExoPlayer's load control puts on them.
 *
 * A preset that breaks the load control's rules is not a preset that stutters; it is a
 * preset that throws, and it would only throw on the box the user switched to it *for*.
 */
class BufferPresetTest {

    @Test
    fun `default preserves the tuning the app had`() {
        val preset = BufferPreset.DEFAULT

        assertThat(preset.minBufferMillis).isEqualTo(15_000)
        assertThat(preset.maxBufferMillis).isEqualTo(60_000)
        assertThat(preset.playbackBufferMillis).isEqualTo(2_500)
        assertThat(preset.rebufferMillis).isEqualTo(5_000)
    }

    @Test
    fun `the presets meet the load control's constraints`() {
        BufferPreset.entries.forEach { preset ->
            assertThat(preset.playbackBufferMillis).isGreaterThan(0)
            assertThat(preset.rebufferMillis).isAtLeast(preset.playbackBufferMillis)
            assertThat(preset.minBufferMillis).isAtLeast(preset.playbackBufferMillis)
            assertThat(preset.minBufferMillis).isAtLeast(preset.rebufferMillis)
            assertThat(preset.maxBufferMillis).isAtLeast(preset.minBufferMillis)
            // A buffer is memory the box has to find. Two minutes is already a lot on a
            // cheap one, and the DVR window lives in the same memory.
            assertThat(preset.maxBufferMillis).isAtMost(120_000)
        }
    }

    @Test
    fun `the presets grow in order`() {
        val ordered = listOf(
            BufferPreset.SMALL,
            BufferPreset.MEDIUM,
            BufferPreset.DEFAULT,
            BufferPreset.LARGE,
            BufferPreset.EXTRA_LARGE,
        )

        ordered.zipWithNext().forEach { (smaller, bigger) ->
            assertThat(smaller.maxBufferMillis).isLessThan(bigger.maxBufferMillis)
        }
    }

    @Test
    fun `a missing or unknown stored value falls back to the default`() {
        assertThat(BufferPreset.fromStoredValue(null)).isEqualTo(BufferPreset.DEFAULT)
        assertThat(BufferPreset.fromStoredValue("")).isEqualTo(BufferPreset.DEFAULT)
        assertThat(BufferPreset.fromStoredValue("A_PRESET_FROM_THE_FUTURE")).isEqualTo(BufferPreset.DEFAULT)
        BufferPreset.entries.forEach {
            assertThat(BufferPreset.fromStoredValue(it.name)).isEqualTo(it)
        }
    }
}
