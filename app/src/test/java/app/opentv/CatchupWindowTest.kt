/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Channel
import app.opentv.data.model.catchupCovers
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The archive window: how far back the provider still keeps a recording.
 *
 * A catch-up link the provider answers with a 404 reads as a broken app, not as an archive
 * that stops seven days back. The depth is known on both kinds of provider — Xtream reports
 * `tv_archive_duration`, a playlist says `catchup-days` — and until now it was stored and
 * never read, so every finished programme offered a link.
 */
class CatchupWindowTest {

    private val now = 1_800_000_000_000L
    private val day = TimeUnit.DAYS.toMillis(1)

    private fun channel(tvArchive: Boolean, tvArchiveDays: Int) = Channel(
        sourceId = 1,
        streamId = "42",
        name = "Channel",
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        tvArchive = tvArchive,
        tvArchiveDays = tvArchiveDays,
        number = null,
        streamUrl = "http://panel:8080/live/42.ts",
    )

    @Test
    fun `a channel with no archive covers nothing`() {
        assertThat(channel(tvArchive = false, tvArchiveDays = 7).catchupCovers(now - day, now)).isFalse()
    }

    @Test
    fun `an archive whose depth was never stated covers everything`() {
        // Zero days means the provider did not say, not that there is no archive. Refusing
        // here would take away the catch-up that works perfectly well.
        assertThat(channel(tvArchive = true, tvArchiveDays = 0).catchupCovers(now - 900 * day, now)).isTrue()
    }

    @Test
    fun `the whole archive covers a programme from last year`() {
        assertThat(channel(tvArchive = true, tvArchiveDays = -1).catchupCovers(now - 900 * day, now)).isTrue()
    }

    @Test
    fun `a programme inside the window is covered`() {
        assertThat(channel(tvArchive = true, tvArchiveDays = 7).catchupCovers(now - 3 * day, now)).isTrue()
    }

    @Test
    fun `a programme older than the window is not`() {
        assertThat(channel(tvArchive = true, tvArchiveDays = 7).catchupCovers(now - 10 * day, now)).isFalse()
    }

    @Test
    fun `the edge of the window still counts`() {
        assertThat(channel(tvArchive = true, tvArchiveDays = 7).catchupCovers(now - 7 * day, now)).isTrue()
    }
}
