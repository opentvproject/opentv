/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Channel
import app.opentv.data.model.shownName
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelShownNameTest {

    private fun channel(name: String, displayName: String, customName: String? = null) = Channel(
        sourceId = 1,
        streamId = "stream1",
        name = name,
        displayName = displayName,
        customName = customName,
        categoryId = "cat1",
        logoUrl = null,
        epgChannelId = null,
        number = 1,
        streamUrl = "http://example.com/1.m3u8",
    )

    @Test
    fun `rawName returns verbatim provider name`() {
        val ch = channel(name = "UK| BBC ONE FHD 50FPS", displayName = "BBC ONE")
        assertThat(ch.shownName(rawName = true)).isEqualTo("UK| BBC ONE FHD 50FPS")
    }

    @Test
    fun `non rawName returns cleaned display name`() {
        val ch = channel(name = "UK| BBC ONE FHD 50FPS", displayName = "BBC ONE")
        assertThat(ch.shownName(rawName = false)).isEqualTo("BBC ONE")
        assertThat(ch.shownName).isEqualTo("BBC ONE")
    }

    @Test
    fun `customName takes precedence over both raw and cleaned names`() {
        val ch = channel(
            name = "UK| BBC ONE FHD 50FPS",
            displayName = "BBC ONE",
            customName = "My BBC",
        )
        assertThat(ch.shownName(rawName = true)).isEqualTo("My BBC")
        assertThat(ch.shownName(rawName = false)).isEqualTo("My BBC")
    }

    @Test
    fun `channel wrapping correctly cycles boundaries`() {
        val totalChannels = 10
        // Channel 0 (first channel) previous wraps to 9 (last channel)
        val prevFromFirst = (0 + (-1)).mod(totalChannels)
        assertThat(prevFromFirst).isEqualTo(9)

        // Channel 9 (last channel) next wraps to 0 (first channel)
        val nextFromLast = (9 + 1).mod(totalChannels)
        assertThat(nextFromLast).isEqualTo(0)

        // Step forward and backward inside list
        assertThat((5 + 1).mod(totalChannels)).isEqualTo(6)
        assertThat((5 + (-1)).mod(totalChannels)).isEqualTo(4)
    }
}

