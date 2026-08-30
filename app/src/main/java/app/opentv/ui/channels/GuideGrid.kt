/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.shownName
import app.opentv.ui.ChannelsViewModel
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The programme guide: channels down the left, a scrolling time-line to the right, with
 * each programme drawn as a block whose width is its duration. This is the "grid" a TV guide
 * is supposed to be — you can see what is on now, what is next, and read across the evening.
 *
 * ## How it lays out without a custom Layout
 *
 * Every row and the time header share one [horizontalScroll] state, so scrolling any of them
 * scrolls all of them in lock-step and the columns stay time-aligned. Within a row, blocks
 * are placed left to right at [MINUTE] width per minute; a leading spacer covers any gap
 * before the first programme, and gaps between programmes get their own spacer. No absolute
 * positioning, no measuring pass — just widths, which is cheap enough for a lazy list of
 * hundreds of channels on a weak TV box.
 */
@Composable
fun GuideGrid(
    rows: List<ChannelsViewModel.Row>,
    windowStartMillis: Long,
    selectedKey: Any?,
    focusRequestKey: Any? = null,
    focusRequestId: Int = 0,
    onSelectRow: (ChannelsViewModel.Row) -> Unit,
    onOpenRowOptions: (ChannelsViewModel.Row) -> Unit,
    onRowOptionsRelease: () -> Unit,
    onFocusRow: (ChannelsViewModel.Row) -> Unit,
    onProgramme: (ChannelsViewModel.Row, Programme) -> Unit = { _, _ -> },
    onToggleFavourite: (ChannelsViewModel.Row) -> Unit = {},
    // Returns true if it handled the key (rail was hidden, so consume it); false to let normal
    // left-navigation carry focus into the already-visible rail.
    onExitLeftFromChannel: () -> Boolean = { false },
    dayOffset: Int = 0,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val listState = rememberLazyListState()
    val returnFocus = remember { FocusRequester() }
    var focusedRowKey by remember { mutableStateOf<Any?>(null) }
    val now = System.currentTimeMillis()
    // Jumping to another day resets the horizontal scroll to that day's start; a within-day time
    // drift (dayOffset unchanged) leaves the user's scroll position untouched.
    androidx.compose.runtime.LaunchedEffect(dayOffset) { scroll.scrollTo(0) }
    androidx.compose.runtime.LaunchedEffect(focusRequestId, focusRequestKey) {
        val index = rows.indexOfFirst { it.key == focusRequestKey }
        if (focusRequestId > 0 && index >= 0) {
            listState.scrollToItem(index)
            requestGuideRowFocus(returnFocus) { focusedRowKey == focusRequestKey }
        }
    }

    Column(modifier.fillMaxSize()) {
        // No pager buttons: the programme blocks are d-pad focusable, so moving right along a row
        // scrolls the whole timeline (header and every row in lock-step) on its own.
        TimeHeader(windowStartMillis, scroll)

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                GuideRow(
                    row = row,
                    isFirstRow = index == 0,
                    isLastRow = index == rows.lastIndex,
                    windowStartMillis = windowStartMillis,
                    nowMillis = now,
                    scroll = scroll,
                    isSelected = row.key == selectedKey,
                    focusRequester = if (row.key == focusRequestKey) returnFocus else null,
                    onSelect = { onSelectRow(row) },
                    onOpenOptions = { onOpenRowOptions(row) },
                    onOptionsRelease = onRowOptionsRelease,
                    onFocus = {
                        focusedRowKey = row.key
                        onFocusRow(row)
                    },
                    onProgramme = { programme -> onProgramme(row, programme) },
                    onToggleFavourite = { onToggleFavourite(row) },
                    onExitLeft = onExitLeftFromChannel,
                )
            }
        }
    }
}

