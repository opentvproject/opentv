/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.ui.channels.GuideChannelActivationPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideChannelActivationPolicyTest {
    @Test
    fun shortPressWatchesImmediately() {
        assertEquals(
            GuideChannelActivationPolicy.Action.WATCH,
            GuideChannelActivationPolicy.action(
                heldMillis = GuideChannelActivationPolicy.LONG_PRESS_MILLIS - 1L,
                systemReportedLongPress = false,
            ),
        )
    }

    @Test
    fun thresholdPressOpensOptions() {
        assertEquals(
            GuideChannelActivationPolicy.Action.OPEN_OPTIONS,
            GuideChannelActivationPolicy.action(
                heldMillis = GuideChannelActivationPolicy.LONG_PRESS_MILLIS,
                systemReportedLongPress = false,
            ),
        )
    }

    @Test
    fun systemLongPressFlagOpensOptionsEvenBeforeThreshold() {
        assertEquals(
            GuideChannelActivationPolicy.Action.OPEN_OPTIONS,
            GuideChannelActivationPolicy.action(
                heldMillis = 100L,
                systemReportedLongPress = true,
            ),
        )
    }
}
