/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.opentv.R
import app.opentv.core.isIgnoringBatteryOptimizations

/**
 * A once-per-session nudge shown the first time the user records or schedules while OpenTV still
 * isn't exempt from battery optimisation. It doesn't block the recording — that has already
 * started — it just offers to grant the exemption so the capture survives standby, the way a
 * set-top box does. Once granted (or once shown this session) it stays out of the way.
 */
object RecordingBackgroundPrompt {

    @Volatile private var shownThisSession = false

    /** True at most once per app session, and only while OpenTV still isn't Doze-exempt. */
    fun shouldShow(context: Context): Boolean =
        !shownThisSession && !context.isIgnoringBatteryOptimizations()

    fun markShown() {
        shownThisSession = true
    }
}

/**
 * The dialog itself. [onAllow] should fire the system battery-optimisation request; [onDismiss] is
 * "Not now". Focus lands on Allow so a single OK press on the remote grants it.
 */
@Composable
fun RecordingBackgroundDialog(onAllow: () -> Unit, onDismiss: () -> Unit) {
    val allowFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth().widthIn(max = 460.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
        ) {
            Text(
                stringResource(R.string.rec_background_prompt_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.rec_background_prompt_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.rec_background_prompt_not_now))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onAllow, modifier = Modifier.focusRequester(allowFocus)) {
                    Text(stringResource(R.string.rec_background_prompt_allow))
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { allowFocus.requestFocus() } }
}