/**
 * The live channels as a plain vertical list instead of the time-grid — one channel per row with
 * its logo, name, and now/next line. This is the "list mode instead of tiles" some users prefer on
 * a busy line-up. It keeps the exact same focus/key contract as [GuideGrid] so [HomeScreen] can
 * swap between the two with nothing else changing: focus follows the d-pad (updating the preview),
 * OK opens the channel menu, and LEFT from a row asks the host to reopen the category rail.
 */
@Composable
fun ChannelList(
    rows: List<ChannelsViewModel.Row>,
    selectedKey: Any?,
    focusRequestKey: Any? = null,
    focusRequestId: Int = 0,
    onSelectRow: (ChannelsViewModel.Row) -> Unit,
    onOpenRowOptions: (ChannelsViewModel.Row) -> Unit,
    onRowOptionsRelease: () -> Unit,
    onFocusRow: (ChannelsViewModel.Row) -> Unit,
    onToggleFavourite: (ChannelsViewModel.Row) -> Unit = {},
    onExitLeftFromChannel: () -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    val now = System.currentTimeMillis()
    val listState = rememberLazyListState()
    val returnFocus = remember { FocusRequester() }
    var focusedRowKey by remember { mutableStateOf<Any?>(null) }
    androidx.compose.runtime.LaunchedEffect(focusRequestId, focusRequestKey) {
        val index = rows.indexOfFirst { it.key == focusRequestKey }
        if (focusRequestId > 0 && index >= 0) {
            listState.scrollToItem(index)
            requestGuideRowFocus(returnFocus) { focusedRowKey == focusRequestKey }
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
            ChannelListRow(
                row = row,
                isFirstRow = index == 0,
                isLastRow = index == rows.lastIndex,
                nowMillis = now,
                isSelected = row.key == selectedKey,
                focusRequester = if (row.key == focusRequestKey) returnFocus else null,
                onSelect = { onSelectRow(row) },
                onOpenOptions = { onOpenRowOptions(row) },
                onOptionsRelease = onRowOptionsRelease,
                onFocus = {
                    focusedRowKey = row.key
                    onFocusRow(row)
                },
                onToggleFavourite = { onToggleFavourite(row) },
                onExitLeft = onExitLeftFromChannel,
            )
        }
    }
}

