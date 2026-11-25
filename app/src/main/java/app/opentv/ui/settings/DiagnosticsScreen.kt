/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.shownName
import app.opentv.diagnostics.CopyPicker
import app.opentv.diagnostics.DiagnosticsRepository
import app.opentv.diagnostics.DiagnosticsVerdict
import app.opentv.diagnostics.DiagnosticsVerdict.mbps
import app.opentv.diagnostics.Finding
import app.opentv.diagnostics.FixReport
import app.opentv.diagnostics.FixStep
import app.opentv.diagnostics.HealthSummary
import app.opentv.diagnostics.ProbeResult
import app.opentv.diagnostics.ProviderReport
import app.opentv.diagnostics.ReportCard
import app.opentv.diagnostics.Severity
import app.opentv.diagnostics.StreamSession
import app.opentv.diagnostics.StreamTestRow
import app.opentv.diagnostics.gradeOf
import app.opentv.ui.player.gradeColor
import app.opentv.ui.player.gradeLabel
import app.opentv.ui.player.transportLabel
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Playback diagnostics: why a stream buffers, and whose side the problem is on.
 *
 * Everything on this screen comes from two places. The first is the record the player keeps of
 * every channel watched — stalls, provider drops, start times — which costs nothing and needs no
 * button pressing. The second is the tests run from here: a neutral internet speed test, a check
 * of each provider's server, and a comparison of every copy of one channel. Put side by side they
 * separate "my connection" from "my provider", which is the question nobody could answer before.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, onOpenOptimise: () -> Unit = {}) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val repo = remember { graph.diagnostics }
    val settings = remember { graph.settings }
    val scope = rememberCoroutineScope()

    val sessions by repo.recentSessions.collectAsState(initial = emptyList())
    val probes by repo.recentProbes.collectAsState(initial = emptyList())
    val allSources by graph.sourceRepository.observeAll().collectAsState(initial = emptyList())
    val sources = allSources.filter { it.enabled }
    val recording by graph.recordingRepository.observeActive().collectAsState(initial = emptyList())
    val autoSwitch by settings.autoSwitchOnBuffering.collectAsState()
    val statsOverlay by settings.statsOverlayEnabled.collectAsState()

    var network by remember { mutableStateOf(repo.networkState()) }
    // One test at a time, whichever it is: they would otherwise measure each other.
    var running by remember { mutableStateOf(Job.NONE) }
    var liveBps by remember { mutableLongStateOf(0L) }
    var copyRows by remember { mutableStateOf<List<StreamTestRow>>(emptyList()) }
    var copiesTested by remember { mutableStateOf(false) }

    // Which channel the copy test is about. Left alone it is the one watched most here that has
    // copies to compare — not simply the last thing that was on, which is how a channel nobody
    // cares about ended up as the only one that could be tested.
    var mostWatched by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var suggested by remember { mutableStateOf<Channel?>(null) }
    var chosen by remember { mutableStateOf<Channel?>(null) }
    var targetCopies by remember { mutableStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var refocusCopies by remember { mutableStateOf(false) }
    val copiesFocus = remember { FocusRequester() }
    val target = chosen ?: suggested

    var fixStep by remember { mutableStateOf<FixStep?>(null) }
    val fixReport by repo.lastFix.collectAsState()
    var reports by remember { mutableStateOf<List<ProviderReport>>(emptyList()) }

    LaunchedEffect(sessions.firstOrNull()?.id, allSources) {
        val watched = repo.mostWatched(SUGGESTED_CHANNELS)
        mostWatched = watched
        suggested = watched.firstOrNull { repo.copiesOf(it, Int.MAX_VALUE).size > 1 } ?: watched.firstOrNull()
        val now = System.currentTimeMillis()
        reports = ReportCard.of(
            repo.sessionsSince(now - 2 * ReportCard.WEEK_MILLIS),
            allSources.filter { it.enabled }.associate { it.id to it.name },
            now,
        )
    }
    LaunchedEffect(target?.id) { targetCopies = target?.let { repo.copiesOf(it, Int.MAX_VALUE).size } ?: 0 }

    val weekAgo = System.currentTimeMillis() - DiagnosticsRepository.SCORE_WINDOW_MILLIS
    val week = sessions.filter { it.startedAtMillis >= weekAgo }
    val findings = remember(sessions, probes, allSources) {
        DiagnosticsVerdict.findings(week, probes, allSources.associate { it.id to it.name })
    }

    fun run(job: Job, block: suspend () -> Unit) {
        if (running != Job.NONE) return
        running = job
        network = repo.networkState()
        scope.launch {
            try {
                block()
            } finally {
                running = Job.NONE
                liveBps = 0
            }
        }
    }

    fun speedTest() = run(Job.INTERNET) { repo.testInternet { liveBps = it } }
    fun testProviders() = run(Job.PROVIDERS) { sources.forEach { repo.testProvider(it) } }
    fun testCopies(channel: Channel) = run(Job.COPIES) {
        copyRows = emptyList()
        copiesTested = true
        copyRows = repo.testCopies(channel) { row -> copyRows = copyRows + row }
    }
    // Everything a VPN could change, measured in one go and stamped with whether one was on.
    fun vpnRound() = run(Job.VPN) {
        repo.testInternet { liveBps = it }
        sources.forEach { repo.testProvider(it) }
        val channel = target
        if (channel != null && recording.isEmpty()) {
            repo.testStream(channel, sources.firstOrNull { it.id == channel.sourceId })
        }
    }
    fun fixMyStreams() = run(Job.FIX) {
        try {
            repo.fixMyStreams(
                onStep = {
                    fixStep = it
                    if (it.stage != FixStep.Stage.INTERNET) liveBps = 0
                },
                onSpeed = { liveBps = it },
            )
        } finally {
            fixStep = null
        }
    }

    val listState = rememberLazyListState()
    if (picking) {
        ChannelPicker(
            suggested = mostWatched,
            onPick = {
                chosen = it
                picking = false
                refocusCopies = true
                copyRows = emptyList()
                copiesTested = false
                if (recording.isEmpty()) testCopies(it)
            },
            onBack = {
                picking = false
                refocusCopies = true
            },
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.settings_diagnostics_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            HubTextButton(stringResource(R.string.common_done), onBack)
        }
        Spacer(Modifier.height(16.dp))

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            // Up from a card whose neighbour has scrolled out of existence would otherwise leap to
            // the Done button. While there is list above, focus stays in the list and the list
            // scrolls to bring that neighbour back.
            modifier = Modifier
                .widthIn(max = 820.dp)
                .focusProperties {
                    @OptIn(ExperimentalComposeUiApi::class)
                    exit = { direction ->
                        if (direction == FocusDirection.Up && listState.canScrollBackward) {
                            scope.launch { listState.animateScrollBy(-listState.layoutInfo.viewportSize.height / 2f) }
                            FocusRequester.Cancel
                        } else {
                            FocusRequester.Default
                        }
                    }
                }
                .focusGroup(),
        ) {
            // ---- One button ----------------------------------------------------------------
            item {
                FixCard(
                    step = fixStep.takeIf { running == Job.FIX },
                    liveBps = liveBps,
                    report = fixReport,
                    recording = recording.isNotEmpty(),
                    idle = running == Job.NONE,
                    onClick = { if (recording.isEmpty()) fixMyStreams() },
                )
            }

            // ---- What it all means ---------------------------------------------------------
            item { SectionTitle(stringResource(R.string.diag_section_findings)) }
            items(findings) { FindingCard(it) }

            // ---- The connection ------------------------------------------------------------
            item { SectionTitle(stringResource(R.string.diag_section_connection)) }
            item {
                val internet = probes.firstOrNull { it.kind == ProbeResult.KIND_INTERNET }
                FocusCard(onClick = ::speedTest) {
                    Text(
                        if (network.online) transportLabel(network.transport, network.vpn)
                        else stringResource(R.string.diag_offline),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    when {
                        running == Job.INTERNET || ((running == Job.VPN || running == Job.FIX) && liveBps > 0) -> {
                            Body(stringResource(R.string.diag_testing_speed, mbps(liveBps)))
                            Spacer(Modifier.height(10.dp))
                            SpeedChart(liveBps)
                        }
                        internet == null -> Body(stringResource(R.string.diag_speed_never))
                        !internet.ok -> Body(stringResource(R.string.diag_test_failed, internet.error.orEmpty()), warn = true)
                        else -> {
                            Body(
                                stringResource(
                                    R.string.diag_speed_result,
                                    mbps(internet.throughputBps), internet.latencyMillis, internet.jitterMillis,
                                ),
                            )
                            Muted(
                                stringResource(R.string.diag_measured_when, ago(internet.atMillis)) + " · " +
                                    transportLabel(internet.transport, internet.vpn),
                            )
                            Spacer(Modifier.height(10.dp))
                            SpeedChart(internet.throughputBps)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Action(stringResource(R.string.diag_run_speed_test), enabled = running == Job.NONE)
                }
            }

            // ---- Providers -----------------------------------------------------------------
            item { SectionTitle(stringResource(R.string.diag_section_providers)) }
            if (sources.isEmpty()) {
                item { Muted(stringResource(R.string.diag_no_providers)) }
            }
            if (reports.any { it.week.sessions > 0 || it.previous.sessions > 0 }) {
                item { ReportCardCard(reports) }
            }
            items(sources, key = { "source-${it.id}" }) { source ->
                ProviderCard(
                    source = source,
                    summary = HealthSummary.of(week.filter { it.sourceId == source.id }),
                    portal = probes.firstOrNull { it.kind == ProbeResult.KIND_PORTAL && it.sourceId == source.id },
                    testing = running == Job.PROVIDERS,
                    onClick = ::testProviders,
                )
            }

            // ---- Best copy of a channel ----------------------------------------------------
            item { SectionTitle(stringResource(R.string.diag_section_copies)) }
            item {
                val channel = target
                LaunchedEffect(refocusCopies) {
                    if (refocusCopies) {
                        runCatching { copiesFocus.requestFocus() }
                        refocusCopies = false
                    }
                }
                FocusCard(
                    modifier = Modifier.focusRequester(copiesFocus),
                    onClick = { if (channel != null && recording.isEmpty()) testCopies(channel) },
                ) {
                    if (channel != null) {
                        Text(channel.shownName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Muted(
                            stringResource(if (chosen != null) R.string.diag_copies_chosen else R.string.diag_copies_most_watched) +
                                " · " + pluralStringResource(R.plurals.diag_pick_copies, targetCopies, targetCopies),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Body(stringResource(R.string.diag_copies_desc))
                    Spacer(Modifier.height(8.dp))
                    when {
                        channel == null -> Muted(stringResource(R.string.diag_copies_none))
                        recording.isNotEmpty() -> Body(stringResource(R.string.diag_copies_recording), warn = true)
                        running == Job.COPIES -> Body(stringResource(R.string.diag_copies_testing, channel.shownName))
                        else -> Action(
                            stringResource(R.string.diag_copies_run, channel.shownName),
                            enabled = running == Job.NONE,
                        )
                    }
                }
            }
            item {
                FocusCard(onClick = { if (running == Job.NONE) picking = true }) {
                    Action(
                        stringResource(if (target == null) R.string.diag_copies_choose else R.string.diag_copies_choose_other),
                        enabled = running == Job.NONE,
                    )
                    Muted(stringResource(R.string.diag_copies_choose_hint))
                }
            }
            if (copiesTested) {
                val ranked = copyRows.sortedWith(DiagnosticsRepository.TEST_ORDER)
                items(ranked.size) { index ->
                    CopyRow(ranked[index], best = index == 0 && ranked[index].result.ok && running != Job.COPIES && ranked.size > 1)
                }
            }

            // ---- VPN -----------------------------------------------------------------------
            item { SectionTitle(stringResource(R.string.diag_section_vpn)) }
            item {
                FocusCard(onClick = ::vpnRound) {
                    Body(stringResource(R.string.diag_vpn_desc))
                    Spacer(Modifier.height(10.dp))
                    VpnTable(probes, sources)
                    Spacer(Modifier.height(10.dp))
                    if (running == Job.VPN) {
                        Body(stringResource(R.string.diag_vpn_running))
                    } else {
                        Action(
                            stringResource(
                                if (network.vpn) R.string.diag_vpn_run_on else R.string.diag_vpn_run_off,
                            ),
                            enabled = running == Job.NONE,
                        )
                    }
                }
            }

            // ---- Behaviour -----------------------------------------------------------------
            item { SectionTitle(stringResource(R.string.diag_section_options)) }
            item {
                FocusCard(onClick = onOpenOptimise) {
                    Text(
                        stringResource(R.string.optimise_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Muted(stringResource(R.string.optimise_entry_subtitle))
                    Spacer(Modifier.height(6.dp))
                    Action(stringResource(R.string.optimise_entry_action), enabled = true)
                }
            }
            item {
                ToggleCard(
                    title = stringResource(R.string.diag_auto_switch_title),
                    subtitle = stringResource(R.string.diag_auto_switch_subtitle),
                    checked = autoSwitch,
                    onToggle = { settings.setAutoSwitchOnBuffering(!autoSwitch) },
                )
            }
            item {
                ToggleCard(
                    title = stringResource(R.string.diag_stats_overlay_title),
                    subtitle = stringResource(R.string.diag_stats_overlay_subtitle),
                    checked = statsOverlay,
                    onToggle = { settings.setStatsOverlayEnabled(!statsOverlay) },
                )
            }

            // ---- The raw record ------------------------------------------------------------
            val trouble = sessions.filter { it.stallCount > 0 || it.reconnects > 0 || it.startupMillis < 0 || it.fatalErrors > 0 || it.streamGaps > 0 }
                .take(MAX_TROUBLE_ROWS)
            if (trouble.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.diag_section_trouble)) }
                items(trouble, key = { "session-${it.id}" }) { SessionRow(it) }
            }

            item {
                Spacer(Modifier.height(6.dp))
                FocusCard(onClick = {
                    scope.launch {
                        repo.clear()
                        copyRows = emptyList()
                        copiesTested = false
                    }
                }) {
                    Action(stringResource(R.string.diag_clear), enabled = true)
                    Muted(stringResource(R.string.diag_privacy))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private enum class Job { NONE, INTERNET, PROVIDERS, COPIES, VPN, FIX }

private const val MAX_TROUBLE_ROWS = 12
private const val SUGGESTED_CHANNELS = 8

@Composable
internal fun ago(atMillis: Long): String {
    val now = System.currentTimeMillis()
    if (now - atMillis < DateUtils.MINUTE_IN_MILLIS) return stringResource(R.string.diag_just_now)
    return DateUtils.getRelativeTimeSpanString(atMillis, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

private fun secondsOf(millis: Long): String = String.format(Locale.US, "%.1f s", millis / 1000.0)

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp),
    )
}

@Composable
internal fun Body(text: String, warn: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
internal fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The "press OK to do this" line inside a card; the card itself is what takes the click. */
@Composable
internal fun Action(label: String, enabled: Boolean) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Every block on this screen is one of these, including the ones with nothing to press: a
 * d-pad can only scroll a list by moving focus through it, so text that cannot take focus is text
 * a TV viewer can never reach.
 */
@Composable
internal fun FocusCard(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        content = content,
    )
}

private fun severityColor(severity: Severity): Color = when (severity) {
    Severity.GOOD -> Color(0xFF43A047)
    Severity.INFO -> Color(0xFF1E88E5)
    Severity.WARN -> Color(0xFFFFB300)
    Severity.BAD -> Color(0xFFE53935)
}

/** The one button: everything this screen can test, run in order and boiled down to a sentence. */
@Composable
private fun FixCard(step: FixStep?, liveBps: Long, report: FixReport?, recording: Boolean, idle: Boolean, onClick: () -> Unit) {
    FocusCard(onClick = onClick) {
        Text(stringResource(R.string.diag_fix_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        when {
            step != null -> Body(
                when (step.stage) {
                    FixStep.Stage.INTERNET -> stringResource(R.string.diag_fix_step_internet, mbps(liveBps))
                    FixStep.Stage.PROVIDERS -> stringResource(R.string.diag_fix_step_providers)
                    FixStep.Stage.COPIES -> stringResource(R.string.diag_fix_step_copies, step.channel, step.done + 1, step.total)
                },
            )
            report != null -> {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(severityColor(report.severity)))
                    Spacer(Modifier.width(10.dp))
                    Body(report.sentence())
                }
                Spacer(Modifier.height(4.dp))
                Muted(
                    stringResource(R.string.diag_measured_when, ago(report.atMillis)) +
                        if (report.copies > 0) " · " + pluralStringResource(R.plurals.diag_fix_tested, report.copies, report.copies) else "",
                )
            }
            else -> Body(stringResource(R.string.diag_fix_desc))
        }
        Spacer(Modifier.height(8.dp))
        when {
            recording -> Body(stringResource(R.string.diag_copies_recording), warn = true)
            step != null -> Muted(stringResource(R.string.diag_fix_wait))
            else -> Action(stringResource(if (report == null) R.string.diag_fix_run else R.string.diag_fix_again), enabled = idle)
        }
    }
}

/** Each provider's week as a school report: a letter, the numbers behind it, and last week's. */
@Composable
private fun ReportCardCard(reports: List<ProviderReport>) {
    FocusCard {
        Text(stringResource(R.string.diag_report_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ReportCard.headline(reports)?.let {
            Spacer(Modifier.height(4.dp))
            Body(it)
        }
        reports.forEach { report ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val letter = report.letter
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            when (letter) {
                                'A', 'B' -> Color(0xFF43A047)
                                'C' -> Color(0xFFFFB300)
                                null -> MaterialTheme.colorScheme.outline
                                else -> Color(0xFFE53935)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(letter?.toString() ?: "–", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(report.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    val week = report.week
                    if (letter == null) {
                        Muted(stringResource(R.string.diag_report_thin))
                    } else {
                        val parts = buildList {
                            add(stringResource(R.string.diag_report_buffering, String.format(Locale.US, "%.0f", week.stallSecondsPerHour)))
                            if (week.avgStartupMillis >= 0) add(stringResource(R.string.diag_provider_startup, secondsOf(week.avgStartupMillis)))
                            add(pluralStringResource(R.plurals.diag_report_drops, week.reconnects, week.reconnects))
                        }
                        Muted(parts.joinToString(" · "))
                    }
                }
                report.trend?.let { trend ->
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(
                            when (trend) {
                                ProviderReport.Trend.BETTER -> R.string.diag_report_better
                                ProviderReport.Trend.SAME -> R.string.diag_report_same
                                ProviderReport.Trend.WORSE -> R.string.diag_report_worse
                            },
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = when (trend) {
                            ProviderReport.Trend.BETTER -> Color(0xFF43A047)
                            ProviderReport.Trend.SAME -> MaterialTheme.colorScheme.onSurfaceVariant
                            ProviderReport.Trend.WORSE -> Color(0xFFE53935)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Muted(stringResource(R.string.diag_report_basis))
    }
}

@Composable
private fun FindingCard(finding: Finding) {
    FocusCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(severityColor(finding.severity)))
            Spacer(Modifier.width(10.dp))
            Text(finding.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Body(finding.detail)
    }
}

@Composable
private fun ProviderCard(source: Source, summary: HealthSummary, portal: ProbeResult?, testing: Boolean, onClick: () -> Unit) {
    FocusCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (summary.confident) {
                val grade = gradeOf(summary.score())
                Box(Modifier.size(10.dp).clip(CircleShape).background(gradeColor(grade)))
                Spacer(Modifier.width(10.dp))
            }
            Text(source.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (summary.confident) {
                Spacer(Modifier.weight(1f))
                Text(
                    gradeLabel(gradeOf(summary.score())) + " · " + stringResource(R.string.diag_score, summary.score()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        if (summary.sessions == 0) {
            Muted(stringResource(R.string.diag_provider_unwatched))
        } else {
            Body(
                stringResource(
                    R.string.diag_provider_record,
                    DateUtils.formatElapsedTime(summary.watchedMillis / 1000),
                    summary.stallCount,
                    secondsOf(summary.stallMillis),
                    summary.reconnects,
                ),
            )
            val extras = buildList {
                if (summary.avgStartupMillis >= 0) add(stringResource(R.string.diag_provider_startup, secondsOf(summary.avgStartupMillis)))
                if (summary.failedStarts > 0) add(stringResource(R.string.diag_provider_failed, summary.failedStarts))
            }
            if (extras.isNotEmpty()) Muted(extras.joinToString(" · "))
        }
        if (portal != null) {
            Spacer(Modifier.height(6.dp))
            if (!portal.ok) {
                Body(stringResource(R.string.diag_test_failed, portal.error.orEmpty()), warn = true)
            } else {
                // Lookup and connect are only known when the test opened a connection of its own.
                if (portal.dnsMillis >= 0 && portal.connectMillis >= 0) {
                    Body(stringResource(R.string.diag_portal_result, portal.ttfbMillis.coerceAtLeast(0), portal.dnsMillis, portal.connectMillis))
                } else {
                    Body(stringResource(R.string.diag_portal_result_plain, portal.ttfbMillis.coerceAtLeast(0)))
                }
            }
            Muted(stringResource(R.string.diag_measured_when, ago(portal.atMillis)) + " · " + transportLabel(portal.transport, portal.vpn))
        }
        Spacer(Modifier.height(8.dp))
        if (testing) Body(stringResource(R.string.diag_testing)) else Action(stringResource(R.string.diag_test_servers), enabled = true)
    }
}

@Composable
private fun CopyRow(row: StreamTestRow, best: Boolean) {
    val p = row.result
    FocusCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOf(row.channel.qualityLabel.ifBlank { row.channel.displayName }, row.sourceName).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val score = when {
                !p.ok -> 0
                p.bitrateBps > 0 -> CopyPicker.score(p.throughputBps.toDouble() / p.bitrateBps)
                else -> null
            }
            if (score != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.diag_copy_score, score),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                score >= 80 -> Color(0xFF43A047)
                                score >= 50 -> Color(0xFFFFB300)
                                else -> Color(0xFFE53935)
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (best) {
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.diag_copy_best),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF43A047))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        when {
            !p.ok -> Body(stringResource(R.string.diag_test_failed, p.error.orEmpty()), warn = true)
            p.bitrateBps > 0 -> Body(
                stringResource(
                    R.string.diag_copy_hls, mbps(p.bitrateBps), mbps(p.throughputBps),
                    String.format(Locale.US, "%.1f", p.throughputBps.toDouble() / p.bitrateBps),
                ),
                warn = p.throughputBps < p.bitrateBps * 13 / 10,
            )
            else -> Body(stringResource(R.string.diag_copy_ts, mbps(p.throughputBps)))
        }
        Muted(stringResource(R.string.diag_copy_server, p.host, p.ttfbMillis.coerceAtLeast(0)))
    }
}

/** Latest result of each kind with the VPN off and with it on, side by side. */
@Composable
private fun VpnTable(probes: List<ProbeResult>, sources: List<Source>) {
    fun latest(kind: String, vpn: Boolean, sourceId: Long? = null) =
        probes.firstOrNull { it.kind == kind && it.vpn == vpn && (sourceId == null || it.sourceId == sourceId) }

    @Composable
    fun line(label: String, off: String, on: String) {
        Row(Modifier.padding(vertical = 2.dp)) {
            Text(label, Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(off, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(on, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }

    val dash = "–"
    line("", stringResource(R.string.diag_vpn_col_off), stringResource(R.string.diag_vpn_col_on))
    line(
        stringResource(R.string.diag_vpn_row_internet),
        latest(ProbeResult.KIND_INTERNET, false)?.takeIf { it.ok }?.let { mbps(it.throughputBps) } ?: dash,
        latest(ProbeResult.KIND_INTERNET, true)?.takeIf { it.ok }?.let { mbps(it.throughputBps) } ?: dash,
    )
    for (source in sources) {
        line(
            stringResource(R.string.diag_vpn_row_server, source.name),
            latest(ProbeResult.KIND_PORTAL, false, source.id)?.takeIf { it.ok }?.let { "${it.ttfbMillis} ms" } ?: dash,
            latest(ProbeResult.KIND_PORTAL, true, source.id)?.takeIf { it.ok }?.let { "${it.ttfbMillis} ms" } ?: dash,
        )
    }
    val streamOff = latest(ProbeResult.KIND_STREAM, false)
    val streamOn = latest(ProbeResult.KIND_STREAM, true)
    if (streamOff != null || streamOn != null) {
        line(
            stringResource(R.string.diag_vpn_row_stream, (streamOn ?: streamOff)?.label.orEmpty()),
            streamOff?.takeIf { it.ok }?.let { mbps(it.throughputBps) } ?: dash,
            streamOn?.takeIf { it.ok }?.let { mbps(it.throughputBps) } ?: dash,
        )
    }
}

@Composable
internal fun ToggleCard(title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    FocusCard(onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Muted(subtitle)
            }
            Spacer(Modifier.width(16.dp))
            // Display only: the card is the control, so the remote has one stop, not two.
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun SessionRow(session: StreamSession) {
    FocusCard {
        Row {
            Text(
                session.channelName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Muted(ago(session.startedAtMillis))
        }
        val parts = buildList {
            if (session.startupMillis < 0) add(stringResource(R.string.diag_session_never_started))
            if (session.stallCount > 0) add(stringResource(R.string.diag_session_stalls, session.stallCount, secondsOf(session.stallMillis)))
            if (session.reconnects > 0) add(stringResource(R.string.diag_session_reconnects, session.reconnects))
            if (session.backwardJumps > 0) add(stringResource(R.string.diag_session_replays, session.backwardJumps))
            if (session.streamGaps > 0) add(stringResource(R.string.diag_session_gaps, session.streamGaps))
        }
        Body(parts.joinToString(" · "))
        Muted(
            listOf(
                session.host,
                stringResource(R.string.diag_session_watched, DateUtils.formatElapsedTime(session.watchedMillis / 1000)),
                transportLabel(session.transport, session.vpn),
            ).filter { it.isNotBlank() }.joinToString(" · "),
        )
        session.lastError?.let { Muted(it) }
    }
}
