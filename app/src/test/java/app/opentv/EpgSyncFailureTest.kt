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
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A guide download that dies half way through.
 *
 * The network does this more often than anyone admits: a panel that restarts mid-transfer,
 * a proxy that cuts the connection, a CDN that serves half a file. The guide that is
 * already on the phone is worth more than the one that was being fetched, so a failed sync
 * has to leave it alone — including not pruning it, which is the part that is easy to get
 * wrong: the prune runs when *any* feed succeeded, and a broken feed is not a reason to
 * throw away what the others found.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class EpgSyncFailureTest {

    private lateinit var db: OpenTvDatabase
    private lateinit var repo: EpgRepository

    private val now = 1_800_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    /** A guide that stops in the middle of a programme — the shape a cut connection takes. */
    private val truncatedGuide = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv generator-info-name="lab">
          <channel id="lab.one"><display-name>Lab One</display-name></channel>
          <programme start="20261020000000 +0000" stop="20261020010000 +0000" channel="lab.one">
            <title>Half a programme
    """.trimIndent()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OpenTvDatabase::class.java,
        ).allowMainThreadQueries().build()

        val http = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(truncatedGuide.toResponseBody("application/xml".toMediaType()))
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

    private suspend fun feed(): EpgFeed {
        db.epgFeeds().insert(EpgFeed(name = "Lab", url = "http://lab/guide.xml", enabled = true))
        return db.epgFeeds().all().first { it.name == "Lab" }
    }

    @Test
    fun `a guide that dies half way leaves the old one alone`() = runBlocking<Unit> {
        val feed = feed()
        db.programmes().upsertAll(
            listOf(
                Programme(
                    feedId = feed.id,
                    epgChannelId = "lab.one",
                    startUtcMillis = now - 2 * day,
                    endUtcMillis = now - 2 * day + 3600_000,
                    title = "The programme from last week",
                ),
            ),
        )

        val summary = repo.syncAll(now, force = true)

        assertThat(summary.feedsFailed).isEqualTo(1)
        assertThat(summary.feedsSucceeded).isEqualTo(0)
        // The old programme is still there — and it is older than the retention cut-off, so
        // its survival also proves the prune did not run.
        val titles = db.programmes().observeWindow(0, Long.MAX_VALUE).first().map { it.title }
        assertThat(titles).contains("The programme from last week")
    }

    @Test
    fun `a failed feed says why, and is retried on the next sync`() = runBlocking<Unit> {
        val feed = feed()

        repo.syncAll(now, force = true)

        val after = db.epgFeeds().all().first { it.id == feed.id }
        assertThat(after.lastResult).isNotEmpty()
        // The stamp is not advanced on a failure, so the next sync tries again instead of
        // waiting out the refresh interval on a feed that never landed.
        assertThat(after.lastSyncMillis).isEqualTo(0L)
    }
}
