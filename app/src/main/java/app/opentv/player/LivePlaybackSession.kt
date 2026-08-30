/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import app.opentv.core.AppSettings
import java.util.EnumSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Owns live playback across the guide and full-screen destinations.
 *
 * The guide and player used to own separate ExoPlayer instances. Navigating between them stopped
 * one stream and opened the same URL again in the other, producing a visible reload. This session
 * keeps one controller alive and lets each destination attach its own PlayerView to the existing
 * player. An explicit, short handoff window preserves playback while Navigation Compose swaps the
 * destinations; leaving live TV for any unrelated screen still stops immediately.
 */
class LivePlaybackSession(
    context: Context,
    httpClient: OkHttpClient,
    settings: AppSettings,
) {
    enum class Surface { GUIDE, PLAYER }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val attachedSurfaces = EnumSet.noneOf(Surface::class.java)
    private var pendingHandoff: Surface? = null
    private var handoffExpiryJob: Job? = null

    val controller = PlayerController(
        context = context,
        scope = scope,
        httpClient = httpClient,
        subtitlesEnabled = settings.subtitlesEnabled.value,
        dvr = settings.livePauseEnabled.value,
    )

    fun attach(surface: Surface) {
        attachedSurfaces += surface
        if (pendingHandoff == surface) {
            pendingHandoff = null
            handoffExpiryJob?.cancel()
            handoffExpiryJob = null
        }
    }

    fun detach(surface: Surface) {
        attachedSurfaces -= surface
        if (attachedSurfaces.isNotEmpty()) return

        // If navigation is between the two live surfaces, the destination gets a brief chance to
        // attach. All other departures stop synchronously so audio cannot leak into another tab.
        if (pendingHandoff == null) stop()
    }

    fun prepareHandoff(target: Surface) {
        pendingHandoff = target
        handoffExpiryJob?.cancel()
        handoffExpiryJob = scope.launch {
            delay(HANDOFF_TIMEOUT_MILLIS)
            if (pendingHandoff == target) {
                pendingHandoff = null
                handoffExpiryJob = null
                if (attachedSurfaces.isEmpty()) stop()
            }
        }
    }

    /** Returns true when the current media item and buffer were reused without a new prepare. */
    fun play(
        request: PlayerController.Request,
        debounce: Boolean,
        debounceMillis: Long? = null,
    ): Boolean {
        if (controller.canReuse(request)) return true
        controller.play(request, debounce = debounce, debounceMillis = debounceMillis)
        return false
    }

    fun playPreview(request: PlayerController.Request, debounce: Boolean): Boolean =
        play(
            request = request,
            debounce = debounce,
            debounceMillis = if (debounce) GUIDE_SWITCH_DEBOUNCE_MILLIS else null,
        )

    fun stop() {
        pendingHandoff = null
        handoffExpiryJob?.cancel()
        handoffExpiryJob = null
        controller.stop()
    }

    private companion object {
        const val HANDOFF_TIMEOUT_MILLIS = 1_500L
        const val GUIDE_SWITCH_DEBOUNCE_MILLIS = 700L
    }
}
