package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf

import android.view.KeyEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.SoftwareKeyboardControllerCompat
import com.termux.terminal.KeyHandler
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import io.itermux.app.TerminalFonts
import io.itermux.app.session.*
import io.itermux.app.R
import io.itermux.ssh.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TerminalPane(session: TerminalConnection, modifier: Modifier, sessions: List<TerminalConnection>, lightTheme: Boolean, onSelectSession: (String) -> Unit, onHostDetail: () -> Unit, onReconnect: (() -> Unit)?, onRestart: () -> Unit, onShell: () -> Unit, onReverify: () -> Unit, onClose: () -> Unit,
    /** Shows the host's desktop small over the terminal, or closes it; null when it has none to open. */
    onDesktop: (() -> Unit)? = null,
    /** Leaves a connection notice without closing the local or remote session. */
    onLeave: () -> Unit = onHostDetail,
    /** The host's desktop in a small window over the terminal, and the dock button's long press. */
    pip: TerminalPip = TerminalPip()) {
    val state by session.state.collectAsStateWithLifecycle()
    val transferStatus by session.transfers.status.collectAsStateWithLifecycle()
    val pending by session.pending.collectAsStateWithLifecycle()
    val warning by session.inputWarning.collectAsStateWithLifecycle()
    val sessionTitle by session.title.collectAsStateWithLifecycle()
    val outputRevision = session.revision.collectAsStateWithLifecycle()
    // The renderer's fill colour, re-read on each output but read only while drawing, so the workspace (and the
    // screen around it) is redrawn when the colour changes (OSC 11, reverse video, theme), not on every output frame.
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val background = remember(session, outputRevision, lightTheme) {
        derivedStateOf { checkNotNull(outputRevision.value); terminalBackground(session) }
    }
    val context = LocalContext.current
    val inputScope = rememberCoroutineScope()
    val landscape = isLandscape()
    val settings = remember { TerminalSettings(context) }
    var fontSize by rememberSaveable { mutableIntStateOf(settings.fontSize) }
    // Snapshot reads: changing font in Settings recomposes this pane and AndroidView.update.
    val terminalFontKey = TerminalFonts.selection to TerminalFonts.revision
    var editor by rememberSaveable { mutableStateOf(false) }
    var draft by remember { mutableStateOf(TextFieldValue(session.draft, TextRange(session.draft.length))) }
    var terminal by remember { mutableStateOf<TerminalView?>(null) }
    var confirmPaste by remember { mutableStateOf<String?>(null) }
    var restartConfirm by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sessionMenu by remember { mutableStateOf(false) }
    var fileAction by remember { mutableStateOf<FileAction?>(null) }
    // The view and its client outlive a switch of session; taps go to the one shown now.
    val shownSession by rememberUpdatedState(session)
    var commonCommandsOpen by rememberSaveable { mutableStateOf(false) }
    val sticky = remember { StickyModifiers() }
    var dockPinned by rememberSaveable { mutableStateOf(settings.dockPinned) }
    var dockExpanded by rememberSaveable { mutableStateOf(false) }
    var dockBounds by remember { mutableStateOf(Rect.Zero) }
    var workspaceOrigin by remember { mutableStateOf(Offset.Zero) }
    val focus = LocalFocusManager.current
    val composeView = LocalView.current
    val keyboard = remember(composeView) { SoftwareKeyboardControllerCompat(composeView) }
    val imeVisible = WindowInsets.isImeVisible
    // Only explicit pinning reserves terminal width; placement follows the IME-sized viewport.
    // Connection notices use the same golden-section anchor; show controls only when ready.
    val dockAvailable = state.phase == Phase.CONNECTED && !editor
    val dockVisible = dockAvailable && (dockPinned || dockExpanded)
    val dockReserved = dockVisible && dockPinned
    val floatingDock = dockVisible && !dockReserved
    fun dismissDockMenus() {
        menu = false
        sessionMenu = false
        if (!dockPinned) dockExpanded = false
        sticky.clear()
    }
    // A dock the pet opened (not pinned) goes back to the pet after a quiet spell; a touch on it starts the spell over,
    // and an open menu holds it.
    // Timed by frames, not an animation: with animations turned off in the system settings one would end at once.
    var dockTouches by remember { mutableIntStateOf(0) }
    val dockCountdown = remember { mutableFloatStateOf(1f) }
    val dockCountsDown = floatingDock && !menu && !sessionMenu && TerminalToolbox.dockHideMillis > 0
    LaunchedEffect(dockCountsDown, dockTouches) {
        dockCountdown.floatValue = 1f
        if (!dockCountsDown) return@LaunchedEffect
        val hideAfter = TerminalToolbox.dockHideMillis.toFloat()
        val start = withFrameMillis { it }
        while (dockCountdown.floatValue > 0f) {
            dockCountdown.floatValue = (1f - (withFrameMillis { it } - start) / hideAfter).coerceAtLeast(0f)
        }
        sticky.clear()
        dockExpanded = false
    }
    // The collapsed toolbox fades out after a quiet spell and returns on the next touch.
    var toolboxAwake by remember { mutableStateOf(true) }
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(touches, dockAvailable, dockVisible) {
        toolboxAwake = true
        val hideAfter = TerminalToolbox.autoHideMillis
        if (dockAvailable && !dockVisible && hideAfter > 0) {
            delay(hideAfter)
            toolboxAwake = false
        }
    }
    val petRing = remember { PetRingState() }
    val petShortcuts = petRing.shortcuts
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(dockAvailable, dockVisible) {
        if (!dockAvailable || dockVisible) petRing.close()
    }
    // An open ring keeps itself and the pet up until a tap, or until it has been left alone for a long spell; then
    // both go together.
    fun dismissPetRing() { petRing.close(); if (TerminalToolbox.autoHideMillis > 0) toolboxAwake = false }
    LaunchedEffect(petRing.pinned, touches) {
        val hideAfter = TerminalToolbox.petRingHideMillis
        if (petRing.pinned && hideAfter > 0) {
            delay(hideAfter)
            dismissPetRing()
        }
    }
    // Resting on a menu item unfolds its sub-ring.
    LaunchedEffect(petRing.hovered, petShortcuts) {
        val menu = petRing.hovered?.let(petShortcuts::getOrNull)?.action as? PetAction.Menu ?: return@LaunchedEffect
        delay(350)
        petRing.hovered = null
        petRing.unfold(menu.items)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    // The cursor-row Enter key stays out of the way while typing and appears once input pauses,
    // or at once on a double tap; it fades after a quiet spell.
    var enterKeyAwake by remember { mutableStateOf(false) }
    var enterKeyTouches by remember { mutableIntStateOf(0) }
    var enterKeyTyping by remember { mutableStateOf(false) }
    // A double tap (Tab) brings Up/Down keys above and below the pet and Left to its left, to move through
    // the completions or menu it opens; they fade after a quiet spell, and each press restarts it. A line
    // starting with `/` brings them too, for its command menu, and keeps them up for as long as it does.
    val slashCommandLine by session.slashCommandLine.collectAsStateWithLifecycle()
    var petArrowsAwake by remember { mutableStateOf(false) }
    var petArrowTouches by remember { mutableIntStateOf(0) }
    // Where the pet and its arrows sit (root coordinates), so the cursor-row Enter/Esc key can step aside.
    var petArrowBounds by remember { mutableStateOf<Rect?>(null) }
    val petArrowsShown = (petArrowsAwake || slashCommandLine != 0) && petRing.center == null
    LaunchedEffect(petArrowTouches) {
        if (petArrowTouches == 0) return@LaunchedEffect
        petArrowsAwake = true
        val hideAfter = TerminalToolbox.petArrowsHideMillis
        if (hideAfter > 0) {
            delay(hideAfter)
            petArrowsAwake = false
        }
    }
    LaunchedEffect(enterKeyTouches) {
        if (enterKeyTouches == 0) return@LaunchedEffect
        if (enterKeyTyping) {
            enterKeyAwake = false
            delay(TerminalToolbox.enterKeyTypingPauseMillis)
        }
        enterKeyAwake = true
        val hideAfter = TerminalToolbox.enterKeyHideMillis
        if (hideAfter > 0) {
            delay(hideAfter)
            enterKeyAwake = false
        }
    }
    // Observe the first press without consuming it: the same tap still reaches the terminal,
    // including its keyboard, selection and scrolling gestures. Pinned controls stay visible.
    val workspaceTouch = Modifier.pointerInput(dockAvailable, floatingDock) {
        if (dockAvailable) awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val presses = event.changes.filter { it.pressed && !it.previousPressed }
                if (presses.isEmpty()) continue
                touches++
                if (floatingDock && presses.any { !dockBounds.contains(it.position + workspaceOrigin) }) {
                    dismissDockMenus()
                }
            }
        }
    }
    fun updateDraft(value: TextFieldValue) {
        draft = if (value.text.length <= MAX_PASTE_SIZE) value else TextFieldValue(value.text.take(MAX_PASTE_SIZE))
        session.draft = draft.text
    }
    val arrowKeys = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
    fun terminalKey(keyCode: Int) {
        // Sticky modifiers apply to arrows (e.g. Shift+Left → CSI ESC[1;2D); other keys are sent unmodified.
        if (keyCode !in arrowKeys) {
            sticky.clear()
        }
        terminal?.sendInputControlKey(keyCode)
        sticky.clear()
    }
    /** An arrow key beside the pet: keeps the arrows up a while longer and sends the key at the live screen. */
    fun petArrow(keyCode: Int) {
        petArrowTouches++
        terminal?.scrollToBottom()
        terminalKey(keyCode)
    }
    fun sendPetShortcut(shortcut: PetShortcut) {
        when (val action = shortcut.action) {
            is PetAction.Keys -> {
                sticky.clear()
                terminal?.scrollToBottom()
                session.write(action.keys)
                if (action.repeat) inputScope.launch {
                    delay(120)
                    session.write(action.keys)
                }
            }
            is PetAction.Key -> {
                val modifiers = (if (sticky.ctrl) KeyHandler.KEYMOD_CTRL else 0) or (if (sticky.shift) KeyHandler.KEYMOD_SHIFT else 0)
                sticky.clear()
                terminal?.scrollToBottom()
                terminal?.sendInputControlKey(action.keyCode, modifiers)
            }
            // Like the dock's Ctrl and Shift keys: armed for the next key, Ctrl with the keyboard up.
            PetAction.Ctrl -> {
                sticky.shift = false
                sticky.ctrl = !sticky.ctrl
                if (sticky.ctrl) terminal?.let { view ->
                    view.requestFocus()
                    SoftwareKeyboardControllerCompat(view).show()
                }
            }
            PetAction.Shift -> {
                sticky.ctrl = false
                sticky.shift = !sticky.shift
                if (sticky.shift) terminal?.requestFocus()
            }
            is PetAction.Menu -> Unit
        }
    }
    /** Swipe-and-hold jumps to the line start (Ctrl+A) or end (Ctrl+E); armed modifiers are dropped, not applied. */
    fun lineEdge(control: String) {
        terminal?.sendInputText(control)
        sticky.clear()
    }
    fun hideKeyboard() {
        // Clear Compose/native focus before hiding so focus transfer cannot reopen the IME.
        sticky.clear()
        focus.clearFocus(force = true)
        terminal?.clearFocus()
        keyboard.hide()
    }
    // Enter puts the keyboard away after 15s without typing, so a follow-up line can still be typed.
    var keyboardHide by remember { mutableStateOf<Job?>(null) }
    fun hideKeyboardSoon() {
        keyboardHide?.cancel()
        keyboardHide = inputScope.launch { delay(KeyboardHideAfterEnterMillis); hideKeyboard() }
    }
    fun sendPaste(text: String) {
        terminal?.scrollToBottom()
        session.paste(text)
    }
    fun paste(text: String) {
        if ((text.contains('\n') || text.contains('\r')) && !session.getEmulator().isBracketedPasteEnabled) confirmPaste = text
        else sendPaste(text)
    }
    fun clipboardText(): String? = context.clipboardText().ifEmpty { null }
    fun showClipboard() {
        // Stage clipboard content in the editor; reading clipboard requires a user gesture.
        clipboardText()?.let { updateDraft(insertDraftText(draft, it, MAX_PASTE_SIZE)); editor = true; hideKeyboard() }
    }
    // A tapped path opens in the file preview, at its line, when it names a file.
    var tappedFile by remember(session.id) { mutableStateOf<Pair<String, Int>?>(null) }
    val openPath by rememberUpdatedState { candidates: List<Pair<String, Int>>, notFound: Runnable ->
        if (session.state.value.phase != Phase.CONNECTED) false else {
            // The fallback replays a tap on the view: it must run on the main thread.
            inputScope.launch(Dispatchers.Main.immediate) {
                // A path a TUI broke across rows comes joined first, then as the tapped piece alone.
                val file = withTimeoutOrNull(PATH_LOOKUP_MILLIS) {
                    candidates.firstNotNullOfOrNull { (path, line) -> session.terminalFile(path)?.let { it to line } }
                }
                if (file == null) notFound.run() else { hideKeyboard(); tappedFile = file }
            }
            true
        }
    }
    // AndroidView can outlive a recomposition/reconnection. Always paste into the current session.
    val pasteClipboard by rememberUpdatedState {
        if (session.state.value.phase == Phase.CONNECTED) clipboardText()?.let { text ->
            sticky.clear()
            paste(text)
        }
    }
    DisposableEffect(session) {
        session.copyToClipboard = { text -> context.copyText(text, tr("终端选区")) }
        session.pasteFromClipboard = { showClipboard() }
        onDispose {
            session.onScreenChanged = null; session.copyToClipboard = null; session.pasteFromClipboard = null
        }
    }
    LaunchedEffect(fontSize) { settings.fontSize = fontSize }
    LaunchedEffect(session.id) { hideKeyboard() }
    LaunchedEffect(state.phase) {
        if (state.phase != Phase.CONNECTED) {
            sticky.clear(); confirmPaste = null
            menu = false; sessionMenu = false
        }
        else session.inputWarning.value = null
    }
    // Opening the arrow menu can hide the IME. Keep Shift armed until the arrow is sent.
    // A keyboard closed some other way drops a pending hide, so reopening it is not undone.
    LaunchedEffect(imeVisible) { if (!imeVisible) { sticky.ctrl = false; keyboardHide?.cancel() } }
    BackHandler(editor || imeVisible) { editor = false; hideKeyboard() }
    val fileControls: @Composable () -> Unit = {
        TerminalFileActions(enabled = state.phase == Phase.CONNECTED && !transferStatus.running) { action ->
            hideKeyboard()
            fileAction = action
        }
    }
    val dock: @Composable (Dp) -> Unit = { availableHeight ->
        TerminalDock(availableHeight, landscape, imeVisible, modifier = Modifier.onGloballyPositioned { dockBounds = it.boundsInRoot() }, collapseControl = {
            IconButton(onClick = {
                sticky.clear()
                dockExpanded = false
                dockPinned = false
                settings.dockPinned = false
            }, modifier = Modifier.testTag("collapse-dock")) {
                // A floating dock's close button counts down the quiet spell before the dock goes on its own.
                val ring = MaterialTheme.colorScheme.primary
                val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                Box(Modifier.size(32.dp).drawBehind {
                    if (dockPinned || TerminalToolbox.dockHideMillis <= 0) return@drawBehind
                    val stroke = Stroke(2.dp.toPx())
                    drawCircle(track, style = stroke)
                    drawArc(ring, -90f, 360f * dockCountdown.floatValue, false, style = stroke)
                }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = tr("收起操作栏"), modifier = Modifier.size(20.dp))
                }
            }
        }, sessionControl = { sessionModifier ->
            SessionSwitcher(session, sessionTitle, state.phase, sessions, sessionMenu, sessionModifier,
                onExpand = { hideKeyboard(); sessionMenu = true }, onDismiss = ::dismissDockMenus,
                onHostDetail = { sessionMenu = false; onHostDetail() },
                onClose = { sessionMenu = false; onClose() },
                onRestart = { sessionMenu = false; restartConfirm = true },
                onSelect = { sessionMenu = false; onSelectSession(it) })
        }, primaryControls = {
            // This host's desktop, beside the session it belongs to.
            // A tap shows the desktop small over the terminal (or closes it), tinted like the sessions button while it
            // shows; a long press opens the desktop page.
            if (onDesktop != null) Box(Modifier.size(48.dp).clip(CircleShape).combinedClickable(
                onClick = { sticky.clear(); dismissDockMenus(); onDesktop() },
                onClickLabel = if (pip.desktop != null) tr("关闭桌面小窗") else tr("小窗查看桌面"), role = androidx.compose.ui.semantics.Role.Button,
                onLongClickLabel = pip.onFull?.let { tr("打开全屏桌面") },
                onLongClick = pip.onFull?.let { full -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    sticky.clear(); hideKeyboard(); full()
                } }).testTag("terminal-desktop"), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_desktop), contentDescription = tr("打开桌面"),
                    tint = if (pip.desktop != null) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }
            fileControls()
            IconButton(
                onClick = { hideKeyboard(); commonCommandsOpen = true },
                enabled = state.phase == Phase.CONNECTED,
                modifier = Modifier.testTag("common-commands")
            ) {
                Icon(painterResource(R.drawable.ic_commands), contentDescription = tr("常用命令"))
            }
            Box {
                IconButton(onClick = { sticky.clear(); menu = true }, modifier = Modifier.testTag("terminal-menu")) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = tr("终端设置"))
                }
                DropdownMenu(menu, ::dismissDockMenus) {
                    DropdownMenuItem(text = { Text(tr("编辑文本")) }, onClick = { menu = false; editor = true; hideKeyboard() })
                    DropdownMenuItem(text = { Text(if (dockPinned) tr("取消固定操作栏") else tr("固定操作栏")) }, onClick = {
                        menu = false; dockPinned = !dockPinned; dockExpanded = true
                        settings.dockPinned = dockPinned
                    })
                    DropdownMenuItem(text = { Text(tr("隐藏操作栏")) }, onClick = {
                        menu = false; dockPinned = false; dockExpanded = false; editor = false; hideKeyboard()
                        settings.dockPinned = false
                    })
                    DropdownMenuItem(text = { Text(tr("粘贴")) }, onClick = { menu = false; showClipboard() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(trf("增大字号 · %d sp", fontSize)) }, onClick = { fontSize = (fontSize + 1).coerceAtMost(TerminalSettings.MAX_FONT_SIZE); menu = false })
                    DropdownMenuItem(text = { Text(trf("减小字号 · %d sp", fontSize)) }, onClick = { fontSize = (fontSize - 1).coerceAtLeast(TerminalSettings.MIN_FONT_SIZE); menu = false })
                }
            }
        })
    }
    // The workspace extends under the transparent status bar in the terminal's own background
    // colour (including remote OSC 11 changes). In portrait the text runs under the bar too, faded
    // by a scrim so the clock and icons stay legible; controls and notices stay below the bar.
    // The keyboard inset jumps to its final height instead of following the slide animation:
    // every intermediate height would reflow the emulator and send the remote a resize, which
    // made opening and closing the keyboard stutter. The background still fills the area the
    // keyboard slides over.
    BoxWithConstraints(modifier.fillMaxWidth().testTag("terminal-workspace")
        .drawBehind { drawRect(background.value) }
        .windowInsetsPadding(WindowInsets.imeAnimationTarget)
        .onGloballyPositioned { workspaceOrigin = it.positionInRoot() }.then(workspaceTouch)) {
        val workspaceHeight = maxHeight
        val safeDockHeight = with(LocalDensity.current) {
            val edges = WindowInsets.displayCutout.union(WindowInsets.statusBars)
            workspaceHeight - edges.getTop(this).toDp() - edges.getBottom(this).toDp() - 8.dp
        }
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                var viewportHeight by remember { mutableIntStateOf(0) }
                Box(Modifier.weight(1f).fillMaxWidth().testTag("terminal-viewport").onSizeChanged { viewportHeight = it.height }) {
                AndroidView(modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 6.dp, top = 6.dp).clipToBounds()
                    .testTag("terminal"), factory = { ctx ->
                    // Session, theme, font and history are bound in update, which also runs after creation.
                    TerminalView(ctx, null).apply {
                        terminal = this
                        setTerminalViewClient(MobileTerminalClient(this,
                            onScale = { factor -> fontSize = (fontSize * factor).toInt().coerceIn(TerminalSettings.MIN_FONT_SIZE, TerminalSettings.MAX_FONT_SIZE) },
                            controlArmed = { sticky.ctrl }, consumeControl = { sticky.ctrl = false },
                            shiftArmed = { sticky.shift }, consumeShift = { sticky.shift = false },
                            onDoubleTap = {
                                terminalKey(KeyEvent.KEYCODE_TAB)
                                enterKeyTyping = false
                                enterKeyTouches++
                                touches++
                                petArrowTouches++
                            }, onTripleTap = { pasteClipboard() },
                            // Swipes send plain arrows; an armed Shift still makes Shift+Left for Codex questions.
                            onSwipeLeft = { terminalKey(KeyEvent.KEYCODE_DPAD_LEFT) }, onSwipeRight = { terminalKey(KeyEvent.KEYCODE_DPAD_RIGHT) },
                            onSwipeHoldLeft = { lineEdge("\u0001") }, onSwipeHoldRight = { lineEdge("\u0005") },
                            // Enter usually runs or submits the line: put the keyboard away soon so the output shows.
                            onSoftKeyboardEnter = { hideKeyboardSoon() },
                            onOpenPath = { candidates, notFound -> openPath(candidates, notFound) },
                            onOpenLoopback = { url -> shownSession.let { openPortPreview(ctx, url, it.host, it::openSocket) } }))
                        // Typing again keeps the keyboard open; the Enter that follows restarts the wait.
                        setUserInputListener { keyboardHide?.cancel(); enterKeyTyping = true; enterKeyTouches++ }
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        isFocusableInTouchMode = true
                    }
                }, update = { view ->
                    // Compose may reuse the view: always rebind the session and its screen callback.
                    view.attachSession(session)
                    view.setCursorDraggingEnabled(state.phase == Phase.CONNECTED)
                    view.setPressedLinkColor(linkColor)
                    session.onScreenChanged = { view.onScreenUpdated() }
                    if (session.getEmulator().mColors.setLightTheme(lightTheme)) view.onScreenUpdated()
                    view.setTextSize((fontSize * view.resources.displayMetrics.density * view.resources.configuration.fontScale).toInt())
                    view.setTypeface(TerminalFonts.resolveTypeface(view.context))
                    checkNotNull(terminalFontKey)
                })
                val cursorKeysAllowed = state.phase == Phase.CONNECTED && !editor
                androidx.compose.animation.AnimatedVisibility(enterKeyAwake && cursorKeysAllowed,
                    modifier = Modifier.align(Alignment.TopEnd), enter = fadeIn(tween(120)), exit = fadeOut(tween(400))) {
                    terminal?.let { view ->
                        CursorRowKeys(view, outputRevision, viewportHeight, CursorKeyHeight, avoid = petArrowBounds.takeIf { petArrowsShown }) {
                            CursorKey(R.drawable.ic_return, tr("回车"), "cursor-enter") {
                                enterKeyTyping = false
                                enterKeyTouches++
                                sticky.clear()
                                view.scrollToBottom()
                                session.write("\r")
                                // Like the soft keyboard's Enter: put an open keyboard away so the output shows.
                                if (imeVisible) hideKeyboardSoon()
                            }
                        }
                    }
                }
                // Esc holds the Enter key's place whenever Enter is hidden.
                androidx.compose.animation.AnimatedVisibility(!enterKeyAwake && cursorKeysAllowed,
                    modifier = Modifier.align(Alignment.TopEnd), enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
                    terminal?.let { view ->
                        CursorRowKeys(view, outputRevision, viewportHeight, CursorKeyHeight, avoid = petArrowBounds.takeIf { petArrowsShown }) {
                            CursorKey(null, tr("发送 Esc"), "cursor-escape", label = "Esc") {
                                sticky.clear()
                                view.scrollToBottom()
                                session.write("\u001b")
                            }
                        }
                    }
                }
                // Hidden (landscape) status bars have no inset, so the scrim only appears under a visible bar.
                val statusBars = WindowInsets.statusBars
                Box(Modifier.fillMaxWidth().windowInsetsTopHeight(statusBars.add(WindowInsets(top = 12.dp)))
                    .testTag("status-bar-scrim").drawBehind {
                        val statusBar = statusBars.getTop(this).toFloat()
                        if (statusBar <= 0f) return@drawBehind
                        val color = background.value
                        drawRect(Brush.verticalGradient(0f to color.copy(alpha = 0.85f),
                            statusBar / size.height to color.copy(alpha = 0.55f), 1f to Color.Transparent))
                    })
                if (pending == null) {
                    if (state.phase != Phase.CONNECTED) TerminalNoticeOverlay {
                        if (state.active) TerminalConnectionProgress(state.phase, onCancel = session::disconnect)
                        else TerminalConnectionNotice(state, onReconnect, onShell, onReverify, onLeave)
                    }
                    else warning?.let { message -> TerminalNoticeOverlay {
                        TerminalInputNotice(message) { session.inputWarning.value = null }
                    } }
                }
                }
                if (editor) DraftEditor(draft, ::updateDraft, landscape, imeVisible, connected = state.phase == Phase.CONNECTED,
                    onCollapse = { editor = false; hideKeyboard() }, onEnter = { session.write("\r") }, onSend = { paste(draft.text) })
            }
            if (dockReserved) TerminalDockPlacement(
                Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End))
                    .width(if (terminalDockTwoColumns(landscape, imeVisible, safeDockHeight)) 96.dp else 48.dp),
                endInset = 0.dp, content = dock)
        }
        pip.desktop?.let { desktop ->
            val density = LocalDensity.current
            val video by desktop.videoSize.collectAsStateWithLifecycle()
            val state = rememberPipState("${session.host.id}.terminal${if (landscape) ".land" else ""}", if (landscape) 0.4f else 0.7f)
            with(density) {
                val w = maxWidth.toPx()
                val h = maxHeight.toPx()
                val edges = WindowInsets.displayCutout.union(WindowInsets.statusBars)
                val margin = 8.dp.toPx()
                // Anywhere it is dragged, clear of the status bar, cutouts, a pinned dock and the gesture bar.
                val end = margin + edges.getRight(this, androidx.compose.ui.unit.LayoutDirection.Ltr) +
                    (if (dockReserved) (if (terminalDockTwoColumns(landscape, imeVisible, safeDockHeight)) 96.dp else 48.dp).toPx() else 0f)
                val bottom = margin + if (imeVisible) 0 else WindowInsets.navigationBars.getBottom(this)
                val room = Rect(margin + edges.getLeft(this, androidx.compose.ui.unit.LayoutDirection.Ltr), margin + edges.getTop(this),
                    w - end, h - bottom)
                PipWindow(state, room, w, if (video.second > 0) video.first / video.second.toFloat() else 1.6f,
                    tag = "pip-desktop", description = tr("桌面小窗，点按切换到桌面"),
                    onTap = { sticky.clear(); hideKeyboard(); pip.onTap() }, onClose = pip.onClose) {
                    DesktopMiniContent(desktop)
                }
            }
        }
        val ringCenter = petRing.center
        if (ringCenter != null && dockAvailable && !dockVisible) {
            val topInset = WindowInsets.displayCutout.union(WindowInsets.statusBars).getTop(LocalDensity.current).toFloat()
            PetShortcutArc(petShortcuts, ringCenter, topInset, petRing.hovered, armed = { action ->
                action == PetAction.Ctrl && sticky.ctrl || action == PetAction.Shift && sticky.shift
            }, onTap = if (petRing.pinned) { index ->
                val picked = index?.let(petShortcuts::getOrNull)
                val menu = picked?.action as? PetAction.Menu
                if (menu != null) {
                    petRing.unfold(menu.items)
                } else {
                    dismissPetRing()
                    if (picked != null && state.phase == Phase.CONNECTED) sendPetShortcut(picked)
                }
            } else null) { petRing.arc = it }
        }
        if (floatingDock) {
            TerminalDockPlacement { height ->
                Surface(modifier = Modifier.pointerInput(Unit) {
                    awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); dockTouches++ } }
                }, shape = terminalDockShape(terminalDockTwoColumns(landscape, imeVisible, height)),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f), shadowElevation = 4.dp
                ) { dock(height) }
            }
        } else if (dockAvailable && !dockVisible) {
            // Leave the composition while hidden so taps on that edge reach the terminal.
            // The ring opens around the pet, so the arrows step aside while it is up.
            AnimatedVisibility(toolboxAwake || petRing.center != null || petArrowsShown, enter = fadeIn(tween(160)) + slideInHorizontally(tween(220)) { it / 2 },
                exit = fadeOut(tween(400)) + slideOutHorizontally(tween(400)) { it / 3 }) {
                // Up above the pet, Down below it and Left to its left; the pet stays put between them.
                // Held in place while the pet is held, so its ring stays around it as the keyboard comes or goes.
                TerminalDockPlacement(held = petRing.center != null) { Column(Modifier.onGloballyPositioned { petArrowBounds = it.boundsInRoot() },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    PetArrowKey(petArrowsShown, R.drawable.ic_expand_up, tr("发送上方向键"), "pet-arrow-up") {
                        petArrow(KeyEvent.KEYCODE_DPAD_UP)
                    }
                    Box(contentAlignment = Alignment.CenterStart) {
                        ToolboxPet(tr("显示操作栏"), onHoldStart = { center ->
                            petRing.hovered = null
                            petRing.menu = null
                            petRing.pinned = false
                            petRing.app = session.screenApp()
                            petRing.center = center - workspaceOrigin
                        }, onHoldMove = { drag ->
                            val arc = petRing.arc
                            // Back over the pet leaves a sub-ring.
                            if (petRing.menu != null && arc != null && drag.getDistance() < arc.radius * 0.25f) {
                                petRing.menu = null
                                petRing.arc = null
                            }
                            val hovered = petRing.arc?.hoveredItem(drag)
                            if (hovered != petRing.hovered) {
                                petRing.hovered = hovered
                                if (hovered != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }, onHoldEnd = { released ->
                            val picked = petRing.hovered?.let(petShortcuts::getOrNull)
                            val menu = picked?.action as? PetAction.Menu
                            touches++
                            if (released && (picked == null || menu != null)) {
                                // Let go between items: keep the ring up for a tap. A menu not yet unfolded opens now.
                                petRing.hovered = null
                                if (menu != null) petRing.unfold(menu.items)
                                petRing.pinned = true
                            } else {
                                petRing.close()
                                if (released && picked != null && state.phase == Phase.CONNECTED) sendPetShortcut(picked)
                            }
                        }) { dockExpanded = true }
                        // Takes no width, so the column stays centred on the pet; the key hangs off its left side.
                        Box(Modifier.layout { measurable, constraints ->
                            val key = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                            layout(0, 0) { key.placeRelative(-key.width, -key.height / 2) }
                        }) {
                            PetArrowKey(petArrowsShown, R.drawable.ic_expand_left, tr("发送左方向键"), "pet-arrow-left") {
                                petArrow(KeyEvent.KEYCODE_DPAD_LEFT)
                            }
                        }
                    }
                    PetArrowKey(petArrowsShown, R.drawable.ic_expand_down, tr("发送下方向键"), "pet-arrow-down") {
                        petArrow(KeyEvent.KEYCODE_DPAD_DOWN)
                    }
                } }
            }
        }
    }
    pending?.let { request -> PromptDialog(request) }
    // Outlives the preview: Download closes it, and these actions then carry the download through.
    val downloads = rememberDownloadActions(session)
    tappedFile?.let { (path, line) ->
        RemoteFilePreview(session, path, state.phase == Phase.CONNECTED,
            canDownload = state.phase == Phase.CONNECTED && !transferStatus.running && !downloads.busy,
            onDownload = {
                tappedFile = null
                downloads.start(listOf(DownloadItem(path)), false)
            }, onDismiss = { tappedFile = null }, line = line)
    }
    FileTransferUi(session, fileAction, downloads = downloads, onInsertPath = { path ->
        updateDraft(insertDraftText(draft, CodexImageAttach.pathForPaste(path), MAX_PASTE_SIZE)); editor = true; hideKeyboard()
    }) { fileAction = null }
    if (commonCommandsOpen) CommonCommandsSheet(session, onSelect = { command ->
        commonCommandsOpen = false
        editor = false
        hideKeyboard()
        terminal?.scrollToBottom()
        session.executeHistoryCommand(command)
    }, onDismiss = { commonCommandsOpen = false })
    confirmPaste?.let { text ->
        PasteConfirmDialog(enabled = state.phase == Phase.CONNECTED, onSend = { sendPaste(text); confirmPaste = null }) { confirmPaste = null }
    }
    if (restartConfirm) RestartSessionDialog(session.mode, onRestart = { restartConfirm = false; onRestart() }) { restartConfirm = false }
}

