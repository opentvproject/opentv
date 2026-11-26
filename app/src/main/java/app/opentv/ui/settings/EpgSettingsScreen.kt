/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.EpgFeed
import app.opentv.ui.EpgViewModel

/**
 * Guide settings: where guide data comes from.
 *
 * Three kinds of feed share the list — the provider's own guide, the curated free sources
 * shipped with the app (off by default; enabling one is one click), and any XMLTV URL the
 * user adds. Every enabled feed merges into a single guide, and each row shows its last
 * sync outcome, because "the guide silently didn't update" is the failure this whole app
 * exists to avoid.
 */
@Composable
fun EpgSettingsScreen(
    onBack: () -> Unit,
    viewModel: EpgViewModel = viewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_guide_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { viewModel.refresh() }, enabled = !ui.syncing) {
                Text(if (ui.syncing) stringResource(R.string.epg_updating) else stringResource(R.string.epg_update_now))
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }

        ui.statusLine?.let { line ->
            Spacer(Modifier.height(8.dp))
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.epg_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 720.dp),
        )
        Spacer(Modifier.height(16.dp))

        ui.suggestion?.let { feedName ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.epg_suggestion_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.epg_suggestion_body, feedName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.acceptSuggestion() }) {
                            Text(stringResource(R.string.epg_suggestion_accept))
                        }
                        TextButton(onClick = { viewModel.dismissSuggestion() }) {
                            Text(stringResource(R.string.epg_suggestion_dismiss))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.feeds, key = { it.id }) { feed ->
                FeedRow(
                    feed = feed,
                    onToggle = { enabled -> viewModel.setEnabled(feed, enabled) },
                    onRemove = { viewModel.remove(feed) },
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                if (!showAdd) {
                    OutlinedButton(onClick = { showAdd = true }) { Text(stringResource(R.string.epg_add_own)) }
                } else {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                label = { Text(stringResource(R.string.epg_name_optional)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = newUrl,
                                onValueChange = { newUrl = it },
                                label = { Text(stringResource(R.string.epg_xmltv_url)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        viewModel.addCustom(newName, newUrl)
                                        newName = ""; newUrl = ""; showAdd = false
                                    },
                                    enabled = newUrl.isNotBlank(),
                                ) { Text(stringResource(R.string.epg_add_guide)) }
                                TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.common_cancel)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedRow(
    feed: EpgFeed,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(feed.name, style = MaterialTheme.typography.titleMedium)
                // The last sync outcome, verbatim — success counts or the actual error.
                // A guide that fails must say so where the user can see it.
                if (feed.lastResult.isNotEmpty()) {
                    Text(
                        feed.lastResult,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // User-added feeds can be removed; provider and built-in ones only toggled.
            if (!feed.builtIn && feed.providerSourceId == null) {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.common_remove)) }
                Spacer(Modifier.width(4.dp))
            }
            Switch(checked = feed.enabled, onCheckedChange = onToggle)
        }
    }
}
