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
import app.opentv.data.model.Channel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A catalogue refresh, and the user's own decisions.
 *
 * The provider is the source of the channel list, but not of what the user did with it:
 * a favourite, a hidden row, a hand-picked order, a rename and an EPG override are the
 * user's, and a refresh that overwrites them is how a TV app loses the thing the user
 * built. The refresh happens every few hours, silently, so a mistake here would not be
 * noticed for days.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class CatalogueRefreshTest {

    private lateinit var db: OpenTvDatabase
    private val sourceId = 7L

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

    private fun channel(
        streamId: String,
        name: String = "Channel $streamId",
        favourite: Boolean = false,
        hidden: Boolean = false,
        sortIndex: Int = 0,
        customName: String? = null,
        epgOverrideId: String? = null,
        matchedEpgId: String? = null,
        catchupSource: String? = null,
        catchupMode: String? = null,
        catchupCorrectionSecs: Int = 0,
    ) = Channel(
        sourceId = sourceId,
        streamId = streamId,
        name = name,
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "http://panel/live/$streamId.ts",
        favourite = favourite,
        hidden = hidden,
        sortIndex = sortIndex,
        customName = customName,
        epgOverrideId = epgOverrideId,
        matchedEpgId = matchedEpgId,
        catchupSource = catchupSource,
        catchupMode = catchupMode,
        catchupCorrectionSecs = catchupCorrectionSecs,
    )

    private suspend fun refresh(incoming: List<Channel>, syncStamp: Long = 1_000L) =
        db.channels().replaceCatalogue(sourceId, incoming, syncStamp)

    private suspend fun byStreamId(streamId: String): Channel? =
        db.channels().userStateForSource(sourceId).firstOrNull { it.streamId == streamId }

    @Test
    fun `a favourite survives a refresh`() = runBlocking<Unit> {
        db.channels().upsertAll(listOf(channel("a", favourite = true)))

        refresh(listOf(channel("a")))

        assertThat(byStreamId("a")?.favourite).isTrue()
    }

    @Test
    fun `a hidden row stays hidden, and a newly hidden one stays hidden too`() = runBlocking<Unit> {
        db.channels().upsertAll(listOf(channel("a", hidden = true)))

        refresh(listOf(channel("a"), channel("b", hidden = true)))

        assertThat(byStreamId("a")?.hidden).isTrue()
        assertThat(byStreamId("b")?.hidden).isTrue()
    }

    @Test
    fun `the order the user chose, the name they typed and the guide they chose all survive`() =
        runBlocking<Unit> {
            db.channels().upsertAll(
                listOf(
                    channel(
                        "a",
                        sortIndex = 7,
                        customName = "Meu nome",
                        epgOverrideId = "meu.id",
                        matchedEpgId = "encontrado.id",
                    ),
                ),
            )

            refresh(listOf(channel("a", name = "Nome novo do provedor")))

            val refreshed = byStreamId("a")
            assertThat(refreshed?.sortIndex).isEqualTo(7)
            assertThat(refreshed?.customName).isEqualTo("Meu nome")
            assertThat(refreshed?.epgOverrideId).isEqualTo("meu.id")
            assertThat(refreshed?.matchedEpgId).isEqualTo("encontrado.id")
            // The provider's name is still the name — the user's is an override on top of it.
            assertThat(refreshed?.name).isEqualTo("Nome novo do provedor")
        }

    @Test
    fun `the catch-up details come from the new catalogue, not from what was there`() =
        runBlocking<Unit> {
            db.channels().upsertAll(
                listOf(
                    channel(
                        "a",
                        catchupSource = "http://velho/arquivo.ts",
                        catchupMode = "shift",
                        catchupCorrectionSecs = -3600,
                    ),
                ),
            )

            refresh(
                listOf(
                    channel(
                        "a",
                        catchupSource = "http://novo/arquivo.ts",
                        catchupMode = "append",
                        catchupCorrectionSecs = 0,
                    ),
                ),
            )

            val refreshed = byStreamId("a")
            assertThat(refreshed?.catchupSource).isEqualTo("http://novo/arquivo.ts")
            assertThat(refreshed?.catchupMode).isEqualTo("append")
            assertThat(refreshed?.catchupCorrectionSecs).isEqualTo(0)
        }

    @Test
    fun `a channel the provider no longer carries is gone`() = runBlocking<Unit> {
        db.channels().upsertAll(listOf(channel("a"), channel("b")))

        refresh(listOf(channel("a")))

        assertThat(byStreamId("a")).isNotNull()
        assertThat(byStreamId("b")).isNull()
    }

    @Test
    fun `a channel the provider carries for the first time arrives`() = runBlocking<Unit> {
        refresh(listOf(channel("novo")))

        assertThat(byStreamId("novo")).isNotNull()
    }

    @Test
    fun `an empty catalogue is left alone`() = runBlocking<Unit> {
        // A provider that answers with nothing is a provider having a bad minute, not one
        // whose channels all disappeared. Emptying the list would take the user's favourites
        // with it.
        db.channels().upsertAll(listOf(channel("a", favourite = true)))

        refresh(emptyList())

        assertThat(byStreamId("a")?.favourite).isTrue()
    }
}
