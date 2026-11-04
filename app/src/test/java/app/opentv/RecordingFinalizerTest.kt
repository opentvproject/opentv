/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.recording.RecordingFinalizer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFinalizerTest {
    @Test
    fun cleanupCanSuspendAndCompleteAfterCaptureCancellation() = runTest {
        var cleanupStarted = false
        var cleanupFinished = false
        val capture = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                RecordingFinalizer.run {
                    cleanupStarted = true
                    yield()
                    cleanupFinished = true
                }
            }
        }

        capture.cancelAndJoin()

        assertTrue(cleanupStarted)
        assertTrue(cleanupFinished)
    }
}
