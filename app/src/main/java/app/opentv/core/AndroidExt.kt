/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.annotation.SuppressLint
import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * The [Activity] behind a Compose [Context], unwrapping the `ContextWrapper` chain Compose hands
 * you. Needed for the handful of things that are genuinely window-level — like holding the screen
 * awake during playback — where a view flag isn't enough on every TV box.
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Whether this device is a television.
 *
 * Two signals, either of which is enough: the system is in television UI mode, or the
 * device declares a leanback/television feature. This is only the app's *opinion* — the
 * user can override it in Settings (see [AppSettings.AppMode]), because a Google TV stick
 * that reports a phone profile is a real thing and so is a phone on a wall-mounted screen.
 */
fun Context.isTelevisionDevice(): Boolean {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val packageManager = packageManager
    return packageManager.hasSystemFeature("android.software.leanback") ||
        packageManager.hasSystemFeature("android.hardware.type.television")
}

/**
 * Whether the OS is currently exempting OpenTV from battery optimisation ("Doze"). When it isn't,
 * Android TV / Fire TV are free to freeze the app while the box is in standby, which stops an
 * in-progress or scheduled recording dead — the single biggest reason captures don't survive the
 * device going to sleep. Pre-Marshmallow there is no Doze, so it is always treated as exempt.
 */
fun Context.isIgnoringBatteryOptimizations(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(packageName)
}

/**
 * Opens the system dialog that asks the user to let OpenTV ignore battery optimisation — the
 * exemption a set-top box relies on to keep recording while asleep. This is the correct,
 * user-granted flow; it cannot (and should not) be granted silently. A few TV boxes ship without
 * the per-app request screen, so we fall back to the general battery-optimisation settings list
 * where the user can still find OpenTV and allow it.
 */
@SuppressLint("BatteryLife")
fun Context.requestIgnoreBatteryOptimizations() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:$packageName"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val launched = runCatching { startActivity(direct) }.isSuccess
    if (!launched) {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
