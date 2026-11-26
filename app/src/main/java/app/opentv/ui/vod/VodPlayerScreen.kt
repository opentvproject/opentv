/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.core.SleepTimer
import app.opentv.core.findActivity
import app.opentv.pairing.SubtitleUploadServer
import app.opentv.player.PlayerController
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays a movie or episode: a single non-live stream, with resume and a proper transport bar.
 *
 * The bar is drawn in Compose rather than left to the Media3 view's own controller, because that
 * controller needs the embedded view to hold d-pad focus to be summoned — which it doesn't, on a
 * TV inside Compose, so it reads as "no controls". This is the same self-drawn, auto-hiding bar as
 * the live player, with a seek bar added since a film needs scrubbing.
 */
@OptIn(UnstableApi::class)
@Composable
fun VodPlayerScreen(
    mediaKey: String,
    streamUrl: String,
    title: String,
    userAgent: String,
    onPlayNext: (mediaKey: String, streamUrl: String, title: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val graph = remember { ServiceLocator.get(context) }
    val settings = remember { graph.settings }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    // A recording that's still being written plays through the tail-following source. It behaves a
    // little differently in the transport: the "length" is how much has been recorded so far, which
    // keeps growing, and seeking is bounded to that recorded extent.
    val growingRec = remember(streamUrl) { streamUrl.startsWith("optvrec://") }
    // The SMB source lets a recording stored on a NAS play and seek in-app; harmless for the
    // http/file URLs of ordinary VOD.
    val controller = remember {
        PlayerController(
            context, scope, graph.streamingHttpClient, subtitlesEnabled = false,
            smbDataSourceFactory = app.opentv.player.SmbDataSource.Factory(graph.settings),
            // Lets an `optvrec://<id>` recording play while it's still being written.
            growingDataSourceFactory =
                app.opentv.player.GrowingRecordingDataSource.Factory(context.applicationContext),
            liveRecording = growingRec,
        )
    }
    // Skip forward/back within the recorded portion. For a growing recording ExoPlayer won't report
    // the item as seekable (no fixed length), so we seek directly, clamped to what's on disk.
    fun seekRelative(deltaMs: Long) {
        val p = controller.player
        val ceiling = if (growingRec) p.bufferedPosition.coerceAtLeast(0L)
        else (p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, ceiling))
    }
    val state by controller.state.collectAsState()
    val tracks by controller.tracks.collectAsState()

    var paused by remember { mutableStateOf(false) }
    // Resume prompt: if there's a saved position, ask "continue or start over" before playing rather
    // than silently resuming. Playback is held until the viewer chooses.
    var showResume by remember { mutableStateOf(false) }
    var resumeMs by remember { mutableLongStateOf(0L) }
    var started by remember { mutableStateOf(false) }
    val resumeFocus = remember { FocusRequester() }
    var vodPanel by remember { mutableStateOf(VodPanel.NONE) }
    val panelFocus = remember { FocusRequester() }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var nextEpisode by remember(mediaKey) { mutableStateOf<app.opentv.data.model.Episode?>(null) }
    var nextEpisodeDismissed by remember(mediaKey) { mutableStateOf(false) }
    var showExternalSubtitleDialog by remember { mutableStateOf(false) }

    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val barFocus = remember { FocusRequester() }
    val rewindFocus = remember { FocusRequester() }
    val forwardFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }

    fun reveal() { controlsVisible = true; interaction++ }

    LaunchedEffect(mediaKey) {
        val episodeId = mediaKey.removePrefix("ep:").takeIf { it != mediaKey }?.toLongOrNull()
        nextEpisode = episodeId?.let { graph.catalogRepository.nextEpisode(it) }
    }

    fun playNextEpisode() {
        val next = nextEpisode ?: return
        val nextTitle = next.title.ifBlank { "S${next.season} E${next.episodeNumber}" }
        onPlayNext("ep:${next.id}", next.streamUrl, nextTitle)
    }

    val nextEpisodeRemainingMs = (durationMs - positionMs).coerceAtLeast(0L)
    val showNextEpisodePrompt = nextEpisode != null && !nextEpisodeDismissed &&
        durationMs > 0L && nextEpisodeRemainingMs <= 60_000L

    LaunchedEffect(showNextEpisodePrompt) {
        if (showNextEpisodePrompt) reveal()
    }

    fun handlePickedSubtitleUri(uri: Uri?) {
        if (uri == null) return
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val name = subtitleDisplayName(context, uri)
        val mimeType = SubtitleUploadServer.subtitleMimeTypeForName(
            name,
            context.contentResolver.getType(uri),
        )
        if (mimeType == null) {
            Toast.makeText(context, R.string.player_external_subtitle_unsupported, Toast.LENGTH_LONG).show()
        } else {
            val resolvedUri = copySubtitleToCache(context, uri, name) ?: uri
            controller.setExternalSubtitle(resolvedUri, mimeType, name?.substringBeforeLast('.'))
            reveal()
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
        ::handlePickedSubtitleUri,
    )
    val getContentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
        ::handlePickedSubtitleUri,
    )

    fun launchExternalSubtitlePicker() {
        val mimeTypes = arrayOf("text/*", "application/x-subrip", "application/ttml+xml", "application/octet-stream")
        val openDocIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }
        val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        when {
            hasRealActivityHandler(context, openDocIntent) -> {
                try {
                    openDocumentLauncher.launch(mimeTypes)
                } catch (_: ActivityNotFoundException) {
                    showExternalSubtitleDialog = true
                }
            }
            hasRealActivityHandler(context, getContentIntent) -> {
                try {
                    getContentLauncher.launch("*/*")
                } catch (_: ActivityNotFoundException) {
                    showExternalSubtitleDialog = true
                }
            }
            else -> showExternalSubtitleDialog = true
        }
    }

    suspend fun savePosition() {
        val player = controller.player
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it > 0 } ?: return
        if (pos > 5_000) {
            graph.playbackPositions.upsert(
                app.opentv.data.model.PlaybackPosition(
                    profileId = settings.activeProfileId.value,
                    mediaKey = mediaKey,
                    positionMillis = pos,
                    durationMillis = dur,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    LaunchedEffect(state, nextEpisode?.id, nextEpisodeDismissed, paused) {
        if (state is PlayerController.State.Ended) {
            savePosition()
            if (nextEpisode != null && !nextEpisodeDismissed && !paused) {
                playNextEpisode()
            }
        }
    }

    // Keep the screen awake during playback — see the note in PlayerScreen; a film is exactly when
    // the screensaver must not fire. Window flag is the reliable path; keepScreenOn is a backstop.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view.keepScreenOn = true
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view.keepScreenOn = false
            scope.launch { savePosition() }
            controller.release()
            scope.cancel()
        }
    }

    fun begin(fromMillis: Long) {
        controller.play(
            PlayerController.Request(
                url = streamUrl,
                title = title,
                userAgent = userAgent,
                startPositionMillis = fromMillis,
                isLive = false,
            ),
            debounce = false,
        )
        started = true
    }

    // Decide how to start: a live recording never resumes; otherwise, if there's a meaningful saved
    // position, show the resume prompt and wait for the choice. No saved position → just play.
    LaunchedEffect(mediaKey) {
        val saved = graph.playbackPositions.get(settings.activeProfileId.value, mediaKey)
            ?.takeIf { !it.isFinished }?.positionMillis ?: 0L
        if (!growingRec && saved > RESUME_MIN_MILLIS) {
            resumeMs = saved
            showResume = true
        } else {
            begin(0L)
        }
    }

    // Periodic position save, only once playback has actually started.
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        while (isActive) {
            delay(15_000)
            savePosition()
        }
    }

    // Put focus on the resume prompt's default action when it appears.
    LaunchedEffect(showResume) {
        if (showResume) {
            delay(60)
            runCatching { resumeFocus.requestFocus() }
        }
    }

    // Sleep timer: leave the film when the armed deadline passes (position is saved on dispose).
    val sleepDeadline by SleepTimer.deadline.collectAsState()
    LaunchedEffect(sleepDeadline) {
        val d = sleepDeadline ?: return@LaunchedEffect
        val wait = d - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        SleepTimer.clear()
        onBack()
    }

    // Poll position/duration for the seek bar while the film plays.
    LaunchedEffect(Unit) {
        while (isActive) {
            if (!scrubbing) {
                positionMs = controller.player.currentPosition.coerceAtLeast(0)
                // A growing recording has no fixed length; the recorded-so-far extent (how far you
                // can skip ahead) is what's been read into the buffer.
                durationMs = if (growingRec) controller.player.bufferedPosition.coerceAtLeast(0)
                else controller.player.duration.takeIf { it > 0 } ?: 0
            }
            delay(500)
        }
    }

    // Auto-hide when playing and not paused/scrubbing and no picker open. A growing recording dips in
    // and out of Buffering as it rides the write head, so for that case Buffering counts as "playing"
    // here — otherwise a single hiccup would pin the transport bar on screen for the rest of the watch.
    LaunchedEffect(controlsVisible, interaction, state, paused, scrubbing, vodPanel, showNextEpisodePrompt, showExternalSubtitleDialog) {
        val activelyPlaying = state is PlayerController.State.Playing ||
            (growingRec && state is PlayerController.State.Buffering)
        if (controlsVisible && !showNextEpisodePrompt && !showExternalSubtitleDialog &&
            !paused && !scrubbing && vodPanel == VodPanel.NONE && activelyPlaying
        ) {
            delay(5_000)
            controlsVisible = false
        }
    }

    LaunchedEffect(controlsVisible, vodPanel, showResume) {
        if (showResume) return@LaunchedEffect
        if (controlsVisible) {
            delay(40)
            runCatching { if (vodPanel != VodPanel.NONE) panelFocus.requestFocus() else barFocus.requestFocus() }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    BackHandler {
        val activelyPlaying = state is PlayerController.State.Playing ||
            (growingRec && state is PlayerController.State.Buffering)
        when {
            showResume -> onBack()
            showExternalSubtitleDialog -> showExternalSubtitleDialog = false
            vodPanel != VodPanel.NONE -> vodPanel = VodPanel.NONE
            controlsVisible && activelyPlaying && !paused -> controlsVisible = false
            else -> onBack()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                when {
                    event.key == Key.Back || event.key == Key.Escape -> false
                    event.type == KeyEventType.KeyDown && !controlsVisible -> { reveal(); true }
                    event.type == KeyEventType.KeyDown -> { interaction++; false }
                    else -> false
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
        )

        when (val current = state) {
            is PlayerController.State.Buffering ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(current.title, color = Color.White)
                    }
                }

            is PlayerController.State.Error ->
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(48.dp),
                    ) {
                        Text(current.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text(current.message, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { controller.retry() }) { Text(stringResource(R.string.common_try_again)) }
                    }
                }

            else -> Unit
        }

        AnimatedVisibility(
            visible = controlsVisible && !showResume,
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
                if (vodPanel != VodPanel.NONE) {
                    TrackPanel(
                        panel = vodPanel,
                        controller = controller,
                        tracks = tracks,
                        firstFocus = panelFocus,
                        onAddExternalSubtitle = {
                            vodPanel = VodPanel.NONE
                            launchExternalSubtitlePicker()
                        },
                        onDone = { vodPanel = VodPanel.NONE; interaction++ },
                    )
                    Spacer(Modifier.height(16.dp))
                }

                if (growingRec) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.FiberManualRecord,
                            contentDescription = null,
                            tint = Color(0xFFE53935),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.rec_watching_live_badge),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFFE53935),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(8.dp))

                // Seek bar with times either side. Left/right/down moves focus to the transport buttons.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDuration(if (scrubbing) scrubValue.toLong() else positionMs), color = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Slider(
                        value = if (scrubbing) scrubValue else positionMs.toFloat(),
                        valueRange = 0f..(durationMs.takeIf { it > 0 }?.toFloat() ?: 1f),
                        onValueChange = { scrubbing = true; scrubValue = it; interaction++ },
                        onValueChangeFinished = {
                            controller.player.seekTo(scrubValue.toLong())
                            positionMs = scrubValue.toLong()
                            scrubbing = false
                            interaction++
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusProperties {
                                left = rewindFocus
                                right = forwardFocus
                                down = barFocus
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)
                                ) {
                                    val target = if (event.key == Key.DirectionLeft) rewindFocus else forwardFocus
                                    runCatching { target.requestFocus() }
                                    true
                                } else false
                            },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(formatDuration(durationMs), color = Color.White)
                }

                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    VodChip(Icons.Filled.FastRewind, stringResource(R.string.player_rewind), rewindFocus) {
                        if (growingRec) seekRelative(-15_000) else controller.seekBackward(); interaction++
                    }
                    Spacer(Modifier.width(10.dp))
                    VodChip(
                        icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        label = if (paused) stringResource(R.string.common_play) else stringResource(R.string.player_pause),
                        focusRequester = barFocus,
                    ) {
                        paused = !paused
                        controller.player.playWhenReady = !paused
                        interaction++
                    }
                    Spacer(Modifier.width(10.dp))
                    VodChip(Icons.Filled.FastForward, stringResource(R.string.player_forward), forwardFocus) {
                        if (growingRec) seekRelative(15_000) else controller.seekForward(); interaction++
                    }
                    Spacer(Modifier.width(20.dp))
                    VodChip(Icons.Filled.ClosedCaption, stringResource(R.string.player_subtitles)) {
                        vodPanel = if (vodPanel == VodPanel.SUBTITLES) VodPanel.NONE else VodPanel.SUBTITLES
                        interaction++
                    }
                    Spacer(Modifier.width(10.dp))
                    VodChip(Icons.Filled.Audiotrack, stringResource(R.string.player_audio)) {
                        vodPanel = if (vodPanel == VodPanel.AUDIO) VodPanel.NONE else VodPanel.AUDIO
                        interaction++
                    }
                    if (showNextEpisodePrompt) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            stringResource(R.string.player_next_episode_in, formatDuration(nextEpisodeRemainingMs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.75f),
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = ::playNextEpisode) {
                            Text(stringResource(R.string.player_play_next))
                        }
                        TextButton(onClick = { nextEpisodeDismissed = true }) {
                            Text(stringResource(R.string.player_dismiss_next))
                        }
                    }
                }
            }
        }

        // Resume-or-restart prompt, drawn on top of everything until the viewer chooses.
        if (showResume) {
            ResumePrompt(
                title = title,
                positionMs = resumeMs,
                firstFocus = resumeFocus,
                onResume = { showResume = false; begin(resumeMs) },
                onStartOver = { showResume = false; begin(0L) },
            )
        }

        if (showExternalSubtitleDialog) {
            ExternalSubtitleDialog(
                onLoaded = { uri, mimeType, label ->
                    showExternalSubtitleDialog = false
                    controller.setExternalSubtitle(uri, mimeType, label)
                    reveal()
                },
                onDismiss = { showExternalSubtitleDialog = false },
            )
        }
    }
}

