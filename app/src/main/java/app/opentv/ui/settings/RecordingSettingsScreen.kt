/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.core.isIgnoringBatteryOptimizations
import app.opentv.core.requestIgnoreBatteryOptimizations
import app.opentv.recording.RecordingStorage
import app.opentv.recording.SmbClient
import app.opentv.recording.SmbConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where recordings are written: this box's internal storage, a NAS over SMB (Synology and the
 * like), or a plugged-in USB / external drive. The NAS credentials live only on this device,
 * exactly like a provider's login; the USB drive is addressed through a folder the user grants
 * with the system picker (Storage Access Framework), which needs no storage permission.
 */
@Composable
fun RecordingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { ServiceLocator.get(context).settings }
    val scope = rememberCoroutineScope()

    var target by remember { mutableStateOf(settings.recordingTarget.value) }
    var host by remember { mutableStateOf(settings.smbHost.value) }
    var share by remember { mutableStateOf(settings.smbShare.value) }
    var folder by remember { mutableStateOf(settings.smbFolder.value) }
    var user by remember { mutableStateOf(settings.smbUser.value) }
    var password by remember { mutableStateOf(settings.smbPassword.value) }
    var usbTree by remember { mutableStateOf(settings.usbTreeUri.value) }
    var usbLabel by remember { mutableStateOf(settings.usbFolderLabel.value) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    // Recording behaviour. These apply the moment they're changed so they hold whether the user
    // leaves via Done or Save.
    var padStart by remember { mutableStateOf(settings.recordPadStartMinutes.value) }
    var padEnd by remember { mutableStateOf(settings.recordPadEndMinutes.value) }
    var autoSwitch by remember { mutableStateOf(settings.recordAutoSwitch.value) }
    var livePause by remember { mutableStateOf(settings.livePauseEnabled.value) }

    // The SAF folder picker. On a granted tree we take a persistable read/write permission so the
    // grant (and thus playback of what we record there) survives restarts, then remember the folder.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            val label = DocumentFile.fromTreeUri(context, uri)?.name
                ?: uri.lastPathSegment ?: context.getString(R.string.recset_usb_label)
            settings.setUsbTree(uri.toString(), label)
            usbTree = uri.toString()
            usbLabel = label
            target = AppSettings.RecordingTarget.USB
            status = context.getString(R.string.recset_usb_saving_to, label)
        }
    }

    fun persist() {
        settings.setSmbConfig(host, share, folder, user, password)
        // Don't commit USB as the destination unless a folder has actually been granted, so
        // leaving the screen without picking one can never strand recordings with nowhere to go.
        val safeTarget =
            if (target == AppSettings.RecordingTarget.USB && usbTree == null) settings.recordingTarget.value
            else target
        settings.setRecordingTarget(safeTarget)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_recording_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { persist(); onBack() }) { Text(stringResource(R.string.common_done)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.recset_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 720.dp),
        )

        Spacer(Modifier.height(20.dp))
        SectionCard(stringResource(R.string.recset_background_title)) {
            BackgroundStatus()
        }

        Spacer(Modifier.height(20.dp))
        SectionCard(stringResource(R.string.recset_behaviour_title)) {
            StepperRow(
                label = stringResource(R.string.recset_pad_start),
                subtitle = stringResource(R.string.recset_pad_start_desc),
                value = padStart,
                unit = stringResource(R.string.recset_minutes_short),
                min = 0, max = 30,
            ) { padStart = it; settings.setRecordPadding(padStart, padEnd) }
            StepperRow(
                label = stringResource(R.string.recset_pad_end),
                subtitle = stringResource(R.string.recset_pad_end_desc),
                value = padEnd,
                unit = stringResource(R.string.recset_minutes_short),
                min = 0, max = 60,
            ) { padEnd = it; settings.setRecordPadding(padStart, padEnd) }
            ToggleRow(
                label = stringResource(R.string.recset_autoswitch),
                subtitle = stringResource(R.string.recset_autoswitch_desc),
                checked = autoSwitch,
            ) { autoSwitch = it; settings.setRecordAutoSwitch(it) }
            ToggleRow(
                label = stringResource(R.string.recset_livepause),
                subtitle = stringResource(R.string.recset_livepause_desc),
                checked = livePause,
            ) { livePause = it; settings.setLivePauseEnabled(it) }
        }

        Spacer(Modifier.height(20.dp))
        SectionCard(stringResource(R.string.recset_save_to)) {
            TargetRow(
                label = stringResource(R.string.recset_internal_label),
                subtitle = RecordingStorage.internalDir(context).absolutePath,
                selected = target == AppSettings.RecordingTarget.INTERNAL,
            ) { target = AppSettings.RecordingTarget.INTERNAL }
            Spacer(Modifier.height(8.dp))
            TargetRow(
                label = stringResource(R.string.recset_smb_label),
                subtitle = stringResource(R.string.recset_smb_subtitle),
                selected = target == AppSettings.RecordingTarget.SMB,
            ) { target = AppSettings.RecordingTarget.SMB }
            Spacer(Modifier.height(8.dp))
            TargetRow(
                label = stringResource(R.string.recset_usb_label),
                subtitle = stringResource(R.string.recset_usb_subtitle),
                selected = target == AppSettings.RecordingTarget.USB,
            ) { target = AppSettings.RecordingTarget.USB }
        }

        if (target == AppSettings.RecordingTarget.SMB) {
            Spacer(Modifier.height(16.dp))
            SectionCard(stringResource(R.string.recset_nas_connection)) {
                Field(stringResource(R.string.recset_field_server), host, "192.168.1.10") { host = it }
                Field(stringResource(R.string.recset_field_share), share, "video") { share = it }
                Field(stringResource(R.string.recset_field_folder), folder, "OpenTV") { folder = it }
                Field(stringResource(R.string.recset_field_username), user, "") { user = it }
                Field(stringResource(R.string.recset_field_password), password, "", isPassword = true) { password = it }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !testing && host.isNotBlank() && share.isNotBlank(),
                        onClick = {
                            testing = true
                            status = context.getString(R.string.recset_status_testing)
                            val cfg = SmbConfig(host.trim(), share.trim(), folder.trim(), user, password)
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { SmbClient.test(cfg) }
                                }
                                testing = false
                                status = result.fold(
                                    onSuccess = { context.getString(R.string.recset_status_connected) },
                                    onFailure = { context.getString(R.string.recset_status_failed, it.message ?: it.javaClass.simpleName) },
                                )
                            }
                        },
                    ) { Text(if (testing) stringResource(R.string.recset_status_testing) else stringResource(R.string.recset_test_connection)) }
                }
                status?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (target == AppSettings.RecordingTarget.USB) {
            Spacer(Modifier.height(16.dp))
            SectionCard(stringResource(R.string.recset_usb_section)) {
                val label = usbLabel
                if (usbTree != null && label != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.recset_usb_saving_to, label),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.recset_usb_none),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    runCatching { folderPicker.launch(null) }
                        .onFailure { status = context.getString(R.string.recset_usb_no_picker) }
                }) {
                    Text(
                        if (usbTree != null) stringResource(R.string.recset_usb_change)
                        else stringResource(R.string.recset_usb_choose),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.recset_usb_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (target == AppSettings.RecordingTarget.USB && usbTree == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.recset_usb_need_folder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (target == AppSettings.RecordingTarget.INTERNAL) {
            Spacer(Modifier.height(20.dp))
            SectionCard(stringResource(R.string.recset_storage_title)) {
                StorageInfo()
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            enabled = target != AppSettings.RecordingTarget.USB || usbTree != null,
            onClick = { persist(); status = context.getString(R.string.recset_status_saved) },
        ) { Text(stringResource(R.string.common_save)) }
    }
}

/** Used-by-recordings and free-space readout for the box's internal storage. */
@Composable
private fun StorageInfo() {
    val context = LocalContext.current
    var used by remember { mutableLongStateOf(-1L) }
    var free by remember { mutableLongStateOf(-1L) }
    var count by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val (u, c, f) = withContext(Dispatchers.IO) {
            val dir = RecordingStorage.internalDir(context)
            val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
            Triple(files.sumOf { it.length() }, files.size, dir.usableSpace)
        }
        used = u; count = c; free = f
    }

    if (used < 0) {
        Text(stringResource(R.string.recset_storage_reading), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text(
            stringResource(R.string.recset_storage_used, formatBytes(used), count),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.recset_storage_free, formatBytes(free)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
}

/** A label + subtitle with a Material switch on the right. */
@Composable
private fun ToggleRow(label: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A label + subtitle with a –/value/+ stepper on the right, clamped to [min]..[max]. */
@Composable
private fun StepperRow(
    label: String,
    subtitle: String,
    value: Int,
    unit: String,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        StepButton(Icons.Filled.Remove, enabled = value > min) { onChange((value - 1).coerceAtLeast(min)) }
        Text(
            "$value $unit",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.widthIn(min = 64.dp).padding(horizontal = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepButton(Icons.Filled.Add, enabled = value < max) { onChange((value + 1).coerceAtMost(max)) }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        enabled -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
    }
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            // The clickable first, then the focusable: Compose finds the key handler of a
            // focused element by walking the chain and stopping at the first focus target,
            // so a focusable() in front of the clickable hides its own handling — and OK
            // stops working on the stepper.
            .clickable(enabled = enabled, onClick = onClick)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) fg else fg.copy(alpha = 0.4f))
    }
}

/**
 * Whether OpenTV is allowed to keep recording in the background (exempt from battery optimisation).
 * Reads *Allowed* or *Not allowed — Fix*; tapping Fix opens the system dialog to grant it. The
 * status is re-read every time the screen resumes, so it flips to Allowed the moment the user
 * comes back from granting it.
 */
@Composable
private fun BackgroundStatus() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = context.isIgnoringBatteryOptimizations()
                // Just came back from granting it: say so out loud so it's unmistakable.
                if (now && !allowed) {
                    Toast.makeText(context, context.getString(R.string.recset_background_toast), Toast.LENGTH_LONG).show()
                }
                allowed = now
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (allowed) {
        // A plain, unmistakable "it's live" confirmation box — the thing the user is looking for
        // when they come back from the system dialog.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF17351F))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF4ADE80),
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.recset_background_on_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF4ADE80),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.recset_background_on_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(R.string.recset_background_not_allowed),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { context.requestIgnoreBatteryOptimizations() }) {
                    Text(stringResource(R.string.recset_background_fix))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.recset_background_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 720.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { content() }
    }
}

@Composable
private fun TargetRow(label: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    isPassword: Boolean = false,
    onChange: (String) -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = true,
        // A recording password is a real credential (a NAS login) — don't paint it on a living-room
        // screen. Masked by default, with an eye to reveal it if you need to check what you typed.
        visualTransformation = if (isPassword && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (!isPassword) null else {
            {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(
                            if (revealed) R.string.recset_pw_hide else R.string.recset_pw_show,
                        ),
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
