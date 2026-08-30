/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

/**
 * Caps held vertical navigation at a rate the guide can render without building an input backlog.
 *
 * Android TV remotes are not consistent about repeat metadata: some preserve one key down-time and
 * increment repeatCount, while others emit a rapid series of ordinary key downs. Event time is the
 * reliable common signal, so repeated key downs in the same direction are coalesced regardless of
 * how the remote labels them. A direction change is always accepted immediately.
 */
internal class GuideDirectionRepeatGate(
    private val minimumIntervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) {
    enum class Direction { UP, DOWN }

    private var lastAcceptedDirection: Direction? = null
    private var lastAcceptedEventTimeMillis: Long = Long.MIN_VALUE

    fun shouldConsume(direction: Direction, eventTimeMillis: Long): Boolean {
        val elapsed = eventTimeMillis - lastAcceptedEventTimeMillis
        if (direction != lastAcceptedDirection || elapsed < 0L || elapsed >= minimumIntervalMillis) {
            lastAcceptedDirection = direction
            lastAcceptedEventTimeMillis = eventTimeMillis
            return false
        }
        return true
    }

    fun reset() {
        lastAcceptedDirection = null
        lastAcceptedEventTimeMillis = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 90L
    }
}

internal object GuideVerticalFocusBoundary {
    fun shouldConsume(
        blockUp: Boolean,
        blockDown: Boolean,
        direction: GuideDirectionRepeatGate.Direction,
    ): Boolean =
        (blockUp && direction == GuideDirectionRepeatGate.Direction.UP) ||
            (blockDown && direction == GuideDirectionRepeatGate.Direction.DOWN)
}
