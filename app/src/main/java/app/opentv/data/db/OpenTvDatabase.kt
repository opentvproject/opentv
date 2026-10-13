/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Episode
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Movie
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Profile
import app.opentv.data.model.Programme
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Reminder
import app.opentv.data.model.Series
import app.opentv.data.model.SeriesRule
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind

class Converters {
    @TypeConverter fun sourceKindToString(value: SourceKind): String = value.name

    @TypeConverter fun stringToSourceKind(value: String): SourceKind =
        runCatching { SourceKind.valueOf(value) }.getOrDefault(SourceKind.M3U)

    @TypeConverter fun liveStreamFormatToString(value: LiveStreamFormat): String = value.name

    @TypeConverter fun stringToLiveStreamFormat(value: String): LiveStreamFormat =
        runCatching { LiveStreamFormat.valueOf(value) }.getOrDefault(LiveStreamFormat.HLS)

    @TypeConverter fun streamKindToString(value: StreamKind): String = value.name

    @TypeConverter fun stringToStreamKind(value: String): StreamKind =
        runCatching { StreamKind.valueOf(value) }.getOrDefault(StreamKind.LIVE)

    @TypeConverter fun recordingStatusToString(value: RecordingStatus): String = value.name

    @TypeConverter fun stringToRecordingStatus(value: String): RecordingStatus =
        runCatching { RecordingStatus.valueOf(value) }.getOrDefault(RecordingStatus.FAILED)
}

