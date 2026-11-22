/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Episode
import app.opentv.data.model.PlaybackPosition
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EpisodeHistoryDaoTest {
    private lateinit var database: OpenTvDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            OpenTvDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `next episode stays within source and series and crosses seasons`() {
        runBlocking {
            database.episodes().upsertAll(
                listOf(
                    episode(id = 1, sourceId = 1, seriesId = "a", season = 1, number = 1),
                    episode(id = 2, sourceId = 1, seriesId = "a", season = 1, number = 2),
                    episode(id = 3, sourceId = 1, seriesId = "a", season = 2, number = 1),
                    episode(id = 4, sourceId = 2, seriesId = "a", season = 1, number = 2),
                    episode(id = 5, sourceId = 1, seriesId = "b", season = 1, number = 2),
                ),
            )

            assertThat(database.episodes().nextForSeries(1, "a", 1, 1)?.id).isEqualTo(2L)
            assertThat(database.episodes().nextForSeries(1, "a", 1, 2)?.id).isEqualTo(3L)
            assertThat(database.episodes().nextForSeries(1, "a", 2, 1)).isNull()
        }
    }

    @Test
    fun `clearing a show and individual movie preserves unrelated profiles and titles`() {
        runBlocking {
            database.episodes().upsertAll(
                listOf(
                    episode(id = 10, sourceId = 1, seriesId = "a", season = 1, number = 1),
                    episode(id = 11, sourceId = 1, seriesId = "a", season = 1, number = 2),
                    episode(id = 12, sourceId = 1, seriesId = "b", season = 1, number = 1),
                    episode(id = 13, sourceId = 2, seriesId = "a", season = 1, number = 1),
                ),
            )
            listOf(
                position(profileId = 1, key = "ep:10"),
                position(profileId = 1, key = "ep:11"),
                position(profileId = 1, key = "ep:12"),
                position(profileId = 1, key = "ep:13"),
                position(profileId = 1, key = "movie:20"),
                position(profileId = 2, key = "ep:10"),
            ).forEach { database.positions().upsert(it) }

            database.positions().deleteForSeries(profileId = 1, sourceId = 1, seriesId = "a")
            assertThat(historyKeys()).containsExactly("1:ep:12", "1:ep:13", "1:movie:20", "2:ep:10")

            database.positions().delete(profileId = 1, key = "movie:20")
            assertThat(historyKeys()).containsExactly("1:ep:12", "1:ep:13", "2:ep:10")

            database.positions().deleteForProfile(1)
            assertThat(historyKeys()).containsExactly("2:ep:10")
        }
    }

    private suspend fun historyKeys(): List<String> =
        database.positions().all().map { "${it.profileId}:${it.mediaKey}" }

    private fun episode(
        id: Long,
        sourceId: Long,
        seriesId: String,
        season: Int,
        number: Int,
    ) = Episode(
        id = id,
        sourceId = sourceId,
        seriesId = seriesId,
        episodeId = "provider-$id",
        season = season,
        episodeNumber = number,
        title = "Episode $number",
        plot = null,
        durationSeconds = null,
        stillUrl = null,
        streamUrl = "https://example.test/$id.mkv",
    )

    private fun position(profileId: Long, key: String) = PlaybackPosition(
        profileId = profileId,
        mediaKey = key,
        positionMillis = 30_000,
        durationMillis = 60_000,
        updatedAtMillis = 1,
    )
}