/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.ui.channels.GuideDirectionRepeatGate
import app.opentv.ui.channels.GuideVerticalFocusBoundary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideDirectionRepeatGateTest {
    @Test
    fun firstDirectionPressIsImmediate() {
        val gate = GuideDirectionRepeatGate()

        assertFalse(gate.shouldConsume(GuideDirectionRepeatGate.Direction.UP, 1_000L))
    }

    @Test
    fun rapidSameDirectionRepeatsAreCoalesced() {
        val gate = GuideDirectionRepeatGate(minimumIntervalMillis = 90L)

        assertFalse(gate.shouldConsume(GuideDirectionRepeatGate.Direction.UP, 1_000L))
        assertTrue(gate.shouldConsume(GuideDirectionRepeatGate.Direction.UP, 1_020L))
        assertTrue(gate.shouldConsume(GuideDirectionRepeatGate.Direction.UP, 1_089L))
        assertFalse(gate.shouldConsume(GuideDirectionRepeatGate.Direction.UP, 1_090L))
    }

    @Test
    fun changingDirectionIsNeverDelayed() {
        val gate = GuideDirectionRepeatGate(minimumIntervalMillis = 90L)

        assertFalse(gate.shouldConsume(GuideDirectionRepeatGate.Direction.UP, 1_000L))
        assertFalse(gate.shouldConsume(GuideDirectionRepeatGate.Direction.DOWN, 1_001L))
        assertFalse(gate.shouldConsume(GuideDirectionRepeatGate.Direction.UP, 1_002L))
    }

    @Test
    fun resetAllowsImmediateSameDirectionPress() {
        val gate = GuideDirectionRepeatGate(minimumIntervalMillis = 90L)

        assertFalse(gate.shouldConsume(GuideDirectionRepeatGate.Direction.UP, 1_000L))
        gate.reset()
        assertFalse(gate.shouldConsume(GuideDirectionRepeatGate.Direction.UP, 1_001L))
    }

    @Test
    fun verticalBoundariesContainOnlyTheOutwardDirection() {
        assertTrue(
            GuideVerticalFocusBoundary.shouldConsume(
                blockUp = true,
                blockDown = false,
                direction = GuideDirectionRepeatGate.Direction.UP,
            ),
        )
        assertFalse(
            GuideVerticalFocusBoundary.shouldConsume(
                blockUp = true,
                blockDown = false,
                direction = GuideDirectionRepeatGate.Direction.DOWN,
            ),
        )
        assertTrue(
            GuideVerticalFocusBoundary.shouldConsume(
                blockUp = false,
                blockDown = true,
                direction = GuideDirectionRepeatGate.Direction.DOWN,
            ),
        )
    }
}
