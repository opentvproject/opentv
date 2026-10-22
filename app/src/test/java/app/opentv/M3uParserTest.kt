/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.M3uParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * These tests are built from the shapes real providers actually emit, including the broken
 * ones. Every "malformed" case here is something that should cost the user one channel, never
 * the whole playlist.
 */
class M3uParserTest {

    @Test
    fun `parses a well formed playlist`() {
        val playlist = """
            #EXTM3U url-tvg="http://example.com/xmltv.php?username=u&password=p"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://img/bbc1.png" group-title="UK",BBC One HD
            http://example.com:8080/live/u/p/1.m3u8
            #EXTINF:-1 tvg-id="itv1.uk" tvg-logo="http://img/itv.png" group-title="UK",ITV 1
            http://example.com:8080/live/u/p/2.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 7)

        assertThat(result.channels).hasSize(2)
        assertThat(result.declaredEpgUrl)
            .isEqualTo("http://example.com/xmltv.php?username=u&password=p")

        val first = result.channels.first()
        assertThat(first.name).isEqualTo("BBC One HD")
        assertThat(first.epgChannelId).isEqualTo("bbc1.uk")
        assertThat(first.categoryId).isEqualTo("UK")
        assertThat(first.logoUrl).isEqualTo("http://img/bbc1.png")
        assertThat(first.sourceId).isEqualTo(7)
        assertThat(first.streamUrl).isEqualTo("http://example.com:8080/live/u/p/1.m3u8")
    }

    @Test
    fun `a URL with no EXTINF is skipped without losing the rest`() {
        val playlist = """
            #EXTM3U
            http://example.com/orphan.m3u8
            #EXTINF:-1 tvg-id="good",Good Channel
            http://example.com/good.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 1)

        assertThat(result.skippedEntries).isEqualTo(1)
        assertThat(result.channels).hasSize(1)
        assertThat(result.channels.single().name).isEqualTo("Good Channel")
    }

