/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.XtreamApi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Xtream client against a real panel's answers.
 *
 * The fixtures under `resources/fixtures` are what a live Brazilian panel actually returned:
 * its account response, its category list, its channel list and a slice of its guide. The
 * credentials are stripped and the host is `panel.example`; everything else — the field
 * names, the odd leading space in a category name, the empty `tv_archive`, the guide that
 * is 8 MB when asked for and empty when not — is exactly as it came.
 *
 * A hand-written response proves the client parses what its author imagined. A captured one
 * proves it parses what the server sends.
 */
@RunWith(RobolectricTestRunner::class)
// The app's own Application schedules a WorkManager job on start-up, and WorkManager is
// initialised by hand in the real app — so a plain Application is what a test wants.
@Config(application = Application::class, sdk = [33])
class XtreamRealResponseTest {

    private val requested = mutableListOf<String>()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) {
            "the fixture $name is missing"
        }.readText()

    private val http = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val url = chain.request().url
                requested += url.toString()
                val action = url.queryParameter("action")
                val body = when {
                    url.encodedPath.endsWith("/xmltv.php") -> fixture("epg.xml")
                    action == null -> fixture("player_api_auth.json")
                    action == "get_live_categories" -> fixture("live_categories.json")
                    action == "get_live_streams" -> fixture("live_streams.json")
                    else -> error("the test did not expect the action $action")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            },
        )
        .build()

    private val api = XtreamApi(http)

    private val source = Source(
        id = 1,
        name = "Painel",
        kind = SourceKind.XTREAM,
        url = "http://panel.example",
        username = "user",
        password = "pass",
    )

    @Test
    fun `the account is read, and with it the panel's own timezone`() = runBlocking<Unit> {
        val account = api.authenticate(source)

        assertThat(account.username).isEqualTo("<username>")
        assertThat(account.maxConnections).isEqualTo(1)
        // The panel's clock, which is what a catch-up URL has to be written in. A Brazilian
        // panel in Sao Paulo is three hours ahead of the device on the other side of the
        // world, and the archive is filed under the panel's hour.
        assertThat(account.timezone).isEqualTo("America/Sao_Paulo")
    }

    @Test
    fun `the categories are read, spaces and all`() = runBlocking<Unit> {
        val categories = api.liveCategories(source)

        assertThat(categories).isNotEmpty()
        // Real panels pad their names; the app is expected to cope.
        assertThat(categories.first().name).doesNotContain("  ")
        assertThat(categories.first().id).isNotEmpty()
    }

    @Test
    fun `the channels are read, with their guide id and their number`() = runBlocking<Unit> {
        val channels = api.liveStreams(source)

        assertThat(channels).isNotEmpty()
        val first = channels.first()
        assertThat(first.name).isNotEmpty()
        assertThat(first.streamId).isNotEmpty()
        assertThat(first.number).isNotNull()
        assertThat(first.epgChannelId).isNotEmpty()
        // This trial line records nothing, and the app has to believe it: a channel that
        // says it has no archive must not be offered one.
        assertThat(first.tvArchive).isFalse()
    }

    @Test
    fun `the guide is asked for with a period, because without one the panel answers empty`() =
        runBlocking<Unit> {
            // The panel this was captured from returns HTTP 200 and zero bytes for
            // xmltv.php unless the period is asked for — an empty guide, with no error to
            // notice. Asking is the whole difference between a guide and no guide.
            api.openEpgStream(source, prevDays = 3, nextDays = 2).use { stream ->
                val text = stream.bufferedReader().readText()
                assertThat(text).contains("<programme")
            }

            val guideRequest = requested.first { it.contains("xmltv.php") }
            assertThat(guideRequest).contains("prev_days=3")
            assertThat(guideRequest).contains("next_days=2")
        }
}