/** "You were watching — resume or start over?" shown when a film has a saved position. */
@Composable
private fun ResumePrompt(
    title: String,
    positionMs: Long,
    firstFocus: FocusRequester,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(48.dp).widthIn(max = 620.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.vod_resume_at, formatDuration(positionMs)),
                color = Color.White.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(28.dp))
            Row {
                ResumeButton(
                    label = stringResource(R.string.vod_resume),
                    primary = true,
                    focusRequester = firstFocus,
                    onClick = onResume,
                )
                Spacer(Modifier.width(16.dp))
                ResumeButton(
                    label = stringResource(R.string.vod_start_over),
                    primary = false,
                    focusRequester = null,
                    onClick = onStartOver,
                )
            }
        }
    }
}

@Composable
private fun ResumeButton(
    label: String,
    primary: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val base = if (primary) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f)
    val container = if (focused) MaterialTheme.colorScheme.primary else base
    val content = if (focused || primary) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(12.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = content)
    }
}

@Composable
private fun VodChip(
    icon: ImageVector,
    label: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val container = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f)
    val content = if (focused) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = content)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

private enum class VodPanel { NONE, SUBTITLES, AUDIO }

@OptIn(UnstableApi::class)
@Composable
private fun TrackPanel(
    panel: VodPanel,
    controller: PlayerController,
    tracks: Tracks,
    firstFocus: FocusRequester,
    onAddExternalSubtitle: () -> Unit,
    onDone: () -> Unit,
) {
    val trackType = if (panel == VodPanel.SUBTITLES) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
    val groups = tracks.groups.filter { it.type == trackType }
    val options = mutableListOf<Triple<String, Boolean, () -> Unit>>()

    if (panel == VodPanel.SUBTITLES) {
        val anySelected = groups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }
        options += Triple(stringResource(R.string.player_subtitles_off), !anySelected) { controller.disableText(); onDone() }
        options += Triple(stringResource(R.string.player_add_external_subtitle), false, onAddExternalSubtitle)
    }
    groups.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            options += Triple(vodTrackLabel(format.label, format.language, options.size), group.isTrackSelected(i)) {
                controller.selectTrack(group, i); onDone()
            }
        }
    }

    Column(
        Modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(16.dp),
    ) {
        Text(
            if (panel == VodPanel.SUBTITLES) stringResource(R.string.player_subtitles) else stringResource(R.string.player_audio),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            if (options.isEmpty()) {
                Text(stringResource(R.string.player_none_available), color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(8.dp))
            }
            options.forEachIndexed { index, (label, selected, onClick) ->
                TrackRow(label, selected, onClick, if (index == 0) firstFocus else null)
            }
        }
    }
}

