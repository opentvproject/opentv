/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.diagnostics

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * What actually happened while one stream was on screen: how long it took to start, how often it
 * stalled, how often the provider dropped it. One row per tune that lasted long enough to mean
 * something. Everything the "which copy of this channel plays best here" ranking knows comes from
 * these rows — it is measured on this device, on this network, not guessed from a channel's name.
 */
@Entity(
    tableName = "stream_sessions",
    indices = [Index("channelId"), Index("sourceId"), Index("groupKey"), Index("startedAtMillis")],
)
data class StreamSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val sourceId: Long,
    val groupKey: String,
    val channelName: String,
    /** The host the stream was requested from. Never the full URL — that carries credentials. */
    val host: String,
    /** `hls`, `ts` or `other`. */
    val container: String,
    val startedAtMillis: Long,
    /** Wall-clock time the stream was up after its first frame, stalls included. */
    val watchedMillis: Long,
    /** Request to first frame. -1 when the stream never produced a picture. */
    val startupMillis: Long,
    val stallCount: Int,
    val stallMillis: Long,
    /** Silent restarts after the provider dropped the connection. Each one replays a few seconds. */
    val reconnects: Int,
    val loadErrors: Int,
    val fatalErrors: Int,
    val lastError: String? = null,
    val droppedFrames: Int = 0,
    /** Bits per second the stream needs, declared or observed. 0 when unknown. */
    val bitrateBps: Long = 0,
    /** Bits per second the link managed while loading it. 0 when it could not be measured. */
    val throughputBps: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    /** Times playback demonstrably jumped back and replayed content. */
    val backwardJumps: Int = 0,
    /** Holes in the stream as it arrived: the provider sent audio with a chunk of time missing. */
    val streamGaps: Int = 0,
    val vpn: Boolean = false,
    /** `wifi`, `ethernet`, `cellular` or `other`. */
    val transport: String = "",
)

/** One active measurement: the internet baseline, a provider's portal, or a single stream. */
@Entity(tableName = "probe_results", indices = [Index("kind"), Index("sourceId"), Index("atMillis")])
data class ProbeResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [KIND_INTERNET], [KIND_PORTAL] or [KIND_STREAM]. */
    val kind: String,
    val sourceId: Long = 0,
    val channelId: Long = 0,
    val label: String = "",
    val host: String = "",
    val atMillis: Long,
    val ok: Boolean,
    val error: String? = null,
    val httpCode: Int = 0,
    val dnsMillis: Long = -1,
    val connectMillis: Long = -1,
    val tlsMillis: Long = -1,
    /** Request sent to first byte back. */
    val ttfbMillis: Long = -1,
    /** Median round trip on a warm connection (internet test only). */
    val latencyMillis: Long = -1,
    val jitterMillis: Long = -1,
    val throughputBps: Long = 0,
    /** What the stream needs, when the probe could tell (HLS). 0 otherwise. */
    val bitrateBps: Long = 0,
    val bytes: Long = 0,
    val vpn: Boolean = false,
    val transport: String = "",
) {
    companion object {
        const val KIND_INTERNET = "internet"
        const val KIND_PORTAL = "portal"
        const val KIND_STREAM = "stream"
    }
}

@Dao
interface StreamSessionDao {
    @Insert suspend fun insert(session: StreamSession): Long

    @Query("SELECT * FROM stream_sessions WHERE startedAtMillis >= :sinceMillis ORDER BY startedAtMillis DESC")
    suspend fun since(sinceMillis: Long): List<StreamSession>

    @Query("SELECT * FROM stream_sessions ORDER BY startedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<StreamSession>>

    @Query("SELECT * FROM stream_sessions WHERE startedAtMillis >= :sinceMillis AND channelId != 0 ORDER BY startedAtMillis DESC")
    suspend fun channelSessionsSince(sinceMillis: Long): List<StreamSession>

    @Query("DELETE FROM stream_sessions WHERE startedAtMillis < :beforeMillis")
    suspend fun prune(beforeMillis: Long)

    @Query("DELETE FROM stream_sessions")
    suspend fun clear()
}

@Dao
interface ProbeResultDao {
    @Insert suspend fun insert(result: ProbeResult): Long

    @Query("SELECT * FROM probe_results ORDER BY atMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ProbeResult>>

    @Query("DELETE FROM probe_results WHERE atMillis < :beforeMillis")
    suspend fun prune(beforeMillis: Long)

    @Query("DELETE FROM probe_results")
    suspend fun clear()
}

/**
 * Deliberately a separate database file from the catalogue.
 *
 * The catalogue database holds things users care about losing (favourites, overrides, recordings),
 * so its schema changes need careful migrations. Diagnostics are disposable measurements: keeping
 * them in their own file means this feature can evolve its schema by simply starting again, and can
 * never be the reason somebody's channel list had to be rebuilt.
 */
@Database(
    entities = [StreamSession::class, ProbeResult::class, StreamStrength::class, PartStrength::class],
    version = 3,
    exportSchema = false,
)
abstract class DiagnosticsDatabase : RoomDatabase() {
    abstract fun sessions(): StreamSessionDao
    abstract fun probes(): ProbeResultDao
    abstract fun strength(): StreamStrengthDao
    abstract fun partStrength(): PartStrengthDao

    companion object {
        fun build(context: Context): DiagnosticsDatabase =
            Room.databaseBuilder(context.applicationContext, DiagnosticsDatabase::class.java, "diagnostics.db")
                .addMigrations(ADD_DAY_PARTS)
                .fallbackToDestructiveMigration()
                .build()

        /** Adds the per-part-of-day table without throwing away what was already measured. */
        private val ADD_DAY_PARTS = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `strength_by_part` (`channelId` INTEGER NOT NULL, " +
                        "`part` INTEGER NOT NULL, `ratio` REAL NOT NULL, `measured` INTEGER NOT NULL, " +
                        "`samples` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`channelId`, `part`))",
                )
            }
        }
    }
}
