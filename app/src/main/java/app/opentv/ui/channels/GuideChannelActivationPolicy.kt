/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

/** Pure remote-press policy kept outside Compose so short/long activation is unit-testable. */
internal object GuideChannelActivationPolicy {
    enum class Action {
        WATCH,
        OPEN_OPTIONS,
    }

    fun action(heldMillis: Long, systemReportedLongPress: Boolean): Action =
        if (systemReportedLongPress || heldMillis >= LONG_PRESS_MILLIS) {
            Action.OPEN_OPTIONS
        } else {
            Action.WATCH
        }

    const val LONG_PRESS_MILLIS = 500L
}
