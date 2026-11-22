/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.PlaybackPosition
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackPositionTest {
    @Test
    fun `watched requires progress to be greater than 95 percent`() {
        val atThreshold = PlaybackPosition(1, "movie:1", 950, 1_000, 1)
        val beyondThreshold = PlaybackPosition(1, "movie:2", 951, 1_000, 2)

        assertThat(atThreshold.isFinished).isFalse()
        assertThat(beyondThreshold.isFinished).isTrue()
    }
}