@Composable
private fun ExternalSubtitleDialog(
    onLoaded: (uri: Uri, mimeType: String, label: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var urlInput by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    val unsupportedSubtitleMessage = stringResource(R.string.player_external_subtitle_unsupported)
    val pickerUnavailableMessage = stringResource(R.string.player_subtitle_file_picker_unavailable)

    // File picker — OpenDocument first, GetContent as fallback.
    val handleUri: (Uri?) -> Unit = { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val name = subtitleDisplayName(context, uri)
            val mime = SubtitleUploadServer.subtitleMimeTypeForName(
                name, context.contentResolver.getType(uri),
            )
            if (mime != null) {
                val resolved = copySubtitleToCache(context, uri, name) ?: uri
                onLoaded(resolved, mime, name?.substringBeforeLast('.'))
                fileError = null
            } else {
                fileError = unsupportedSubtitleMessage
            }
        }
    }
    val mimeTypes = arrayOf("text/*", "application/x-subrip", "application/ttml+xml", "application/octet-stream")
    val openDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), handleUri)
    val getContentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { handleUri(it) }

    fun launchPicker() {
        val openDocIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }
        when {
            hasRealActivityHandler(context, openDocIntent) ->
                try { openDocLauncher.launch(mimeTypes) } catch (_: ActivityNotFoundException) {
                    val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    }
                    if (hasRealActivityHandler(context, getContentIntent)) {
                        try {
                            getContentLauncher.launch("*/*")
                        } catch (_: ActivityNotFoundException) {
                            fileError = pickerUnavailableMessage
                        }
                    } else {
                        fileError = pickerUnavailableMessage
                    }
                }
            hasRealActivityHandler(context, Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }) -> try {
                getContentLauncher.launch("*/*")
            } catch (_: ActivityNotFoundException) {
                fileError = pickerUnavailableMessage
            }
            else -> fileError = pickerUnavailableMessage
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.player_add_external_subtitle), style = MaterialTheme.typography.titleLarge)

            // File picker button
            var pickFocused by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (pickFocused) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primaryContainer,
                    )
                    .onFocusChanged { pickFocused = it.isFocused }
                    .clickable { launchPicker() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                val tint = if (pickFocused) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onPrimaryContainer
                Icon(
                    androidx.compose.material.icons.Icons.Filled.FolderOpen,
                    contentDescription = null,
                    tint = tint,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.player_subtitle_browse_file),
                    style = MaterialTheme.typography.titleMedium,
                    color = tint,
                )
            }
            fileError?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Text(
                stringResource(R.string.player_external_subtitle_formats),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // URL input
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it; urlError = null },
                singleLine = true,
                label = { Text(stringResource(R.string.player_external_subtitle_url_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            urlError?.let { err ->
                Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Spacer(Modifier.width(10.dp))
                val unsupportedMsg = stringResource(R.string.player_external_subtitle_unsupported)
                val invalidUrlMsg = stringResource(R.string.player_external_subtitle_url_invalid)
                Button(
                    onClick = {
                        val trimmed = urlInput.trim()
                        val parsed = runCatching { Uri.parse(trimmed) }.getOrNull()
                        val scheme = parsed?.scheme?.lowercase(Locale.ROOT)
                        if (parsed == null || scheme !in setOf("http", "https")) {
                            urlError = invalidUrlMsg; return@Button
                        }
                        val mimeType = SubtitleUploadServer.subtitleMimeTypeForName(trimmed)
                        if (mimeType == null) { urlError = unsupportedMsg; return@Button }
                        val label = parsed.lastPathSegment?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                        onLoaded(parsed, mimeType, label)
                    },
                    enabled = urlInput.isNotBlank(),
                ) { Text(stringResource(R.string.player_external_subtitle_load_url)) }
            }
        }
    }
}