    @Test
    fun `falls back to tvg-name when the display name after the comma is empty`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="x" tvg-name="Fallback Name",
            http://example.com/x.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.name).isEqualTo("Fallback Name")
    }

    @Test
    fun `quality variants under one tvg-id are both kept`() {
        // Providers routinely list SD and HD variants under one tvg-id. The unique index on
        // (sourceId, streamId) would otherwise abort the entire import — and dropping the
        // second one left the quality switch with nothing to switch between.
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="dup",Channel SD
            http://example.com/sd.m3u8
            #EXTINF:-1 tvg-id="dup",Channel HD
            http://example.com/hd.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 1)

        assertThat(result.channels).hasSize(2)
        assertThat(result.channels.map { it.name }).containsExactly("Channel SD", "Channel HD").inOrder()
        // Distinct ids, or the import dies on the unique index.
        assertThat(result.channels[0].streamId).isNotEqualTo(result.channels[1].streamId)
    }

    @Test
    fun `two different channels sharing a tvg-id are both kept`() {
        // The bug in issue #11: a playlist where the provider reuses one id across
        // unrelated channels came out with fewer channels than every other player showed.
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="sport" group-title="Sports",Sport One
            http://example.com/one.m3u8
            #EXTINF:-1 tvg-id="sport" group-title="Sports",Sport Two
            http://example.com/two.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 1)

        assertThat(result.channels).hasSize(2)
        assertThat(result.channels.map { it.name }).containsExactly("Sport One", "Sport Two").inOrder()
    }

    @Test
    fun `the same channel listed twice is still one channel`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="dup" group-title="UK",BBC One
            http://example.com/one.m3u8
            #EXTINF:-1 tvg-id="dup" group-title="UK",BBC One
            http://example.com/two.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 1)

        assertThat(result.channels).hasSize(1)
        assertThat(result.channels.single().name).isEqualTo("BBC One")
    }

    @Test
    fun `the same channel in two categories is two entries`() {
        // The same channel listed under two categories is one channel the user sees twice,
        // not one entry — the categories are how the list is browsed.
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc1" group-title="UK",BBC One
            http://example.com/uk.m3u8
            #EXTINF:-1 tvg-id="bbc1" group-title="UK VIP",BBC One
            http://example.com/vip.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 1)

        assertThat(result.channels).hasSize(2)
        assertThat(result.channels.map { it.categoryId }).containsExactly("UK", "UK VIP").inOrder()
    }

    @Test
    fun `the first entry with a tvg-id keeps the plain id`() {
        // Favourites, renames and guide matches are carried across a refresh by the stream
        // id. A user who has already marked a channel as a favourite must not lose it to
        // this change.
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc1.uk",BBC One
            http://example.com/one.m3u8
            #EXTINF:-1 tvg-id="bbc1.uk",BBC One HD
            http://example.com/hd.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 1)

        assertThat(result.channels[0].streamId).isEqualTo("tvg:bbc1.uk")
    }

    @Test
    fun `stream ids are stable across parses`() {
        // An unstable id means favourites are lost on every refresh.
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc1.uk",BBC One
            http://example.com/one.m3u8
            #EXTINF:-1 tvg-id="bbc1.uk",BBC One HD
            http://example.com/hd.m3u8
        """.trimIndent()

        val first = M3uParser.parse(playlist, sourceId = 1).channels
        val second = M3uParser.parse(playlist, sourceId = 1).channels

        assertThat(first.map { it.streamId }).isEqualTo(second.map { it.streamId })
    }

    @Test
    fun `channels without a tvg-id still get a stable id derived from the url`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,No Id Channel
            http://example.com/noid.m3u8
        """.trimIndent()

        val first = M3uParser.parse(playlist, sourceId = 1).channels.single()
        val second = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(first.streamId).startsWith("url:")
        // Stability matters: an unstable id means favourites are lost on every refresh.
        assertThat(first.streamId).isEqualTo(second.streamId)
        assertThat(first.epgChannelId).isNull()
    }

    @Test
    fun `unknown directives between EXTINF and the url are ignored`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",Channel A
            #EXTVLCOPT:http-user-agent=SomePlayer/1.0
            #EXTHTTP:{"User-Agent":"SomePlayer/1.0"}
            http://example.com/a.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(playlist, sourceId = 1).channels

        assertThat(channels).hasSize(1)
        assertThat(channels.single().streamUrl).isEqualTo("http://example.com/a.m3u8")
    }

    @Test
    fun `channel numbers come from tvg-chno when present`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" tvg-chno="101",Channel A
            http://example.com/a.m3u8
        """.trimIndent()

        assertThat(M3uParser.parse(playlist, sourceId = 1).channels.single().number).isEqualTo(101)
    }

    @Test
    fun `an empty playlist yields no channels rather than throwing`() {
        val result = M3uParser.parse("#EXTM3U\n", sourceId = 1)

        assertThat(result.channels).isEmpty()
        assertThat(result.skippedEntries).isEqualTo(0)
    }

    @Test
    fun `a channel with no catch-up tags has no catch-up`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",Channel A
            http://example.com/a.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.tvArchive).isFalse()
        assertThat(channel.catchupSource).isNull()
        assertThat(channel.catchupMode).isNull()
        assertThat(channel.catchupCorrectionSecs).isEqualTo(0)
    }

    @Test
    fun `the catch-up tags land on the channel, template and all`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" catchup="append" catchup-source="&cutv={Y}-{m}-{d}T{H}:{M}:{S}" catchup-days="7",Channel A
            http://example.com/a.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.tvArchive).isTrue()
        assertThat(channel.catchupMode).isEqualTo("append")
        assertThat(channel.catchupSource)
            .isEqualTo("http://example.com/a.m3u8&cutv={Y}-{m}-{d}T{H}:{M}:{S}")
        assertThat(channel.tvArchiveDays).isEqualTo(7)
    }

    @Test
    fun `the playlist's own catch-up tags are the default for its channels`() {
        val playlist = """
            #EXTM3U catchup="shift" catchup-correction="-3.0"
            #EXTINF:-1 tvg-id="a",Channel A
            http://example.com/a.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.tvArchive).isTrue()
        assertThat(channel.catchupMode).isEqualTo("shift")
        assertThat(channel.catchupSource)
            .isEqualTo("http://example.com/a.m3u8?utc={utc}&lutc={lutc}")
        // The playlist writes the correction in hours; the app keeps it in seconds.
        assertThat(channel.catchupCorrectionSecs).isEqualTo(-3 * 3600)
    }

    @Test
    fun `a channel's own catch-up tags beat the playlist's`() {
        val playlist = """
            #EXTM3U catchup="shift"
            #EXTINF:-1 tvg-id="a" catchup="default" catchup-source="http://example.com/a.ts?utc={utc}",Channel A
            http://example.com/a.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.catchupMode).isEqualTo("default")
        assertThat(channel.catchupSource).isEqualTo("http://example.com/a.ts?utc={utc}")
    }

    @Test
    fun `a catch-up mode we do not know leaves the channel without catch-up`() {
        // Better no catch-up than a URL assembled from a mode we did not understand.
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" catchup="maybe" catchup-source="http://example.com/a.ts?utc={utc}",Channel A
            http://example.com/a.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.tvArchive).isFalse()
        assertThat(channel.catchupSource).isNull()
    }
}
