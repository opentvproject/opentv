/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.diagnostics

import android.content.Context
import app.opentv.core.AppSettings
import app.opentv.data.db.ChannelDao
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.shownName
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.SourceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Another copy of the channel on screen that is likely to play better than the one struggling. */
data class StreamSuggestion(
    val channel: Channel,
    val sourceName: String,
    /** 0–100 from [StreamMatcher]. */
    val score: Int,
    /** True when the score comes from this exact stream's own record rather than an estimate. */
    val measured: Boolean,
    val otherProvider: Boolean,
)

/** One copy of a channel as tested from the diagnostics screen. */
data class StreamTestRow(val channel: Channel, val sourceName: String, val result: ProbeResult)

/**
 * The one door into playback diagnostics: stores what the recorder observes, runs the active
 * tests, and answers "which copy of this channel should be playing instead".
 */
class DiagnosticsRepository(
    private val context: Context,
    private val database: DiagnosticsDatabase,
    private val channelDao: ChannelDao,
    private val sources: SourceRepository,
    private val catalog: CatalogRepository,
    private val settings: AppSettings,
    httpClient: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probe = NetworkProbe(context, httpClient)

    val recentSessions: Flow<List<StreamSession>> = database.sessions().observeRecent(RECENT_SESSIONS)
    val recentProbes: Flow<List<ProbeResult>> = database.probes().observeRecent(RECENT_PROBES)

    /** Every copy's measured strength, by channel id. Held in memory: the guide reads it per row. */
    private val allStrength = MutableStateFlow<Map<Long, StreamStrength>>(emptyMap())

    /** The same, narrowed to each part of the day it was measured in. */
    private val allParts = MutableStateFlow<Map<Long, List<PartStrength>>>(emptyMap())

    /** The part of the day it is now, re-read often enough that the evening starts on time. */
    private val dayPart: Flow<DayPart> = flow {
        while (true) {
            emit(DayPart.at(System.currentTimeMillis()))
            delay(DAY_PART_CHECK_MILLIS)
        }
    }.distinctUntilChanged()

    /**
     * What is known about every copy *at this time of day*: where a copy has been measured or
     * watched during the current part of the day, that is what counts, because a server that is
     * quick overnight and choked at peak time is only quick when nobody is watching.
     */
    val current: StateFlow<Map<Long, StreamStrength>> =
        combine(allStrength, allParts, dayPart) { overall, parts, now ->
            if (parts.isEmpty()) overall
            else overall.mapValues { (id, row) ->
                CopyPicker.atPart(row, parts[id]?.firstOrNull { it.part == now.ordinal })
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * The measurements the app is allowed to act on when choosing which copy to open: all of them,
     * or none at all while the viewer has "use the results" switched off. Turning that off is the
     * undo — nothing is deleted, the app simply goes back to opening the highest quality.
     */
    val picks: StateFlow<Map<Long, StreamStrength>> =
        combine(current, settings.useOptimisedCopies) { map, on -> if (on) map else emptyMap() }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val strengthCount: Flow<Int> = database.strength().observeCount()

    init {
        scope.launch { runCatching { reloadStrength() } }
    }

    fun networkState(): NetworkState = NetworkState.read(context)

    /** Called by the recorder from the main thread; the write happens off it. */
    fun record(session: StreamSession) {
        scope.launch {
            runCatching {
                database.sessions().insert(session)
                foldViewing(session)
                val cutoff = System.currentTimeMillis() - KEEP_MILLIS
                database.sessions().prune(cutoff)
                database.probes().prune(cutoff)
                database.partStrength().prune(System.currentTimeMillis() - PART_KEEP_MILLIS)
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        database.sessions().clear()
        database.probes().clear()
    }

    // ---- Active tests ----------------------------------------------------------------------

    suspend fun testInternet(onProgress: (Long) -> Unit = {}): ProbeResult =
        probe.internet(onProgress).also { save(it) }

    suspend fun testProvider(source: Source): ProbeResult =
        probe.portal(source.id, source.name, source.url, source.userAgent).also { save(it) }

    suspend fun testStream(channel: Channel, source: Source?): ProbeResult {
        val url = runCatching { catalog.resolvePlaybackUrl(channel, source) }.getOrDefault(channel.streamUrl)
        return probe.stream(
            sourceId = channel.sourceId,
            channelId = channel.id,
            label = labelOf(channel),
            url = url,
            userAgent = source?.userAgent ?: Source.DEFAULT_USER_AGENT,
        ).also { save(it) }
    }

    /**
     * The same measurement as [testStream], but kept out of the test history: an overnight run
     * makes hundreds of these and would bury the handful of tests the viewer ran by hand.
     */
    suspend fun measure(channel: Channel, source: Source?): ProbeResult {
        val url = runCatching { catalog.resolvePlaybackUrl(channel, source) }.getOrDefault(channel.streamUrl)
        return probe.stream(
            sourceId = channel.sourceId,
            channelId = channel.id,
            label = labelOf(channel),
            url = url,
            userAgent = source?.userAgent ?: Source.DEFAULT_USER_AGENT,
        )
    }

    // ---- Strength --------------------------------------------------------------------------

    suspend fun strengthOf(channelId: Long): StreamStrength? = withContext(Dispatchers.IO) {
        database.strength().byId(channelId)
    }

    suspend fun allStrength(): List<StreamStrength> = withContext(Dispatchers.IO) { database.strength().all() }

    /** Folds one stream test into what is known about [channel] and returns the new record. */
    suspend fun noteTest(channel: Channel, result: ProbeResult): StreamStrength = withContext(Dispatchers.IO) {
        val dao = database.strength()
        val previous = dao.byId(channel.id)
        val row = when {
            !result.ok -> StreamStrength(
                channelId = channel.id, sourceId = channel.sourceId, groupKey = channel.groupKey,
                // One failed test does not erase a good record; a second one in a row all but does.
                ratio = (previous?.ratio ?: 0.0) * FAILED_TEST_KEEPS,
                measured = previous?.measured ?: true,
                samples = (previous?.samples ?: 0) + 1,
                failures = (previous?.failures ?: 0) + 1,
                bitrateBps = previous?.bitrateBps ?: 0,
                throughputBps = 0,
                testedAtMillis = result.atMillis,
            )
            result.bitrateBps <= 0 -> StreamStrength(
                channelId = channel.id, sourceId = channel.sourceId, groupKey = channel.groupKey,
                ratio = CopyPicker.KEEPS_UP, measured = false,
                samples = (previous?.samples ?: 0) + 1,
                throughputBps = result.throughputBps,
                testedAtMillis = result.atMillis,
            )
            else -> StreamStrength(
                channelId = channel.id, sourceId = channel.sourceId, groupKey = channel.groupKey,
                ratio = CopyPicker.blend(previous, result.throughputBps.toDouble() / result.bitrateBps),
                samples = (previous?.samples ?: 0) + 1,
                bitrateBps = result.bitrateBps,
                throughputBps = result.throughputBps,
                testedAtMillis = result.atMillis,
            )
        }
        dao.upsert(row)
        // A copy that gave no video is dead at every hour; only what played says anything about
        // this particular time of day.
        if (result.ok) {
            notePart(
                channel.id, result.atMillis,
                ratio = if (result.bitrateBps > 0) result.throughputBps.toDouble() / result.bitrateBps else CopyPicker.KEEPS_UP,
                measured = result.bitrateBps > 0,
            )
        }
        row
    }

    /** Folds one observation into the record for the part of the day it was made in. */
    private suspend fun notePart(channelId: Long, atMillis: Long, ratio: Double, measured: Boolean = true): PartStrength {
        val dao = database.partStrength()
        val part = DayPart.at(atMillis).ordinal
        val previous = dao.byId(channelId, part)
        val row = PartStrength(
            channelId = channelId, part = part,
            ratio = if (measured) CopyPicker.blendPart(previous, ratio) else ratio,
            measured = measured,
            samples = (previous?.samples ?: 0) + 1,
            updatedAtMillis = System.currentTimeMillis(),
        )
        dao.upsert(row)
        return row
    }

    /** Puts a record back exactly as it was, or removes it when there was none. */
    suspend fun restoreStrength(channelId: Long, previous: StreamStrength?) = withContext(Dispatchers.IO) {
        if (previous == null) database.strength().delete(channelId) else database.strength().upsert(previous)
    }

    /** Makes everything measured so far visible to the code that chooses which copy to open. */
    suspend fun reloadStrength() = withContext(Dispatchers.IO) {
        allParts.value = database.partStrength().since(System.currentTimeMillis() - PART_KEEP_MILLIS).groupBy { it.channelId }
        allStrength.value = database.strength().all().associateBy { it.channelId }
    }

    suspend fun clearStrength() = withContext(Dispatchers.IO) {
        database.strength().clear()
        database.partStrength().clear()
        allParts.value = emptyMap()
        allStrength.value = emptyMap()
    }

    /**
     * What real viewing says about a copy. A test is ten seconds on one night; an hour in front of
     * the channel is the truth. A session that stalled marks its copy down, a long clean one marks
     * it up, and anything too short to mean much is left out.
     */
    private suspend fun foldViewing(session: StreamSession) {
        if (session.channelId == 0L) return
        val dao = database.strength()
        val previous = dao.byId(session.channelId)
        val observed = when {
            session.startupMillis < 0 -> VIEWING_NO_PICTURE
            session.watchedMillis < VIEWING_MIN_MILLIS -> return
            session.stallMillis > session.watchedMillis * VIEWING_STALL_SHARE ||
                session.stallCount >= VIEWING_STALLS && session.stallCount * 600_000L > session.watchedMillis * VIEWING_STALLS ->
                VIEWING_STALLED
            session.stallCount == 0 && session.reconnects == 0 && session.watchedMillis >= VIEWING_CLEAN_MILLIS ->
                maxOf(previous?.takeIf { it.measured && it.failures == 0 }?.ratio ?: 0.0, VIEWING_CLEAN)
            else -> return
        }
        val row = StreamStrength(
            channelId = session.channelId, sourceId = session.sourceId, groupKey = session.groupKey,
            ratio = CopyPicker.blend(previous, observed),
            samples = (previous?.samples ?: 0) + 1,
            bitrateBps = previous?.bitrateBps ?: 0,
            throughputBps = previous?.throughputBps ?: 0,
            // Deliberately not "now": viewing must not make a copy look freshly tested, or the next
            // optimise run would skip it.
            testedAtMillis = previous?.testedAtMillis ?: 0,
        )
        dao.upsert(row)
        // The same verdict, filed under the time of day it was watched at. This is what lets an
        // evening of buffering count against a copy in the evening without spoiling it at lunch.
        val partBefore = database.partStrength().byId(session.channelId, DayPart.at(session.startedAtMillis).ordinal)
        val part = notePart(
            session.channelId, session.startedAtMillis,
            ratio = if (observed >= VIEWING_CLEAN) maxOf(partBefore?.takeIf { it.measured }?.ratio ?: 0.0, VIEWING_CLEAN) else observed,
        )
        allParts.value = allParts.value + (row.channelId to (allParts.value[row.channelId].orEmpty().filter { it.part != part.part } + part))
        allStrength.value = allStrength.value + (row.channelId to row)
    }

    /**
     * The copy that should actually open when the viewer asks for [channel].
     *
     * Stays with what was asked for unless the measurements give a reason not to: either that copy
     * is known to struggle, or a higher quality one from the same provider is proven strong. It
     * never crosses to another provider — a viewer who chose a provider's list meant that provider
     * — and never reaches for a higher quality that has merely not been tested.
     */
    suspend fun preferredCopy(channel: Channel): Channel {
        val map = picks.value
        if (map.isEmpty() || channel.groupKey.isEmpty()) return channel
        val copies = withContext(Dispatchers.IO) { channelDao.variantsInGroup(channel.groupKey) }
        return preferredAmong(channel, copies, map)
    }

    // ---- Copies of one channel -----------------------------------------------------------

    /** The channel most recently watched for long enough to be recorded, if it still exists. */
    suspend fun lastWatchedChannel(): Channel? = withContext(Dispatchers.IO) {
        val recent = database.sessions().since(System.currentTimeMillis() - KEEP_MILLIS)
        recent.firstNotNullOfOrNull { channelDao.byId(it.channelId) }
    }

    /**
     * The channels this device actually spends its time on, most-watched first, one entry per
     * channel however many of its copies were watched.
     */
    suspend fun mostWatched(limit: Int): List<Channel> = withContext(Dispatchers.IO) {
        val enabled = sources.enabled().mapTo(HashSet()) { it.id }
        val byChannel = database.sessions().channelSessionsSince(System.currentTimeMillis() - KEEP_MILLIS)
            .groupBy { it.groupKey.ifEmpty { "#${it.channelId}" } }
            .values
            .sortedByDescending { group -> group.sumOf { it.watchedMillis } }
        val found = ArrayList<Channel>()
        for (group in byChannel) {
            if (found.size >= limit) break
            // The copy watched most, if the provider still lists it; any surviving copy if not.
            val copies = group.groupBy { it.channelId }.entries.sortedByDescending { c -> c.value.sumOf { it.watchedMillis } }
            for (copy in copies) {
                val channel = channelDao.byId(copy.key)?.takeIf { it.sourceId in enabled && !it.hidden } ?: continue
                found += channel
                break
            }
        }
        found
    }

    /** Everything watched since [sinceMillis], newest first. */
    suspend fun sessionsSince(sinceMillis: Long): List<StreamSession> = withContext(Dispatchers.IO) {
        database.sessions().since(sinceMillis)
    }

    /**
     * Every playable copy of [channel] across the enabled providers, the given one first, capped so
     * that a test of all of them stays under a minute.
     */
    suspend fun copiesOf(channel: Channel, limit: Int = MAX_COPIES): List<Pair<Channel, Source?>> = withContext(Dispatchers.IO) {
        val enabled = sources.enabled().associateBy { it.id }
        val all = if (channel.groupKey.isEmpty()) listOf(channel) else channelDao.variantsInGroup(channel.groupKey)
        (listOf(channel) + all)
            .filter { it.sourceId in enabled }
            // The group key ignores punctuation, so "V Sport" and "V Sport+" share one. They are
            // different channels; a "+" has to be on both names or neither.
            .filter { it.name.count { c -> c == '+' } == channel.name.count { c -> c == '+' } }
            .distinctBy { it.id }
            .distinctBy { it.sourceId to it.streamUrl }
            .take(limit)
            .map { it to enabled[it.sourceId] }
    }

    /**
     * Tests each copy in turn, never two at once: most accounts allow a single connection, and a
     * parallel test would have the provider reject its own second request.
     */
    suspend fun testCopies(channel: Channel, onRow: (StreamTestRow) -> Unit): List<StreamTestRow> {
        val rows = ArrayList<StreamTestRow>()
        for ((copy, source) in copiesOf(channel)) {
            val row = StreamTestRow(copy, source?.name.orEmpty(), testStream(copy, source))
            runCatching { noteTest(copy, row.result) }
            rows += row
            onRow(row)
        }
        runCatching { reloadStrength() }
        return rows.sortedWith(TEST_ORDER)
    }

    // ---- One button ------------------------------------------------------------------------

    /**
     * "Fix my streams": the speed test, each provider's server, then every copy of the channels
     * watched most, in that order because each only means something if the one before it passed.
     * Whatever the copy tests find takes effect at once — the next time one of those channels is
     * opened, it opens on the copy that arrived best.
     */
    suspend fun fixMyStreams(onStep: (FixStep) -> Unit, onSpeed: (Long) -> Unit = {}): FixReport =
        runFix(onStep, onSpeed).also { lastFix.value = it }

    /** What the last run found, for as long as the app stays open. */
    val lastFix = MutableStateFlow<FixReport?>(null)

    private suspend fun runFix(onStep: (FixStep) -> Unit, onSpeed: (Long) -> Unit): FixReport {
        onStep(FixStep(FixStep.Stage.INTERNET))
        val internet = testInternet(onSpeed)
        if (!internet.ok) return FixReport(System.currentTimeMillis(), online = false, internetBps = 0)

        onStep(FixStep(FixStep.Stage.PROVIDERS))
        val enabled = sources.enabled()
        val down = enabled.filter { !testProvider(it).ok }
        val downIds = down.mapTo(HashSet()) { it.id }

        val watched = mostWatched(FIX_CHANNELS).filter { it.sourceId !in downIds }
        val improved = ArrayList<String>()
        val hopeless = ArrayList<String>()
        var copiesTested = 0
        for ((index, channel) in watched.withIndex()) {
            val name = channel.shownName
            onStep(FixStep(FixStep.Stage.COPIES, name, index, watched.size))
            val copies = copiesOf(channel, FIX_COPIES).filter { it.first.sourceId !in downIds }
            // What the guide would open for this channel: the provider's best quality, unless the
            // measurements say otherwise. Asked before and after, the difference is what was fixed.
            val own = copies.map { it.first }.filter { it.sourceId == channel.sourceId }
            val default = own.maxByOrNull { it.qualityRank } ?: channel
            val before = preferredAmong(default, own, current.value)
            val grades = ArrayList<StrengthGrade>()
            for ((copy, source) in copies) {
                val result = testStream(copy, source)
                grades += runCatching { noteTest(copy, result) }.getOrNull()?.grade ?: continue
                copiesTested++
            }
            runCatching { reloadStrength() }
            val after = preferredAmong(default, own, current.value)
            if (after.id != before.id) improved += name
            if (grades.isNotEmpty() && grades.none { it == StrengthGrade.STRONG || it == StrengthGrade.PLAYS || it == StrengthGrade.BORDERLINE }) {
                hopeless += name
            }
        }
        return FixReport(
            atMillis = System.currentTimeMillis(),
            online = true,
            internetBps = internet.throughputBps,
            providersDown = down.map { it.name },
            providers = enabled.size,
            channels = watched.size,
            copies = copiesTested,
            improved = improved,
            hopeless = hopeless,
        )
    }

    // ---- Matching --------------------------------------------------------------------------

    /**
     * The copy of [channel] most likely to play well right now, or null when there is no other copy
     * or none looks better than a coin toss.
     */
    suspend fun bestAlternative(channel: Channel): StreamSuggestion? = withContext(Dispatchers.IO) {
        if (channel.groupKey.isEmpty()) return@withContext null
        // Uncapped: on a channel with a dozen copies the strong one may well be the ninth.
        val copies = copiesOf(channel, Int.MAX_VALUE).filter { it.first.id != channel.id }
        if (copies.isEmpty()) return@withContext null

        val history = database.sessions().since(System.currentTimeMillis() - SCORE_WINDOW_MILLIS)
        val byChannel = history.groupBy { it.channelId }
        val byProvider = history.groupBy { it.sourceId }
        // A copy that failed a stream test in the last day is not worth sending anybody to.
        val dayAgo = System.currentTimeMillis() - 24 * 3_600_000L
        val recentlyDead = database.probes().observeRecent(RECENT_PROBES).first()
            .filter { it.kind == ProbeResult.KIND_STREAM && it.atMillis >= dayAgo }
            .distinctBy { it.channelId }
            .filter { !it.ok }
            .mapTo(HashSet()) { it.channelId }
        val strength = current.value

        fun candidate(c: Channel) = StreamMatcher.Candidate(
            channelId = c.id,
            sourceId = c.sourceId,
            qualityRank = c.qualityRank,
            own = HealthSummary.of(byChannel[c.id].orEmpty()),
            provider = HealthSummary.of(byProvider[c.sourceId].orEmpty()),
            strength = strength[c.id]?.grade,
        )
        val best = StreamMatcher.best(
            current = candidate(channel),
            alternatives = copies.map { it.first }.filter { it.id !in recentlyDead }.map(::candidate),
        ) ?: return@withContext null
        val (picked, source) = copies.first { it.first.id == best.candidate.channelId }
        StreamSuggestion(
            channel = picked,
            sourceName = source?.name.orEmpty(),
            score = best.score,
            measured = best.measured,
            otherProvider = picked.sourceId != channel.sourceId,
        )
    }

    private suspend fun save(result: ProbeResult) = withContext(Dispatchers.IO) {
        runCatching { database.probes().insert(result) }
    }

    companion object {
        const val SCORE_WINDOW_MILLIS = 7 * 24 * 3_600_000L
        private const val KEEP_MILLIS = 30 * 24 * 3_600_000L
        private const val RECENT_SESSIONS = 400
        private const val RECENT_PROBES = 120
        private const val MAX_COPIES = 6
        /** "Fix my streams" stays under about three minutes: this many channels, this many copies each. */
        private const val FIX_CHANNELS = 4
        private const val FIX_COPIES = 5
        private const val DAY_PART_CHECK_MILLIS = 60_000L
        /** A time-of-day record older than this says more about last month than about tonight. */
        private const val PART_KEEP_MILLIS = 21 * 24 * 3_600_000L

        private const val FAILED_TEST_KEEPS = 0.3
        private const val VIEWING_MIN_MILLIS = 60_000L
        private const val VIEWING_CLEAN_MILLIS = 3 * 60_000L
        private const val VIEWING_STALL_SHARE = 0.05
        private const val VIEWING_STALLS = 3
        private const val VIEWING_NO_PICTURE = 0.7
        private const val VIEWING_STALLED = 0.8
        private const val VIEWING_CLEAN = 1.6

        /** See [preferredCopy]. Pure, so the rule can be tested without a database. */
        fun preferredAmong(channel: Channel, copies: List<Channel>, strength: Map<Long, StreamStrength>): Channel {
            val plus = channel.name.count { it == '+' }
            val candidates = copies
                // "V Sport" and "V Sport+" share a group key but are different channels.
                .filter { it.sourceId == channel.sourceId && it.name.count { c -> c == '+' } == plus }
                .sortedWith(
                    compareByDescending<Channel> { it.qualityRank }
                        .thenByDescending { CopyPicker.sortWeight(strength[it.id]) },
                )
            if (candidates.none { it.id == channel.id } || candidates.none { it.id in strength }) return channel
            val proven = candidates.firstOrNull { strength[it.id]?.grade == StrengthGrade.STRONG }
            if (proven != null && proven.qualityRank > channel.qualityRank) return proven
            val own = strength[channel.id]?.grade
            if (own == null || own == StrengthGrade.STRONG || own == StrengthGrade.PLAYS) return channel
            return CopyPicker.pick(candidates) { strength[it.id] } ?: channel
        }

        fun labelOf(channel: Channel): String =
            listOf(channel.displayName.ifBlank { channel.name }, channel.qualityLabel).filter { it.isNotBlank() }.joinToString(" ")

        private fun headroom(row: StreamTestRow): Double {
            val p = row.result
            return if (p.bitrateBps > 0) p.throughputBps.toDouble() / p.bitrateBps else 1.0
        }

        /**
         * Working copies first. Among those, every copy with room to spare beats every copy
         * without, and the best of them is the highest quality — not the one with the most
         * headroom, which is always the SD copy because SD needs so little.
         */
        val TEST_ORDER: Comparator<StreamTestRow> = compareByDescending<StreamTestRow> { it.result.ok }
            .thenByDescending { headroom(it) >= CopyPicker.STRONG }
            .thenByDescending { if (headroom(it) >= CopyPicker.STRONG) it.channel.qualityRank else 0 }
            .thenByDescending { headroom(it) }
            .thenBy { if (it.result.ttfbMillis < 0) Long.MAX_VALUE else it.result.ttfbMillis }
    }
}
