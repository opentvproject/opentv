/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.ui.formatBehind
import app.opentv.ui.formatDuration
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The times the transport bar prints.
 *
 * Two players show them and they have to agree, and the live one has a second form: how far
 * behind the live edge the picture is, which is the one number a viewer of live TV actually
 * wants to know.
 */
class TimeFormatTest {

    @Test
    fun `a duration reads as minutes and seconds`() {
        assertThat(formatDuration(0)).isEqualTo("0:00")
        assertThat(formatDuration(-1)).isEqualTo("0:00")
        assertThat(formatDuration(1_000)).isEqualTo("0:01")
        assertThat(formatDuration(65_000)).isEqualTo("1:05")
        assertThat(formatDuration(599_000)).isEqualTo("9:59")
    }

    @Test
    fun `a duration past the hour keeps the hours`() {
        assertThat(formatDuration(3_600_000)).isEqualTo("1:00:00")
        assertThat(formatDuration(3_725_000)).isEqualTo("1:02:05")
    }

    @Test
    fun `being behind the live edge reads as a negative offset`() {
        assertThat(formatBehind(0)).isEqualTo("-0:00")
        assertThat(formatBehind(30_000)).isEqualTo("-0:30")
        assertThat(formatBehind(125_000)).isEqualTo("-2:05")
    }
}
