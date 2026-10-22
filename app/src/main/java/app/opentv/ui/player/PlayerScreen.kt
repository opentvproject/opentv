/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Slider
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.core.SleepTimer
import app.opentv.core.findActivity
import app.opentv.core.requestIgnoreBatteryOptimizations
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.shownName
import app.opentv.player.PlaybackQueue
import app.opentv.ui.formatBehind
import app.opentv.ui.catchupUrl
import app.opentv.ui.formatProgrammeTime
import app.opentv.ui.formatDuration
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.channels.GuideGrid
import app.opentv.ui.channels.VerticalListFocus
import app.opentv.player.PlayerController
import app.opentv.ui.RecordingBackgroundDialog
import app.opentv.ui.RecordingBackgroundPrompt
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Full-screen live playback.
 *
 * The video fills the screen with no chrome. A single control bar slides up from the bottom and
 * holds everything — transport (play/pause, rewind, forward), and pickers for subtitles, audio,
 * quality and aspect ratio. It hides after a few seconds and any remote button brings it back.
 * Nothing is ever left permanently painted over the picture.
 *
 * Subtitles and audio come from the actual tracks in the stream ([PlayerController.tracks]) and
 * are selected explicitly — that is the fix for "captions on but nothing shows", which happens
 * when the renderer is merely enabled and left to guess a language.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    channelId: Long?,
    onBack: () -> Unit,
    // A programme that already aired plays back from the provider's archive, which is a
    // different kind of stream — the VOD player, not this one.
    onPlayCatchup: (key: String, url: String, title: String, userAgent: String?) -> Unit = { _, _, _, _ -> },
) {
    val context = LocalContext.current
    val view = LocalView.current
    val graph = remember { ServiceLocator.get(context) }
    val settings = remember { graph.settings }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val subtitlesDefault by settings.subtitlesEnabled.collectAsState()
    val controller = remember {
        PlayerController(
            context, scope, graph.streamingHttpClient,
            subtitlesEnabled = settings.subtitlesEnabled.value,
            // Opt-in shallow DVR so the transport's pause/rewind actually holds on a live stream.
            dvr = settings.livePauseEnabled.value,
        )
    }
    val state by controller.state.collectAsState()
    val tracks by controller.tracks.collectAsState()
    // What's recording right now, so the Record button can show as armed for this channel.
    val activeRecordings by graph.recordingRepository.observeActive().collectAsState(initial = emptyList())

    // The mode the user chose: a television or a phone. It decides two things here — whether
    // the picture is held awake, and whether the television's power button is offered at all.
    val appMode by settings.appMode.collectAsState()
    val isTelevision = appMode == AppSettings.AppMode.TV

    // Hold the screen awake while the player is on screen — on a phone or tablet, where the
    // screen dimming mid-film is the app being rude. On a TV it is the other way round: the
    // screensaver *is* the television's ambience, and holding the picture on means the
    // viewer cannot turn the set off and leave the programme playing in the background. A
    // view-level keepScreenOn flag isn't reliable on every TV box, so the window flag is
    // set directly — on anything that is not a television.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        if (!isTelevision) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view.keepScreenOn = true
        }
        onDispose {
            if (!isTelevision) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                view.keepScreenOn = false
            }
            controller.release()
            scope.cancel()
        }
    }

    var variants by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var currentId by remember { mutableStateOf<Long?>(null) }
    // The channel we were on before this one — powers the "Last channel" recall in the list.
    var previousId by remember { mutableStateOf<Long?>(null) }
    // Digits typed on the remote accumulate here, then jump to that channel number after a beat.
    var numberEntry by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(settings.playerResizeMode.value) }
    // The transport's scrub bar. For a live stream the bar is a *window*, not a file: the
    // right end is the live edge and the left end is as far back as the player can reach.
    // For anything with a duration it is the ordinary position/duration pair.
    var positionMs by remember { mutableLongStateOf(0L) }
    var barStartMs by remember { mutableLongStateOf(0L) }
    var barEndMs by remember { mutableLongStateOf(0L) }
    var barIsLive by remember { mutableStateOf(false) }
    var canRewind by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    var controlsVisible by remember { mutableStateOf(true) }
    // Whether the d-pad is currently on the control bar. The bar's own buttons are the only
    // things a remote can reach with the arrows, so the key handler asks this before it
    // decides that up/down means "change the channel".
    var barHasFocus by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var channelListVisible by remember { mutableStateOf(false) }
    // The guide, over the video. Watching a channel and wanting to know what else is on is
    // the most ordinary thing a viewer does, and making it leave the player to find out is
    // the kind of small hostility that makes an app feel like a website.
    var guideVisible by remember { mutableStateOf(false) }
    // What already aired on this channel, and the provider's way back to it. Opened from the
    // player because "I missed the show that just ended" is asked while watching it.
    var catchupVisible by remember { mutableStateOf(false) }
    var catchupProgrammes by remember { mutableStateOf<List<Programme>>(emptyList()) }
    // Whether this channel has an archive at all. The button is only worth showing when there
    // is something behind it.
    val channelHasArchive = variants.any { it.tvArchive }
    // The guide's data, and the view model behind it. Collected only while the guide is
    // open: the EPG index is the most expensive thing the app builds, and a viewer who is
    // watching television should not pay for it.
    val guideViewModel: ChannelsViewModel = viewModel()
    val guideWindowStart by guideViewModel.windowStartMillis.collectAsState()
    val guideRows by remember(guideVisible) {
        if (guideVisible) guideViewModel.rows else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    var interaction by remember { mutableIntStateOf(0) }
    // Offered once per session the first time the user records here while OpenTV isn't exempt from
    // battery optimisation, so the capture survives the screen sleeping. Never blocks recording.
    var showBackgroundPrompt by remember { mutableStateOf(false) }

    // Picture-in-picture. While the player is on it is "eligible" to shrink to a floating window
    // (pressing Home does it, handled in MainActivity); [inPip] drives hiding all the chrome.
    val inPip by app.opentv.core.PipState.inPip.collectAsState()
    val pipSupported = remember {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE,
            )
    }
    DisposableEffect(Unit) {
        app.opentv.core.PipState.eligible = true
        onDispose {
            app.opentv.core.PipState.eligible = false
            app.opentv.core.PipState.isPlaying = false
        }
    }
    LaunchedEffect(paused) { app.opentv.core.PipState.isPlaying = !paused }
    val barFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val guideFocus = remember { FocusRequester() }
    val catchupFocus = remember { FocusRequester() }

    // The channel list you were browsing, for channel up/down and the in-player list. Snapshotted
    // on entry so it doesn't shift under you mid-session.
    val queue = remember { PlaybackQueue.items }

    fun reveal() {
        controlsVisible = true
        interaction++
    }

    fun tuneTo(channel: Channel) {
        currentId = channel.id
        paused = false
        settings.lastChannelId = channel.id
        scope.launch {
            val source = graph.sourceRepository.byId(channel.sourceId)
            // Xtream/M3U carry a ready URL; a Stalker channel's URL is minted here from its cmd.
            val url = graph.catalogRepository.resolvePlaybackUrl(channel, source)
            controller.play(
                PlayerController.Request(
                    url = url,
                    title = channel.shownName,
                    userAgent = source?.userAgent ?: "OpenTV/0.1 (Android)",
                    isLive = true,
                ),
                debounce = false,
            )
        }
    }

    fun playChannelId(id: Long) {
        // Remember where we came from so "Last channel" can bounce straight back. Quality switches
        // go through tuneTo directly, so they never count as a channel change here.
        currentId?.let { if (it != id) previousId = it }
        scope.launch {
            val channel = graph.catalogRepository.channel(id) ?: return@launch
            variants = graph.catalogRepository.variants(channel)
            tuneTo(variants.firstOrNull { it.id == channel.id } ?: channel)
        }
    }

    fun zapBy(delta: Int) {
        if (queue.isEmpty()) return
        val cur = queue.indexOfFirst { it.id == currentId }.let { if (it < 0) 0 else it }
        val next = (cur + delta).coerceIn(0, queue.size - 1)
        if (next != cur) playChannelId(queue[next].id)
    }

    fun toggleRecord() {
        val active = activeRecordings.firstOrNull { it.channelId == currentId }
        if (active != null) {
            graph.recordingEngine.stop(active.id)
        } else {
            val channel = variants.firstOrNull { it.id == currentId } ?: return
            scope.launch { graph.recordingEngine.startChannel(channel) }
            if (RecordingBackgroundPrompt.shouldShow(context)) {
                RecordingBackgroundPrompt.markShown()
                showBackgroundPrompt = true
            }
        }
        interaction++
    }

    LaunchedEffect(channelId) {
        val id = channelId ?: return@LaunchedEffect
        val channel = graph.catalogRepository.channel(id) ?: return@LaunchedEffect
        variants = graph.catalogRepository.variants(channel)
        tuneTo(variants.firstOrNull { it.id == channel.id } ?: channel)
    }

    // On a television, the box takes the screen away when the set is left alone — that is the
    // screensaver, and it is the television going dark. A television that keeps talking to an
    // empty room is not a television, so the channel stops with the picture, and starts again
    // when the viewer comes back: the way a set wakes up with the channel still on it.
    val lifecycleOwner = LocalLifecycleOwner.current
    var stoppedWithTheScreen by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, isTelevision) {
        val observer = LifecycleEventObserver { _, event ->
            when {
                !isTelevision -> Unit
                event == Lifecycle.Event.ON_STOP -> {
                    controller.stop()
                    stoppedWithTheScreen = true
                }
                event == Lifecycle.Event.ON_START && stoppedWithTheScreen -> {
                    stoppedWithTheScreen = false
                    // Wake up with the channel still on.
                    currentId?.let { playChannelId(it) }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Sleep timer: when the armed deadline passes, stop and leave the player. Re-arming from
    // settings restarts this effect with the new deadline.
    val sleepDeadline by SleepTimer.deadline.collectAsState()
    LaunchedEffect(sleepDeadline) {
        val d = sleepDeadline ?: return@LaunchedEffect
        val wait = d - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        SleepTimer.clear()
        controller.stop()
        onBack()
    }

    // Apply the saved captions default once the stream's tracks arrive. Only auto-selects when
    // nothing is chosen yet, so it never overrides a track the user picked by hand.
    LaunchedEffect(tracks) {
        val hasText = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
        val textChosen = tracks.groups.any { g ->
            g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.isTrackSelected(it) }
        }
        if (subtitlesDefault && hasText && !textChosen) controller.setSubtitlesEnabled(true)
    }

    // Poll the playhead for the transport bar. Half a second is quick enough to look live
    // and slow enough to cost nothing on a weak box.
    LaunchedEffect(Unit) {
        val window = androidx.media3.common.Timeline.Window()
        while (isActive) {
            if (!scrubbing) {
                val player = controller.player
                val position = player.currentPosition.coerceAtLeast(0L)
                positionMs = position

                val timeline = player.currentTimeline
                val live = !timeline.isEmpty && timeline.getWindow(0, window).isDynamic
                barIsLive = live

                if (live) {
                    // Live TV: the bar is a window anchored to the **live edge**, not to the
                    // playhead. The edge is the point the stream is being produced at, which
                    // is what the timeline's default position says; the playhead sits
                    // somewhere inside the window, and the window keeps sliding forward as
                    // the stream plays. Anchoring to the playhead instead would make the bar
                    // jump back to the right the moment the user let go of it.
                    val edge = window.defaultPositionUs
                        .takeIf { it != C.TIME_UNSET }
                        ?.div(1000)
                        ?: position
                    val back = controller.backBufferMillis
                    barStartMs = (edge - back).coerceAtLeast(0L)
                    barEndMs = edge
                    canRewind = back > 0 && player.isCurrentMediaItemSeekable
                } else {
                    val duration = player.duration.takeIf { it > 0 } ?: 0L
                    barStartMs = 0L
                    barEndMs = duration
                    canRewind = duration > 0
                }
            }
            delay(500)
        }
    }

    // Auto-hide the bar after a few seconds — but never while paused or with a picker open.
    LaunchedEffect(controlsVisible, interaction, state, paused, panel, channelListVisible, guideVisible, catchupVisible) {
        // The bar only auto-hides when it is the only thing on screen. A panel — the channel
        // list, the guide, the catch-up list — is something the viewer is reading, and the
        // bar fading out under it takes the focus with it: the panel loses the focus to the
        // picture behind, and the arrows stop working.
        val overlayOpen = channelListVisible || guideVisible || catchupVisible
        if (controlsVisible && !paused && panel == Panel.NONE && !overlayOpen &&
            state is PlayerController.State.Playing
        ) {
            delay(CONTROLS_TIMEOUT_MILLIS)
            controlsVisible = false
        }
    }

    // Focus: a picker's first row when one is open, otherwise the bar, otherwise the full-screen
    // catcher (so the next remote press brings the bar back).
    LaunchedEffect(controlsVisible, panel, channelListVisible, guideVisible, catchupVisible) {
        val overlayOpen = channelListVisible || guideVisible || catchupVisible
        if (controlsVisible) {
            delay(40)
            if (!overlayOpen) {
                runCatching {
                    if (panel != Panel.NONE) panelFocus.requestFocus() else barFocus.requestFocus()
                }
            }
            // An open panel (the channel list, the guide, the catch-up list) has the focus
            // itself: it asks for it when it opens, and taking it back here would throw the
            // viewer's place away.
        } else {
            // The bar is gone, so it cannot have the focus — and the key handler has to know
            // that, or up/down would stop changing the channel.
            barHasFocus = false
            if (!overlayOpen) runCatching { rootFocus.requestFocus() }
        }
    }

    // Back steps back out one layer at a time — channel list, then picker, then the control bar —
    // and only leaves the player once nothing is on screen. From immersive it's a single press out,
    // so it never traps you, but it also no longer throws you all the way to the guide just because
    // you wanted to dismiss the bar.
    BackHandler {
        when {
            guideVisible -> guideVisible = false
            channelListVisible -> channelListVisible = false
            catchupVisible -> catchupVisible = false
            panel != Panel.NONE -> panel = Panel.NONE
            controlsVisible -> controlsVisible = false
            else -> {
                controller.stop()
                onBack()
            }
        }
    }

    // Number entry: once digits stop coming, jump to that channel number in the browsing list.
    LaunchedEffect(numberEntry) {
        if (numberEntry.isEmpty()) return@LaunchedEffect
        delay(NUMBER_ENTRY_TIMEOUT_MILLIS)
        val num = numberEntry.toIntOrNull()
        numberEntry = ""
        val target = num?.let { n -> queue.firstOrNull { it.number == n } }
        if (target != null) playChannelId(target.id)
    }

    // The channel list is the whole provider, not the folder the viewer happened to come from.
    //
    // "Canais" on a television means every channel you can tune to. Handing over the folder
    // you were browsing makes the list a dead end the moment you want to change channel, which
    // is the only reason to open it.
    var channelDirectory by remember { mutableStateOf<List<PlaybackQueue.Item>>(emptyList()) }
    LaunchedEffect(channelListVisible, currentId) {
        if (!channelListVisible) return@LaunchedEffect
        val sourceId = currentId?.let { graph.catalogRepository.channel(it)?.sourceId }
        graph.catalogRepository.observeChannels(sourceId).collect { channels ->
            channelDirectory = channels.map {
                PlaybackQueue.Item(it.id, it.shownName, it.logoUrl, it.number)
            }
        }
    }
    // The list the player shows: the whole provider once it is loaded, the browsing list until
    // then (so the list is never empty while it opens).
    val channelListItems = remember(channelDirectory, queue) {
        if (channelDirectory.isNotEmpty()) channelDirectory else queue
    }

    // The d-pad's up/down through the guide over the picture, done by hand for the same
    // reason as in the home screen's guide.
    val guideVerticalFocus = remember { VerticalListFocus() }
    val guideVerticalScope = rememberCoroutineScope()
    LaunchedEffect(guideVerticalScope) { guideVerticalFocus.attach(guideVerticalScope) }
    LaunchedEffect(guideRows) { guideVerticalFocus.update(guideRows.map { it.key }) }

    // The d-pad's up/down through the channel list, done by hand for the same reason as in the
    // guide: Compose's 2-D focus search does not walk a list of rows on this screen.
    val channelListFocus = remember { VerticalListFocus() }
    val channelListScope = rememberCoroutineScope()
    LaunchedEffect(channelListScope) { channelListFocus.attach(channelListScope) }
    LaunchedEffect(channelListItems) { channelListFocus.update(channelListItems.map { it.id }) }

    // Put the focus on the channel being watched — but only once the full list is there.
    //
    // The list is rebuilt when the directory arrives (the browsing list is replaced by the whole
    // provider), and a rebuilt list detaches the row that had the focus, which throws the
    // pending focus request away. Asking after the swap is what makes the focus stick.
    LaunchedEffect(channelListVisible, channelListItems) {
        if (channelListVisible) {
            if (channelListItems.isEmpty()) return@LaunchedEffect
            delay(80)
            // The list is rebuilt when the provider's channel list is re-read, and the row
            // that had the focus goes with the old one — so the focus goes back where it
            // was, on the channel being watched or on the row the viewer had reached.
            val key = channelListFocus.lastFocusedKey ?: currentId
            key?.let { channelListFocus.focusKey(it) }
        } else if (!controlsVisible) {
            runCatching { rootFocus.requestFocus() }
        }
    }

    // The guide takes the focus when it opens, so the d-pad walks the timeline and not the
    // transport bar that is still behind it.
    //
    // The focus lands on a *channel*, not on the panel that holds the grid. A focus that
    // sits on the panel has nothing to move: the arrows walk the focus tree, and a panel
    // with the focus is a dead end — the guide opened and nothing happened, which is the
    // one thing a guide must never do. The channel being watched is the natural first
    // stop, and the line-up is the fallback.
    LaunchedEffect(guideVisible, guideRows) {
        if (!guideVisible) return@LaunchedEffect
        if (guideRows.isEmpty()) return@LaunchedEffect
        delay(80)
        val key = currentId?.let { id -> guideRows.firstOrNull { it.primary.id == id }?.key }
            ?: guideRows.firstOrNull()?.key
        if (key == null || !guideVerticalFocus.focusKey(key)) {
            // No rows to put the focus on yet: the panel takes it, so the guide at least
            // has the arrows.
            runCatching { guideFocus.requestFocus() }
        }
    }

    /**
     * Plays a programme that already aired, from the provider's archive.
     *
     * The archive is a different kind of stream — seekable, and not the live edge — so it
     * plays in the VOD player rather than here.
     */
    fun playCatchup(programme: Programme) {
        val channel = variants.firstOrNull() ?: return
        scope.launch {
            val source = graph.sourceRepository.byId(channel.sourceId) ?: return@launch
            val url = catchupUrl(
                source = source,
                channel = channel,
                programme = programme,
                nowUtcMillis = System.currentTimeMillis(),
                xtreamApi = graph.xtreamApi,
            ) ?: return@launch
            onPlayCatchup(
                "catchup:${channel.id}:${programme.startUtcMillis}",
                url,
                "${channel.shownName} — ${programme.title}",
                source.userAgent,
            )
            catchupVisible = false
        }
    }

    // The catch-up panel takes the focus when it opens, for the same reason the guide does.
    LaunchedEffect(catchupVisible) {
        if (catchupVisible) {
            delay(60)
            runCatching { catchupFocus.requestFocus() }
        }
    }

    // What already aired on this channel, loaded when the panel opens — the guide index is
    // the expensive part, and nobody is going to look at it while the panel is shut.
    LaunchedEffect(catchupVisible, channelId) {
        if (!catchupVisible) return@LaunchedEffect
        val channel = variants.firstOrNull() ?: return@LaunchedEffect
        catchupProgrammes = guideViewModel.finishedProgrammes(
            channel = channel,
            variants = variants,
            nowUtcMillis = System.currentTimeMillis(),
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = keyToDigit(event.key)
                when {
                    // Never swallow Back/Escape — they must reach the back handler.
                    event.key == Key.Back || event.key == Key.Escape -> false
                    // Typing a channel number jumps to it, TiviMate-style.
                    digit != null -> {
                        numberEntry = (numberEntry + digit).take(4)
                        reveal(); interaction++; true
                    }
                    // The remote's GUIDE key opens the guide, the way it does on a set-top
                    // box — and closes it again. Checked before the "an overlay owns the
                    // d-pad" rule below, or the second press would fall into the grid.
                    event.key == Key.Guide -> {
                        guideVisible = !guideVisible
                        if (!guideVisible) channelListVisible = false
                        if (guideVisible) catchupVisible = false
                        interaction++
                        true
                    }
                    // A picker, the channel list or the guide owns the whole d-pad while it's up.
                    channelListVisible || guideVisible || panel != Panel.NONE -> {
                        interaction++
                        false
                    }
                    // Channel up/down works whether or not the bar is showing — this remote has no
                    // CH+/CH- keys, so up/down IS the channel changer. Each zap flashes the bar as a
                    // channel banner, then it auto-hides.
                    //
                    // The exception: when the d-pad is already walking the bar's own buttons, up
                    // and down belong to the bar. Zapping the channel out from under a button the
                    // viewer is trying to reach is how you end up watching a different programme
                    // than the one you asked for.
                    event.key == Key.DirectionUp || event.key == Key.ChannelUp -> {
                        if (!barHasFocus) zapBy(-1)
                        reveal(); true
                    }
                    event.key == Key.DirectionDown || event.key == Key.ChannelDown -> {
                        if (!barHasFocus) zapBy(1)
                        reveal(); true
                    }
                    // With the bar up, left/right drive its buttons; let them through.
                    controlsVisible -> {
                        interaction++
                        false
                    }
                    // Immersive: left opens the channel list, right the quality picker.
                    // Left/right open the channel list and the quality picker — but only when
                    // the d-pad is not on the transport bar. The bar's own buttons are reached
                    // with the arrows, and eating left/right here meant the only thing a
                    // remote could press was whatever had the focus: play/pause.
                    event.key == Key.DirectionLeft -> {
                        if (barHasFocus) { interaction++; false }
                        else {
                            if (queue.isNotEmpty()) channelListVisible = true
                            true
                        }
                    }
                    event.key == Key.DirectionRight -> {
                        if (barHasFocus) { interaction++; false }
                        else {
                            if (variants.size > 1) { reveal(); panel = Panel.QUALITY }
                            true
                        }
                    }
                    else -> { reveal(); true }
                }
            }
            .focusRequester(rootFocus)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures { if (controlsVisible) controlsVisible = false else reveal() }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = controller.player
                    useController = false
                    subtitleView?.setUserDefaultStyle()
                    subtitleView?.setUserDefaultTextSize()
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { it.resizeMode = resizeMode },
        )

        // The channel number as you type it, top-right, until it resolves.
        if (numberEntry.isNotEmpty() && !inPip) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(horizontal = 28.dp, vertical = 16.dp),
            ) {
                Text(numberEntry, color = Color.White, style = MaterialTheme.typography.displaySmall)
            }
        }

        when (val current = state) {
            is PlayerController.State.Buffering -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(current.title, color = Color.White)
                    }
                }
            }

            is PlayerController.State.Error -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(48.dp),
                    ) {
                        Text(current.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            current.message,
                            color = Color.White.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { controller.retry() }) { Text(stringResource(R.string.common_try_again)) }
                    }
                }
            }

            else -> Unit
        }

        val channelTitle = when (val s = state) {
            is PlayerController.State.Buffering -> s.title
            is PlayerController.State.Playing -> s.title
            is PlayerController.State.Error -> s.title
            else -> ""
        }

        AnimatedVisibility(
            visible = controlsVisible && !inPip && !guideVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                    // Keep the controls clear of the phone/tablet system nav bar (and the side nav
                    // bar / display cutout in landscape). Zero on a TV, so the 10-foot layout is
                    // unchanged; the gradient above stays full-bleed to the screen edge.
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    )
                    .padding(horizontal = 28.dp, vertical = 20.dp),
            ) {
                // The open picker sits above the transport row.
                if (panel != Panel.NONE) {
                    OptionPanel(
                        panel = panel,
                        controller = controller,
                        settings = settings,
                        tracks = tracks,
                        variants = variants,
                        currentId = currentId,
                        resizeMode = resizeMode,
                        onResize = { resizeMode = it; settings.setPlayerResizeMode(it) },
                        onTune = { tuneTo(it) },
                        firstFocus = panelFocus,
                        onDone = { panel = Panel.NONE; interaction++ },
                    )
                    Spacer(Modifier.height(16.dp))
                }

                if (channelTitle.isNotEmpty()) {
                    Text(
                        channelTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                // The scrub bar. For live TV this is a window, not a file: the right end is
                // the live edge and the left end is as far back as the player can reach, so
                // the labels say so rather than pretending the stream has an end.
                if (canRewind) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (barIsLive) formatBehind(barEndMs - positionMs) else formatDuration(barStartMs),
                            color = Color.White,
                        )
                        Spacer(Modifier.width(12.dp))
                        Slider(
                            value = if (scrubbing) scrubValue else positionMs.toFloat(),
                            // A range of zero would make the slider unusable, so the end is
                            // pushed a second past the start when there is nothing to show.
                            valueRange = barStartMs.toFloat()..
                                maxOf(barEndMs.toFloat(), barStartMs.toFloat() + 1f),
                            onValueChange = { scrubbing = true; scrubValue = it; interaction++ },
                            onValueChangeFinished = {
                                controller.player.seekTo(scrubValue.toLong())
                                positionMs = scrubValue.toLong()
                                scrubbing = false
                                interaction++
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (barIsLive) stringResource(R.string.player_live) else formatDuration(barEndMs),
                            color = Color.White,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                } else if (barIsLive) {
                    // This stream cannot be rewound. Saying where the past *is* beats
                    // offering buttons that do nothing.
                    Text(
                        stringResource(R.string.player_no_rewind),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    Spacer(Modifier.height(10.dp))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // The row can hold more chips than a phone in landscape fits — the
                    // transport alone is ten of them now. Scrolling it sideways beats
                    // squeezing the last one into a column of letters.
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        // Whether the d-pad is on the bar right now. The key handler needs it
                        // to know whether up/down means "change the channel" or "walk the bar".
                        .onFocusChanged {
                        barHasFocus = it.isFocused || it.hasFocus
                    },
                ) {                    // Where to go next, before the transport: the guide and the channel
                    // list. Both used to mean walking out of the player and back in, which
                    // is no way to change channel.
                    //
                    // The first chip carries the bar's focus. It is deliberately not the
                    // play/pause button: a bar that lands on "pause" means the first OK
                    // press a viewer makes stops the programme, and on a remote that is
                    // one stray press away from the channel they were watching.
                    BarChip(
                        icon = Icons.Filled.DateRange,
                        label = stringResource(R.string.player_guide),
                        selected = false,
                        focusRequester = barFocus,
                    ) {
                        guideVisible = true
                        channelListVisible = false
                        catchupVisible = false
                        interaction++
                    }
                    Spacer(Modifier.width(10.dp))
                    BarChip(Icons.Filled.List, stringResource(R.string.common_channels), false) {
                        channelListVisible = true
                        guideVisible = false
                        catchupVisible = false
                        interaction++
                    }
                    Spacer(Modifier.width(10.dp))
                    // What already aired here, and the provider's way back to it. The button
                    // is only there when there is something to watch: a channel with no
                    // archive has no past to offer.
                    if (channelHasArchive) {
                        BarChip(Icons.Filled.History, stringResource(R.string.player_catchup_title), false) {
                            catchupVisible = true
                            guideVisible = false
                            channelListVisible = false
                            interaction++
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    if (controller.isSeekable || canRewind) {
                        BarChip(Icons.Filled.FastRewind, stringResource(R.string.player_rewind), false) {
                            controller.seekBackward(); interaction++
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    BarChip(
                        icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        label = if (paused) stringResource(R.string.common_play) else stringResource(R.string.player_pause),
                        selected = false,
                    ) {
                        paused = !paused
                        controller.player.playWhenReady = !paused
                        interaction++
                    }
                    if (controller.isSeekable || canRewind) {
                        Spacer(Modifier.width(10.dp))
                        BarChip(Icons.Filled.FastForward, stringResource(R.string.player_forward), false) {
                            controller.seekForward(); interaction++
                        }
                    }
                    if (barIsLive && canRewind) {
                        // Back to the edge. The picture is a few seconds behind the stream
                        // being produced — that is the live edge, and this is the way back.
                        Spacer(Modifier.width(10.dp))
                        BarChip(Icons.Filled.LiveTv, stringResource(R.string.player_live), false) {
                            controller.seekToLive(); interaction++
                        }
                    }

                    Spacer(Modifier.width(20.dp))
                    BarChip(Icons.Filled.Subtitles, stringResource(R.string.player_subtitles), panel == Panel.SUBTITLES) {
                        panel = if (panel == Panel.SUBTITLES) Panel.NONE else Panel.SUBTITLES; interaction++
                    }
                    Spacer(Modifier.width(10.dp))
                    BarChip(Icons.Filled.Audiotrack, stringResource(R.string.player_audio), panel == Panel.AUDIO) {
                        panel = if (panel == Panel.AUDIO) Panel.NONE else Panel.AUDIO; interaction++
                    }
                    if (variants.size > 1) {
                        Spacer(Modifier.width(10.dp))
                        BarChip(Icons.Filled.HighQuality, stringResource(R.string.player_quality), panel == Panel.QUALITY) {
                            panel = if (panel == Panel.QUALITY) Panel.NONE else Panel.QUALITY; interaction++
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    BarChip(Icons.Filled.AspectRatio, stringResource(R.string.player_aspect), panel == Panel.ASPECT) {
                        panel = if (panel == Panel.ASPECT) Panel.NONE else Panel.ASPECT; interaction++
                    }
                    Spacer(Modifier.width(10.dp))
                    val recordingThis = activeRecordings.any { it.channelId == currentId }
                    BarChip(
                        icon = Icons.Filled.FiberManualRecord,
                        label = if (recordingThis) stringResource(R.string.common_stop) else stringResource(R.string.player_record),
                        selected = recordingThis,
                        iconTint = Color(0xFFE53935),
                    ) { toggleRecord() }
                    if (pipSupported) {
                        Spacer(Modifier.width(10.dp))
                        BarChip(Icons.Filled.PictureInPictureAlt, stringResource(R.string.player_pop_out), false) {
                            (context.findActivity() as? app.opentv.MainActivity)?.enterPipNow()
                            interaction++
                        }
                    }
                    // The television's power button, from inside the app: the stream stops, the
                    // app gets out of the way, and the box is left idle.
                    //
                    // The screen-off the system does on its own — the screensaver, on a set
                    // that is left alone — keeps the channel playing, which is exactly right
                    // for "leave it on and come back to it". It is wrong for "I am done
                    // tonight": there the whole box has to stop, and until now the only way
                    // to do that from here was to leave the app by hand.
                    if (isTelevision) {
                        Spacer(Modifier.width(10.dp))
                        BarChip(
                            icon = Icons.Filled.PowerSettingsNew,
                            label = stringResource(R.string.player_turn_off),
                            selected = false,
                            iconTint = Color(0xFFE53935),
                        ) {
                            // Stop the stream before the window goes, so the audio does not
                            // outlive the app by even a moment.
                            controller.stop()
                            // "I am leaving, and this is where I was": the next launch opens
                            // on this channel, the way a television set does when it is
                            // switched on again.
                            settings.resumeOnNextLaunch = true
                            context.findActivity()?.finish()
                        }
                    }
                }
            }
        }

        // Left-side transparent channel list — d-pad Left opens it, pick a channel to switch.
        AnimatedVisibility(
            visible = channelListVisible && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            val currentIndex = channelListItems.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
            val listState = rememberLazyListState()
            LaunchedEffect(Unit) { runCatching { listState.scrollToItem(currentIndex) } }
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(380.dp)
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(vertical = 16.dp)
                    .onPreviewKeyEvent { event -> channelListFocus.handle(event) },
            ) {
                Text(
                    stringResource(R.string.common_channels),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                // A quick "jump back to the channel you just left" pin, TiviMate-style.
                val lastItem = previousId?.let { pid -> channelListItems.firstOrNull { it.id == pid } }
                if (lastItem != null && lastItem.id != currentId) {
                    ChannelListRow(
                        item = lastItem,
                        playing = false,
                        focusRequester = null,
                        leadingLabel = stringResource(R.string.player_last),
                        onClick = {
                            playChannelId(lastItem.id)
                            channelListVisible = false
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                LazyColumn(state = listState) {
                    itemsIndexed(channelListItems, key = { _, item -> item.id }) { index, item ->
                        ChannelListRow(
                            item = item,
                            playing = item.id == currentId,
                            focusRequester = channelListFocus.requesterFor(item.id),
                            onFocus = { channelListFocus.onRowFocused(item.id) },
                            onClick = {
                                playChannelId(item.id)
                                channelListVisible = false
                            },
                        )
                    }
                }
            }
        }

        // The guide, over the video. The same grid the home screen shows, so what the viewer
        // learns there is the same here: OK on a channel switches to it, and the d-pad walks
        // the timeline. It draws over the picture rather than replacing it, so the channel
        // being watched stays visible behind the guide.
        AnimatedVisibility(
            visible = guideVisible && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
                    // The guide takes the d-pad when it opens. Without this the focus would
                    // stay on the transport bar behind it, and the arrows would walk the
                    // chips instead of the timeline.
                    .focusRequester(guideFocus)
                    .focusable(),
            ) {
                // "Now" for the guide's progress bars, read once a minute rather than on
                // every recomposition: a clock that changes with every frame would force
                // every programme block on screen to be redrawn when the d-pad moves.
                var guideNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(60_000)
                        guideNow = System.currentTimeMillis()
                    }
                }
                GuideGrid(
                    rows = guideRows,
                    windowStartMillis = guideWindowStart,
                    // The same clock the guide draws "now" from, so the progress bars agree
                    // with the programme list. Read once per composition rather than per row.
                    nowMillis = guideNow,
                    selectedKey = currentId?.let { id -> guideRows.firstOrNull { it.primary.id == id }?.key },
                    // OK on a channel is "watch this one", not "open its menu": the viewer is
                    // already watching something and the guide is a way to change that.
                    onSelectRow = { row ->
                        playChannelId(row.primary.id)
                        guideVisible = false
                    },
                    onFocusRow = { },
                    onExitLeftFromChannel = { guideVisible = false; true },
                    vertical = guideVerticalFocus,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
            }
        }

        // What already aired on this channel, and the provider's way back to it. Its own panel
        // over the picture — because "I missed the one that just ended" is asked while watching.
        //
        // It used to live *inside* the guide and was drawn over the whole of it, so opening
        // the guide showed the catch-up list instead of the guide, and the arrows walked a
        // list nobody had asked for. One overlay or the other, never both.
        AnimatedVisibility(
            visible = catchupVisible && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .padding(16.dp)
                    .focusRequester(catchupFocus)
                    .focusable(),
            ) {
                Text(
                    stringResource(R.string.player_catchup_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Spacer(Modifier.height(8.dp))
                if (catchupProgrammes.isEmpty()) {
                    Text(
                        stringResource(R.string.player_catchup_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                } else {
                    LazyColumn {
                        items(catchupProgrammes) { programme ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    // Focusable, or the list is a picture on a television: the
                                    // arrows cannot reach a row that is not a focus target.
                                    .focusable()
                                    .clickable {
                                        playCatchup(programme)
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    formatProgrammeTime(programme.startUtcMillis, System.currentTimeMillis()),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.width(150.dp),
                                )
                                Text(
                                    programme.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBackgroundPrompt) {
        RecordingBackgroundDialog(
            onAllow = {
                showBackgroundPrompt = false
                context.requestIgnoreBatteryOptimizations()
            },
            onDismiss = { showBackgroundPrompt = false },
        )
    }
}

@Composable
private fun ChannelListRow(
    item: PlaybackQueue.Item,
    playing: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    leadingLabel: String? = null,
    onFocus: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        playing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        else -> Color.Transparent
    }
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            // Focusable, so the d-pad can reach a channel in the list. A clickable row alone
            // is not a focus target.
            //
            // And it has to come *after* the clickable: Compose finds the key handler of a
            // focused element by walking the modifier chain and stopping at the first focus
            // target, so a focusable() in front of the clickable hides the clickable's own
            // handling — and OK stops doing anything on the row.
            .background(bg)
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A "Last" tag, or the channel number if the provider gives one — a fixed-width slot so
        // the logos and names line up down the list.
        Text(
            leadingLabel ?: item.number?.toString().orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = fg.copy(alpha = 0.7f),
            maxLines = 1,
            modifier = Modifier.width(40.dp),
        )
        AsyncImage(
            model = item.logoUrl,
            contentDescription = null,
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Text(item.name, style = MaterialTheme.typography.titleMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private enum class Panel { NONE, SUBTITLES, AUDIO, QUALITY, ASPECT }

@OptIn(UnstableApi::class)
@Composable
private fun OptionPanel(
    panel: Panel,
    controller: PlayerController,
    settings: app.opentv.core.AppSettings,
    tracks: Tracks,
    variants: List<Channel>,
    currentId: Long?,
    resizeMode: Int,
    onResize: (Int) -> Unit,
    onTune: (Channel) -> Unit,
    firstFocus: FocusRequester,
    onDone: () -> Unit,
) {
    val offLabel = stringResource(R.string.player_subtitles_off)
    val standardLabel = stringResource(R.string.player_quality_standard)
    val fitLabel = stringResource(R.string.player_aspect_fit)
    val fillLabel = stringResource(R.string.player_aspect_fill)
    val stretchLabel = stringResource(R.string.player_aspect_stretch)
    val options: List<Option> = when (panel) {
        Panel.SUBTITLES -> buildSubtitleOptions(controller, settings, tracks, offLabel, onDone)
        Panel.AUDIO -> buildAudioOptions(controller, tracks, onDone)
        Panel.QUALITY -> variants.map { v ->
            Option(v.qualityLabel.ifEmpty { standardLabel }, v.id == currentId) { onTune(v); onDone() }
        }
        Panel.ASPECT -> listOf(
            Option(fitLabel, resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                onResize(AspectRatioFrameLayout.RESIZE_MODE_FIT); onDone()
            },
            Option(fillLabel, resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                onResize(AspectRatioFrameLayout.RESIZE_MODE_ZOOM); onDone()
            },
            Option(stretchLabel, resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL) {
                onResize(AspectRatioFrameLayout.RESIZE_MODE_FILL); onDone()
            },
        )
        Panel.NONE -> emptyList()
    }

    val title = when (panel) {
        Panel.SUBTITLES -> stringResource(R.string.player_subtitles)
        Panel.AUDIO -> stringResource(R.string.player_audio)
        Panel.QUALITY -> stringResource(R.string.player_quality)
        Panel.ASPECT -> stringResource(R.string.player_aspect_ratio_title)
        Panel.NONE -> ""
    }

    Column(
        Modifier
            .widthIn(max = 460.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (options.isEmpty()) {
                Text(stringResource(R.string.player_none_available), color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(8.dp))
            }
            options.forEachIndexed { index, option ->
                OptionRow(
                    label = option.label,
                    selected = option.selected,
                    onClick = option.onClick,
                    focusRequester = if (index == 0) firstFocus else null,
                )
            }
        }
    }
}

private data class Option(val label: String, val selected: Boolean, val onClick: () -> Unit)

@OptIn(UnstableApi::class)
private fun buildSubtitleOptions(
    controller: PlayerController,
    settings: app.opentv.core.AppSettings,
    tracks: Tracks,
    offLabel: String,
    onDone: () -> Unit,
): List<Option> {
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    val anySelected = textGroups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }
    val list = mutableListOf<Option>()
    list += Option(offLabel, !anySelected) {
        controller.disableText()
        settings.setSubtitlesEnabled(false)
        onDone()
    }
    textGroups.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            list += Option(trackLabel(format.label, format.language, list.size), group.isTrackSelected(i)) {
                controller.selectTrack(group, i)
                settings.setSubtitlesEnabled(true)
                onDone()
            }
        }
    }
    return list
}

@OptIn(UnstableApi::class)
private fun buildAudioOptions(
    controller: PlayerController,
    tracks: Tracks,
    onDone: () -> Unit,
): List<Option> {
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val list = mutableListOf<Option>()
    audioGroups.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            list += Option(trackLabel(format.label, format.language, list.size), group.isTrackSelected(i)) {
                controller.selectTrack(group, i)
                onDone()
            }
        }
    }
    return list
}

private fun trackLabel(label: String?, language: String?, index: Int): String {
    if (!label.isNullOrBlank()) return label
    if (!language.isNullOrBlank() && language != "und") {
        return runCatching { Locale(language).displayLanguage.ifBlank { language } }.getOrDefault(language)
    }
    return "Track ${index + 1}"
}

@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f)
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            // Focusable, so the d-pad can walk the picker's options. Without it the panel is
            // a dead end on a remote: OK does nothing because nothing in it can hold focus.
            //
            // After the clickable, so the clickable keeps its own key handling: a
            // focusable() in front of it hides it, and OK stops pressing the option.
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = fg, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_selected), tint = fg)
        }
    }
}

@Composable
private fun BarChip(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    iconTint: Color? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val container = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else -> Color.White.copy(alpha = 0.14f)
    }
    val content = if (focused) MaterialTheme.colorScheme.onPrimary else Color.White

    Row(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            // The border is always in the chain, transparent when the chip is not focused.
            // Adding and removing a modifier changes the chain, and a chain that changes
            // under a focus target can take the focus with it — and the chip becomes a
            // picture the arrows walk straight past.
            .border(
                2.dp,
                if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            // Focusable, or the chip is a picture on a television. A clickable row is not a
            // focus target — the d-pad walks straight past it — so on a remote these buttons
            // could only be reached by touch.
            //
            // And it has to come *after* the clickable. Compose finds the key handler of a
            // focused element by walking the modifier chain and stopping at the first focus
            // target, so a focusable() in front of the clickable hides it — and the OK
            // button stops doing anything, which is how "the buttons don't work" was
            // reported.
            .focusable()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = if (focused) content else (iconTint ?: content))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

private const val CONTROLS_TIMEOUT_MILLIS = 5_000L
private const val NUMBER_ENTRY_TIMEOUT_MILLIS = 2_000L

/** Maps a remote's number keys (top row and numeric keypad) to a digit, or null for other keys. */
private fun keyToDigit(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}