@Composable
private fun ChannelListRow(
    row: ChannelsViewModel.Row,
    isFirstRow: Boolean,
    isLastRow: Boolean,
    nowMillis: Long,
    isSelected: Boolean,
    focusRequester: FocusRequester?,
    onSelect: () -> Unit,
    onOpenOptions: () -> Unit,
    onOptionsRelease: () -> Unit,
    onFocus: () -> Unit,
    onToggleFavourite: () -> Unit,
    onExitLeft: () -> Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // LEFT from a row reopens the collapsed rail, exactly like the grid's channel column.
            .onPreviewKeyEvent { e ->
                when {
                    e.type != KeyEventType.KeyDown -> false
                    isFirstRow && e.key == Key.DirectionUp -> true
                    isLastRow && e.key == Key.DirectionDown -> true
                    e.key == Key.DirectionLeft -> onExitLeft()
                    else -> false
                }
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .guideChannelActivation(
                enabled = focused,
                onClick = onSelect,
                onLongClick = onOpenOptions,
                onLongClickRelease = onOptionsRelease,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        row.primary.number?.let { num ->
            Text(
                "$num",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.width(30.dp),
            )
        }
        AsyncImage(
            model = row.primary.logoUrl,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.primary.shownName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.variants.size > 1) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.guide_qualities_count, row.variants.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            val nowProg = row.now
            Text(
                text = nowProg?.let { "${clockFormat.format(Date(it.startUtcMillis))}  ${it.title}" }
                    ?: stringResource(R.string.guide_no_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (nowProg != null) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(nowProg.progressAt(nowMillis).coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            row.next?.let { next ->
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.guide_next_prefix, clockFormat.format(Date(next.startUtcMillis)), next.title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToggleFavourite, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (row.primary.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = if (row.primary.favourite) stringResource(R.string.common_remove_favourite)
                else stringResource(R.string.common_favourite),
                tint = if (row.primary.favourite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimeHeader(windowStartMillis: Long, scroll: androidx.compose.foundation.ScrollState) {
    Row(Modifier.fillMaxWidth().height(28.dp)) {
        // Empty corner above the channel column.
        Box(Modifier.width(CHANNEL_COLUMN))
        Row(
            Modifier
                .horizontalScroll(scroll)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // A label every half hour across the whole window.
            repeat(HOURS_IN_WINDOW * 2) { i ->
                val slotStart = windowStartMillis + i * HALF_HOUR_MS
                Box(
                    Modifier.width(HALF_HOUR_WIDTH).height(28.dp).padding(start = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        clockFormat.format(Date(slotStart)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    row: ChannelsViewModel.Row,
    isFirstRow: Boolean,
    isLastRow: Boolean,
    windowStartMillis: Long,
    nowMillis: Long,
    scroll: androidx.compose.foundation.ScrollState,
    isSelected: Boolean,
    focusRequester: FocusRequester?,
    onSelect: () -> Unit,
    onOpenOptions: () -> Unit,
    onOptionsRelease: () -> Unit,
    onFocus: () -> Unit,
    onProgramme: (Programme) -> Unit,
    onToggleFavourite: () -> Unit = {},
    onExitLeft: () -> Boolean = { false },
) {
    // Focus (the d-pad border) just moves the highlight. Selection (the filled cell) is the
    // channel the preview is playing — it only changes when you press OK.
    var focused by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            // Never let a held vertical direction fall out of the lazy guide at either edge.
            // Without this boundary Compose's spatial search can choose MainScreen's global rail.
            .onPreviewKeyEvent { event ->
                val direction = event.key.guideVerticalDirection()
                event.type == KeyEventType.KeyDown && direction != null &&
                    GuideVerticalFocusBoundary.shouldConsume(
                        blockUp = isFirstRow,
                        blockDown = isLastRow,
                        direction = direction,
                    )
            },
    ) {

        // ---- Fixed channel cell (does not scroll) --------------------------------------
        Row(
            Modifier
                .width(CHANNEL_COLUMN)
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                )
                .then(
                    if (focused) Modifier.border(
                        2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp),
                    ) else Modifier,
                )
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                // LEFT from this leftmost column asks the host to reopen the collapsed category
                // rail; onExitLeft consumes the key only when it handled it (rail was hidden), so
                // moving left onto programme blocks and into an already-visible rail still works.
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) onExitLeft()
                    else false
                }
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocus()
                }
                .guideChannelActivation(
                    enabled = focused,
                    onClick = onSelect,
                    onLongClick = onOpenOptions,
                    onLongClickRelease = onOptionsRelease,
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            row.primary.number?.let { num ->
                Text(
                    "$num",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.width(28.dp),
                )
            }
            AsyncImage(
                model = row.primary.logoUrl,
                contentDescription = null,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.primary.shownName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.variants.size > 1) {
                    Text(
                        stringResource(R.string.guide_qualities_count, row.variants.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
            // Favourite toggle. Phone/tablet users had no visible way to favourite from the guide
            // (only the Channel Manager) — this puts a tappable star on every row; it stays d-pad
            // focusable so it works from a TV remote too.
            IconButton(
                onClick = onToggleFavourite,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (row.primary.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (row.primary.favourite) stringResource(R.string.common_remove_favourite)
                    else stringResource(R.string.common_favourite),
                    tint = if (row.primary.favourite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.width(3.dp))

        // ---- Scrolling programme strip -------------------------------------------------
        Row(Modifier.horizontalScroll(scroll)) {
            val programmes = row.programmes
            if (programmes.isEmpty()) {
                // Honest placeholder spanning the window, so a guide-less channel reads as
                // "no guide" rather than as a suspiciously empty gap.
                Box(
                    Modifier
                        .width(HALF_HOUR_WIDTH * HOURS_IN_WINDOW.toFloat() * 2)
                        .fillMaxSize()
                        .padding(end = 3.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        stringResource(R.string.guide_no_info),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                var cursor = windowStartMillis
                // A programme almost over (or a very short one) would otherwise be a sliver too
                // thin to read its name — the "Poi n..." problem. We floor each block at
                // [MIN_BLOCK_WIDTH] so the title stays legible, then carry the borrowed width as
                // `debt` and repay it out of the following gaps, so everything downstream stays
                // aligned with the time header instead of drifting right.
                var debt = 0.dp
                for (programme in programmes) {
                    // Clamp to the window's left edge; a programme that started earlier is
                    // drawn from `windowStart` so its block does not push everything right.
                    val start = programme.startUtcMillis.coerceAtLeast(windowStartMillis)
                    val gap = widthFor(cursor, start)
                    val repaid = minOf(debt, gap)
                    debt -= repaid
                    val spacer = gap - repaid
                    if (spacer > 0.dp) {
                        Spacer(Modifier.width(spacer))
                    }
                    val end = programme.endUtcMillis
                    val isNow = nowMillis in programme.startUtcMillis until end
                    val trueWidth = widthFor(start, end)
                    val drawnWidth = maxOf(trueWidth, MIN_BLOCK_WIDTH)
                    debt += drawnWidth - trueWidth
                    ProgrammeBlock(
                        title = programme.title,
                        width = drawnWidth,
                        isNow = isNow,
                        progress = if (isNow) programme.progressAt(nowMillis) else 0f,
                        onClick = { onProgramme(programme) },
                    )
                    cursor = end
                }
            }
        }
    }
}

@Composable
private fun ProgrammeBlock(title: String, width: Dp, isNow: Boolean, progress: Float, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(width)
            .fillMaxSize()
            .padding(end = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    focused -> MaterialTheme.colorScheme.primary
                    isNow -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .then(
                if (focused || isNow) Modifier.border(
                    if (focused) 2.dp else 1.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(6.dp),
                ) else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                focused -> MaterialTheme.colorScheme.onPrimary
                isNow -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp),
        )
        // How far through the current programme we are — a live fill along the bottom edge.
        if (isNow && progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Minutes between two instants, as guide width. Never negative; tiny slots stay legible. */
private fun widthFor(fromMillis: Long, toMillis: Long): Dp {
    val minutes = ((toMillis - fromMillis) / 60_000L).coerceAtLeast(0)
    return (minutes * MINUTE_DP).dp
}

// A weak TV box will happily scroll this; the whole strip is ~12h * 60 * 4dp = ~2880dp wide.
private const val MINUTE_DP = 4f
/**
 * Lazy rows are not guaranteed to have a focus target in the same frame as a scroll. Try
 * again only while Compose rejects the request; once accepted, stop immediately so a fast user
 * can move away with the d-pad without a later retry snapping focus back.
 */
private suspend fun requestGuideRowFocus(requester: FocusRequester, isTargetFocused: () -> Boolean) {
    repeat(GUIDE_RETURN_FOCUS_ATTEMPTS) { attempt ->
        if (isTargetFocused()) return
        delay(if (attempt == 0) GUIDE_RETURN_INITIAL_DELAY_MILLIS else GUIDE_RETURN_RETRY_DELAY_MILLIS)
        if (isTargetFocused()) return
        runCatching { requester.requestFocus() }
        delay(GUIDE_RETURN_SETTLE_DELAY_MILLIS)
        if (isTargetFocused()) return
    }
}

private fun Key.guideVerticalDirection(): GuideDirectionRepeatGate.Direction? = when (this) {
    Key.DirectionUp -> GuideDirectionRepeatGate.Direction.UP
    Key.DirectionDown -> GuideDirectionRepeatGate.Direction.DOWN
    else -> null
}

/**
 * TV remotes report Select as key events, and Compose's pointer long-click detector does not
 * consistently interpret Android TV key repeats. Start a timer on key-down so the menu opens as
 * soon as the threshold is reached, without waiting for release. Native long-press/repeat events
 * can trigger it earlier; key-up retains an elapsed-time fallback. Pointer/touch users still get
 * combinedClickable.
 * When the row's favourite child owns focus this handler is disabled, preserving the star action.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.guideChannelActivation(
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onLongClickRelease: () -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    var activeDownTimeMillis by remember { mutableLongStateOf(0L) }
    var longPressTriggered by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    return onPreviewKeyEvent { event ->
        if (!enabled || !event.key.isGuideSelectKey()) return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> {
                val nativeEvent = event.nativeKeyEvent
                if (activeDownTimeMillis != nativeEvent.downTime) {
                    longPressJob?.cancel()
                    activeDownTimeMillis = nativeEvent.downTime
                    longPressTriggered = false
                    val pressDownTimeMillis = nativeEvent.downTime
                    longPressJob = scope.launch {
                        delay(GuideChannelActivationPolicy.LONG_PRESS_MILLIS)
                        if (
                            activeDownTimeMillis == pressDownTimeMillis &&
                            !longPressTriggered
                        ) {
                            longPressTriggered = true
                            longPressJob = null
                            onLongClick()
                        }
                    }
                }
                if (
                    !longPressTriggered &&
                    (nativeEvent.isLongPress || nativeEvent.repeatCount > 0)
                ) {
                    longPressJob?.cancel()
                    longPressJob = null
                    longPressTriggered = true
                    onLongClick()
                }
                true
            }
            KeyEventType.KeyUp -> {
                longPressJob?.cancel()
                longPressJob = null
                val wasLongPress = longPressTriggered
                val heldMillis = (
                    event.nativeKeyEvent.eventTime - activeDownTimeMillis
                ).coerceAtLeast(0L)
                activeDownTimeMillis = 0L
                longPressTriggered = false
                if (wasLongPress) {
                    onLongClickRelease()
                    return@onPreviewKeyEvent true
                }
                val action = GuideChannelActivationPolicy.action(
                    heldMillis = heldMillis,
                    systemReportedLongPress = false,
                )
                when (action) {
                    GuideChannelActivationPolicy.Action.WATCH -> onClick()
                    GuideChannelActivationPolicy.Action.OPEN_OPTIONS -> {
                        onLongClick()
                        // This fallback opens on key-up, so no later release event is coming.
                        onLongClickRelease()
                    }
                }
                true
            }
            else -> true
        }
    }.combinedClickable(
        onClick = onClick,
        onLongClick = {
            // Pointer/touch long-clicks have no Select key-up for the dialog guard to observe.
            onLongClick()
            onLongClickRelease()
        },
    )
}

private fun Key.isGuideSelectKey(): Boolean =
    this == Key.DirectionCenter || this == Key.Enter || this == Key.NumPadEnter

private const val GUIDE_RETURN_FOCUS_ATTEMPTS = 6
private const val GUIDE_RETURN_INITIAL_DELAY_MILLIS = 100L
private const val GUIDE_RETURN_RETRY_DELAY_MILLIS = 250L
private const val GUIDE_RETURN_SETTLE_DELAY_MILLIS = 50L
private const val HOURS_IN_WINDOW = 24
private const val HALF_HOUR_MS = 30 * 60 * 1000L
private val CHANNEL_COLUMN = 220.dp
private val ROW_HEIGHT = 64.dp
// Floor for a programme block so a nearly-finished or very short show still shows its name.
private val MIN_BLOCK_WIDTH = 72.dp
private val HALF_HOUR_WIDTH: Dp = (30 * MINUTE_DP).dp

private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
