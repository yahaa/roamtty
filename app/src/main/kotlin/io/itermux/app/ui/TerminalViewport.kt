package io.itermux.app.ui

import android.view.KeyEvent
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.view.TerminalView
import io.itermux.app.R
import io.itermux.app.TerminalFonts
import io.itermux.app.session.TerminalConnection
import io.itermux.app.tr
import io.itermux.ssh.ConnectionState
import io.itermux.ssh.Phase

/** The session's screen with what lies over it: the cursor-row keys, the status bar scrim and [notices]. */
@Composable
internal fun TerminalViewport(pane: TerminalPaneState, modifier: Modifier, lightTheme: Boolean, background: State<Color>,
    outputRevision: State<Long>, landscape: Boolean, imeVisible: Boolean, petArrowsShown: Boolean,
    notices: @Composable () -> Unit) {
    var viewportHeight by remember { mutableIntStateOf(0) }
    Box(modifier.testTag("terminal-viewport").onSizeChanged { viewportHeight = it.height }) {
        TerminalScreen(pane, lightTheme, landscape)
        CursorRowControls(pane, outputRevision, viewportHeight, landscape, imeVisible, petArrowsShown)
        StatusBarScrim(background)
        notices()
    }
}

/** The Termux view, bound to the session shown now. */
@Composable
private fun TerminalScreen(pane: TerminalPaneState, lightTheme: Boolean, landscape: Boolean) {
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    // Snapshot reads: changing font in Settings recomposes this and AndroidView.update.
    val terminalFontKey = TerminalFonts.selection to TerminalFonts.revision
    val session = pane.session
    val connected = pane.connected
    val fontSize = pane.fontSize
    val startPadding = if (landscape) 10.dp else 4.dp
    val endPadding = if (landscape) 6.dp else 4.dp
    AndroidView(modifier = Modifier.fillMaxSize().padding(start = startPadding, end = endPadding, top = 6.dp).clipToBounds()
        .pointerInput(Unit) {
            // A touch on the terminal takes the input back from the desktop's small window.
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    if (pane.pipFocused && e.changes.any { it.pressed && !it.previousPressed }) pane.unfocusPip()
                }
            }
        }
        .testTag("terminal"), factory = { ctx ->
        // Session, theme, font and history are bound in update, which also runs after creation.
        TerminalView(ctx, null).apply {
            pane.terminal = this
            setTerminalViewClient(terminalClient(this, pane))
            setUserInputListener { pane.userTyped() }
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isFocusableInTouchMode = true
        }
    }, update = { view ->
        // Compose may reuse the view: always rebind the session and its screen callback.
        view.attachSession(session)
        view.setCursorDraggingEnabled(connected)
        view.setPressedLinkColor(linkColor)
        session.onScreenChanged = { view.onScreenUpdated() }
        if (session.getEmulator().mColors.setLightTheme(lightTheme)) view.onScreenUpdated()
        view.setTextSize((fontSize * view.resources.displayMetrics.density * view.resources.configuration.fontScale).toInt())
        view.setTypeface(TerminalFonts.resolveTypeface(view.context))
        checkNotNull(terminalFontKey)
    })
}

/** What the view's gestures do. The view outlives a switch of session: everything goes through [pane] to the one shown now. */
private fun terminalClient(view: TerminalView, pane: TerminalPaneState) = MobileTerminalClient(view,
    onScale = { factor -> pane.changeFontSize((pane.fontSize * factor).toInt()) },
    controlArmed = { pane.sticky.ctrl }, consumeControl = { pane.sticky.ctrl = false },
    shiftArmed = { pane.sticky.shift }, consumeShift = { pane.sticky.shift = false },
    onDoubleTap = pane::doubleTap, onTripleTap = pane::pasteClipboard,
    // Swipes send plain arrows; an armed Shift still makes Shift+Left for Codex questions.
    onSwipeLeft = { pane.terminalKey(KeyEvent.KEYCODE_DPAD_LEFT) }, onSwipeRight = { pane.terminalKey(KeyEvent.KEYCODE_DPAD_RIGHT) },
    onSwipeHoldLeft = { pane.lineEdge("\u0001") }, onSwipeHoldRight = { pane.lineEdge("\u0005") },
    // Enter usually runs or submits the line: put the keyboard away soon so the output shows.
    onSoftKeyboardEnter = pane::hideKeyboardSoon,
    onOpenPath = pane::openPath,
    onOpenLoopback = { url -> pane.session.let { openPortPreview(view.context, url, it.host, it::openSocket) } })

