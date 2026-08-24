/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Runs terminal recording cleanup even when the capture job was canceled by a user stop. */
internal object RecordingFinalizer {
    suspend fun run(block: suspend () -> Unit) {
        withContext(NonCancellable) { block() }
    }
}
