/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.diagnostics

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.util.Calendar
import kotlinx.coroutines.flow.Flow

/**
 * How well one copy of a channel arrives on this device: the rate its video comes down the line
 * divided by the rate the video needs. 2.0 means it downloads twice as fast as it plays, with room
 * to ride out a bad minute; anything under 1.0 cannot keep up and will buffer however long it is
 * left. One row per stream, blended across every test and every viewing, so a single unlucky
 * sample neither condemns a copy nor blesses it.
 */
@Entity(tableName = "stream_strength", indices = [Index("groupKey"), Index("testedAtMillis")])
data class StreamStrength(
    @PrimaryKey val channelId: Long,
    val sourceId: Long,
    val groupKey: String,
    /** Blended arrival rate ÷ needed rate. 0 for a copy that would not play at all. */
    val ratio: Double,
    /** False when the copy plays but its headroom cannot be measured (a raw, server-paced stream). */
    val measured: Boolean = true,
    val samples: Int = 1,
    /** Tests in a row that got no video. */
    val failures: Int = 0,
    val bitrateBps: Long = 0,
    val throughputBps: Long = 0,
    val testedAtMillis: Long,
) {
    val grade: StrengthGrade
        get() = when {
            failures > 0 && ratio < CopyPicker.KEEPS_UP -> StrengthGrade.DEAD
            !measured -> StrengthGrade.PLAYS
            ratio >= CopyPicker.STRONG -> StrengthGrade.STRONG
            ratio >= CopyPicker.KEEPS_UP -> StrengthGrade.BORDERLINE
            else -> StrengthGrade.TOO_SLOW
        }
}

/**
 * [StreamStrength.grade] as a number out of 100, for showing beside a channel. Null when the copy
 * has played but its bitrate was never learned, so there is nothing honest to put a number on.
 */
val StreamStrength.score: Int?
    get() = when {
        grade == StrengthGrade.DEAD -> 0
        !measured -> null
        else -> CopyPicker.score(ratio)
    }

enum class StrengthGrade { STRONG, PLAYS, BORDERLINE, TOO_SLOW, DEAD }

/**
 * The stretch of the day a measurement was taken in.
 *
 * A provider's server that flies at four in the morning can crawl at eight in the evening, when
 * everybody is watching the same match. A test run overnight says nothing about that, so what is
 * learned is also kept per part of the day, and the part it is *now* is the one that decides.
 */
enum class DayPart {
    MORNING, AFTERNOON, EVENING, NIGHT;

    companion object {
        fun of(hourOfDay: Int): DayPart = when (hourOfDay) {
            in 6..11 -> MORNING
            in 12..17 -> AFTERNOON
            in 18..22 -> EVENING
            else -> NIGHT
        }

        fun at(millis: Long): DayPart =
            of(Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY))
    }
}

/** How one copy arrives during one [DayPart]: the same ratio as [StreamStrength], narrower in time. */
@Entity(tableName = "strength_by_part", primaryKeys = ["channelId", "part"])
data class PartStrength(
    val channelId: Long,
    /** [DayPart.ordinal]. */
    val part: Int,
    val ratio: Double,
    val measured: Boolean = true,
    val samples: Int = 1,
    val updatedAtMillis: Long,
)

@Dao
interface StreamStrengthDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: StreamStrength)

    @Query("SELECT * FROM stream_strength WHERE channelId = :channelId")
    suspend fun byId(channelId: Long): StreamStrength?

    @Query("SELECT * FROM stream_strength")
    suspend fun all(): List<StreamStrength>

    @Query("SELECT COUNT(*) FROM stream_strength")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM stream_strength WHERE channelId = :channelId")
    suspend fun delete(channelId: Long)

    @Query("DELETE FROM stream_strength")
    suspend fun clear()
}

@Dao
interface PartStrengthDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: PartStrength)

    @Query("SELECT * FROM strength_by_part WHERE channelId = :channelId AND part = :part")
    suspend fun byId(channelId: Long, part: Int): PartStrength?

    @Query("SELECT * FROM strength_by_part WHERE updatedAtMillis >= :sinceMillis")
    suspend fun since(sinceMillis: Long): List<PartStrength>

    @Query("DELETE FROM strength_by_part WHERE updatedAtMillis < :beforeMillis")
    suspend fun prune(beforeMillis: Long)

    @Query("DELETE FROM strength_by_part")
    suspend fun clear()
}

