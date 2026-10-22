/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.CatchupDefaults
import app.opentv.data.parser.M3uCatchup
import app.opentv.data.parser.catchupDefaultsOf
import app.opentv.data.parser.catchupTagsOf
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/**
 * Catch-up URL building, against the examples the reference implementation documents.
 *
 * Every string here comes from Kodi's IPTV Simple Client — its README examples and the channel
 * setup in `Channel::ConfigureCatchupMode`. Those are the playlists real providers ship, so these
 * are the shapes that have to come out the other side.
 *
 * The zone is pinned to São Paulo on purpose: the time inside a catch-up URL is what makes these
 * break abroad, and a UTC-3 device is where an off-by-three-hours mistake would first show up.
 */
class M3uCatchupTest {

    private lateinit var originalZone: TimeZone

    private val start = Instant.parse("2026-10-20T22:00:00Z").toEpochMilli() // 19:00 in São Paulo
    private val end = start + 60 * 60 * 1000
    private val now = Instant.parse("2026-10-21T02:00:00Z").toEpochMilli()  // four hours later

    @Before
    fun pinTheZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"))
    }

    @After
    fun restoreTheZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `reads the catchup tags, including the ones spelled differently`() {
        assertThat(M3uCatchup.modeOf("default")).isEqualTo(M3uCatchup.Mode.DEFAULT)
        assertThat(M3uCatchup.modeOf("Append")).isEqualTo(M3uCatchup.Mode.APPEND)
        assertThat(M3uCatchup.modeOf("shift")).isEqualTo(M3uCatchup.Mode.SHIFT)
        assertThat(M3uCatchup.modeOf("fs")).isEqualTo(M3uCatchup.Mode.FLUSSONIC)
        assertThat(M3uCatchup.modeOf("flussonic-ts")).isEqualTo(M3uCatchup.Mode.FLUSSONIC)
        assertThat(M3uCatchup.modeOf("xc")).isEqualTo(M3uCatchup.Mode.XTREAM_CODES)
        assertThat(M3uCatchup.modeOf("vod")).isEqualTo(M3uCatchup.Mode.VOD)
    }

    @Test
    fun `an unknown mode is no mode, not a guess`() {
        assertThat(M3uCatchup.modeOf("maybe")).isNull()
        assertThat(M3uCatchup.modeOf("")).isNull()
        assertThat(M3uCatchup.modeOf(null)).isNull()
    }

    @Test
    fun `append puts the playlist's own source after the stream URL`() {
        val source = M3uCatchup.sourceFor(
            M3uCatchup.Mode.APPEND,
            "&cutv={Y}-{m}-{d}T{H}:{M}:{S}",
            "http://host/live/channel-c.ts",
        )

        assertThat(source).isEqualTo("http://host/live/channel-c.ts&cutv={Y}-{m}-{d}T{H}:{M}:{S}")
    }

    @Test
    fun `append with no source of its own has no catch-up to build`() {
        // Kodi falls back to its "query format string" setting here. We have no such setting, so the
        // honest answer is that this channel has no catch-up — not a query invented on its behalf.
        val source = M3uCatchup.sourceFor(M3uCatchup.Mode.APPEND, null, "http://host/live/c.ts")

        assertThat(source).isNull()
    }

    @Test
    fun `shift appends the SIPTV query, with the right separator`() {
        assertThat(M3uCatchup.sourceFor(M3uCatchup.Mode.SHIFT, null, "http://host/live/c.ts"))
            .isEqualTo("http://host/live/c.ts?utc={utc}&lutc={lutc}")
        assertThat(M3uCatchup.sourceFor(M3uCatchup.Mode.SHIFT, null, "http://host/live/c.ts?token=x"))
            .isEqualTo("http://host/live/c.ts?token=x&utc={utc}&lutc={lutc}")
    }

    @Test
    fun `the legacy timeshift tag is the same as shift`() {
        assertThat(M3uCatchup.sourceFor(M3uCatchup.Mode.TIMESHIFT, null, "http://host/live/c.ts"))
            .isEqualTo("http://host/live/c.ts?utc={utc}&lutc={lutc}")
    }

    @Test
    fun `xc rebuilds the timeshift path from the account in the stream URL`() {
        assertThat(M3uCatchup.sourceFor(M3uCatchup.Mode.XTREAM_CODES, null, "http://list.tv:8080/my@account.xc/my_password/1477"))
            .isEqualTo("http://list.tv:8080/timeshift/my@account.xc/my_password/{duration:60}/{Y}-{m}-{d}:{H}-{M}/1477.ts")
    }

    @Test
    fun `xc keeps the extension and skips the live segment`() {
        assertThat(M3uCatchup.sourceFor(M3uCatchup.Mode.XTREAM_CODES, null, "http://list.tv:8080/live/my@account.xc/my_password/1477.m3u8"))
            .isEqualTo("http://list.tv:8080/timeshift/my@account.xc/my_password/{duration:60}/{Y}-{m}-{d}:{H}-{M}/1477.m3u8")
    }

    @Test
    fun `flussonic mpegts archive goes back by absolute time`() {
        val source = M3uCatchup.sourceFor(
            M3uCatchup.Mode.FLUSSONIC,
            null,
            "http://ch01.spr24.net/151/mpegts?token=my_token",
        )

        assertThat(source).isEqualTo("http://ch01.spr24.net/151/timeshift_abs-\${start}.ts?token=my_token")
    }

    @Test
    fun `flussonic index playlist shifts relative to now`() {
        assertThat(M3uCatchup.sourceFor(M3uCatchup.Mode.FLUSSONIC, null, "http://list.tv:8888/325/index.m3u8?token=secret"))
            .isEqualTo("http://list.tv:8888/325/timeshift_rel-{offset:1}.m3u8?token=secret")
    }

    @Test
    fun `flussonic named playlist keeps its own name`() {
        assertThat(M3uCatchup.sourceFor(M3uCatchup.Mode.FLUSSONIC, null, "http://list.tv:8888/325/mono.m3u8?token=secret"))
            .isEqualTo("http://list.tv:8888/325/mono-timeshift_rel-{offset:1}.m3u8?token=secret")
    }

    @Test
    fun `flussonic on a server that accepts any path follows the tag's stream type`() {
        assertThat(M3uCatchup.sourceFor(M3uCatchup.Mode.FLUSSONIC, null, "http://list.tv:8888/325/live?token=my_token", flussonicTs = true))
            .isEqualTo("http://list.tv:8888/325/timeshift_abs-\${start}.ts?token=my_token")
        assertThat(M3uCatchup.sourceFor(M3uCatchup.Mode.FLUSSONIC, null, "http://list.tv:8888/325/live?token=my_token", flussonicTs = false))
            .isEqualTo("http://list.tv:8888/325/timeshift_rel-{offset:1}.m3u8?token=my_token")
    }

    @Test
    fun `a stream URL that is neither xc nor flussonic gets no catch-up`() {
        assertThat(M3uCatchup.sourceFor(M3uCatchup.Mode.XTREAM_CODES, null, "udp://@239.239.3.38:5239")).isNull()
        assertThat(M3uCatchup.modeOf("default")).isNotNull()
    }

    @Test
    fun `fills the time, the duration in minutes and the offset in the template`() {
        val url = M3uCatchup.buildUrl(
            "http://host/ts?utc={utc}&end={utcend}&duration={duration:60}&offset={offset:60}",
            start, end, now,
        )

        // 1792533600 is 2026-10-20T22:00:00Z; an hour of programme; four hours back to now.
        assertThat(url).isEqualTo(
            "http://host/ts?utc=${start / 1000}&end=${end / 1000}&duration=60&offset=240",
        )
    }

    @Test
    fun `the correction moves the instant, as Kodi does it`() {
        // A provider whose archive runs three hours behind its own guide:
        // catchup-correction="-3.0" -> the URL asks for a time three hours later.
        val url = M3uCatchup.buildUrl(
            "http://host/ts/{Y}-{m}-{d}:{H}-{M}",
            start, end, now,
            correctionSecs = -3 * 3600,
        )

        assertThat(url).isEqualTo("http://host/ts/2026-10-20:22-00")
    }

    @Test
    fun `the format specifier writes the instant in the device's zone`() {
        assertThat(M3uCatchup.buildUrl("http://host/ts/{utc:Ymd-HM}", start, end, now))
            .isEqualTo("http://host/ts/20261020-1900")
        assertThat(M3uCatchup.buildUrl("http://host/ts/\${start:Y-m-dT H:M:S}", start, end, now))
            .isEqualTo("http://host/ts/2026-10-20T 19:00:00")
    }

    @Test
    fun `the time is written on the provider's clock when the provider has one`() {
        // A panel that keeps its archive on its own clock names the recording on that clock
        // too. Paris is two hours ahead of São Paulo in October, so the same instant is a
        // different hour on each.
        val url = M3uCatchup.buildUrl(
            "http://host/ts/{Y}-{m}-{d}:{H}-{M}",
            start, end, now,
            zone = TimeZone.getTimeZone("Europe/Paris"),
        )

        assertThat(url).isEqualTo("http://host/ts/2026-10-21:00-00")
    }

    @Test
    fun `the parts are case-sensitive, so the month is not the minute`() {
        assertThat(M3uCatchup.buildUrl("http://host/{Y}/{m}/{d}/{H}/{M}/{S}", start, end, now))
            .isEqualTo("http://host/2026/10/20/19/00/00")
    }

    @Test
    fun `a token we cannot fill is left where it is`() {
        // {catchup-id} comes from the XMLTV entry and OpenTV does not model it. Leaving it visible
        // fails loudly; emptying it would silently ask the provider for the wrong programme.
        val url = M3uCatchup.buildUrl("http://host/ts?id={catchup-id}&utc={utc}", start, end, now)

        assertThat(url).isEqualTo("http://host/ts?id={catchup-id}&utc=${start / 1000}")
    }

    @Test
    fun `the older timeshift spelling is a mode of its own`() {
        assertThat(M3uCatchup.modeOf("timeshift")).isEqualTo(M3uCatchup.Mode.TIMESHIFT)
    }

    @Test
    fun `a channel that says nothing about catch-up has none`() {
        assertThat(catchupTagsOf(mapOf("tvg-id" to "a"), CatchupDefaults())).isNull()
    }

    @Test
    fun `the catchup-type spelling is read as the catchup tag`() {
        val tags = catchupTagsOf(mapOf("catchup-type" to "append", "catchup-source" to "&utc={utc}"), CatchupDefaults())

        assertThat(tags?.mode).isEqualTo(M3uCatchup.Mode.APPEND)
        assertThat(tags?.declaredSource).isEqualTo("&utc={utc}")
    }

    @Test
    fun `SIPTV's timeshift attribute is the shift mode`() {
        val tags = catchupTagsOf(mapOf("timeshift" to "3"), CatchupDefaults())

        assertThat(tags?.mode).isEqualTo(M3uCatchup.Mode.TIMESHIFT)
        // A timeshift of "0" means the provider offers no timeshift at all.
        assertThat(catchupTagsOf(mapOf("timeshift" to "0"), CatchupDefaults())).isNull()
    }

    @Test
    fun `a source with no mode of its own is the default mode`() {
        val tags = catchupTagsOf(mapOf("catchup-source" to "http://host/ts?utc={utc}"), CatchupDefaults())

        assertThat(tags?.mode).isEqualTo(M3uCatchup.Mode.DEFAULT)
        assertThat(tags?.declaredSource).isEqualTo("http://host/ts?utc={utc}")
    }

    @Test
    fun `the archive's depth in days is read from the playlist`() {
        val tags = catchupTagsOf(mapOf("catchup" to "default", "catchup-days" to "7"), CatchupDefaults())

        assertThat(tags?.days).isEqualTo(7)
    }

    @Test
    fun `no limit on the archive is not the same as none`() {
        // Kodi reads catchup-days="0" as "the whole archive", and the VOD mode is the whole
        // archive by definition.
        assertThat(catchupTagsOf(mapOf("catchup" to "default", "catchup-days" to "0"), CatchupDefaults())?.days)
            .isEqualTo(M3uCatchup.IGNORE_CATCHUP_DAYS)
        assertThat(catchupTagsOf(mapOf("catchup" to "vod"), CatchupDefaults())?.days)
            .isEqualTo(M3uCatchup.IGNORE_CATCHUP_DAYS)
    }

    @Test
    fun `the correction is read in hours, including the fractional ones`() {
        val tags = catchupTagsOf(mapOf("catchup" to "default", "catchup-correction" to "-3.5"), CatchupDefaults())

        assertThat(tags?.correctionSecs).isEqualTo(-(3 * 3600 + 1800))
    }

    @Test
    fun `the channel's own tags win over the playlist's`() {
        val defaults = CatchupDefaults(modeTag = "shift", days = 7, correctionSecs = 3600)
        val tags = catchupTagsOf(mapOf("catchup" to "default"), defaults)

        assertThat(tags?.mode).isEqualTo(M3uCatchup.Mode.DEFAULT)
        // The channel said nothing about the depth or the correction, so the playlist's stand.
        assertThat(tags?.days).isEqualTo(7)
        assertThat(tags?.correctionSecs).isEqualTo(3600)
    }

    @Test
    fun `the playlist's own tags are read from its header`() {
        val defaults = catchupDefaultsOf(
            mapOf(
                "catchup" to "flussonic-ts",
                "catchup-source" to "http://host/ch/timeshift_abs-\${start}.ts",
                "catchup-days" to "3",
                "catchup-correction" to "1.0",
            ),
        )

        assertThat(defaults.modeTag).isEqualTo("flussonic-ts")
        assertThat(defaults.days).isEqualTo(3)
        assertThat(defaults.correctionSecs).isEqualTo(3600)

        val tags = catchupTagsOf(mapOf(), defaults)
        assertThat(tags?.mode).isEqualTo(M3uCatchup.Mode.FLUSSONIC)
        assertThat(tags?.tsStream).isTrue()
    }
}