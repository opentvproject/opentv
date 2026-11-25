/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.work.OptimiseWorker
import app.opentv.diagnostics.OptimiseOverview
import app.opentv.diagnostics.OptimiseProgress
import app.opentv.diagnostics.OptimiseReport
import app.opentv.diagnostics.OptimisedChannel
import app.opentv.diagnostics.StreamOptimizer
import app.opentv.diagnostics.StrengthGrade
import java.util.Locale
import kotlinx.coroutines.launch

/** Categories that are really one ("UK Sports", "UK SPORTS HD"), offered as a single choice. */
private data class PickGroup(val key: String, val label: String, val ids: List<String>)

/**
 * Settings → Playback diagnostics → Optimise streams.
 *
 * Says what optimising is before asking for anything, because a button that quietly changes which
 * stream every channel opens on would be a hard thing to trust. Then: what to test, when, whether
 * to use the answers, and what the answers were.
 */
@Composable
fun OptimiseScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val settings = remember { graph.settings }
    val optimizer = remember { graph.optimizer }
    val scope = rememberCoroutineScope()

    val progress by optimizer.progress.collectAsState()
    val report by optimizer.report.collectAsState()
    val chosenScope by settings.optimiseScope.collectAsState()
    val chosenCategories by settings.optimiseCategories.collectAsState()
    val nightly by settings.optimiseNightly.collectAsState()
    val useResults by settings.useOptimisedCopies.collectAsState()
    val hidden by settings.hiddenCategories.collectAsState()
    val hiddenUnlocked by settings.hiddenUnlocked.collectAsState()
    val rawCategories by graph.catalogRepository.observeCategories(StreamKind.LIVE).collectAsState(initial = emptyList())

    val groups = remember(rawCategories, hidden, hiddenUnlocked) {
        val folded = LinkedHashMap<String, Pair<String, MutableList<String>>>()
        for (category in rawCategories) {
            val n = ChannelNameNormalizer.normalize(category.name)
            val key = n.groupKey.ifEmpty { category.id }
            folded.getOrPut(key) { n.baseName.ifBlank { category.name } to mutableListOf() }.second += category.id
        }
        folded.map { (key, value) -> PickGroup(key, value.first, value.second) }
            .filter { hiddenUnlocked || it.key !in hidden }
    }

    var picking by remember { mutableStateOf(false) }
    var overview by remember { mutableStateOf(OptimiseOverview()) }
    // -1 while counting: "everything" on a big provider takes a moment to add up.
    var targetCount by remember { mutableIntStateOf(-1) }
    // The button was pressed but the worker has not reported in yet.
    var starting by remember { mutableStateOf(false) }

    LaunchedEffect(chosenScope, chosenCategories, picking) {
        if (picking) return@LaunchedEffect
        targetCount = -1
        targetCount = runCatching { optimizer.targets().size }.getOrDefault(0)
    }
    LaunchedEffect(report, progress.phase, progress.done / 40, useResults) {
        overview = runCatching { optimizer.overview() }.getOrDefault(OptimiseOverview())
    }
    LaunchedEffect(progress.active, report) { if (progress.active || report != null) starting = false }

    BackHandler(enabled = picking) { picking = false }
    // Swapping the list's contents drops focus; put it on the first category rather than on Done.
    val firstPick = remember { FocusRequester() }
    LaunchedEffect(picking, groups.isNotEmpty()) {
        if (picking && groups.isNotEmpty()) runCatching { firstPick.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(if (picking) R.string.optimise_pick_title else R.string.optimise_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.weight(1f))
            HubTextButton(stringResource(R.string.common_done)) { if (picking) picking = false else onBack() }
        }
        Spacer(Modifier.height(16.dp))

        val listState = rememberLazyListState()
        val pickState = rememberLazyListState()
        val active = if (picking) pickState else listState
        LazyColumn(
            state = active,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .widthIn(max = 820.dp)
                .focusProperties {
                    @OptIn(ExperimentalComposeUiApi::class)
                    exit = { direction ->
                        if (direction == FocusDirection.Up && active.canScrollBackward) {
                            scope.launch { active.animateScrollBy(-active.layoutInfo.viewportSize.height / 2f) }
                            FocusRequester.Cancel
                        } else {
                            FocusRequester.Default
                        }
                    }
                }
                .focusGroup(),
        ) {
            if (picking) {
                itemsIndexed(groups, key = { _, it -> it.key }) { index, group ->
                    val on = group.ids.any { it in chosenCategories }
                    FocusCard(modifier = if (index == 0) Modifier.focusRequester(firstPick) else Modifier, onClick = {
                        settings.setOptimiseCategories(
                            if (on) chosenCategories - group.ids.toSet() else chosenCategories + group.ids,
                        )
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(group.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                return@LazyColumn
            }

            // ---- What it is ----------------------------------------------------------------
            item {
                FocusCard {
                    Text(
                        stringResource(R.string.optimise_explain_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Body(stringResource(R.string.optimise_explain_1))
                    Spacer(Modifier.height(6.dp))
                    Body(stringResource(R.string.optimise_explain_2))
                    Spacer(Modifier.height(6.dp))
                    Body(stringResource(R.string.optimise_explain_3))
                }
            }

            // ---- What to test --------------------------------------------------------------
            item { SectionTitle(stringResource(R.string.optimise_section_scope)) }
            item {
                ScopeCard(
                    title = stringResource(R.string.optimise_scope_favourites),
                    subtitle = stringResource(R.string.optimise_scope_favourites_sub),
                    selected = chosenScope == StreamOptimizer.SCOPE_FAVOURITES,
                    enabled = !progress.active,
                ) { settings.setOptimiseScope(StreamOptimizer.SCOPE_FAVOURITES) }
            }
            item {
                val count = groups.count { g -> g.ids.any { it in chosenCategories } }
                ScopeCard(
                    title = stringResource(R.string.optimise_scope_categories),
                    subtitle = if (count == 0) stringResource(R.string.optimise_scope_categories_none)
                    else stringResource(R.string.optimise_scope_categories_some, count),
                    selected = chosenScope == StreamOptimizer.SCOPE_CATEGORIES,
                    enabled = !progress.active,
                ) {
                    settings.setOptimiseScope(StreamOptimizer.SCOPE_CATEGORIES)
                    picking = true
                }
            }
            item {
                ScopeCard(
                    title = stringResource(R.string.optimise_scope_all),
                    subtitle = stringResource(R.string.optimise_scope_all_sub),
                    selected = chosenScope == StreamOptimizer.SCOPE_ALL,
                    enabled = !progress.active,
                ) { settings.setOptimiseScope(StreamOptimizer.SCOPE_ALL) }
            }

            // ---- Run -----------------------------------------------------------------------
            item { SectionTitle(stringResource(R.string.optimise_section_run)) }
            item {
                val running = progress.active || starting
                FocusCard(onClick = {
                    if (running) {
                        starting = false
                        OptimiseWorker.stop(context)
                    } else if (targetCount > 0) {
                        starting = true
                        OptimiseWorker.runNow(context)
                    }
                }) {
                    when {
                        progress.phase == OptimiseProgress.Phase.PAUSED ->
                            Body(stringResource(R.string.optimise_paused, progress.done, progress.total))
                        progress.phase == OptimiseProgress.Phase.RUNNING -> {
                            Body(stringResource(R.string.optimise_progress, progress.done + 1, progress.total))
                            if (progress.current.isNotBlank()) Muted(progress.current)
                        }
                        running -> Body(stringResource(R.string.optimise_preparing))
                        targetCount < 0 -> Body(stringResource(R.string.optimise_estimate_counting))
                        targetCount == 0 -> Body(
                            stringResource(
                                when (chosenScope) {
                                    StreamOptimizer.SCOPE_FAVOURITES -> R.string.optimise_estimate_no_favourites
                                    StreamOptimizer.SCOPE_CATEGORIES -> R.string.optimise_estimate_no_categories
                                    else -> R.string.optimise_estimate_none
                                },
                            ),
                        )
                        else -> Body(stringResource(R.string.optimise_estimate, targetCount, duration(targetCount)))
                    }
                    if (progress.total > 0) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress.done.toFloat() / progress.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (!running) Muted(stringResource(R.string.optimise_run_now_sub))
                    Spacer(Modifier.height(6.dp))
                    Action(
                        stringResource(if (running) R.string.optimise_stop else R.string.optimise_run_now),
                        enabled = running || targetCount > 0,
                    )
                }
            }
            item {
                ToggleCard(
                    title = stringResource(R.string.optimise_nightly_title),
                    subtitle = stringResource(R.string.optimise_nightly_sub),
                    checked = nightly,
                    onToggle = {
                        settings.setOptimiseNightly(!nightly)
                        OptimiseWorker.syncNightly(context, replace = true)
                    },
                )
            }
            item {
                ToggleCard(
                    title = stringResource(R.string.optimise_use_title),
                    subtitle = stringResource(R.string.optimise_use_sub),
                    checked = useResults,
                    onToggle = { settings.setUseOptimisedCopies(!useResults) },
                )
            }

            // ---- Results -------------------------------------------------------------------
            item { SectionTitle(stringResource(R.string.optimise_section_results)) }
            item {
                FocusCard {
                    if (overview.copies == 0) {
                        Body(stringResource(R.string.optimise_results_none))
                    } else {
                        Body(stringResource(R.string.optimise_results_counts, overview.copies, overview.channels))
                        Muted(
                            stringResource(
                                R.string.optimise_results_grades,
                                overview.strong, overview.borderline, overview.tooSlow, overview.dead,
                            ),
                        )
                        if (overview.unmeasurable > 0) {
                            Muted(stringResource(R.string.optimise_results_unmeasurable, overview.unmeasurable))
                        }
                        if (overview.channelsWithoutGoodCopy > 0) {
                            Spacer(Modifier.height(6.dp))
                            Body(stringResource(R.string.optimise_results_hopeless, overview.channelsWithoutGoodCopy), warn = true)
                        }
                    }
                    report?.let {
                        Spacer(Modifier.height(6.dp))
                        Muted(lastRunLine(it))
                    }
                }
            }
            if (overview.changed.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.optimise_section_changed, overview.changed.size)) }
                items(overview.changed, key = { it.before.id }) { ChangedRow(it) }
            }
            if (overview.copies > 0 && !progress.active) {
                item {
                    FocusCard(onClick = { scope.launch { optimizer.clear(); overview = OptimiseOverview() } }) {
                        Action(stringResource(R.string.optimise_clear), enabled = true)
                        Muted(stringResource(R.string.optimise_clear_sub))
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeCard(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FocusCard(onClick = { if (enabled) onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Display only: the card is the control.
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Muted(subtitle)
            }
        }
    }
}

@Composable
private fun duration(copies: Int): String {
    val minutes = (copies.toLong() * StreamOptimizer.SECONDS_PER_COPY / 60).toInt().coerceAtLeast(1)
    return if (minutes < 60) stringResource(R.string.optimise_minutes, minutes)
    else stringResource(R.string.optimise_hours, minutes / 60, minutes % 60)
}

@Composable
private fun lastRunLine(report: OptimiseReport): String {
    val whenText = ago(report.finishedAtMillis)
    return when (report.outcome) {
        OptimiseReport.Outcome.COMPLETE -> stringResource(R.string.optimise_last_complete, whenText, report.tested)
        OptimiseReport.Outcome.STOPPED -> stringResource(R.string.optimise_last_stopped, whenText, report.tested, report.remaining)
        OptimiseReport.Outcome.OUT_OF_TIME -> stringResource(R.string.optimise_last_out_of_time, whenText, report.tested, report.remaining)
        OptimiseReport.Outcome.PROVIDER_DOWN -> stringResource(R.string.optimise_last_provider_down, whenText)
        OptimiseReport.Outcome.NOTHING_TO_TEST -> stringResource(R.string.optimise_last_nothing, whenText)
    }
}

private fun qualityOf(channel: Channel): String = channel.qualityLabel.ifBlank { "SD" }

private fun times(ratio: Double): String = String.format(Locale.US, "%.1f", ratio)

@Composable
private fun ChangedRow(change: OptimisedChannel) {
    val from = qualityOf(change.before)
    val to = qualityOf(change.after)
    FocusCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(change.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.optimise_changed_line, from, to),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        val before = change.beforeStrength
        val after = change.afterStrength
        val why = when {
            before?.grade == StrengthGrade.DEAD -> stringResource(R.string.optimise_changed_why_dead, from)
            before != null && before.measured && after != null && after.measured &&
                (before.grade == StrengthGrade.TOO_SLOW || before.grade == StrengthGrade.BORDERLINE) ->
                stringResource(R.string.optimise_changed_why_slow, from, times(before.ratio), to, times(after.ratio))
            change.after.qualityRank > change.before.qualityRank ->
                stringResource(R.string.optimise_changed_why_better, to)
            else -> null
        }
        if (why != null) Muted(why)
    }
}