/**
 * Chooses which copy of a channel opens when the viewer picks it.
 *
 * Without measurements the answer is simply the highest quality — which is how a channel list ends
 * up opening a 4K copy that its server cannot deliver while a perfectly good HD copy sits beneath
 * it. With them, the answer is the highest quality *that arrives fast enough to play*: a copy is
 * only passed over when it has actually been measured and found wanting, never on a hunch.
 */
object CopyPicker {
    /** Arrives half as fast again as it plays: enough slack to ride out a busy evening. */
    /**
     * Headroom as a mark out of 100. The bands line up with the grades so the number and the
     * colour never disagree: under 50 cannot keep up, 50–79 only just does, 80 and over has room
     * to spare, and three times what the stream needs is as good as it gets.
     */
    fun score(ratio: Double): Int = when {
        ratio.isNaN() || ratio <= 0.0 -> 0
        ratio < KEEPS_UP -> (ratio / KEEPS_UP * 49).toInt()
        ratio < STRONG -> 50 + ((ratio - KEEPS_UP) / (STRONG - KEEPS_UP) * 29).toInt()
        else -> (80 + ((ratio - STRONG) / (FULL_MARKS - STRONG) * 20).toInt()).coerceAtMost(100)
    }

    /** Headroom beyond which a copy cannot score any higher. */
    const val FULL_MARKS = 3.0

    const val STRONG = 1.5
    /** Arrives exactly as fast as it plays: no slack at all. */
    const val KEEPS_UP = 1.0

    /**
     * The copy to open from [variants], given best quality first.
     *
     * A copy is acceptable when it measured strong, plays but cannot be measured, or has not been
     * tested at all. The first acceptable one wins, so quality is only ever given up to get away
     * from a copy known to struggle. When every copy is known to struggle, the least bad one wins.
     */
    fun <T> pick(variants: List<T>, strength: (T) -> StreamStrength?): T? {
        if (variants.size < 2) return variants.firstOrNull()
        variants.firstOrNull { v ->
            when (strength(v)?.grade) {
                null, StrengthGrade.STRONG, StrengthGrade.PLAYS -> true
                else -> false
            }
        }?.let { return it }
        // maxByOrNull keeps the first of equals, and the list is best quality first.
        return variants.maxByOrNull { strength(it)?.ratio ?: 0.0 }
    }

    /**
     * Where a copy sorts among others of the *same* quality: proven strong, then untested, then
     * the ones known to be weak. Used so that when two categories carry the same channel at the
     * same quality from different servers, the stronger one is the one that survives de-duplication.
     */
    fun sortWeight(strength: StreamStrength?): Double = when (strength?.grade) {
        null, StrengthGrade.PLAYS -> UNTESTED_WEIGHT
        StrengthGrade.STRONG -> strength.ratio.coerceAtLeast(STRONG)
        else -> strength.ratio.coerceAtMost(UNTESTED_WEIGHT - 0.01)
    }

    /** One new measurement folded into what was already known, leaning toward the newer. */
    fun blend(previous: StreamStrength?, ratio: Double): Double =
        if (previous == null || previous.samples == 0 || !previous.measured || previous.failures > 0) ratio
        else NEW_WEIGHT * ratio + (1 - NEW_WEIGHT) * previous.ratio

    /** The same blend for a [PartStrength], which has no failures to reset it. */
    fun blendPart(previous: PartStrength?, ratio: Double): Double =
        if (previous == null || !previous.measured) ratio
        else NEW_WEIGHT * ratio + (1 - NEW_WEIGHT) * previous.ratio

    /**
     * What is known about a copy at this time of day: its overall record, with the ratio swapped
     * for the one measured during this part of the day when there is one. A copy whose last test
     * got no video keeps its overall record — dead is dead at any hour.
     */
    fun atPart(overall: StreamStrength, part: PartStrength?): StreamStrength =
        if (part == null || overall.failures > 0) overall
        else overall.copy(ratio = part.ratio, measured = part.measured)

    private const val UNTESTED_WEIGHT = 1.49
    private const val NEW_WEIGHT = 0.6
}