/** Idle timeout of the collapsed toolbox button; 0 keeps it visible (instrumentation tests). */
internal object TerminalToolbox {
    @Volatile var autoHideMillis = 5_000L
    /** Idle timeout of a shortcut ring left open after the pet is let go; the pet leaves with it. 0 keeps it. */
    @Volatile var petRingHideMillis = 4_000L
    /** Idle timeout of the cursor-row Enter key; 0 keeps it until the pane leaves. */
    @Volatile var enterKeyHideMillis = 3_000L
    /** How long typing must pause before the cursor-row Enter key appears. */
    @Volatile var enterKeyTypingPauseMillis = 700L
    /** Idle timeout of the Up/Down keys a double tap brings above and below the pet; 0 keeps them. */
    @Volatile var petArrowsHideMillis = 4_000L
    /** Idle timeout of the dock the pet opens (a pinned one stays); 0 keeps it. */
    @Volatile var dockHideMillis = 5_000L
}

internal const val KeyboardHideAfterEnterMillis = 15_000L
/** A tapped path that takes longer than this to look up is treated as no file. */
/**
 * A tap on a path that names no file only does what a plain tap does once this is up, so a slow host would leave the
 * tap without an answer that long; a first lookup that opens SFTP takes about a second. iOS waits as long.
 */
private const val PATH_LOOKUP_MILLIS = 3_000L

/** The colour the terminal renderer fills its canvas with, so the status bar area matches it. */
private fun terminalBackground(session: TerminalConnection): Color {
    val emulator = session.getEmulator()
    return Color(emulator.mColors.mCurrentColors[if (emulator.isReverseVideo) TextStyle.COLOR_INDEX_FOREGROUND
        else TextStyle.COLOR_INDEX_BACKGROUND])
}

/**
 * The terminal page's small window: the host's [desktop] (null for none), what a tap on it does ([onTap]: the
 * desktop page takes the screen), what closes it, and [onFull], the long press on the dock's desktop button that
 * opens the desktop page (null when the host has no desktop to show).
 */
internal data class TerminalPip(
    val desktop: io.itermux.app.desktop.DesktopConnection? = null,
    val onTap: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onFull: (() -> Unit)? = null,
)