private fun hasRealActivityHandler(context: Context, intent: Intent): Boolean {
    val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    return matches.any { info ->
        val pkg = info.activityInfo?.packageName?.lowercase(Locale.ROOT).orEmpty()
        val name = info.activityInfo?.name?.lowercase(Locale.ROOT).orEmpty()
        pkg.isNotEmpty() &&
            !pkg.contains("frameworkpackagestubs") &&
            !pkg.contains("packagestubs") &&
            !name.endsWith(".stubactivity") &&
            !name.contains("unsupported")
    }
}

private fun copySubtitleToCache(context: Context, uri: Uri, displayName: String?): Uri? {
    if (uri.scheme != "content") return null
    return runCatching {
        val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
        val safeName = SubtitleUploadServer.sanitizeSubtitleFileName(displayName ?: "subtitle.srt")
        val target = File(dir, "picked_${System.currentTimeMillis()}_$safeName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        Uri.fromFile(target)
    }.getOrNull()
}

private fun subtitleDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit, focusRequester: FocusRequester?) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f)
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = fg, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_selected), tint = fg)
    }
}

private fun vodTrackLabel(label: String?, language: String?, index: Int): String {
    if (!label.isNullOrBlank()) return label
    if (!language.isNullOrBlank() && language != "und") {
        return runCatching { java.util.Locale(language).displayLanguage.ifBlank { language } }.getOrDefault(language)
    }
    return "Track ${index + 1}"
}

/** Below this, a saved position isn't worth a prompt — just start from the top. */
private const val RESUME_MIN_MILLIS = 15_000L

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
