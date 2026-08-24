/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import app.opentv.core.AppSettings

/** Pure selection policy kept outside Compose so the guide-preview contract is unit-testable. */
internal object GuidePreviewPolicy {
    fun channelId(
        mode: AppSettings.GuidePreviewMode,
        currentChannelId: Long,
        highlightedChannelId: Long?,
    ): Long? = when (mode) {
        AppSettings.GuidePreviewMode.CURRENT_CHANNEL ->
            currentChannelId.takeIf { it > 0L } ?: highlightedChannelId
        AppSettings.GuidePreviewMode.HIGHLIGHTED_CHANNEL -> highlightedChannelId
    }

    /** The Watch control belongs to the video card, so it must open the video actually shown. */
    fun watchChannelId(
        previewVisible: Boolean,
        previewChannelId: Long?,
        highlightedChannelId: Long?,
    ): Long? = if (previewVisible) {
        previewChannelId ?: highlightedChannelId
    } else {
        highlightedChannelId
    }
}
