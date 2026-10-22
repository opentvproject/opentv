/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.XtreamApi
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/**
 * The Xtream catch-up URL, and the clock it is written on.
 *
 * The panel keeps its archive as `/tv_archive/{stream_id}/YYYY-MM-DD:HH-mm.ts` and looks that
 * name up against *its* clock, so the start time in the URL has to be the panel's local time.
 * A device in São Paulo asking a Paris panel for "19:00" gets a recording from three hours
 * earlier — the wrong programme, or none at all.
 *
 * The zone is pinned to São Paulo on purpose: the two zones are deliberately three hours
 * apart, so a mistake here cannot hide.
 */
class XtreamCatchupUrlTest {

    private lateinit var originalZone: TimeZone
    private val api = XtreamApi(OkHttpClient())

    // 22:00 in São Paulo (UTC-3), and 00:00 the next day in Paris (UTC+2 in October).
    private val start = Instant.parse("2026-10-20T22:00:00Z").toEpochMilli()

    @Before
    fun pinTheZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"))
    }

    @After
    fun restoreTheZone() {
        TimeZone.setDefault(originalZone)
    }

    private fun source(timezone: String?) = Source(
        id = 1,
        name = "Panel",
        kind = SourceKind.XTREAM,
        url = "http://panel:8080",
        username = "user",
        password = "pass",
        timezone = timezone,
    )

    @Test
    fun `the start time is written on the panel's clock`() {
        val url = api.catchupUrl(source("Europe/Paris"), "42", start, 60)

        assertThat(url).isEqualTo("http://panel:8080/timeshift/user/pass/60/2026-10-21:00-00/42.ts")
    }

    @Test
    fun `a panel that does not say where it is uses the device's clock`() {
        // The same behaviour as before the fix, and the right one whenever the two agree.
        val url = api.catchupUrl(source(null), "42", start, 60)

        assertThat(url).isEqualTo("http://panel:8080/timeshift/user/pass/60/2026-10-20:19-00/42.ts")
    }

    @Test
    fun `a zone nobody has heard of is ignored, not guessed at`() {
        // Guessing UTC would be worse than the old behaviour: it would be wrong even for a
        // provider in the same city as the viewer.
        val url = api.catchupUrl(source("Mars/Olympus"), "42", start, 60)

        assertThat(url).isEqualTo("http://panel:8080/timeshift/user/pass/60/2026-10-20:19-00/42.ts")
    }

    @Test
    fun `an unknown zone falls back to the device's, a known one does not`() {
        assertThat(XtreamApi.zoneOf("Europe/Paris")).isEqualTo(TimeZone.getTimeZone("Europe/Paris"))
        assertThat(XtreamApi.zoneOf(null)).isEqualTo(TimeZone.getDefault())
        assertThat(XtreamApi.zoneOf("  ")).isEqualTo(TimeZone.getDefault())
        assertThat(XtreamApi.zoneOf("Mars/Olympus")).isEqualTo(TimeZone.getDefault())
    }

    @Test
    fun `the duration is never zero minutes`() {
        val url = api.catchupUrl(source("Europe/Paris"), "42", start, 0)

        assertThat(url).contains("/1/")
    }
}
