/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Intent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.window.Dialog
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.core.findActivity
import app.opentv.core.requestIgnoreBatteryOptimizations
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.Reminder
import app.opentv.data.model.shownName
import app.opentv.reminders.ReminderScheduler
import app.opentv.player.PlaybackQueue
import app.opentv.player.PlayerController
import app.opentv.player.LivePlaybackSession
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.RecordingBackgroundDialog
import app.opentv.ui.RecordingBackgroundPrompt
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live TV: a category rail on the left, the channel list on the right.
 *
 * The rail is vertical because provider category lists run to dozens of entries and a
 * horizontal chip row hides all but the first few — on a d-pad, anything you cannot see
 * you cannot reach. Favourites and All are pinned at the top.
 *
 * The list shows what is on *now* and *next* against every channel. That doubles as a
 * permanent, glanceable health indicator for the guide: when the EPG stops updating, these
 * lines say so honestly instead of going quietly stale.
 */
@Composable
fun HomeScreen(
    isTelevision: Boolean,
    hasSources: Boolean,
    isSyncing: Boolean,
    onPlayChannel: (Channel) -> Unit,
    onAddSource: () -> Unit,
    onRefresh: () -> Unit,
    onPlayCatchup: (mediaKey: String, url: String, title: String, ua: String) -> Unit = { _, _, _, _ -> },
    viewModel: ChannelsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val settings = remember { graph.settings }
    val liveSession = remember { graph.livePlaybackSession }
    val previewEnabled by settings.guidePreviewVideo.collectAsState()
    val previewMode by settings.guidePreviewMode.collectAsState()
    val channelLayout by settings.channelLayout.collectAsState()

    val categories by viewModel.visibleCategoryGroups.collectAsState()
    val rows by viewModel.rows.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val favouritesOnly by viewModel.favouritesOnly.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val selectedSource by viewModel.selectedSource.collectAsState()
    val windowStart by viewModel.windowStartMillis.collectAsState()
    // How many days ahead the guide is scrolled (0 = live now). Drives the ‹ Today › nav.
    val guideDayOffset by viewModel.guideDayOffset.collectAsState()
    // Tri-state: null = still checking the catalogue, true = channels on disk, false = confirmed
    // empty. Drives the choice between the loading spinner and a recoverable error below.
    val channelsPresent by viewModel.channelsPresent.collectAsState()

    // The highlight is where the d-pad is in the grid. The preview stream is selected separately:
    // by default it remains on the last full-screen channel, with focus-following available as a
    // setting for viewers who prefer the old browse-to-preview behaviour.
    var highlightedRow by remember { mutableStateOf<ChannelsViewModel.Row?>(null) }
    var currentChannelId by remember { mutableLongStateOf(settings.lastChannelId) }
    var pendingGuideReturnSelection by remember { mutableStateOf(true) }
    var returnFilterResetAttempted by remember { mutableStateOf(false) }
    var guideFocusTargetKey by remember { mutableStateOf<Any?>(null) }
    var guideFocusRequestId by remember { mutableIntStateOf(0) }
    val previewSound by settings.guidePreviewSound.collectAsState()
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Recording from the guide: what's capturing now, and a scope to kick a capture off.
    val activeRecordings by graph.recordingRepository.observeActive().collectAsState(initial = emptyList())
    val recordScope = rememberCoroutineScope()
    // The programme the user pressed OK on in the grid — drives the per-programme record menu.
    var recordTarget by remember { mutableStateOf<Pair<ChannelsViewModel.Row, Programme>?>(null) }
    // The channel whose OK menu (Watch / Record / Schedule) is open.
    var channelMenu by remember { mutableStateOf<ChannelsViewModel.Row?>(null) }
    // A held Select opens the dialog before release. While true, the dialog consumes that same
    // press's repeats and key-up so its initially focused Watch action cannot activate itself.
    var guardChannelMenuSelectUntilRelease by remember { mutableStateOf(false) }
    var channelMenuReleaseJob by remember { mutableStateOf<Job?>(null) }
    fun armChannelMenuSelectGuard() {
        channelMenuReleaseJob?.cancel()
        channelMenuReleaseJob = null
        guardChannelMenuSelectUntilRelease = true
    }
    fun releaseChannelMenuSelectGuardAfterEventTail() {
        channelMenuReleaseJob?.cancel()
        channelMenuReleaseJob = recordScope.launch {
            // Compose can synthesize the focused row's click immediately after key-up. Keep every
            // menu action inert until that tail of the original held press has drained.
            delay(CHANNEL_MENU_RELEASE_TAIL_MILLIS)
            guardChannelMenuSelectUntilRelease = false
            channelMenuReleaseJob = null
        }
    }
    fun clearChannelMenuSelectGuard() {
        channelMenuReleaseJob?.cancel()
        channelMenuReleaseJob = null
        guardChannelMenuSelectUntilRelease = false
    }

    // First time the user records or schedules while OpenTV isn't exempt from battery optimisation,
    // offer the exemption so the capture survives standby. Once per session; never blocks recording.
    var showBackgroundPrompt by remember { mutableStateOf(false) }
    fun promptBackgroundIfNeeded() {
        if (RecordingBackgroundPrompt.shouldShow(context)) {
            RecordingBackgroundPrompt.markShown()
            showBackgroundPrompt = true
        }
    }

    // ---- Category rail collapse (Change 2) ---------------------------------------------------
    // The rail is full-width while focus is on it, then slides shut once focus moves into the
    // guide (onFocusRow) so the grid gets the whole width. Pressing d-pad LEFT from the guide's
    // leftmost (channel) column slides it back and drops focus on the selected category.
    var railExpanded by remember { mutableStateOf(true) }
    val railWidth by animateDpAsState(
        targetValue = if (railExpanded) 240.dp else 0.dp,
        label = "railWidth",
    )
    val railFocusRequester = remember { FocusRequester() }
    val railListState = rememberLazyListState()
    var railHasFocus by remember { mutableStateOf(false) }
    // Set when LEFT reopens the rail. It remains true until the rail confirms that one of its
    // entries owns focus, so another LEFT cannot escape to the app-wide navigation rail while the
    // width animation or a busy guide frame is still in progress.
    var pendingRailFocus by remember { mutableStateOf(false) }
    val railFocusCategoryKey = remember(selectedCategory, categories) {
        selectedCategory?.takeIf { selected -> categories.any { it.key == selected } }
    }
    val selectedRailIndex = remember(sources, favouritesOnly, railFocusCategoryKey, categories) {
        val providerPrefix = if (sources.size > 1) sources.size + 3 else 0
        when {
            favouritesOnly -> providerPrefix
            railFocusCategoryKey == null -> providerPrefix + 1
            else -> {
                val categoryIndex = categories.indexOfFirst { it.key == railFocusCategoryKey }
                providerPrefix + 2 + categoryIndex
            }
        }
    }
    LaunchedEffect(pendingRailFocus, selectedRailIndex) {
        while (pendingRailFocus && !railHasFocus) {
            // The selected category can be outside the lazy rail's composed viewport. Bring it
            // into composition first, then retry focus after layout until the rail confirms it.
            runCatching { railListState.scrollToItem(selectedRailIndex) }
            delay(RAIL_FOCUS_RETRY_MILLIS)
            runCatching { railFocusRequester.requestFocus() }
            delay(RAIL_FOCUS_RETRY_MILLIS)
        }
        if (railHasFocus) pendingRailFocus = false
    }

    val directionRepeatGate = remember { GuideDirectionRepeatGate() }

    // Re-evaluate "now" once a minute so progress bars advance without leaving the screen.
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            viewModel.tick()
            delay(60_000)
        }
    }

    // Returning from full-screen playback selects and focuses the row that contains the channel
    // just watched. If a category/provider filter hides it, return once to All channels so the
    // promised target is actually available. Later EPG refreshes preserve normal browsing focus.
    LaunchedEffect(
        rows,
        pendingGuideReturnSelection,
        currentChannelId,
        selectedSource,
        selectedCategory,
        favouritesOnly,
    ) {
        if (rows.isEmpty()) return@LaunchedEffect
        if (pendingGuideReturnSelection) {
            val currentIndex = GuidePreviewPolicy.returningRowIndex(
                currentChannelId = currentChannelId,
                rowChannelIds = rows.map { row ->
                    buildSet {
                        add(row.primary.id)
                        row.variants.forEach { add(it.id) }
                    }
                },
            )
            val filterActive = selectedSource != null || selectedCategory != null || favouritesOnly
            if (currentChannelId > 0L && currentIndex == null && filterActive && !returnFilterResetAttempted) {
                returnFilterResetAttempted = true
                viewModel.selectSource(null)
                return@LaunchedEffect
            }
            val target = currentIndex?.let(rows::get)
                ?: rows.firstOrNull { it.key == highlightedRow?.key }
                ?: rows.firstOrNull()
            highlightedRow = target
            pendingGuideReturnSelection = false
            target?.let {
                railExpanded = false
                guideFocusTargetKey = it.key
                guideFocusRequestId++
            }
        } else {
            highlightedRow = rows.firstOrNull { it.key == highlightedRow?.key } ?: rows.firstOrNull()
        }
    }

    // ---- Live preview player -----------------------------------------------------------------
    // The guide and full-screen destination share this controller. Only the PlayerView changes,
    // so returning to the current channel can retain its decoder, media item and buffered data.
    val previewController = liveSession.controller
    DisposableEffect(Unit) {
        liveSession.attach(LivePlaybackSession.Surface.GUIDE)
        previewController.disableText()
        onDispose {
            liveSession.detach(LivePlaybackSession.Surface.GUIDE)
        }
    }

    // Watching a live channel while a recording runs opens a second stream on the same line — which
    // cuts the recording and can get a single-connection account banned. So every jump to full-screen
    // live is funnelled through [requestLive]: with a recording active it asks first.
    var pendingLiveChannel by remember { mutableStateOf<Channel?>(null) }
    fun startLive(channel: Channel) {
        PlaybackQueue.items = rows.map {
            PlaybackQueue.Item(it.primary.id, it.primary.shownName, it.primary.logoUrl, it.primary.number)
        }
        liveSession.prepareHandoff(LivePlaybackSession.Surface.PLAYER)
        onPlayChannel(channel)
    }
    fun requestLive(channel: Channel) {
        if (activeRecordings.isNotEmpty()) pendingLiveChannel = channel else startLive(channel)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var screenResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    liveSession.attach(LivePlaybackSession.Surface.GUIDE)
                    previewController.disableText()
                    // PlayerScreen writes this before playback begins. Re-read it every time the
                    // guide returns so Back restores that channel in both the preview and focus.
                    currentChannelId = settings.lastChannelId
                    returnFilterResetAttempted = false
                    pendingGuideReturnSelection = true
                    screenResumed = true
                }
                Lifecycle.Event.ON_PAUSE -> {
                    screenResumed = false
                    liveSession.detach(LivePlaybackSession.Surface.GUIDE)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Hold the screen awake while the guide's live preview is playing — otherwise the box's
    // screensaver fires while you're browsing with a channel running in the preview pane.
    DisposableEffect(previewEnabled, screenResumed) {
        val window = context.findActivity()?.window
        if (previewEnabled && screenResumed) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Preview audio follows the setting. It defaults on so Back keeps the current channel audible.
    LaunchedEffect(previewSound, screenResumed) {
        if (screenResumed) previewController.player.volume = if (previewSound) 1f else 0f
    }

    // By default the preview returns to the channel that was playing full-screen and stays there
    // while focus moves through the guide. The alternate setting follows the highlighted channel;
    // only that mode is debounced so holding a direction doesn't re-tune every step.
    // While a recording is running the preview is silenced entirely: on a single-connection line a
    // muted preview is still a second stream, which fights the recording and risks a provider ban.
    val recordingActive = activeRecordings.isNotEmpty()
    val highlightedChannel = highlightedRow?.primary
    val previewChannelId = GuidePreviewPolicy.channelId(
        mode = previewMode,
        currentChannelId = currentChannelId,
        highlightedChannelId = highlightedChannel?.id,
    )
    val previewVisible = previewEnabled && !recordingActive
    LaunchedEffect(
        previewChannelId,
        previewEnabled,
        screenResumed,
        recordingActive,
    ) {
        if (!screenResumed) return@LaunchedEffect
        if (!previewEnabled || recordingActive || previewChannelId == null) {
            liveSession.stop()
            return@LaunchedEffect
        }
        val channel = if (previewChannelId == highlightedChannel?.id) {
            highlightedChannel
        } else {
            graph.catalogRepository.channel(previewChannelId)
        } ?: highlightedChannel ?: run {
            liveSession.stop()
            return@LaunchedEffect
        }
        val source = graph.sourceRepository.byId(channel.sourceId)
        liveSession.playPreview(
            PlayerController.Request(
                url = channel.streamUrl,
                title = channel.shownName,
                userAgent = source?.userAgent ?: "OpenTV/0.1 (Android)",
                isLive = true,
            ),
            debounce = previewMode == AppSettings.GuidePreviewMode.HIGHLIGHTED_CHANNEL,
        )
    }

    val guideBackEnabled = currentChannelId > 0L &&
        channelMenu == null &&
        recordTarget == null &&
        pendingLiveChannel == null &&
        !showBackgroundPrompt
    fun returnToCurrentChannel() {
        val visibleChannel = rows.asSequence()
            .flatMap { sequenceOf(it.primary) + it.variants.asSequence() }
            .firstOrNull { it.id == currentChannelId }
        if (visibleChannel != null) {
            requestLive(visibleChannel)
        } else {
            recordScope.launch {
                graph.catalogRepository.channel(currentChannelId)?.let(::requestLive)
            }
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            // The app shell normally treats Back on Live TV as an exit request. Consume both
            // event halves here and navigate on key-up so guide Back resumes the last watched
            // channel without leaking into either MainScreen or the newly-opened PlayerScreen.
            .onPreviewKeyEvent { event ->
                val verticalDirection = when (event.key) {
                    Key.DirectionUp -> GuideDirectionRepeatGate.Direction.UP
                    Key.DirectionDown -> GuideDirectionRepeatGate.Direction.DOWN
                    else -> null
                }
                when {
                    // Consume both halves of LEFT while the inner rail is taking focus. This is
                    // the containment boundary that prevents fallback focus search from reaching
                    // MainScreen's OpenTV / Live TV / Recordings rail.
                    pendingRailFocus && event.key == Key.DirectionLeft -> true
                    guideBackEnabled && (event.key == Key.Back || event.key == Key.Escape) -> {
                        if (event.type == KeyEventType.KeyUp) returnToCurrentChannel()
                        true
                    }
                    event.type == KeyEventType.KeyDown && verticalDirection != null &&
                        directionRepeatGate.shouldConsume(
                            direction = verticalDirection,
                            eventTimeMillis = event.nativeKeyEvent.eventTime,
                        ) -> true
                    event.type == KeyEventType.KeyDown && verticalDirection == null -> {
                        directionRepeatGate.reset()
                        false
                    }
                    else -> false
                }
            },
    ) {

        // ---- Category rail -----------------------------------------------------------------
        // Width animates to 0 while focus is in the guide (see onFocusRow) so the grid gets the
        // whole screen; d-pad LEFT from the guide's channel column slides it back (onExitLeft…).
        Column(
            Modifier
                .width(railWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .clipToBounds()
                .focusGroup()
                .onFocusChanged {
                    railHasFocus = it.hasFocus
                    if (it.hasFocus) pendingRailFocus = false
                }
                .padding(vertical = 16.dp),
        ) {
            Text(
                stringResource(R.string.nav_live_tv),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
            // The top "Search channels" bar was removed — Search now lives in the global nav rail.

            LazyColumn(
                state = railListState,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Provider switch — only when there's more than one source. Lets the user keep
                // several playlists and flip between them (cardiodoc's request); "All" folds them.
                if (sources.size > 1) {
                    item(key = "provider-header") {
                        Text(
                            stringResource(R.string.channels_manager_source_header),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    item(key = "provider-all") {
                        RailEntry(
                            label = stringResource(R.string.channels_manager_all_sources),
                            selected = selectedSource == null,
                            onClick = { viewModel.selectSource(null) },
                            modifier = Modifier.containRailVerticalFocus(blockUp = true),
                        )
                    }
                    items(sources, key = { "src-${it.id}" }) { source ->
                        RailEntry(
                            label = source.name,
                            selected = selectedSource == source.id,
                            onClick = { viewModel.selectSource(source.id) },
                        )
                    }
                    item(key = "provider-divider") { Spacer(Modifier.height(10.dp)) }
                }
                // The currently-selected entry carries the rail's FocusRequester, so reopening the
                // rail (d-pad LEFT in the guide) lands focus straight back on the current category.
                item {
                    RailEntry(
                        label = stringResource(R.string.guide_favourites),
                        selected = favouritesOnly,
                        onClick = viewModel::selectFavourites,
                        modifier = Modifier
                            .containRailVerticalFocus(blockUp = sources.size <= 1)
                            .then(
                                if (favouritesOnly) Modifier.focusRequester(railFocusRequester)
                                else Modifier,
                            ),
                    )
                }
                item {
                    val allSelected = !favouritesOnly && selectedCategory == null
                    val allReceivesFocus = !favouritesOnly && railFocusCategoryKey == null
                    RailEntry(
                        label = stringResource(R.string.guide_all_channels),
                        selected = allSelected,
                        onClick = { viewModel.selectCategory(null) },
                        modifier = Modifier
                            .containRailVerticalFocus(blockDown = categories.isEmpty())
                            .then(
                                if (allReceivesFocus) Modifier.focusRequester(railFocusRequester)
                                else Modifier,
                            ),
                    )
                }
                items(categories, key = { it.key }) { group ->
                    val groupSelected = !favouritesOnly && selectedCategory == group.key
                    RailEntry(
                        label = group.label,
                        selected = groupSelected,
                        onClick = { viewModel.selectCategory(group.key) },
                        modifier = Modifier
                            .containRailVerticalFocus(blockDown = group.key == categories.lastOrNull()?.key)
                            .then(
                                if (!favouritesOnly && group.key == railFocusCategoryKey) {
                                    Modifier.focusRequester(railFocusRequester)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }

        // ---- Preview + guide ---------------------------------------------------------------
        Column(Modifier.weight(1f)) {
            if (rows.isEmpty()) {
                when {
                    favouritesOnly -> NoFavouritesState()
                    // Work genuinely in progress: a sync is running, the catalogue check hasn't
                    // returned yet, or channels ARE on disk and the guide is still building. A
                    // large provider takes a while, and showing "No channels" during it reads as
                    // failure — which is how someone concludes an app is broken thirty seconds
                    // after installing it.
                    isSyncing || channelsPresent == null || channelsPresent == true -> LoadingState(isSyncing)
                    // Nothing is syncing and the catalogue is confirmed empty. With a provider
                    // configured, the last load failed or returned nothing — surface a clear error
                    // with Retry and a way back to setup instead of spinning forever.
                    hasSources -> ChannelsErrorState(onRetry = onRefresh, onEditProvider = onAddSource)
                    else -> EmptyState(onAddSource)
                }
            } else {
                GuidePreview(
                    row = highlightedRow,
                    nowMillis = nowMillis,
                    previewPlayer = if (previewVisible && screenResumed) previewController.player else null,
                )
                // Shared by both layouts: focus follows the highlight and collapses the rail; LEFT
                // from the leftmost element reopens the rail (consumed only when it was hidden).
                val onFocusChannel: (ChannelsViewModel.Row) -> Unit = {
                    highlightedRow = it
                    railExpanded = false
                    pendingRailFocus = false
                }
                val onExitLeftChannel: () -> Boolean = {
                    if (!railHasFocus) {
                        railExpanded = true
                        pendingRailFocus = true
                        true
                    } else {
                        false
                    }
                }
                if (channelLayout == AppSettings.ChannelLayout.LIST) {
                    ChannelList(
                        rows = rows,
                        selectedKey = highlightedRow?.key,
                        focusRequestKey = guideFocusTargetKey,
                        focusRequestId = guideFocusRequestId,
                        onSelectRow = { row -> requestLive(row.primary) },
                        onOpenRowOptions = { row ->
                            armChannelMenuSelectGuard()
                            channelMenu = row
                        },
                        onRowOptionsRelease = {
                            releaseChannelMenuSelectGuardAfterEventTail()
                        },
                        onFocusRow = onFocusChannel,
                        onToggleFavourite = { viewModel.toggleFavourite(it) },
                        onExitLeftFromChannel = onExitLeftChannel,
                        modifier = Modifier.weight(1f).padding(start = 12.dp, end = 12.dp),
                    )
                } else {
                    GuideGrid(
                        rows = rows,
                        windowStartMillis = windowStart,
                        dayOffset = guideDayOffset,
                        selectedKey = highlightedRow?.key,
                        focusRequestKey = guideFocusTargetKey,
                        focusRequestId = guideFocusRequestId,
                        // A short OK watches immediately. Hold OK for Watch/external/record options.
                        // Programme blocks keep their separate record/schedule action dialog.
                        onSelectRow = { row -> requestLive(row.primary) },
                        onOpenRowOptions = { row ->
                            armChannelMenuSelectGuard()
                            channelMenu = row
                        },
                        onRowOptionsRelease = {
                            releaseChannelMenuSelectGuardAfterEventTail()
                        },
                        onFocusRow = onFocusChannel,
                        onProgramme = { row, programme -> recordTarget = row to programme },
                        onToggleFavourite = { viewModel.toggleFavourite(it) },
                        onExitLeftFromChannel = onExitLeftChannel,
                        modifier = Modifier.weight(1f).padding(start = 12.dp, end = 12.dp),
                    )
                }
            }
        }
    }

    // The record menu for a programme picked in the grid.
    recordTarget?.let { (targetRow, programme) ->
        val channel = targetRow.primary
        // Which quality actually gets recorded. Defaults to the best variant, but the user can pick
        // (Sky Q's "record HD or SD"). Each variant channel carries its own stream URL, so the pick
        // flows straight through to the capture. Only surfaced when the channel has more than one.
        var chosenVariant by remember(targetRow.primary.id) { mutableStateOf(targetRow.primary) }
        // Which provider records it, when this channel exists on more than one. Recording from a
        // second provider's account is what lets you keep watching on the first — the only real way
        // around a single-connection provider (something even TiviMate can't do).
        var sourceOptions by remember(channel.id) { mutableStateOf<List<Channel>>(emptyList()) }
        LaunchedEffect(channel.id) { sourceOptions = graph.catalogRepository.recordSourceOptions(channel) }
        val multiSource = sourceOptions.size > 1
        LaunchedEffect(sourceOptions) {
            if (sourceOptions.size > 1) {
                chosenVariant = sourceOptions.firstOrNull { it.sourceId == channel.sourceId } ?: sourceOptions.first()
            }
        }
        val liveNow = nowMillis in programme.startUtcMillis until programme.endUtcMillis
        val recordingThis = activeRecordings.firstOrNull { it.channelId == channel.id }
        Dialog(onDismissRequest = { recordTarget = null }) {
            Column(
                Modifier
                    .width(440.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
            ) {
                Text(
                    programme.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatTime(programme.startUtcMillis)}–${formatTime(programme.endUtcMillis)}   ${channel.shownName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                if (multiSource) {
                    // Record from which provider — keeps the other account free to watch on.
                    Text(
                        stringResource(R.string.rec_record_from),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sourceOptions.forEach { opt ->
                            QualityChip(
                                label = sources.firstOrNull { it.id == opt.sourceId }?.name
                                    ?: stringResource(R.string.rec_source_unknown),
                                selected = opt.id == chosenVariant.id,
                                onClick = { chosenVariant = opt },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                } else if (targetRow.variants.size > 1) {
                    Text(
                        stringResource(R.string.rec_quality_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        targetRow.variants.forEach { variant ->
                            QualityChip(
                                label = variant.qualityLabel.ifBlank { stringResource(R.string.rec_quality_sd) },
                                selected = variant.id == chosenVariant.id,
                                onClick = { chosenVariant = variant },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                val isPast = programme.endUtcMillis <= nowMillis
                when {
                    liveNow && recordingThis != null -> RecordActionRow(stringResource(R.string.rec_stop_recording), primary = true) {
                        graph.recordingEngine.stop(recordingThis.id)
                        Toast.makeText(context, context.getString(R.string.rec_recording_stopped), Toast.LENGTH_SHORT).show()
                        recordTarget = null
                    }
                    liveNow -> RecordActionRow(stringResource(R.string.rec_record_now), primary = true) {
                        recordScope.launch { graph.recordingEngine.startChannel(chosenVariant, programme) }
                        Toast.makeText(context, context.getString(R.string.rec_recording_channel, channel.shownName), Toast.LENGTH_LONG).show()
                        promptBackgroundIfNeeded()
                        recordTarget = null
                    }
                    // A finished programme on a catch-up channel plays back from the archive.
                    isPast && channel.tvArchive -> RecordActionRow(stringResource(R.string.guide_watch_from_start), primary = true) {
                        recordScope.launch {
                            val source = graph.sourceRepository.byId(channel.sourceId)
                            if (source == null) {
                                Toast.makeText(context, context.getString(R.string.guide_catchup_link_failed), Toast.LENGTH_SHORT).show()
                            } else {
                                val mins = (programme.durationMillis / 60000).toInt().coerceAtLeast(1)
                                val url = graph.xtreamApi.catchupUrl(source, channel.streamId, programme.startUtcMillis, mins)
                                onPlayCatchup(
                                    "catchup:${channel.id}:${programme.startUtcMillis}",
                                    url,
                                    "${channel.shownName} — ${programme.title}",
                                    source.userAgent,
                                )
                            }
                        }
                        recordTarget = null
                    }
                    !isPast -> RecordActionRow(stringResource(R.string.rec_schedule_recording), primary = true) {
                        recordScope.launch { graph.recordingEngine.scheduleProgramme(chosenVariant, programme) }
                        Toast.makeText(context, context.getString(R.string.rec_scheduled_title, programme.title), Toast.LENGTH_LONG).show()
                        promptBackgroundIfNeeded()
                        recordTarget = null
                    }
                }

                // Reminders — only for something that hasn't started yet.
                if (!isPast) {
                    var reminderSet by remember(channel.id, programme.startUtcMillis) {
                        mutableStateOf<Boolean?>(null)
                    }
                    LaunchedEffect(channel.id, programme.startUtcMillis) {
                        reminderSet =
                            graph.reminderRepository.forProgramme(channel.id, programme.startUtcMillis) != null
                    }
                    if (reminderSet == true) {
                        RecordActionRow(stringResource(R.string.guide_cancel_reminder)) {
                            recordScope.launch {
                                graph.reminderRepository.forProgramme(channel.id, programme.startUtcMillis)?.let {
                                    ReminderScheduler.cancel(context, it.id)
                                    graph.reminderRepository.delete(it.id)
                                }
                            }
                            Toast.makeText(context, context.getString(R.string.guide_reminder_removed), Toast.LENGTH_SHORT).show()
                            recordTarget = null
                        }
                    } else {
                        RecordActionRow(stringResource(R.string.guide_remind_me)) {
                            recordScope.launch { setReminder(graph, context, channel, programme, autoTune = false) }
                            Toast.makeText(context, context.getString(R.string.guide_reminder_set, programme.title), Toast.LENGTH_LONG).show()
                            recordTarget = null
                        }
                        RecordActionRow(stringResource(R.string.guide_auto_switch)) {
                            recordScope.launch { setReminder(graph, context, channel, programme, autoTune = true) }
                            Toast.makeText(context, context.getString(R.string.guide_will_switch, channel.shownName), Toast.LENGTH_LONG).show()
                            recordTarget = null
                        }
                    }
                }
                RecordActionRow(stringResource(R.string.rec_record_series)) {
                    recordScope.launch {
                        graph.recordingEngine.recordSeries(channel, programme, targetRow.programmes)
                    }
                    Toast.makeText(context, context.getString(R.string.rec_series_recording_set, programme.title), Toast.LENGTH_LONG).show()
                    promptBackgroundIfNeeded()
                    recordTarget = null
                }
                RecordActionRow(stringResource(R.string.guide_watch_channel)) {
                    recordTarget = null
                    requestLive(channel)
                }
                RecordActionRow(stringResource(R.string.common_cancel)) { recordTarget = null }
            }
        }
    }

    // Single-connection guard: confirm before opening a live stream that would fight a recording.
    pendingLiveChannel?.let { liveChannel ->
        val recTitle = activeRecordings.firstOrNull()?.title.orEmpty()
        Dialog(onDismissRequest = { pendingLiveChannel = null }) {
            Column(
                Modifier
                    .width(480.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp),
            ) {
                Text(
                    stringResource(R.string.rec_live_warn_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.rec_live_warn_body, recTitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                RecordActionRow(stringResource(R.string.rec_live_warn_watch)) {
                    pendingLiveChannel = null
                    activeRecordings.forEach { graph.recordingEngine.stop(it.id) }
                    startLive(liveChannel)
                }
                RecordActionRow(stringResource(R.string.rec_live_warn_keep)) { pendingLiveChannel = null }
            }
        }
    }

    // The channel menu — opened by pressing OK on a channel. Watch, record what's on now, schedule
    // a later programme, or record the whole series. A plain vertical list, so it's reliable on any
    // remote — no fiddly timeline navigation needed.
    channelMenu?.let { menuRow ->
        val channel = menuRow.primary
        val nowProg = menuRow.now
        val recordingThis = activeRecordings.firstOrNull { it.channelId == channel.id }
        val upcoming = menuRow.programmes.filter { it.startUtcMillis > nowMillis }.take(8)
        val heldPressFocusRequester = remember(menuRow.key) { FocusRequester() }
        Dialog(
            onDismissRequest = {
                clearChannelMenuSelectGuard()
                channelMenu = null
            },
        ) {
            LaunchedEffect(menuRow.key) {
                runCatching { heldPressFocusRequester.requestFocus() }
            }
            Column(
                Modifier
                    .width(480.dp)
                    .onPreviewKeyEvent { event ->
                        if (
                            guardChannelMenuSelectUntilRelease &&
                            event.key.isChannelMenuSelectKey()
                        ) {
                            if (event.type == KeyEventType.KeyUp) {
                                releaseChannelMenuSelectGuardAfterEventTail()
                            }
                            true
                        } else {
                            false
                        }
                    }
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp),
            ) {
                // Own the remainder of the held press. Action rows stay disabled until the release
                // guard drains, and focus remains here afterwards. The user must make a fresh
                // directional choice before Select can activate Watch or another action.
                Box(
                    Modifier
                        .size(1.dp)
                        .focusRequester(heldPressFocusRequester)
                        .focusable(),
                )
                Text(
                    channel.shownName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                nowProg?.let {
                    Text(
                        stringResource(R.string.guide_now_title, it.title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(14.dp))

                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    RecordActionRow(
                        stringResource(R.string.guide_watch),
                        blockActivation = guardChannelMenuSelectUntilRelease,
                    ) {
                        channelMenu = null
                        requestLive(channel)
                    }
                    RecordActionRow(
                        stringResource(
                            if (channel.favourite) {
                                R.string.common_remove_favourite
                            } else {
                                R.string.common_favourite
                            },
                        ),
                        blockActivation = guardChannelMenuSelectUntilRelease,
                    ) {
                        viewModel.toggleFavourite(menuRow)
                        channelMenu = null
                    }
                    RecordActionRow(
                        stringResource(R.string.guide_open_external),
                        blockActivation = guardChannelMenuSelectUntilRelease,
                    ) {
                        channelMenu = null
                        recordScope.launch {
                            val ua = graph.sourceRepository.byId(channel.sourceId)?.userAgent ?: "OpenTV/0.1 (Android)"
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(android.net.Uri.parse(channel.streamUrl), "video/*")
                                putExtra("title", channel.shownName)
                                // MX Player / VLC read the User-Agent from this header extra.
                                putExtra("headers", arrayOf("User-Agent", ua))
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(intent, context.getString(R.string.guide_play_channel_with))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure {
                                Toast.makeText(context, context.getString(R.string.guide_no_external_player), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    if (recordingThis != null) {
                        RecordActionRow(
                            stringResource(R.string.rec_stop_recording),
                            blockActivation = guardChannelMenuSelectUntilRelease,
                        ) {
                            graph.recordingEngine.stop(recordingThis.id)
                            Toast.makeText(context, context.getString(R.string.rec_recording_stopped), Toast.LENGTH_SHORT).show()
                            channelMenu = null
                        }
                    } else {
                        RecordActionRow(
                            stringResource(R.string.guide_record_now_playing),
                            blockActivation = guardChannelMenuSelectUntilRelease,
                        ) {
                            recordScope.launch { graph.recordingEngine.startChannel(channel, nowProg) }
                            Toast.makeText(context, context.getString(R.string.rec_recording_started_see_tab, channel.shownName), Toast.LENGTH_LONG).show()
                            promptBackgroundIfNeeded()
                            channelMenu = null
                        }
                    }
                    if (nowProg != null) {
                        RecordActionRow(
                            stringResource(R.string.rec_record_series_named, nowProg.title),
                            blockActivation = guardChannelMenuSelectUntilRelease,
                        ) {
                            recordScope.launch {
                                graph.recordingEngine.recordSeries(channel, nowProg, menuRow.programmes)
                            }
                            Toast.makeText(context, context.getString(R.string.rec_series_recording_set, nowProg.title), Toast.LENGTH_LONG).show()
                            promptBackgroundIfNeeded()
                            channelMenu = null
                        }
                    }
                    if (upcoming.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.guide_later_header),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        upcoming.forEach { programme ->
                            RecordActionRow(
                                "${formatTime(programme.startUtcMillis)}   ${programme.title}",
                                blockActivation = guardChannelMenuSelectUntilRelease,
                            ) {
                                // Open the programme dialog so the choice is record, remind or auto-switch —
                                // not a surprise one-tap recording.
                                channelMenu = null
                                recordTarget = menuRow to programme
                            }
                        }
                    }
                    RecordActionRow(
                        stringResource(R.string.common_cancel),
                        blockActivation = guardChannelMenuSelectUntilRelease,
                    ) { channelMenu = null }
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

private fun Key.isChannelMenuSelectKey(): Boolean =
    this == Key.DirectionCenter || this == Key.Enter || this == Key.NumPadEnter

private fun Modifier.containRailVerticalFocus(
    blockUp: Boolean = false,
    blockDown: Boolean = false,
): Modifier = onPreviewKeyEvent { event ->
    val direction = when (event.key) {
        Key.DirectionUp -> GuideDirectionRepeatGate.Direction.UP
        Key.DirectionDown -> GuideDirectionRepeatGate.Direction.DOWN
        else -> null
    }
    event.type == KeyEventType.KeyDown && direction != null &&
        GuideVerticalFocusBoundary.shouldConsume(blockUp, blockDown, direction)
}

/** Inserts a reminder for a future programme and arms its alarm. No-op if one already exists. */
private suspend fun setReminder(
    graph: ServiceLocator.Graph,
    context: android.content.Context,
    channel: Channel,
    programme: Programme,
    autoTune: Boolean,
) {
    if (graph.reminderRepository.forProgramme(channel.id, programme.startUtcMillis) != null) return
    val id = graph.reminderRepository.insert(
        Reminder(
            channelId = channel.id,
            channelName = channel.displayName,
            logoUrl = channel.logoUrl,
            title = programme.title,
            startUtcMillis = programme.startUtcMillis,
            endUtcMillis = programme.endUtcMillis,
            autoTune = autoTune,
            createdAtMillis = System.currentTimeMillis(),
        ),
    )
    ReminderScheduler.set(context, id, programme.startUtcMillis)
}

@Composable
private fun RecordActionRow(
    label: String,
    primary: Boolean = false,
    blockActivation: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        primary -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        primary -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        color = fg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(
                enabled = !blockActivation,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** A small focusable quality pill (FHD / HD / SD…) for the record dialog's "record HD or SD" choice. */
@Composable
private fun QualityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = fg,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun RailEntry(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun ChannelRow(
    row: ChannelsViewModel.Row,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val background =
        if (isSelected) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.background

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = row.primary.logoUrl,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            // Logo, name, guide — and nothing else. Quality is a playback decision;
            // its switch lives in the player, not as clutter on every row.
            Text(
                row.primary.shownName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = row.now?.let { "${formatTime(it.startUtcMillis)}  ${it.title}" }
                    // Honest rather than blank. A user who sees this on every channel knows
                    // the guide is the problem, not their provider.
                    ?: stringResource(R.string.guide_no_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.now?.let { programme ->
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { programme.progressAt(now) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
            row.next?.let { next ->
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.guide_next_prefix, formatTime(next.startUtcMillis), next.title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(onClick = onToggleFavourite) {
            Icon(
                imageVector = if (row.primary.favourite) Icons.Default.Star
                else Icons.Outlined.StarOutline,
                contentDescription = if (row.primary.favourite) stringResource(R.string.common_remove_favourite) else stringResource(R.string.common_favourite),
            )
        }
    }
}

@Composable
private fun LoadingState(isSyncing: Boolean) {
    val s by app.opentv.core.StatusBus.message.collectAsState()
    val p by app.opentv.core.StatusBus.progress.collectAsState()
    val status = s
    val progress = p
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            if (progress == null) {
                CircularProgressIndicator()
            } else {
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.guide_loading_channels), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    // Actively assembling — this is work in progress, not a failure.
                    status != null -> status
                    isSyncing -> stringResource(R.string.guide_fetching_desc)
                    // Channels are already on the device; the guide is being built from them.
                    // A big provider takes a moment (longer on this debug build) — not a failure.
                    else -> stringResource(R.string.guide_building_desc)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (progress != null) {
                Spacer(Modifier.height(20.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
        }
    }
}

@Composable
private fun NoFavouritesState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.guide_no_favourites_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.guide_no_favourites_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shown when a provider is configured but there are no channels to display and nothing is
 * syncing — i.e. the last catalogue load failed or came back empty. Replaces the endless
 * "Loading your channels" spinner with something the user can act on.
 */
@Composable
private fun ChannelsErrorState(onRetry: () -> Unit, onEditProvider: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Text(
                stringResource(R.string.guide_load_failed_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.guide_load_failed_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.Button(onClick = onRetry) {
                    Text(stringResource(R.string.common_try_again))
                }
                androidx.compose.material3.OutlinedButton(onClick = onEditProvider) {
                    Text(stringResource(R.string.guide_edit_provider))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onAddSource: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.guide_no_channels_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.guide_add_provider_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(onClick = onAddSource) { Text(stringResource(R.string.guide_add_provider_button)) }
        }
    }
}

/** UTC in the database, device zone on screen. Converted here and nowhere else. */
private const val CHANNEL_MENU_RELEASE_TAIL_MILLIS = 300L
private const val RAIL_FOCUS_RETRY_MILLIS = 16L
private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(utcMillis: Long): String = timeFormat.format(Date(utcMillis))
