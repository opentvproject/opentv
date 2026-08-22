/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.player.AdaptiveLivePolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdaptiveLivePolicyTest {

    @Test
    fun `policy applies only to live HLS urls`() {
        assertThat(AdaptiveLivePolicy.appliesTo(true, "https://example.com/live/1.m3u8?token=x"))
            .isTrue()
        assertThat(AdaptiveLivePolicy.appliesTo(true, "https://example.com/live/1.M3U8"))
            .isTrue()
        assertThat(AdaptiveLivePolicy.appliesTo(true, "https://example.com/live/1.ts"))
            .isFalse()
        assertThat(AdaptiveLivePolicy.appliesTo(false, "https://example.com/vod/1.m3u8"))
            .isFalse()
    }

    @Test
    fun `new channels start at the safety target`() {
        assertThat(AdaptiveLivePolicy.targetFor(null)).isEqualTo(30_000L)
    }

    @Test
    fun `a rebuffer moves only that channel one safety step farther back`() {
        assertThat(AdaptiveLivePolicy.targetAfterRebuffer(30_000L)).isEqualTo(35_000L)
        assertThat(AdaptiveLivePolicy.targetAfterRebuffer(35_000L)).isEqualTo(40_000L)
    }

    @Test
    fun `broken or stale saved values are bounded`() {
        assertThat(AdaptiveLivePolicy.targetFor(5_000L)).isEqualTo(30_000L)
        assertThat(AdaptiveLivePolicy.targetFor(90_000L)).isEqualTo(60_000L)
        assertThat(AdaptiveLivePolicy.targetAfterRebuffer(60_000L)).isEqualTo(60_000L)
    }
}
