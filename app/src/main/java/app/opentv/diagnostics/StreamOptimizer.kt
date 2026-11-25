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
import app.opentv.data.repo.SourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Whether anything in the app is holding a connection to a provider for playback right now.
 *
 * Most accounts allow one connection. A stream test opened while something is playing would
 * either knock the picture off or be refused, so the optimiser waits for this to clear. Every
 * player registers here — the full-screen one and the guide's preview alike.
 */
object PlaybackBusy {
    private val holders = HashSet<Any>()
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun set(holder: Any, playing: Boolean) {
        synchronized(holders) {
            if (playing) holders.add(holder) else holders.remove(holder)
            _active.value = holders.isNotEmpty()
        }
    }
}

/** Where an optimise run is up to. */
data class OptimiseProgress(
    val phase: Phase = Phase.IDLE,
    val done: Int = 0,
    val total: Int = 0,
    /** The copy being tested, for the progress line. */
    val current: String = "",
) {
    enum class Phase { IDLE, PREPARING, RUNNING, PAUSED }

    val active: Boolean get() = phase != Phase.IDLE
}

/** How the last run ended. Kept across restarts so the screen can say what happened overnight. */
data class OptimiseReport(
    val finishedAtMillis: Long,
    val tested: Int,
    /** Copies still waiting when the run ended. 0 for a run that got through everything. */
    val remaining: Int,
    val outcome: Outcome,
) {
    enum class Outcome { COMPLETE, STOPPED, OUT_OF_TIME, PROVIDER_DOWN, NOTHING_TO_TEST }
}

/** One channel that now opens on a different copy than it would have, and why. */
data class OptimisedChannel(
    val name: String,
    val before: Channel,
    val after: Channel,
    val beforeStrength: StreamStrength?,
    val afterStrength: StreamStrength?,
)

/** Everything measured so far, in the numbers the screen shows. */
data class OptimiseOverview(
    val copies: Int = 0,
    val strong: Int = 0,
    val borderline: Int = 0,
    val tooSlow: Int = 0,
    val dead: Int = 0,
    /** Plays, but on a stream whose headroom cannot be measured. */
    val unmeasurable: Int = 0,
    val channels: Int = 0,
    /** Channels with no copy that keeps up: nothing the app can pick will fix these. */
    val channelsWithoutGoodCopy: Int = 0,
    val changed: List<OptimisedChannel> = emptyList(),
)

/**
 * Tests copies of channels one after another and records how well each arrives, so that the app
 * can open the best quality that actually plays instead of the best quality on paper.
 *
 * It is slow on purpose. One connection at a time, a breath between tests, and it steps aside the
 * moment anything starts playing or recording — the point is to learn about the provider's servers
 * without the provider, or the person watching television, ever noticing it happen. That is also
 * why it suits the small hours: nobody is watching, and nothing has to wait.
 */