@Database(
    entities = [
        Source::class,
        Category::class,
        Channel::class,
        EpgFeed::class,
        EpgChannelAlias::class,
        Programme::class,
        Movie::class,
        Series::class,
        Episode::class,
        PlaybackPosition::class,
        Profile::class,
        Recording::class,
        SeriesRule::class,
        Reminder::class,
    ],
    version = 12,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OpenTvDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun categories(): CategoryDao
    abstract fun channels(): ChannelDao
    abstract fun epgFeeds(): EpgFeedDao
    abstract fun epgAliases(): EpgChannelAliasDao
    abstract fun programmes(): ProgrammeDao
    abstract fun movies(): MovieDao
    abstract fun series(): SeriesDao
    abstract fun episodes(): EpisodeDao
    abstract fun positions(): PlaybackPositionDao
    abstract fun profiles(): ProfileDao
    abstract fun recordings(): RecordingDao
    abstract fun seriesRules(): SeriesRuleDao
    abstract fun reminders(): ReminderDao

    companion object {
        /**
         * v2 → v3: profiles. Adds the profiles table with a default profile (id 1, "Me"), and
         * re-keys resume positions by (profileId, mediaKey) — existing positions all become the
         * default profile's, so nobody loses their place. Written to match Room's own DDL so the
         * identity check passes; the destructive fallback below is only a backstop.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `profiles` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL)",
                )
                db.execSQL("INSERT INTO `profiles` (`id`, `name`, `createdAtMillis`) VALUES (1, 'Me', 0)")
                db.execSQL(
                    "CREATE TABLE `playback_positions_new` (" +
                        "`profileId` INTEGER NOT NULL, `mediaKey` TEXT NOT NULL, " +
                        "`positionMillis` INTEGER NOT NULL, `durationMillis` INTEGER NOT NULL, " +
                        "`updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `mediaKey`))",
                )
                db.execSQL(
                    "INSERT INTO `playback_positions_new` " +
                        "(`profileId`, `mediaKey`, `positionMillis`, `durationMillis`, `updatedAtMillis`) " +
                        "SELECT 1, `mediaKey`, `positionMillis`, `durationMillis`, `updatedAtMillis` " +
                        "FROM `playback_positions`",
                )
                db.execSQL("DROP TABLE `playback_positions`")
                db.execSQL("ALTER TABLE `playback_positions_new` RENAME TO `playback_positions`")
            }
        }

        /**
         * v3 → v4: recordings. Purely additive — two new tables (recordings, series_rules) and
         * their indices, nothing existing is touched. Written to match Room's generated DDL so
         * the schema-identity check passes and favourites/overrides survive the upgrade (rather
         * than falling through to the destructive rebuild below).
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recordings` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`channelId` INTEGER NOT NULL, " +
                        "`sourceId` INTEGER NOT NULL, " +
                        "`channelName` TEXT NOT NULL, " +
                        "`logoUrl` TEXT, " +
                        "`title` TEXT NOT NULL, " +
                        "`description` TEXT, " +
                        "`filePath` TEXT NOT NULL, " +
                        "`streamUrl` TEXT NOT NULL, " +
                        "`userAgent` TEXT NOT NULL, " +
                        "`scheduledStartMillis` INTEGER NOT NULL, " +
                        "`scheduledEndMillis` INTEGER NOT NULL, " +
                        "`startedAtMillis` INTEGER NOT NULL, " +
                        "`endedAtMillis` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`seriesRuleId` INTEGER, " +
                        "`error` TEXT)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recordings_status` ON `recordings` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recordings_channelId` ON `recordings` (`channelId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `series_rules` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`channelId` INTEGER NOT NULL, " +
                        "`channelName` TEXT NOT NULL, " +
                        "`titleKey` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`createdAtMillis` INTEGER NOT NULL, " +
                        "`enabled` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_rules_channelId` ON `series_rules` (`channelId`)")
            }
        }

        /**
         * v4 → v5: catch-up. Adds the archive columns to channels. Additive; the DEFAULT 0 matches
         * the entity's @ColumnInfo(defaultValue = "0") so the schema-identity check passes.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `tvArchive` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `tvArchiveDays` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5 → v6: programme reminders. Purely additive — one new table (reminders) and its two
         * indices, matching Room's generated DDL so favourites, recordings and overrides survive
         * the upgrade rather than falling through to the destructive rebuild.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reminders` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`channelId` INTEGER NOT NULL, " +
                        "`channelName` TEXT NOT NULL, " +
                        "`logoUrl` TEXT, " +
                        "`title` TEXT NOT NULL, " +
                        "`startUtcMillis` INTEGER NOT NULL, " +
                        "`endUtcMillis` INTEGER NOT NULL, " +
                        "`autoTune` INTEGER NOT NULL, " +
                        "`createdAtMillis` INTEGER NOT NULL, " +
                        "`fired` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_startUtcMillis` ON `reminders` (`startUtcMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_channelId` ON `reminders` (`channelId`)")
            }
        }

        /**
         * v6 → v7: per-source live-stream format. Adds the `liveFormat` column to sources. Additive;
         * the DEFAULT 'HLS' back-fills existing rows to the historical behaviour so nobody's channels
         * change container until they opt in. The entity carries no @ColumnInfo(defaultValue) — like
         * the other enum-as-String columns (`kind`) — so the DB-only default is not part of the
         * schema-identity check and the upgrade preserves favourites and overrides.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `liveFormat` TEXT NOT NULL DEFAULT 'HLS'")
            }
        }

        /**
         * v7 → v8: per-channel custom name. Adds the nullable `customName` column to channels — the
         * user's manual rename, carried across re-syncs by [ChannelDao.replaceCatalogue]. Additive
         * and nullable (no NOT NULL, no default) to match the nullable Kotlin `String?`, so the
         * schema-identity check passes and favourites/overrides survive the upgrade.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `customName` TEXT")
            }
        }

        /**
         * v8 → v9: richer VOD metadata for the Netflix-style Movies/Series redesign. Adds the
         * enrichment columns (backdrop, cast, genre, tmdbId, and — movies only — director) pulled
         * from the provider's own Xtream `get_vod_info`/`get_series_info`. Every column is nullable
         * TEXT with no default, matching the nullable Kotlin `String?` fields, so the schema-identity
         * check passes and favourites/overrides survive the upgrade rather than falling through to
         * the destructive rebuild.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `backdropUrl` TEXT")
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `cast` TEXT")
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `genre` TEXT")
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `tmdbId` TEXT")
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `director` TEXT")
                db.execSQL("ALTER TABLE `series` ADD COLUMN `backdropUrl` TEXT")
                db.execSQL("ALTER TABLE `series` ADD COLUMN `cast` TEXT")
                db.execSQL("ALTER TABLE `series` ADD COLUMN `genre` TEXT")
                db.execSQL("ALTER TABLE `series` ADD COLUMN `tmdbId` TEXT")
            }
        }

        /** Recordings gain a profile owner so a booking can be tagged to whoever set it. Nullable
         *  (no default) to match Room's generated DDL for a `Long?` field — the project's pattern. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `profileId` INTEGER")
            }
        }

        /**
         * v10 → v11: Stalker/Ministra portal support. Adds a nullable `macAddress` to sources (the
         * portal credential) and a nullable `cmd` to channels (the play command resolved on demand
         * at tune time). Both nullable TEXT with no default, matching the `String?` fields, so the
         * schema-identity check passes and favourites/overrides survive the upgrade.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `macAddress` TEXT")
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `cmd` TEXT")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN sortIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): OpenTvDatabase =
            Room.databaseBuilder(context, OpenTvDatabase::class.java, "opentv.db")
                // WAL keeps guide writes from blocking guide reads, so a background EPG
                // refresh cannot make the UI stutter on a slow TV box.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(
                    MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                )
                /*
                 * Pre-1.0 policy: schema changes drop and rebuild the database. Everything
                 * in it is re-derivable from the provider (one sync away) except favourites
                 * and overrides, which is a real but small loss for testers. The policy
                 * flips to real migrations at the first tagged release — from then on,
                 * every schema change ships a Migration and this line is deleted.
                 */
                .fallbackToDestructiveMigration()
                .build()
    }
}
