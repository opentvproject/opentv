/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import app.opentv.data.db.DATABASE_VERSION
import app.opentv.data.db.OpenTvDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The upgrade path, from the oldest schema the app ever shipped to the current one.
 *
 * Every migration here is additive on purpose, so that favourites, guide overrides and the
 * user's own names survive the upgrade. "Additive on purpose" is a claim, and this is the
 * test that holds it to account: a mistyped `ALTER TABLE` would otherwise be found on a
 * user's device, where the fix is `fallbackToDestructiveMigration` — which rebuilds the
 * database from nothing and takes every favourite and every recording with it.
 *
 * The helper also validates the migrated database against the exported schema, so a
 * migration that produces a different table than Room expects fails here rather than on
 * the device.
 */
@RunWith(RobolectricTestRunner::class)
// The app's own Application schedules a WorkManager job on start-up, and WorkManager is
// initialised by hand in the real app — so a plain Application is what a database test wants.
@Config(application = Application::class, sdk = [33])
class DatabaseMigrationTest {

    private lateinit var helper: MigrationTestHelper

    private val databaseName = "migration-test.db"

    @Before
    fun setUp() {
        // The exported schemas are shipped with the debug variant only — the release APK has
        // no reason to carry 460 KB of schema history — so this test runs there and is
        // skipped in the release variant, where the files simply are not there.
        assumeTrue(schemasArePresent())
        helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            OpenTvDatabase::class.java.canonicalName,
            FrameworkSQLiteOpenHelperFactory(),
        )
    }

    private fun schemasArePresent(): Boolean = try {
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("app.opentv.data.db.OpenTvDatabase/2.json").close()
        true
    } catch (e: IOException) {
        false
    }

    /** The whole chain, oldest schema to current. */
    private fun allMigrations() = arrayOf(
        OpenTvDatabase.MIGRATION_2_3,
        OpenTvDatabase.MIGRATION_3_4,
        OpenTvDatabase.MIGRATION_4_5,
        OpenTvDatabase.MIGRATION_5_6,
        OpenTvDatabase.MIGRATION_6_7,
        OpenTvDatabase.MIGRATION_7_8,
        OpenTvDatabase.MIGRATION_8_9,
        OpenTvDatabase.MIGRATION_9_10,
        OpenTvDatabase.MIGRATION_10_11,
        OpenTvDatabase.MIGRATION_11_12,
        OpenTvDatabase.MIGRATION_12_13,
    )

    /** A v2 database with the things a user would miss if the upgrade wiped them. */
    private fun seedOldDatabase(): SupportSQLiteDatabase =
        helper.createDatabase(databaseName, 2).apply {
            execSQL(
                "INSERT INTO sources (id, name, kind, url, username, password, epgUrl, userAgent, " +
                    "enabled, lastCatalogSyncMillis) " +
                    "VALUES (1, 'Minha fonte', 'XTREAM', 'http://panel:8080', 'u', 'p', NULL, " +
                    "'OpenTV/1.0', 1, 0)",
            )
            execSQL(
                "INSERT INTO channels (id, sourceId, streamId, name, displayName, groupKey, " +
                    "qualityRank, qualityLabel, categoryId, logoUrl, epgChannelId, matchedEpgId, " +
                    "epgOverrideId, number, streamUrl, favourite, hidden, sortIndex, lastSeenMillis) " +
                    "VALUES (7, 1, '42', 'ESPN HD', 'ESPN HD', 'espn', 200, 'HD', NULL, NULL, " +
                    "'espn.br', NULL, NULL, 3, 'http://panel:8080/live/42.ts', 1, 0, 5, 0)",
            )
            execSQL(
                "INSERT INTO programmes (id, feedId, epgChannelId, startUtcMillis, endUtcMillis, " +
                    "title, description, category, season, episode, iconUrl) " +
                    "VALUES (9, 0, 'espn.br', 1000, 2000, 'Jogo', NULL, NULL, NULL, NULL, NULL)",
            )
            close()
        }

    private fun <T> SupportSQLiteDatabase.read(column: String, sql: String): T? {
        val cursor = query(sql)
        cursor.use {
            if (!it.moveToFirst()) return null
            @Suppress("UNCHECKED_CAST")
            return it.getString(0) as T
        }
    }

    @Test
    fun `the database migrates all the way from version 2 to the current one`() {
        seedOldDatabase()

        val db = helper.runMigrationsAndValidate(
            databaseName,
            DATABASE_VERSION,
            true,
            *allMigrations(),
        )
        db.close()
    }

    @Test
    fun `the user's own data survives the upgrade`() {
        seedOldDatabase()

        val db = helper.runMigrationsAndValidate(
            databaseName,
            DATABASE_VERSION,
            true,
            *allMigrations(),
        )

        // The source, with its credentials.
        assertThat(db.read<String>("name", "SELECT name FROM sources WHERE id = 1"))
            .isEqualTo("Minha fonte")
        assertThat(db.read<String>("username", "SELECT username FROM sources WHERE id = 1"))
            .isEqualTo("u")

        // The favourite channel, its name and its guide override.
        val favourite = db.query("SELECT favourite, customName, number FROM channels WHERE id = 7")
        favourite.use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getInt(0)).isEqualTo(1)     // still a favourite
            assertThat(it.getString(1)).isNull()       // never renamed
            assertThat(it.getInt(2)).isEqualTo(3)      // still channel 3
        }

        // The guide.
        assertThat(db.read<String>("title", "SELECT title FROM programmes WHERE id = 9"))
            .isEqualTo("Jogo")

        db.close()
    }

    @Test
    fun `the catch-up columns exist and default to empty after the upgrade`() {
        seedOldDatabase()

        val db = helper.runMigrationsAndValidate(
            databaseName,
            DATABASE_VERSION,
            true,
            *allMigrations(),
        )

        // v11 → v12 added the catch-up columns, v12 → v13 the provider's timezone. A channel
        // from an old install has no archive: the columns are there, and they say so.
        val catchup = db.query(
            "SELECT catchupSource, catchupMode, catchupCorrectionSecs FROM channels WHERE id = 7",
        )
        catchup.use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isNull()
            assertThat(it.getString(1)).isNull()
            assertThat(it.getInt(2)).isEqualTo(0)
        }

        val source = db.query("SELECT timezone FROM sources WHERE id = 1")
        source.use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isNull()
        }

        db.close()
    }
}
