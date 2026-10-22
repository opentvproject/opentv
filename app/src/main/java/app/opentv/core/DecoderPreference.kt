/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/**
 * Which decoder the player should reach for.
 *
 * AUTO leaves it to the platform, which is right almost always: the hardware decoder is
 * faster and, on most boxes, correct.
 *
 * SOFTWARE_FIRST is the escape hatch for the boxes whose hardware decoder is not: a
 * particular codec comes out stuttering or green, the app gets blamed for it, and the
 * workaround everyone finds is to decode in software instead. Slower, but correct.
 */
enum class DecoderPreference {
    AUTO,
    SOFTWARE_FIRST;

    companion object {
        fun fromStoredValue(value: String?): DecoderPreference =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}
