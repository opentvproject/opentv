/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.opentv.R
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

/**
 * A duration as `m:ss`, or `h:mm:ss` when it runs past the hour.
 *
 * Both players show times and they have to agree: a transport that says "1:02:03" in one
 * place and "62:03" in another is the kind of small wrongness that makes people distrust
 * the rest of the screen.
 */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * How far back a live stream can be rewound, as the user reads it: "-2:00".
 *
 * A leading minus is the convention every TV app uses for "that much behind the live
 * edge", and it is unambiguous next to the live edge itself.
 */
fun formatBehind(ms: Long): String = "-${formatDuration(ms)}"

/**
 * A finished programme's time, with the day spelled out when it is not today.
 *
 * "20:00" for today, "Yesterday 20:00" for the day before, "Mon 20:00" further back. The
 * catch-up list is mostly yesterday, and a bare clock time would not say which day it was.
 */
@Composable
fun formatProgrammeTime(utcMillis: Long, nowUtcMillis: Long): String {
    val time = formatTime(utcMillis)
    val then = java.util.Calendar.getInstance().apply { timeInMillis = utcMillis }
    val now = java.util.Calendar.getInstance().apply { timeInMillis = nowUtcMillis }
    // Days between the two dates, counted on the calendar rather than in milliseconds, so a
    // clock change in the night does not make yesterday look like two days ago.
    val days = (now.get(java.util.Calendar.YEAR) - then.get(java.util.Calendar.YEAR)) * 365 +
        (now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR))
    return when (days) {
        0 -> time
        1 -> stringResource(R.string.catchup_yesterday, time)
        else -> "${dayFormat.format(Date(utcMillis))} $time"
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())

fun formatTime(utcMillis: Long): String = timeFormat.format(Date(utcMillis))
