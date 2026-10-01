package io.itermux.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.itermux.app.TerminalFonts
import io.itermux.app.tr
import kotlinx.coroutines.channels.Channel
import kotlin.math.roundToInt

/** Read-only transcript. The terminal's existing History/Live control owns mode changes. */
@Composable
internal fun OutputHistoryView(lines: List<String>, fontSize: Int) {
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = (lines.size - 1).coerceAtLeast(0))
    val context = LocalContext.current
    val font = remember(TerminalFonts.selection, TerminalFonts.revision, context) {
        FontFamily(TerminalFonts.resolveTypeface(context))
    }
    Surface(Modifier.fillMaxSize().testTag("output-history"), color = MaterialTheme.colorScheme.background) {
        Box {
            SelectionContainer {
                LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("output-history-lines"),
                    contentPadding = PaddingValues(start = 8.dp, end = 24.dp, top = 8.dp, bottom = 8.dp)) {
                    items(lines) { line ->
                        Text(line, fontFamily = font, fontSize = fontSize.sp, color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }
            OutputHistoryScrollBar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(24.dp))
        }
    }
}

private data class HistoryPosition(val first: Float, val visible: Float, val total: Int) {
    val lastStart get() = (total - visible).coerceAtLeast(0f)
    val fraction get() = if (lastStart == 0f) 0f else (first / lastStart).coerceIn(0f, 1f)
}

/** Count partially visible rows, including wrapped lines, instead of assuming fixed row heights. */
private fun LazyListState.historyPosition(): HistoryPosition {
    val layout = layoutInfo
    val visible = layout.visibleItemsInfo.sumOf { item ->
        val start = maxOf(item.offset, layout.viewportStartOffset)
        val end = minOf(item.offset + item.size, layout.viewportEndOffset)
        (end - start).coerceAtLeast(0).toDouble() / item.size.coerceAtLeast(1)
    }.toFloat()
    val firstSize = layout.visibleItemsInfo.firstOrNull { it.index == firstVisibleItemIndex }?.size ?: 1
    val first = when {
        !canScrollBackward -> 0f
        !canScrollForward -> (layout.totalItemsCount - visible).coerceAtLeast(0f)
        else -> firstVisibleItemIndex + firstVisibleItemScrollOffset.toFloat() / firstSize.coerceAtLeast(1)
    }
    return HistoryPosition(first, visible, layout.totalItemsCount)
}

@Composable
private fun OutputHistoryScrollBar(scroll: LazyListState, modifier: Modifier) {
    val position by remember(scroll) { derivedStateOf { scroll.historyPosition() } }
    if (!scroll.canScrollBackward && !scroll.canScrollForward) return

    val density = LocalDensity.current
    val inset = with(density) { 8.dp.toPx() }
    val minimumThumb = with(density) { 32.dp.toPx() }
    var height by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    val track = (height - 2 * inset).coerceAtLeast(0f)
    val thumb = (track * position.visible / position.total.coerceAtLeast(1)).coerceIn(minOf(minimumThumb, track), track)
    val travel = track - thumb
    val thumbTop = inset + travel * position.fraction
    val currentPosition by rememberUpdatedState(position)
    val currentThumb by rememberUpdatedState(thumb)
    val currentThumbTop by rememberUpdatedState(thumbTop)
    val currentTravel by rememberUpdatedState(travel)
    val requests = remember(scroll) { Channel<Float>(Channel.CONFLATED) }
    LaunchedEffect(scroll) {
        for (fraction in requests) {
            val latest = scroll.historyPosition()
            val desired = fraction * latest.lastStart
            val target = when {
                fraction <= 0f -> 0
                fraction >= 1f -> latest.total - 1
                else -> desired.toInt()
            }.coerceIn(0, (latest.total - 1).coerceAtLeast(0))
            scroll.scrollToItem(target)
            val rowHeight = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }?.size ?: 0
            // A wrapped row can itself be taller than the viewport. Seek inside it too.
            val offset = when {
                fraction >= 1f -> rowHeight
                fraction <= 0f -> 0
                else -> ((desired - target) * rowHeight).roundToInt()
            }
            if (offset > 0) scroll.scrollToItem(target, offset)
        }
    }
    DisposableEffect(requests) { onDispose { requests.close() } }
    val alpha by animateFloatAsState(if (dragging || scroll.isScrollInProgress) 0.85f else 0.35f, label = "history-thumb")
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val description = tr("历史位置")
    Canvas(modifier.onSizeChanged { height = it.height }.testTag("output-history-scrollbar")
        .semantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(position.fraction, 0f..1f)
            setProgress { value ->
                if (value.isFinite()) requests.trySend(value.coerceIn(0f, 1f)).isSuccess else false
            }
        }
        .pointerInput(scroll) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val offset = if (down.position.y in currentThumbTop..(currentThumbTop + currentThumb))
                    down.position.y - currentThumbTop else currentThumb / 2f
                fun move(y: Float) {
                    val fraction = if (currentTravel <= 0f) currentPosition.fraction
                        else ((y - inset - offset) / currentTravel).coerceIn(0f, 1f)
                    requests.trySend(fraction)
                }
                dragging = true
                try {
                    move(down.position.y)
                    do {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        move(change.position.y)
                    } while (change.pressed)
                } finally { dragging = false }
            }
        }) {
        val width = if (dragging) 5.dp.toPx() else 3.dp.toPx()
        val x = size.width - 4.dp.toPx() - width
        if (dragging) drawRoundRect(color.copy(alpha = 0.1f), Offset(x, inset), Size(width, track), CornerRadius(width))
        drawRoundRect(color.copy(alpha = alpha), Offset(x, thumbTop), Size(width, thumb), CornerRadius(width))
    }
}
