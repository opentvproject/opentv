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
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The curated built-in guide list, and what happens when the host renames a file.
 *
 * These guides live on someone else's server, and that server renumbers them now and then —
 * the US and Canadian ones moved from 1 to 2, which left the app pointing at files that
 * answer 404. The settings screen can only report the failure; the fix has to happen here.
 */
@RunWith(RobolectricTestRunner::class)
// The app's own Application schedules a WorkManager job on start-up, and WorkManager is
// initialised by hand in the real app — so a plain Application is what a database test wants.
@Config(application = Application::class, sdk = [33])
class BuiltInFeedsTest {

    private lateinit var db: OpenTvDatabase
    private lateinit var repo: EpgRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OpenTvDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = EpgRepository(
            programmeDao = db.programmes(),
            feedDao = db.epgFeeds(),
            aliasDao = db.epgAliases(),
            channelDao = db.channels(),
            sourceDao = db.sources(),
            api = XtreamApi(OkHttpClient()),
            http = OkHttpClient(),
            settings = AppSettings.get(
                ApplicationProvider.getApplicationContext(),
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun feeds() = db.epgFeeds().all()

    @Test
    fun `the built-in feeds are created, off by default`() = runBlocking<Unit> {
        repo.ensureFeeds()

        val builtIns = feeds().filter { it.builtIn }
        assertThat(builtIns).isNotEmpty()
        assertThat(builtIns.none { it.enabled }).isTrue()
    }

    @Test
    fun `a feed the host renamed is repointed, not duplicated`() = runBlocking<Unit> {
        // What an install that ran before the rename has in the database: the right name,
        // a file that no longer exists, and the user having switched it on.
        db.epgFeeds().insert(
            EpgFeed(
                name = "USA — epgshare01",
                url = "https://epgshare01.online/epgshare01/epg_ripper_US1.xml.gz",
                builtIn = true,
                enabled = true,
                lastSyncMillis = 1234L,
                lastResult = "HTTP 404",
            ),
        )

        repo.ensureFeeds()

        val usa = feeds().filter { it.name == "USA — epgshare01" }
        assertThat(usa).hasSize(1)
        assertThat(usa.single().url).endsWith("epg_ripper_US2.xml.gz")
        // The user's choice survives, and the stale failure message does not.
        assertThat(usa.single().enabled).isTrue()
        assertThat(usa.single().lastResult).isEmpty()
        // The stamp is cleared so the next sync fetches the new file instead of waiting
        // out the refresh interval on a URL that is dead.
        assertThat(usa.single().lastSyncMillis).isEqualTo(0L)
    }

    @Test
    fun `a guide the user added themselves is not duplicated`() = runBlocking<Unit> {
        val url = "https://epgshare01.online/epgshare01/epg_ripper_BR1.xml.gz"
        db.epgFeeds().insert(EpgFeed(name = "Meu guia", url = url, enabled = true))

        repo.ensureFeeds()

        assertThat(feeds().count { it.url == url }).isEqualTo(1)
    }

    @Test
    fun `every built-in points at a file the server actually has`() = runBlocking<Unit> {
        // The names the host serves, from its own index.
        val served = setOf("UK1", "US2", "CA2", "AU1")
        val epgshare = EpgRepository.BUILT_IN_FEEDS.filter { it.second.contains("epg_ripper_") }
        assertThat(epgshare).isNotEmpty()

        val numbers = epgshare.map { it.second.substringAfter("epg_ripper_").substringBefore(".xml.gz") }
        assertThat(numbers).containsExactlyElementsIn(served)
    }
}
