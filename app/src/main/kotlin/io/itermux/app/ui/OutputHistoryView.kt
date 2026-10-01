package io.itermux.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import io.itermux.app.TerminalFonts
import io.itermux.app.session.OutputHistoryLine
import io.itermux.app.session.OutputHistorySession
import io.itermux.app.tr
import kotlin.math.ceil
import kotlin.math.roundToInt

private data class HistoryViewport(val first: Int, val rows: Int, val last: Int)

/** Render captured physical rows with the same renderer, cell metrics and selection as live output. */
@Composable
internal fun OutputHistoryView(lines: List<OutputHistoryLine>, fontSize: Int, lightTheme: Boolean = false) {
    val context = LocalContext.current
    var firstLine by rememberSaveable { mutableIntStateOf(Int.MAX_VALUE) }
    var horizontalPosition by rememberSaveable { mutableIntStateOf(0) }
    val snapshot = remember(lines) {
        OutputHistorySession(lines, firstLine) { text ->
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(tr("终端选区"), text))
        }
    }
    var viewport by remember(snapshot) { mutableStateOf(HistoryViewport(snapshot.firstLine, snapshot.emulator.mRows, snapshot.lastFirstLine)) }
    val font = remember(TerminalFonts.selection, TerminalFonts.revision, context) { TerminalFonts.resolveTypeface(context) }
    val description = tr("历史位置")
    Box(Modifier.fillMaxSize().testTag("output-history")) {
        key(snapshot) { AndroidView(modifier = Modifier.fillMaxSize().testTag("output-history-lines").semantics {
            text = AnnotatedString(lines.subList(viewport.first, minOf(lines.size, viewport.first + viewport.rows))
                .joinToString("\n") { it.text.trimEnd() })
            verticalScrollAxisRange = ScrollAxisRange({ viewport.first.toFloat() }, { viewport.last.toFloat() })
            scrollToIndex { index -> snapshot.showFirstLine(index); true }
        }, factory = { ctx ->
            HistoryGrid(ctx).apply {
                terminal.setTerminalViewClient(object : com.termux.view.TerminalViewClient by
                    MobileTerminalClient(terminal, {}, { false }, {}) {
                    override fun onSingleTapUp(e: MotionEvent?) = Unit
                    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = keyCode != KeyEvent.KEYCODE_BACK
                    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = true
                })
                snapshot.onViewportChanged = {
                    firstLine = snapshot.firstLine
                    viewport = HistoryViewport(snapshot.firstLine, snapshot.emulator.mRows, snapshot.lastFirstLine)
                    terminal.setRemoteHistory(snapshot.lastFirstLine, snapshot.lastFirstLine - snapshot.firstLine) { position ->
                        terminal.stopTextSelectionMode()
                        snapshot.showFirstLine(snapshot.lastFirstLine - position)
                    }
                    terminal.invalidate()
                }
                terminal.setTextSize((fontSize * resources.displayMetrics.scaledDensity).toInt())
                terminal.setTypeface(font)
                snapshot.emulator.mColors.setLightTheme(lightTheme)
                terminal.attachSession(snapshot)
                val savedHorizontal = horizontalPosition
                setOnScrollChangeListener { _, x, _, _, _ -> horizontalPosition = x; terminal.invalidate() }
                post { scrollTo(savedHorizontal, 0) }
            }
        }, update = { grid ->
            grid.terminal.setTextSize((fontSize * grid.resources.displayMetrics.scaledDensity).toInt())
            grid.terminal.setTypeface(font)
            snapshot.emulator.mColors.setLightTheme(lightTheme)
            grid.terminal.attachSession(snapshot)
            grid.fitGridWidth()
            grid.terminal.invalidate()
        }, onRelease = { snapshot.onViewportChanged = {}; it.terminal.stopTextSelectionMode() }) }
        if (viewport.last > 0) Box(Modifier.align(Alignment.CenterEnd)
            .width(24.dp).fillMaxHeight().testTag("output-history-scrollbar").semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(viewport.first.toFloat() / viewport.last, 0f..1f)
                setProgress { value ->
                    if (!value.isFinite()) false else {
                        snapshot.showFirstLine((value.coerceIn(0f, 1f) * snapshot.lastFirstLine).roundToInt()); true
                    }
                }
            })
    }
}

/** Horizontal panning preserves old wide grids after rotation instead of folding their borders. */
internal class HistoryGrid(context: Context) : HorizontalScrollView(context) {
    val terminal = TerminalView(context, null).apply {
        tag = "output-history-terminal"
        isFocusableInTouchMode = true // Selection needs focus; the read-only session still disables the IME.
    }
    init {
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        addView(terminal, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitGridWidth()
    }
    fun fitGridWidth() {
        val session = terminal.currentSession ?: return
        val wanted = maxOf(width, ceil(session.emulator.mColumns * terminal.mRenderer.fontWidth).toInt())
        // HorizontalScrollView measures its child with UNSPECIFIED width; View uses its minimum in that mode.
        if (terminal.minimumWidth != wanted) terminal.minimumWidth = wanted
    }
}
