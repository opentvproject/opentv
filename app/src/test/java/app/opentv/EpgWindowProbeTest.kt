/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A panel that only answers for the window it generated.
 *
 * The panel this was captured from serves its guide for exactly one window — asked for
 * another, it answers HTTP 200 with an empty body and no error to notice. The guide is
 * then simply absent, and the app has no way of telling that from a provider with no guide
 * at all, unless it asks again.
 *
 * So it does: the windows are tried in turn, and the empty answers cost nothing, because
 * an empty guide is a zero-byte file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class EpgWindowProbeTest {

    private lateinit var db: OpenTvDatabase
    private lateinit var repo: EpgRepository
    private val asked = mutableListOf<String>()
    private val now = 1_800_000_000_000L

    /** The guide, with a programme that finished two hours before "now". */
    private fun guide(now: Long): String {
        val start = now - 2 * 3600_000L
        val stop = start + 3600_000L
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv generator-info-name="panel.example">
              <channel id="um.canal"><display-name>Um Canal</display-name></channel>
              <programme start="${stamp(start)}" stop="${stamp(stop)}" channel="um.canal">
                <title>Um Programa</title>
              </programme>
            </tv>
        """.trimIndent()
    }

    private fun stamp(millis: Long): String {
        val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(millis))
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OpenTvDatabase::class.java,
        ).allowMainThreadQueries().build()

        val http = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val url = chain.request().url
                    asked += url.toString()
                    val body = when {
                        url.encodedPath.endsWith("/xmltv.php") -> {
                            // The guide only exists for this one window.
                            if (url.queryParameter("prev_days") == "3" &&
                                url.queryParameter("next_days") == "1"
                            ) {
                                guide(now)
                            } else {
                                ""
                            }
                        }
                        else -> """{"user_info":{"username":"u","status":"Active"},
                            "server_info":{"url":"panel.example","timezone":"America/Sao_Paulo"}}"""
                    }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/xml".toMediaType()))
                        .build()
                },
            )
            .build()

        repo = EpgRepository(
            programmeDao = db.programmes(),
            feedDao = db.epgFeeds(),
            aliasDao = db.epgAliases(),
            channelDao = db.channels(),
            sourceDao = db.sources(),
            api = XtreamApi(http),
            http = http,
            settings = AppSettings.get(ApplicationProvider.getApplicationContext()),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a panel that answers for one window only is asked again until it answers`() =
        runBlocking<Unit> {
            db.sources().insert(
                Source(
                    id = 1,
                    name = "Painel",
                    kind = SourceKind.XTREAM,
                    url = "http://panel.example",
                    username = "u",
                    password = "p",
                ),
            )

            repo.syncAll(now, force = true)

            // The window that has the guide was found, and the guide arrived.
            val programmes = db.programmes().observeWindow(0, Long.MAX_VALUE).first()
            assertThat(programmes.map { it.title }).contains("Um Programa")
            assertThat(asked.any { it.contains("prev_days=3") && it.contains("next_days=1") }).isTrue()
        }
}
