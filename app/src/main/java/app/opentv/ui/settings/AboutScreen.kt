/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import app.opentv.pairing.QrCodes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.opentv.BuildConfig
import app.opentv.R
import app.opentv.core.ProjectInfo
import app.opentv.core.ServiceLocator
import app.opentv.update.UpdateChecker
import kotlinx.coroutines.launch

/**
 * About: what this is, what version it is, and how to get involved. The update check here is the
 * manual counterpart to the automatic one — someone who just heard a fix landed can pull it now
 * instead of waiting for the periodic check.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateLine by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }

        Spacer(Modifier.height(20.dp))

        Section(stringResource(R.string.about_version)) {
            Text("OpenTV ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !checking,
                    onClick = {
                        checking = true
                        updateLine = null
                        scope.launch {
                            val graph = ServiceLocator.get(context)
                            val update = runCatching {
                                UpdateChecker(graph.httpClient, BuildConfig.VERSION_NAME).check()
                            }.getOrNull()
                            updateLine = when {
                                update != null -> context.getString(R.string.about_update_available, update.versionName)
                                else -> context.getString(R.string.about_up_to_date)
                            }
                            // Raise the shared update prompt right here — UpdateGate overlays every
                            // screen, so the install dialog appears over About immediately.
                            if (update != null) {
                                app.opentv.update.UpdateHub.state.value =
                                    app.opentv.update.UpdateUiState.Available(update)
                            }
                            checking = false
                        }
                    },
                ) { Text(if (checking) stringResource(R.string.about_checking) else stringResource(R.string.about_check_updates)) }
                updateLine?.let {
                    Spacer(Modifier.width(16.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Section(stringResource(R.string.about_what_is_title)) {
            Text(
                stringResource(R.string.about_what_is_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        Section(stringResource(R.string.about_built_open_title)) {
            Text(
                stringResource(R.string.about_built_open_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        Section(stringResource(R.string.about_support_title)) {
            Text(
                stringResource(R.string.about_support_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinkLine(stringResource(R.string.about_sponsor_github), "github.com/sponsors/legionnaireneyland")
            Spacer(Modifier.height(12.dp))
            LinkLine(
                stringResource(R.string.about_donate_paypal),
                "leetobin1982@gmail.com",
                url = "https://www.paypal.com/donate/?business=leetobin1982@gmail.com&item_name=Support+OpenTV&no_recurring=0&currency_code=GBP",
            )
            Spacer(Modifier.height(12.dp))
            val donateQr = remember { QrCodes.render("https://github.com/sponsors/legionnaireneyland", 400) }
            donateQr?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.about_scan_to_donate),
                    modifier = Modifier.size(180.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.about_scan_to_donate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.about_donation_goes_to_upstream),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        Section(stringResource(R.string.about_licence_links_title)) {
            LinkLine(
                stringResource(R.string.about_licence_label),
                ProjectInfo.LICENSE_NAME,
                url = ProjectInfo.LICENSE_URL,
            )
            LinkLine(stringResource(R.string.about_source_code), ProjectInfo.SOURCE_URL.removePrefix("https://"))
            LinkLine(stringResource(R.string.about_report_bug), ProjectInfo.ISSUES_URL.removePrefix("https://"))
            LinkLine(stringResource(R.string.about_install_page), ProjectInfo.RELEASES_URL.removePrefix("https://"))
            LinkLine(stringResource(R.string.about_upstream_project), ProjectInfo.UPSTREAM_URL.removePrefix("https://"))
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.about_free_software_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
    Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

/**
 * A tappable link row. Focusable on purpose: on a TV this is what lets the d-pad travel down the
 * About screen (and so scroll it) — a screen of plain Text has nothing to carry the scroll. Clicking
 * opens the URL in a browser, or shows it as a toast where there is no browser (some TV boxes).
 */
@Composable
private fun LinkLine(label: String, value: String, url: String = "https://$value") {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (focused) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                else Modifier,
            )
            .clickable {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { Toast.makeText(context, url, Toast.LENGTH_LONG).show() }
            }
            .padding(vertical = 6.dp, horizontal = 6.dp),
    ) {
        Text(
            "$label:  ",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
