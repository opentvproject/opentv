/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.remote.XtreamApi
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * What a failed request says to the user.
 *
 * A 404 from `player_api.php` is the one code that means two different things: the address
 * is wrong, or the provider's panel is down behind a CDN that answers 404 for everything.
 * The two call for opposite responses from the user — fix the address, or wait — so the
 * message has to tell them apart.
 */
class XtreamErrorMessageTest {

    private val api = XtreamApi(OkHttpClient())

    @Test
    fun `a 404 on a dead provider says the provider is down`() {
        val message = api.describeHttpFailure(404, rootStatus = 520)

        assertThat(message).contains("down")
        assertThat(message).contains("520")
    }

    @Test
    fun `a 404 on an address that answers says the address is wrong`() {
        val message = api.describeHttpFailure(404, rootStatus = 200)

        assertThat(message).contains("no Xtream API")
        assertThat(message).contains("200")
    }

    @Test
    fun `a 404 on an unreachable address says both things`() {
        val message = api.describeHttpFailure(404, rootStatus = null)

        assertThat(message).contains("could not be reached")
        assertThat(message).contains("probably down")
    }

    @Test
    fun `the other codes still say what they mean`() {
        assertThat(api.describeHttpFailure(401)).contains("username or password")
        assertThat(api.describeHttpFailure(403)).contains("User-Agent")
        assertThat(api.describeHttpFailure(405)).contains("plain playlist")
        assertThat(api.describeHttpFailure(429)).contains("rate-limiting")
        assertThat(api.describeHttpFailure(503)).contains("having problems")
        assertThat(api.describeHttpFailure(500)).contains("having problems")
        assertThat(api.describeHttpFailure(418)).contains("418")
    }
}
