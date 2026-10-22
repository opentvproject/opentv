/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Programme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The query behind the catch-up list: the programmes a channel has already finished.
 *
 * The guide grid starts at the current half-hour, so the past is only ever reachable
 * through this query. Getting its bounds wrong is quiet — a list that is empty looks like a
 * provider with no archive, and a list that reaches too far offers recordings the provider
 * has already deleted.
 */
@RunWith(RobolectricTestRunner::class)
// The app's own Application schedules a WorkManager job on start-up, and WorkManager is
// initialised by hand in the real app — so a plain Application is what a database test wants.
@Config(application = Application::class, sdk = [33])
class FinishedProgrammesTest {

    private lateinit var db: OpenTvDatabase

    private val now = 1_800_000_000_000L
    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OpenTvDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun programme(
        epgChannelId: String,
        startUtcMillis: Long,
        title: String,
    ) = Programme(
        feedId = 1,
        epgChannelId = epgChannelId,
        startUtcMillis = startUtcMillis,
        endUtcMillis = startUtcMillis + hour,
        title = title,
    )

    private suspend fun insert(vararg items: Programme) {
        db.programmes().upsertAll(items.toList())
    }

    @Test
    fun `the finished programmes come back newest first`() = runBlocking<Unit> {
        insert(
            programme("news", now - 5 * hour, "Earlier"),
            programme("news", now - 2 * hour, "Later"),
        )

        val finished = db.programmes().finished("news", now, now - day, 10)

        assertThat(finished.map { it.title }).containsExactly("Later", "Earlier").inOrder()
    }

    @Test
    fun `a programme still running is not finished`() = runBlocking<Unit> {
        insert(programme("news", now - 30 * 60_000L, "On now"))

        val finished = db.programmes().finished("news", now, now - day, 10)

        assertThat(finished).isEmpty()
    }

    @Test
    fun `a programme that has not started is not finished`() = runBlocking<Unit> {
        insert(programme("news", now + hour, "Later today"))

        val finished = db.programmes().finished("news", now, now - day, 10)

        assertThat(finished).isEmpty()
    }

    @Test
    fun `programmes older than the retention window are left out`() = runBlocking<Unit> {
        insert(programme("news", now - 3 * day, "Ancient"))

        val finished = db.programmes().finished("news", now, now - day, 10)

        assertThat(finished).isEmpty()
    }

    @Test
    fun `another channel's programmes are not mixed in`() = runBlocking<Unit> {
        insert(
            programme("news", now - hour, "News"),
            programme("sport", now - hour, "Sport"),
        )

        val finished = db.programmes().finished("news", now, now - day, 10)

        assertThat(finished.map { it.title }).containsExactly("News")
    }

    @Test
    fun `the list is capped at the limit asked for`() = runBlocking<Unit> {
        insert(*(1..6).map { programme("news", now - it * hour, "Programme $it") }.toTypedArray())

        val finished = db.programmes().finished("news", now, now - day, 3)

        assertThat(finished).hasSize(3)
    }
}
