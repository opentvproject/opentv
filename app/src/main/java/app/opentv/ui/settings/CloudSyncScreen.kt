/* SPDX-License-Identifier: GPL-3.0-or-later */
package app.opentv.ui.settings

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import app.opentv.pairing.QrCodes
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.sync.cloud.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.text.DateFormat
import java.util.Date

/** Focusable Material controls and a vertical list support D-pad, touch, and system keyboards. */
@Composable
fun CloudSyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cloud = remember { ServiceLocator.get(context).cloudSync }
    val prefs = cloud.preferences
    val enabled by prefs.enabled.collectAsState()
    val status by prefs.status.collectAsState()
    val lastSuccess by prefs.lastSuccess.collectAsState()
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(cloud.isSignedIn()) }
    var hasKey by remember { mutableStateOf(cloud.hasKey()) }
    var url by remember { mutableStateOf(prefs.url) }
    var publishable by remember { mutableStateOf(prefs.publishableKey) }
    var nickname by remember { mutableStateOf(prefs.nickname) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var savedRecovery by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var ownCode by remember { mutableStateOf<String?>(null) }
    var comparison by remember { mutableStateOf<String?>(null) }
    var receiving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(JsonArray(emptyList())) }
    var categories by remember { mutableStateOf(prefs.categories()) }
    val errorText = stringResource(R.string.cloud_error)
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; message = ""
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: CloudApiException) { message = e.message.orEmpty() }
            catch (_: Exception) { message = errorText }
            finally { signedIn = cloud.isSignedIn(); hasKey = cloud.hasKey(); busy = false }
        }
    }
    // Passwords and recovery material are never saved in Compose saved-state or screenshots.
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        val secureBefore = (window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { cloud.cancelPairing(); if (!secureBefore) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(ownCode) {
        if (ownCode == null) return@LaunchedEffect
        repeat(200) {
            delay(3000)
            try {
                val value = cloud.pollPairing()
                if (value != null) { comparison = value; receiving = true; return@LaunchedEffect }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = errorText; ownCode = null; return@LaunchedEffect }
        }
        ownCode = null; message = errorText
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row {
            Text(stringResource(R.string.cloud_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 780.dp)) {
            item { Text(stringResource(R.string.cloud_description)) }
            item { Text(status, style = MaterialTheme.typography.titleMedium) }
            if (message.isNotBlank()) item { Text(message, color = MaterialTheme.colorScheme.error) }
            if (lastSuccess > 0) item { Text(stringResource(R.string.cloud_last_sync, DateFormat.getDateTimeInstance().format(Date(lastSuccess)))) }
            item {
                Button(enabled = !busy && signedIn && hasKey, onClick = { action { cloud.setEnabled(!enabled) } }) {
                    Text(stringResource(if (enabled) R.string.cloud_disable else R.string.cloud_enable))
                }
            }
            if (!signedIn) {
                item { CloudField(stringResource(R.string.cloud_url), url) { url = it } }
                item { CloudField(stringResource(R.string.cloud_publishable), publishable) { publishable = it } }
                item { CloudField(stringResource(R.string.cloud_nickname), nickname) { nickname = it } }
                item { CloudField(stringResource(R.string.cloud_email), email) { email = it } }
                item { CloudField(stringResource(R.string.cloud_password), password, true) { password = it } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = !busy && email.isNotBlank() && password.isNotEmpty(), onClick = { action {
                            try { cloud.configure(url, publishable, nickname); message = cloud.signIn(email, password, false) }
                            finally { password = "" }
                        } }) { Text(stringResource(R.string.cloud_sign_in)) }
                        OutlinedButton(enabled = !busy && email.isNotBlank() && password.isNotEmpty(), onClick = { action {
                            try { cloud.configure(url, publishable, nickname); message = cloud.signIn(email, password, true) }
                            finally { password = "" }
                        } }) { Text(stringResource(R.string.cloud_create_account)) }
                    }
                }
            } else {
                if (!enabled) {
                    item { Text(stringResource(R.string.cloud_reauth_help)) }
                    item { CloudField(stringResource(R.string.cloud_email), email) { email = it } }
                    item { CloudField(stringResource(R.string.cloud_password), password, true) { password = it } }
                    item { OutlinedButton(enabled = !busy && email.isNotBlank() && password.isNotEmpty(), onClick = { action {
                        try { message = cloud.signIn(email, password, false) } finally { password = "" }
                    } }) { Text(stringResource(R.string.cloud_reauth)) } }
                }
                item { CloudField(stringResource(R.string.cloud_nickname), nickname) { nickname = it } }
                item { OutlinedButton(enabled = !busy, onClick = { action { cloud.rename(nickname); devices = cloud.devices() } }) { Text(stringResource(R.string.cloud_save_nickname)) } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = enabled && !busy, onClick = { CloudSyncWorker.enqueue(context) }) { Text(stringResource(R.string.cloud_sync_now)) }
                        OutlinedButton(enabled = !busy, onClick = { action { devices = cloud.devices() } }) { Text(stringResource(R.string.cloud_devices)) }
                    }
                }
                devices.forEach { element ->
                    val device = element.jsonObject
                    if (device["revoked_at"] == JsonNull) item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(device.getValue("nickname").jsonPrimitive.content, modifier = Modifier.weight(1f))
                            OutlinedButton(enabled = !busy, onClick = { action { cloud.unlink(device.getValue("id").jsonPrimitive.content); if (cloud.isSignedIn()) devices = cloud.devices() } }) {
                                Text(stringResource(R.string.cloud_unlink))
                            }
                        }
                    }
                }
                item { Text(stringResource(R.string.cloud_categories), style = MaterialTheme.typography.titleMedium) }
                CloudCategory.entries.forEach { category -> item {
                    val label = when (category) {
                        CloudCategory.PROVIDERS -> R.string.cloud_providers; CloudCategory.PLAYLISTS -> R.string.cloud_playlists
                        CloudCategory.FAVORITES -> R.string.cloud_favorites; CloudCategory.WATCH -> R.string.cloud_watch
                        CloudCategory.PROFILES -> R.string.cloud_profiles; CloudCategory.SETTINGS -> R.string.cloud_settings
                    }
                    OutlinedButton(enabled = !busy, onClick = {
                        categories = if (category in categories) categories - category else categories + category
                        prefs.setCategories(categories); cloud.localChanged(); if (enabled) CloudSyncWorker.enqueue(context)
                    }) { Text("${if (category in categories) "✓ " else ""}${stringResource(label)}") }
                } }
                if (!hasKey) {
                    item { CloudField(stringResource(R.string.cloud_recovery), recovery, true) { recovery = it } }
                    item { Button(enabled = !busy && recovery.isNotBlank(), onClick = { action { try { cloud.recover(recovery) } finally { recovery = "" } } }) { Text(stringResource(R.string.cloud_recover)) } }
                    item { OutlinedButton(enabled = !busy, onClick = { action { ownCode = cloud.beginPairing(); comparison = null } }) { Text(stringResource(R.string.cloud_request_pair)) } }
                } else {
                    item { Text(stringResource(R.string.cloud_recovery_warning)) }
                    item { OutlinedButton(enabled = !busy, onClick = { action { savedRecovery = if (savedRecovery == null) cloud.recoveryKey() else null } }) { Text(stringResource(R.string.cloud_show_recovery)) } }
                    if (savedRecovery != null) item { Text(savedRecovery!!, style = MaterialTheme.typography.titleMedium) }
                    item { CloudField(stringResource(R.string.cloud_pair_code), code) { code = it.take(10).uppercase() } }
                    item { OutlinedButton(enabled = !busy && code.length == 10, onClick = { action { comparison = cloud.approvePairing(code); receiving = false } }) { Text(stringResource(R.string.cloud_approve_pair)) } }
                }
                if (ownCode != null) item {
                    Text(stringResource(R.string.cloud_code_expires, ownCode!!), style = MaterialTheme.typography.headlineSmall)
                    val bitmap = remember(ownCode) { QrCodes.render("OpenTV pairing: $ownCode", 256) }
                    if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = stringResource(R.string.cloud_pair_qr), modifier = Modifier.size(180.dp))
                }
                if (comparison != null) item {
                    Text(stringResource(R.string.cloud_compare, comparison!!), style = MaterialTheme.typography.titleLarge)
                    if (receiving) Button(enabled = !busy, onClick = { action { cloud.confirmPairing(); ownCode = null; comparison = null } }) { Text(stringResource(R.string.cloud_confirm_pair)) }
                }
                item { OutlinedButton(enabled = !busy, onClick = { action { try { cloud.signOut() } finally { savedRecovery = null; devices = JsonArray(emptyList()); ownCode = null; comparison = null } } }) { Text(stringResource(R.string.cloud_sign_out)) } }
            }
        }
    }
}

@Composable
private fun CloudField(label: String, value: String, secret: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}