/**
 * The key at the end of the cursor's row: Enter once input pauses (or at once on a double tap), Esc in its place
 * whenever Enter is hidden. In landscape an open keyboard adds a key that puts it away.
 */
@Composable
private fun BoxScope.CursorRowControls(pane: TerminalPaneState, outputRevision: State<Long>, viewportHeight: Int,
    landscape: Boolean, imeVisible: Boolean, petArrowsShown: Boolean) {
    val allowed = pane.connected && !pane.editor
    val avoid = pane.petArrowBounds.takeIf { petArrowsShown }
    // Landscape keyboards often have no dismiss key and leave no room to tap outside them:
    // while one is up, a key left of Esc/Enter puts it away, as on iOS.
    val hideKeyboardKey: @Composable () -> Unit = {
        if (landscape && imeVisible) CursorKey(R.drawable.ic_keyboard_hide, tr("收起键盘"), "cursor-hide-keyboard") {
            pane.cancelKeyboardHide()
            pane.hideKeyboard()
        }
    }
    AnimatedVisibility(pane.enterKeyAwake && allowed,
        modifier = Modifier.align(Alignment.TopEnd), enter = fadeIn(tween(120)), exit = fadeOut(tween(400))) {
        pane.terminal?.let { view ->
            CursorRowKeys(view, outputRevision, viewportHeight, CursorKeyHeight, avoid = avoid) {
                hideKeyboardKey()
                CursorKey(R.drawable.ic_return, tr("回车"), "cursor-enter") {
                    pane.showEnterKey()
                    pane.sendKeys("\r")
                    // Like the soft keyboard's Enter: put an open keyboard away so the output shows.
                    if (imeVisible) pane.hideKeyboardSoon()
                }
            }
        }
    }
    AnimatedVisibility(!pane.enterKeyAwake && allowed,
        modifier = Modifier.align(Alignment.TopEnd), enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
        pane.terminal?.let { view ->
            CursorRowKeys(view, outputRevision, viewportHeight, CursorKeyHeight, avoid = avoid) {
                hideKeyboardKey()
                CursorKey(null, tr("发送 Esc"), "cursor-escape", label = "Esc") { pane.sendKeys("\u001b") }
            }
        }
    }
}

/** Fades the text that runs under the status bar. Hidden (landscape) status bars have no inset, so it only appears under a visible bar. */
@Composable
private fun StatusBarScrim(background: State<Color>) {
    val statusBars = WindowInsets.statusBars
    Box(Modifier.fillMaxWidth().windowInsetsTopHeight(statusBars.add(WindowInsets(top = 12.dp)))
        .testTag("status-bar-scrim").drawBehind {
            val statusBar = statusBars.getTop(this).toFloat()
            if (statusBar <= 0f) return@drawBehind
            val color = background.value
            drawRect(Brush.verticalGradient(0f to color.copy(alpha = 0.85f),
                statusBar / size.height to color.copy(alpha = 0.55f), 1f to Color.Transparent))
        })
}

/** Connection progress or failure while not connected, an input warning while connected; nothing under a login question. */
@Composable
internal fun TerminalNotices(session: TerminalConnection, state: ConnectionState, onReconnect: (() -> Unit)?,
    onShell: () -> Unit, onReverify: () -> Unit, onLeave: () -> Unit) {
    val pending by session.pending.collectAsStateWithLifecycle()
    val warning by session.inputWarning.collectAsStateWithLifecycle()
    if (pending != null) return
    if (state.phase != Phase.CONNECTED) TerminalNoticeOverlay {
        if (state.active) TerminalConnectionProgress(state.phase, onCancel = session::disconnect)
        else TerminalConnectionNotice(state, onReconnect, onShell, onReverify, onLeave)
    }
    else warning?.let { message -> TerminalNoticeOverlay {
        TerminalInputNotice(message) { session.inputWarning.value = null }
    } }
}
