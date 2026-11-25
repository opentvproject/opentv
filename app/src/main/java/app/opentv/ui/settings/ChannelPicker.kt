/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.shownName
import app.opentv.ui.channels.OnScreenKeyboard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** One channel as the picker lists it: a copy to stand for it, and how many copies there are. */
private data class Pick(val channel: Channel, val copies: Int)

/**
 * Chooses the channel a test should be run on.
 *
 * The channel that matters is different in every house — the big match in one country is a channel
 * nobody has heard of in the next — so the app does not guess beyond "what you watch most". This
 * lists those first and finds anything else by name, with the same remote-only keyboard as search.
 */
@Composable
internal fun ChannelPicker(suggested: List<Channel>, onPick: (Channel) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pick>>(emptyList()) }
    var searched by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<Pick>>(emptyList()) }

    LaunchedEffect(suggested) {
        suggestions = suggested.map { Pick(it, graph.diagnostics.copiesOf(it, Int.MAX_VALUE).size) }
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            searched = ""
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MILLIS)
        // One row per channel, not per copy: the test is of all a channel's copies at once.
        results = graph.catalogRepository.searchChannels(q).first()
            .groupBy { c -> if (c.groupKey.isEmpty()) "#${c.id}" else "${c.groupKey}|${c.name.count { it == '+' }}" }
            .values
            .map { copies -> Pick(copies.maxBy { it.qualityRank }, copies.size) }
            // The real channel has copies; the look-alikes and one-off event feeds mostly do not.
            .sortedByDescending { it.copies }
            .take(MAX_RESULTS)
        searched = q
    }
    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.diag_pick_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(20.dp))
            Text(
                query.ifEmpty { stringResource(R.string.search_type_name) },
                style = MaterialTheme.typography.titleLarge,
                color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            HubTextButton(stringResource(R.string.common_cancel), onBack)
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxSize()) {
            OnScreenKeyboard(
                onKey = { if (query.length < 40) query += it },
                onSpace = { if (query.length < 40) query += " " },
                onBackspace = { query = query.dropLast(1) },
                onClear = { query = "" },
            )
            Spacer(Modifier.width(28.dp))
            val typed = query.trim()
            val shown = if (typed.length < 2) suggestions else results
            LazyColumn(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    when {
                        typed.isEmpty() && suggestions.isNotEmpty() -> SectionTitle(stringResource(R.string.diag_pick_suggested))
                        typed.isEmpty() -> Muted(stringResource(R.string.diag_pick_hint))
                        typed.length < 2 -> Muted(stringResource(R.string.common_keep_typing))
                        searched == typed && results.isEmpty() -> Muted(stringResource(R.string.search_no_results, typed))
                    }
                }
                items(shown, key = { it.channel.id }) { pick ->
                    FocusCard(onClick = { onPick(pick.channel) }) {
                        Text(pick.channel.shownName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Muted(pluralStringResource(R.plurals.diag_pick_copies, pick.copies, pick.copies))
                    }
                }
            }
        }
    }
}

private const val SEARCH_DEBOUNCE_MILLIS = 250L
private const val MAX_RESULTS = 40