class StreamOptimizer(
    context: Context,
    private val repo: DiagnosticsRepository,
    private val channelDao: ChannelDao,
    private val sources: SourceRepository,
    private val settings: AppSettings,
    /** True while playback or a recording holds the provider's connection. */
    private val busy: Flow<Boolean>,
) {
    private val prefs = context.applicationContext.getSharedPreferences("optimise", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _progress = MutableStateFlow(OptimiseProgress())
    val progress: StateFlow<OptimiseProgress> = _progress.asStateFlow()

    private val _report = MutableStateFlow(loadReport())
    val report: StateFlow<OptimiseReport?> = _report.asStateFlow()

    /**
     * The copies a run with the current settings would test, in the order it would test them.
     * Whole channels stay together, favourites go first, and whatever has waited longest since its
     * last test comes before what was tested recently — so a run cut short still did the most
     * useful part, and the next one carries on from where it stopped.
     */
    suspend fun targets(): List<Channel> = withContext(Dispatchers.IO) {
        val enabled = sources.enabled().mapTo(HashSet()) { it.id }
        val favourites = channelDao.observeFavourites().first()
        val seeds = when (settings.optimiseScope.value) {
            SCOPE_ALL -> favourites + enabled.flatMap { channelDao.forSource(it) }
            SCOPE_CATEGORIES -> {
                val ids = settings.optimiseCategories.value.toList()
                // In slices: SQLite caps how many values one query may be handed.
                ids.chunked(400).flatMap { channelDao.observeInCategories(it).first() }
            }
            else -> favourites
        }
        // A seed stands for its whole channel: every copy from the same provider has to be
        // measured, or there is nothing to choose between.
        val expanded = LinkedHashMap<Long, Channel>()
        val seenGroups = HashSet<String>()
        for (seed in seeds) {
            if (seed.groupKey.isEmpty()) {
                expanded[seed.id] = seed
                continue
            }
            val plus = seed.name.count { it == '+' }
            if (!seenGroups.add("${seed.sourceId}|${seed.groupKey}|$plus")) continue
            expanded[seed.id] = seed
            channelDao.variantsInGroup(seed.groupKey)
                .filter { it.sourceId == seed.sourceId && it.name.count { c -> c == '+' } == plus }
                .forEach { expanded[it.id] = it }
        }
        val known = repo.allStrength().associate { it.channelId to it.testedAtMillis }
        val favouriteGroups = favourites.mapTo(HashSet()) { groupOf(it) }
        expanded.values
            .filter { !it.hidden && it.sourceId in enabled && (it.streamUrl.isNotBlank() || !it.cmd.isNullOrBlank()) }
            .groupBy { groupOf(it) }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<Channel>>> { it.key in favouriteGroups }
                    .thenBy { entry -> entry.value.minOf { known[it.id] ?: 0L } },
            )
            .flatMap { entry -> entry.value.sortedByDescending { it.qualityRank } }
    }

    /**
     * Runs until every target is tested, [deadlineMillis] passes, or the coroutine is cancelled.
     *
     * @param manual the viewer pressed the button. A manual run that finds everything already
     * fresh tests it all again, because that is plainly what was asked for; a scheduled one
     * would simply have nothing to do.
     */
    suspend fun run(manual: Boolean, deadlineMillis: Long = Long.MAX_VALUE): OptimiseReport? {
        if (!mutex.tryLock()) return null
        var tested = 0
        var remaining = 0
        var outcome = OptimiseReport.Outcome.COMPLETE
        try {
            _progress.value = OptimiseProgress(OptimiseProgress.Phase.PREPARING)
            val all = targets()
            val freshAfter = System.currentTimeMillis() - FRESH_MILLIS
            val known = repo.allStrength().associate { it.channelId to it.testedAtMillis }
            var queue = all.filter { (known[it.id] ?: 0L) < freshAfter }
            if (queue.isEmpty() && manual) queue = all
            if (queue.isEmpty()) {
                outcome = OptimiseReport.Outcome.NOTHING_TO_TEST
                return finish(tested, 0, outcome)
            }
            remaining = queue.size
            val sourceById = sources.enabled().associateBy { it.id }
            // Failures in a row, with what each copy's record was before: if the provider turns
            // out to be down, these were never measurements of the copies and are put back.
            val streak = ArrayList<Pair<Long, StreamStrength?>>()

            for ((index, channel) in queue.withIndex()) {
                if (System.currentTimeMillis() >= deadlineMillis) {
                    outcome = OptimiseReport.Outcome.OUT_OF_TIME
                    break
                }
                awaitQuiet(index, queue.size)
                _progress.value = OptimiseProgress(
                    OptimiseProgress.Phase.RUNNING, index, queue.size, DiagnosticsRepository.labelOf(channel),
                )
                val before = repo.strengthOf(channel.id)
                val result = repo.measure(channel, sourceById[channel.sourceId])
                if (busy.first()) {
                    // Something started playing mid-test. Whatever came back was shaped by that,
                    // so it is thrown away and this copy waits for the next run.
                    continue
                }
                repo.noteTest(channel, result)
                tested++
                remaining = queue.size - index - 1
                if (result.ok) {
                    streak.clear()
                } else {
                    streak += channel.id to before
                    if (streak.size >= PROVIDER_DOWN_STREAK) {
                        streak.forEach { (id, previous) -> repo.restoreStrength(id, previous) }
                        tested -= streak.size
                        remaining = queue.size - index - 1 + streak.size
                        outcome = OptimiseReport.Outcome.PROVIDER_DOWN
                        break
                    }
                }
                if (tested % PUBLISH_EVERY == 0) repo.reloadStrength()
                delay(GAP_MILLIS)
            }
            return finish(tested, if (outcome == OptimiseReport.Outcome.COMPLETE) 0 else remaining, outcome)
        } catch (e: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) { finish(tested, remaining, OptimiseReport.Outcome.STOPPED) }
            throw e
        } finally {
            _progress.value = OptimiseProgress()
            mutex.unlock()
        }
    }

    /** Holds the run while something is playing or recording, and for a moment after it stops. */
    private suspend fun awaitQuiet(done: Int, total: Int) {
        while (busy.first()) {
            _progress.value = OptimiseProgress(OptimiseProgress.Phase.PAUSED, done, total)
            runCatching { repo.reloadStrength() }
            busy.first { !it }
            // Long enough for the provider to notice the last connection has gone.
            delay(SETTLE_MILLIS)
        }
    }

    private suspend fun finish(tested: Int, remaining: Int, outcome: OptimiseReport.Outcome): OptimiseReport {
        runCatching { repo.reloadStrength() }
        val report = OptimiseReport(System.currentTimeMillis(), tested, remaining, outcome)
        prefs.edit()
            .putLong(KEY_AT, report.finishedAtMillis)
            .putInt(KEY_TESTED, tested)
            .putInt(KEY_REMAINING, remaining)
            .putString(KEY_OUTCOME, outcome.name)
            .apply()
        _report.value = report
        return report
    }

    private fun loadReport(): OptimiseReport? {
        val at = prefs.getLong(KEY_AT, 0L)
        if (at == 0L) return null
        val outcome = runCatching { OptimiseReport.Outcome.valueOf(prefs.getString(KEY_OUTCOME, "").orEmpty()) }
            .getOrDefault(OptimiseReport.Outcome.COMPLETE)
        return OptimiseReport(at, prefs.getInt(KEY_TESTED, 0), prefs.getInt(KEY_REMAINING, 0), outcome)
    }

    /** Forgets every measurement, and with them every choice made from them. */
    suspend fun clear() {
        repo.clearStrength()
        prefs.edit().clear().apply()
        _report.value = null
    }

    /**
     * What has been measured, and which channels it changes. Computed from the stored
     * measurements on request rather than kept up to date: nothing needs it but the screen.
     */
    suspend fun overview(): OptimiseOverview = withContext(Dispatchers.IO) {
        val all = repo.allStrength().filter { it.samples > 0 }
        if (all.isEmpty()) return@withContext OptimiseOverview()
        val byId = all.associateBy { it.channelId }
        val changed = ArrayList<OptimisedChannel>()
        var channels = 0
        var hopeless = 0
        // Only copies that were tested count as a channel here; viewing alone marks single copies.
        val groups = all.filter { it.testedAtMillis > 0 }.groupBy { "${it.sourceId}|${it.groupKey.ifEmpty { "#${it.channelId}" }}" }
        for (rows in groups.values) {
            channels++
            if (rows.none { it.grade == StrengthGrade.STRONG || it.grade == StrengthGrade.PLAYS || it.grade == StrengthGrade.BORDERLINE }) hopeless++
            val key = rows.first().groupKey
            if (key.isEmpty() || changed.size >= MAX_CHANGED) continue
            val copies = channelDao.variantsInGroup(key).filter { it.sourceId == rows.first().sourceId }
            // The same channel can hide two under one key ("V Sport" / "V Sport+"); take each apart.
            for (set in copies.groupBy { it.name.count { c -> c == '+' } }.values) {
                val default = set.firstOrNull() ?: continue
                val pick = DiagnosticsRepository.preferredAmong(default, set, byId)
                if (pick.id != default.id) {
                    changed += OptimisedChannel(default.displayName.ifBlank { default.name }, default, pick, byId[default.id], byId[pick.id])
                }
            }
        }
        OptimiseOverview(
            copies = all.count { it.testedAtMillis > 0 },
            strong = all.count { it.testedAtMillis > 0 && it.grade == StrengthGrade.STRONG },
            borderline = all.count { it.testedAtMillis > 0 && it.grade == StrengthGrade.BORDERLINE },
            tooSlow = all.count { it.testedAtMillis > 0 && it.grade == StrengthGrade.TOO_SLOW },
            dead = all.count { it.testedAtMillis > 0 && it.grade == StrengthGrade.DEAD },
            unmeasurable = all.count { it.testedAtMillis > 0 && it.grade == StrengthGrade.PLAYS },
            channels = channels,
            channelsWithoutGoodCopy = hopeless,
            changed = changed.sortedBy { it.name.lowercase() },
        )
    }

    private fun groupOf(channel: Channel): String =
        if (channel.groupKey.isEmpty()) "#${channel.id}"
        else "${channel.sourceId}|${channel.groupKey}|${channel.name.count { it == '+' }}"

    companion object {
        const val SCOPE_FAVOURITES = "favourites"
        const val SCOPE_CATEGORIES = "categories"
        const val SCOPE_ALL = "all"

        /** Roughly what one copy costs, pauses included. Only used to tell the viewer how long. */
        const val SECONDS_PER_COPY = 8

        /** A copy tested this recently is not tested again by a run that is merely continuing. */
        private const val FRESH_MILLIS = 6 * 3_600_000L
        private const val GAP_MILLIS = 700L
        private const val SETTLE_MILLIS = 8_000L
        private const val PUBLISH_EVERY = 40
        private const val PROVIDER_DOWN_STREAK = 8
        private const val MAX_CHANGED = 200

        private const val KEY_AT = "finished_at"
        private const val KEY_TESTED = "tested"
        private const val KEY_REMAINING = "remaining"
        private const val KEY_OUTCOME = "outcome"
    }
}
