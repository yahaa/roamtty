package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.SoftwareKeyboardControllerCompat
import androidx.core.content.edit
import com.termux.view.TerminalView
import io.itermux.app.TerminalFonts
import io.itermux.app.session.*
import io.itermux.app.R
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalPane(session: TerminalConnection, modifier: Modifier, sessions: List<TerminalConnection>, lightTheme: Boolean, onSelectSession: (String) -> Unit, onSessions: () -> Unit, onHosts: () -> Unit, onReconnect: () -> Unit, onNewSession: () -> Unit, onShell: () -> Unit, onReverify: () -> Unit, onClose: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val transferStatus by session.transfers.status.collectAsStateWithLifecycle()
    val pending by session.pending.collectAsStateWithLifecycle()
    val warning by session.inputWarning.collectAsStateWithLifecycle()
    val tmuxHistory by session.tmuxHistory.collectAsStateWithLifecycle()
    val showingOutput = tmuxHistory?.let { it.active && it.source == io.itermux.ssh.HistorySource.OUTPUT } == true
    val recordedOutput = remember(session, showingOutput) { if (showingOutput) session.outputHistorySnapshot() else emptyList() }
    BackHandler(showingOutput) { session.exitTmuxHistory() }
    val sessionTitle by session.title.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val inputScope = rememberCoroutineScope()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val prefs = remember { context.getSharedPreferences("terminal-settings", Context.MODE_PRIVATE) }
    var fontSize by rememberSaveable { mutableIntStateOf(prefs.getInt("fontSize", 13)) }
    // Snapshot reads: changing font in Settings recomposes this pane and AndroidView.update.
    val terminalFontKey = TerminalFonts.selection to TerminalFonts.revision
    var editor by rememberSaveable { mutableStateOf(false) }
    var draft by remember { mutableStateOf(TextFieldValue(session.draft, TextRange(session.draft.length))) }
    var terminal by remember { mutableStateOf<TerminalView?>(null) }
    var confirmPaste by remember { mutableStateOf<String?>(null) }
    var closeConfirm by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sessionMenu by remember { mutableStateOf(false) }
    var shortcutMenu by remember { mutableStateOf(false) }
    var fileAction by remember { mutableStateOf<FileAction?>(null) }
    var commonCommandsOpen by rememberSaveable { mutableStateOf(false) }
    var editSessionTitle by remember { mutableStateOf(false) }
    var ctrlArmed by remember { mutableStateOf(false) }
    var shiftArmed by remember { mutableStateOf(false) }
    var dockPinned by rememberSaveable { mutableStateOf(prefs.getBoolean("dockPinned", false)) }
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
        shortcutMenu = false
        if (!dockPinned) dockExpanded = false
        ctrlArmed = false
        shiftArmed = false
    }
    // Observe the first press without consuming it: the same tap still reaches the terminal,
    // including its keyboard, selection and scrolling gestures. Pinned controls stay visible.
    val outsideDockTouch = Modifier.pointerInput(floatingDock) {
        if (floatingDock) awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed && !it.previousPressed &&
                        !dockBounds.contains(it.position + workspaceOrigin) }) {
                    dismissDockMenus()
                }
            }
        }
    }
    fun updateDraft(value: TextFieldValue) {
        draft = if (value.text.length <= 128 * 1024) value else TextFieldValue(value.text.take(128 * 1024))
        session.draft = draft.text
    }
    fun terminalKey(keyCode: Int) {
        val arrow = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        // Sticky Shift applies to arrows (e.g. Shift+Left → CSI ESC[1;2D); Ctrl stays keyboard-only.
        if (!arrow) {
            ctrlArmed = false
            shiftArmed = false
        }
        terminal?.sendInputControlKey(keyCode)
        if (arrow) {
            shiftArmed = false
            ctrlArmed = false
        }
    }
    fun hideKeyboard() {
        // Clear Compose/native focus before hiding so focus transfer cannot reopen the IME.
        ctrlArmed = false
        shiftArmed = false
        focus.clearFocus(force = true)
        terminal?.clearFocus()
        keyboard.hide()
    }
    fun sendPaste(text: String) {
        inputScope.launch {
            terminal?.scrollToBottom()
            session.pasteIntoLiveTerminal(text)
        }
    }
    fun paste(text: String) {
        if ((text.contains('\n') || text.contains('\r')) && !session.getEmulator().isBracketedPasteEnabled) confirmPaste = text
        else sendPaste(text)
    }
    fun showClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let {
            // Stage clipboard content in the editor; reading clipboard requires a user gesture.
            updateDraft(insertDraftText(draft, it, 128 * 1024)); editor = true; hideKeyboard()
        }
    }
    // AndroidView can outlive a recomposition/reconnection. Always paste into the current session.
    val pasteClipboard by rememberUpdatedState {
        if (session.state.value.phase == Phase.CONNECTED) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                ?.takeIf { it.isNotEmpty() }?.let { text ->
                    ctrlArmed = false
                    shiftArmed = false
                    paste(text)
                }
        }
    }
    DisposableEffect(session) {
        session.copyToClipboard = { text -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(tr("终端选区"), text)) }
        session.pasteFromClipboard = { showClipboard() }
        onDispose {
            session.onScreenChanged = null; session.copyToClipboard = null; session.pasteFromClipboard = null
        }
    }
    LaunchedEffect(fontSize) { prefs.edit { putInt("fontSize", fontSize) } }
    LaunchedEffect(session.id) { hideKeyboard() }
    LaunchedEffect(showingOutput) { if (showingOutput) { editor = false; hideKeyboard() } }
    LaunchedEffect(state.phase) {
        if (state.phase != Phase.CONNECTED) {
            ctrlArmed = false; shiftArmed = false; confirmPaste = null
            menu = false; sessionMenu = false; shortcutMenu = false
        }
        else session.inputWarning.value = null
    }
    // Opening the arrow menu can hide the IME. Keep Shift armed until the arrow is sent.
    LaunchedEffect(imeVisible) { if (!imeVisible) ctrlArmed = false }
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
                ctrlArmed = false
                shiftArmed = false
                dockExpanded = false
                dockPinned = false
                prefs.edit { putBoolean("dockPinned", false) }
            }, modifier = Modifier.testTag("collapse-dock")) {
                Text("×", fontSize = 24.sp, modifier = Modifier.semantics { contentDescription = tr("收起操作栏") })
            }
        }, sessionControl = { sessionModifier ->
            Box(sessionModifier) {
                TextButton(onClick = { hideKeyboard(); sessionMenu = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = trf("切换会话：%s", session.titleForDisplay(sessionTitle)) }.testTag("session-switcher"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("●", color = if (state.phase == Phase.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontSize = 9.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("▤", fontSize = 18.sp)
                }
                DropdownMenu(sessionMenu, ::dismissDockMenus) {
                    DropdownMenuItem(text = { Text(tr("返回会话列表")) }, onClick = { sessionMenu = false; onSessions() })
                    DropdownMenuItem(text = { Text(tr("返回主机列表")) }, onClick = { sessionMenu = false; onHosts() })
                    DropdownMenuItem(text = { Text(tr("修改会话标题")) }, onClick = { sessionMenu = false; editSessionTitle = true })
                    if (session.mode == SessionMode.TMUX) DropdownMenuItem(
                        text = { Text(tr("新会话")) },
                        onClick = { sessionMenu = false; onNewSession() },
                        modifier = Modifier.testTag("new-session")
                    )
                    HorizontalDivider()
                    sessions.forEach { other ->
                        val otherState by other.state.collectAsStateWithLifecycle()
                        val otherTitle by other.title.collectAsStateWithLifecycle()
                        DropdownMenuItem(text = { Text("${if (other.id == session.id) "✓ " else ""}${other.titleForDisplay(otherTitle)} · ${phaseLabel(otherState.phase)}",
                            maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { sessionMenu = false; onSelectSession(other.id) })
                    }
                }
            }
        }, primaryControls = {
            if (session.mode == SessionMode.TMUX) {
                InputControlButton(if (tmuxHistory?.active == true) tr("实时") else tr("历史"),
                    if (tmuxHistory?.active == true) tr("返回实时画面") else tr("浏览历史记录"),
                    "tmux-history", enabled = state.phase == Phase.CONNECTED, selected = tmuxHistory?.active == true,
                    onClick = { hideKeyboard(); if (tmuxHistory?.active == true) session.exitTmuxHistory() else session.enterTmuxHistory() })
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
                IconButton(onClick = { ctrlArmed = false; shiftArmed = false; menu = true }, modifier = Modifier.testTag("terminal-menu")) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = tr("终端设置"))
                }
                DropdownMenu(menu, ::dismissDockMenus) {
                    DropdownMenuItem(text = { Text(tr("编辑文本")) }, onClick = { menu = false; editor = true; hideKeyboard() })
                    DropdownMenuItem(text = { Text(if (dockPinned) tr("取消固定操作栏") else tr("固定操作栏")) }, onClick = {
                        menu = false; dockPinned = !dockPinned; dockExpanded = true
                        prefs.edit { putBoolean("dockPinned", dockPinned) }
                    })
                    DropdownMenuItem(text = { Text(tr("隐藏操作栏")) }, onClick = {
                        menu = false; dockPinned = false; dockExpanded = false; editor = false; hideKeyboard()
                        prefs.edit { putBoolean("dockPinned", false) }
                    })
                    DropdownMenuItem(text = { Text(tr("粘贴")) }, onClick = { menu = false; showClipboard() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(trf("增大字号 · %d sp", fontSize)) }, onClick = { fontSize = (fontSize + 1).coerceAtMost(28); menu = false })
                    DropdownMenuItem(text = { Text(trf("减小字号 · %d sp", fontSize)) }, onClick = { fontSize = (fontSize - 1).coerceAtLeast(8); menu = false })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(tr("关闭连接")) }, onClick = { menu = false; if (state.active) closeConfirm = true else onClose() })
                }
            }
        }, keyControls = {
            Box {
                InputControlButton("⌘", tr("快捷键"), "terminal-shortcuts", selected = ctrlArmed || shiftArmed,
                    onClick = { shortcutMenu = true })
                DropdownMenu(shortcutMenu, ::dismissDockMenus) {
                    FlowRow(Modifier.widthIn(max = 224.dp).padding(horizontal = 8.dp), maxItemsInEachRow = 4) {
                        InputControlButton("Esc", tr("发送 Esc"), "terminal-escape", enabled = state.phase == Phase.CONNECTED,
                            onClick = { shortcutMenu = false; terminalKey(KeyEvent.KEYCODE_ESCAPE) })
                        InputControlButton("Tab", tr("发送 Tab"), "terminal-tab", enabled = state.phase == Phase.CONNECTED,
                            onClick = { shortcutMenu = false; terminalKey(KeyEvent.KEYCODE_TAB) })
                        InputControlButton("Ctrl", tr("Ctrl，作用于下一次键盘字符"), "terminal-control",
                            enabled = state.phase == Phase.CONNECTED, selected = ctrlArmed, onClick = {
                                shortcutMenu = false
                                shiftArmed = false
                                ctrlArmed = !ctrlArmed
                                if (ctrlArmed) terminal?.let { view ->
                                    view.requestFocus()
                                    SoftwareKeyboardControllerCompat(view).show()
                                }
                            })
                        InputControlButton("Shift", tr("Shift，与方向键组合发送；也可作用于下一次键盘字符"), "terminal-shift",
                            enabled = state.phase == Phase.CONNECTED, selected = shiftArmed, onClick = {
                                ctrlArmed = false
                                shiftArmed = !shiftArmed
                                if (shiftArmed) terminal?.requestFocus()
                            })
                        InputControlButton("←", tr("光标左移"), "terminal-left", enabled = state.phase == Phase.CONNECTED,
                            onClick = { shortcutMenu = false; terminalKey(KeyEvent.KEYCODE_DPAD_LEFT) })
                        InputControlButton("→", tr("光标右移"), "terminal-right", enabled = state.phase == Phase.CONNECTED,
                            onClick = { shortcutMenu = false; terminalKey(KeyEvent.KEYCODE_DPAD_RIGHT) })
                        InputControlButton("⌫", tr("删除字符，长按清空当前输入"), "terminal-delete", enabled = state.phase == Phase.CONNECTED,
                            onLongClick = { shortcutMenu = false; ctrlArmed = false; shiftArmed = false; terminal?.clearCurrentInput() },
                            onClick = { shortcutMenu = false; terminalKey(KeyEvent.KEYCODE_DEL) })
                    }
                }
            }
        })
    }
    BoxWithConstraints(modifier.fillMaxWidth().testTag("terminal-workspace")
        .onGloballyPositioned { workspaceOrigin = it.positionInRoot() }.then(outsideDockTouch)) {
        val workspaceHeight = maxHeight
        val safeDockHeight = with(LocalDensity.current) {
            workspaceHeight - WindowInsets.displayCutout.getTop(this).toDp() -
                WindowInsets.displayCutout.getBottom(this).toDp() - 8.dp
        }
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(modifier = Modifier.fillMaxSize().clipToBounds().background(MaterialTheme.colorScheme.background).testTag("terminal"), factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        session.getEmulator().mColors.setLightTheme(lightTheme)
                        terminal = this
                        setTextSize((fontSize * ctx.resources.displayMetrics.scaledDensity).toInt())
                        setTypeface(TerminalFonts.resolveTypeface(ctx))
                        setTerminalViewClient(MobileTerminalClient(this,
                            onScale = { factor -> fontSize = (fontSize * factor).toInt().coerceIn(8, 28) },
                            controlArmed = { ctrlArmed }, consumeControl = { ctrlArmed = false },
                            shiftArmed = { shiftArmed }, consumeShift = { shiftArmed = false },
                            onDoubleTap = { pasteClipboard() }))
                        attachSession(session)
                        setRemoteHistory(0, 0, null)
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        session.onScreenChanged = { onScreenUpdated() }
                        isFocusableInTouchMode = true
                    }
                }, update = { view ->
                    // Harden against Compose reuse: always rebind session + screen callback (factory alone is not enough).
                    view.attachSession(session)
                    view.setCursorDraggingEnabled(state.phase == Phase.CONNECTED)
                    session.onScreenChanged = { view.onScreenUpdated() }
                    if (session.getEmulator().mColors.setLightTheme(lightTheme)) view.onScreenUpdated()
                    view.setTextSize((fontSize * view.resources.displayMetrics.scaledDensity).toInt())
                    view.setTypeface(TerminalFonts.resolveTypeface(view.context))
                    checkNotNull(terminalFontKey)
                    val activeHistory = tmuxHistory?.takeIf { it.active }
                    val history = activeHistory?.takeIf { it.source == io.itermux.ssh.HistorySource.TMUX }
                    view.setApplicationHistory(false)
                    view.setRemoteHistory(history?.rows ?: 0, history?.position ?: 0,
                        if (history == null) null else TerminalView.RemoteHistoryListener(session::moveTmuxHistoryTo))
                })
                if (showingOutput) OutputHistoryView(recordedOutput, fontSize, lightTheme)
                if (pending == null) {
                    if (state.phase != Phase.CONNECTED) TerminalNoticeOverlay {
                        if (state.active) TerminalConnectionProgress(state.phase, onCancel = session::disconnect)
                        else TerminalConnectionNotice(state, onReconnect, onShell, onReverify)
                    }
                    else warning?.let { message -> TerminalNoticeOverlay {
                        TerminalInputNotice(message) { session.inputWarning.value = null }
                    } }
                }
                }
                if (editor && landscape) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(draft, ::updateDraft,
                            placeholder = { Text(tr("输入文本后，在这里确认"), maxLines = 1) },
                            minLines = 1, maxLines = if (imeVisible) 2 else 3,
                            modifier = Modifier.weight(1f).testTag("prompt-editor"))
                        DraftInputControls(draft, ::updateDraft)
                        TextButton(onClick = { editor = false; hideKeyboard() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr("收起")) }
                        TextButton(onClick = { session.write("\r") }, enabled = state.phase == Phase.CONNECTED, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr("回车 ↵")) }
                        Button(onClick = { paste(draft.text) }, enabled = draft.text.isNotEmpty() && state.phase == Phase.CONNECTED, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(tr("发送文本")) }
                    }
                } else if (editor) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                        OutlinedTextField(draft, ::updateDraft, placeholder = { Text(tr("输入文本后，在这里确认")) }, minLines = 1, maxLines = if (imeVisible) 2 else 4, modifier = Modifier.fillMaxWidth().testTag("prompt-editor"))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            DraftInputControls(draft, ::updateDraft)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { editor = false; hideKeyboard() }, modifier = Modifier.width(48.dp), contentPadding = PaddingValues(4.dp)) { Text(tr("收起")) }
                            TextButton(onClick = { session.write("\r") }, enabled = state.phase == Phase.CONNECTED, modifier = Modifier.width(48.dp).semantics { contentDescription = tr("回车") }, contentPadding = PaddingValues(4.dp)) { Text("↵", fontSize = 22.sp) }
                            Button(onClick = { paste(draft.text) }, enabled = draft.text.isNotEmpty() && state.phase == Phase.CONNECTED, modifier = Modifier.width(88.dp), contentPadding = PaddingValues(8.dp)) { Text(tr("发送文本")) }
                        }
                    }
                }
            }
            if (dockReserved) TerminalDockPlacement(
                Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End))
                    .width(if (landscape && !imeVisible && safeDockHeight >= 320.dp) 96.dp else 48.dp),
                endInset = 0.dp, content = dock)
        }
        if (floatingDock) {
            TerminalDockPlacement { height ->
                Surface(shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f), shadowElevation = 4.dp
                ) { dock(height) }
            }
        } else if (dockAvailable && !dockVisible) {
            TerminalDockPlacement {
                FilledTonalIconButton(
                    onClick = { dockExpanded = true },
                    modifier = Modifier.size(48.dp).testTag("restore-dock"),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                ) {
                    Text("⋯", fontSize = 26.sp,
                        modifier = Modifier.semantics { contentDescription = tr("显示操作栏") })
                }
            }
        }
    }
    pending?.let { request -> PromptDialog(request) }
    if (editSessionTitle) SessionTitleDialog(session) { editSessionTitle = false }
    FileTransferUi(session, fileAction, onInsertPath = { path ->
        updateDraft(insertDraftText(draft, io.itermux.app.session.CodexImageAttach.pathForPaste(path), 128 * 1024)); editor = true; hideKeyboard()
    }) { fileAction = null }
    if (commonCommandsOpen) CommonCommandsSheet(session, onSelect = { command ->
        commonCommandsOpen = false
        editor = false
        hideKeyboard()
        terminal?.scrollToBottom()
        session.executeHistoryCommand(command)
    }, onDismiss = { commonCommandsOpen = false })
    confirmPaste?.let { text -> AlertDialog(onDismissRequest = { confirmPaste = null }, title = { Text(tr("发送多行文本？")) }, text = { Text(tr("远端尚未启用 bracketed paste。文本中的换行可能立即执行命令，请确认当前程序能安全接收多行输入。")) }, confirmButton = { TextButton(onClick = { sendPaste(text); confirmPaste = null }, enabled = state.phase == Phase.CONNECTED) { Text(tr("发送")) } }, dismissButton = { TextButton(onClick = { confirmPaste = null }) { Text(tr("取消")) } }) }
    if (closeConfirm) AlertDialog(onDismissRequest = { closeConfirm = false }, title = { Text(tr("关闭此连接？")) }, text = { Text(if (session.mode == SessionMode.TMUX) tr("远端 tmux 会话会继续运行，会话卡片保留，可稍后重连。") else tr("普通 SSH shell 会结束；需要保留的任务请先放入 tmux。")) }, confirmButton = { TextButton(onClick = { closeConfirm = false; onClose() }) { Text(tr("关闭连接")) } }, dismissButton = { TextButton(onClick = { closeConfirm = false }) { Text(tr("取消")) } })
}
