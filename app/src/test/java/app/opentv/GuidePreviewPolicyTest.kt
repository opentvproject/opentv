/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.AppSettings
import app.opentv.ui.channels.GuidePreviewPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuidePreviewPolicyTest {
    @Test
    fun currentModeKeepsThePlayingChannel() {
        assertEquals(
            42L,
            GuidePreviewPolicy.channelId(
                AppSettings.GuidePreviewMode.CURRENT_CHANNEL,
                currentChannelId = 42L,
                highlightedChannelId = 99L,
            ),
        )
    }

    @Test
    fun currentModeFallsBackToHighlightBeforeFirstPlayback() {
        assertEquals(
            99L,
            GuidePreviewPolicy.channelId(
                AppSettings.GuidePreviewMode.CURRENT_CHANNEL,
                currentChannelId = 0L,
                highlightedChannelId = 99L,
            ),
        )
    }

    @Test
    fun highlightedModeFollowsBrowsing() {
        assertEquals(
            99L,
            GuidePreviewPolicy.channelId(
                AppSettings.GuidePreviewMode.HIGHLIGHTED_CHANNEL,
                currentChannelId = 42L,
                highlightedChannelId = 99L,
            ),
        )
    }

    @Test
    fun noChannelIsChosenBeforePlaybackOrGuideData() {
        assertNull(
            GuidePreviewPolicy.channelId(
                AppSettings.GuidePreviewMode.CURRENT_CHANNEL,
                currentChannelId = 0L,
                highlightedChannelId = null,
            ),
        )
    }

    @Test
    fun watchOpensTheChannelShownInThePreview() {
        assertEquals(
            42L,
            GuidePreviewPolicy.watchChannelId(
                previewVisible = true,
                previewChannelId = 42L,
                highlightedChannelId = 99L,
            ),
        )
    }

    @Test
    fun watchUsesHighlightWhenPreviewIsHidden() {
        assertEquals(
            99L,
            GuidePreviewPolicy.watchChannelId(
                previewVisible = false,
                previewChannelId = 42L,
                highlightedChannelId = 99L,
            ),
        )
    }

    @Test
    fun watchFallsBackToHighlightWhilePreviewTargetIsUnavailable() {
        assertEquals(
            99L,
            GuidePreviewPolicy.watchChannelId(
                previewVisible = true,
                previewChannelId = null,
                highlightedChannelId = 99L,
            ),
        )
    }
}
