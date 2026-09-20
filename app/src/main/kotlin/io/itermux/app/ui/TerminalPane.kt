package io.itermux.app.ui

import android.content.*
import android.content.res.Configuration
import android.view.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.SoftwareKeyboardControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.terminal.TerminalSession
import com.termux.view.*
import io.itermux.app.session.*
import io.itermux.app.R
import io.itermux.ssh.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalPane(session: TerminalConnection, modifier: Modifier, sessions: List<TerminalConnection>, onSelectSession: (String) -> Unit, onHosts: () -> Unit, onReconnect: () -> Unit, onShell: () -> Unit, onReverify: () -> Unit, onClose: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val pending by session.pending.collectAsStateWithLifecycle()
    val warning by session.inputWarning.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val prefs = remember { context.getSharedPreferences("terminal-settings", Context.MODE_PRIVATE) }
    var fontSize by rememberSaveable { mutableIntStateOf(prefs.getInt("fontSize", 14)) }
    var editor by rememberSaveable { mutableStateOf(false) }
    var draft by remember { mutableStateOf(TextFieldValue(session.draft, TextRange(session.draft.length))) }
    var terminal by remember { mutableStateOf<TerminalView?>(null) }
    var confirmPaste by remember { mutableStateOf<String?>(null) }
    var closeConfirm by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sessionMenu by remember { mutableStateOf(false) }
    var dockPinned by rememberSaveable { mutableStateOf(prefs.getBoolean("dockPinned", false)) }
    var dockExpanded by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val composeView = LocalView.current
    val keyboard = remember(composeView) { SoftwareKeyboardControllerCompat(composeView) }
    val imeVisible = WindowInsets.isImeVisible
    // Keep controls in the same place when the keyboard opens; only explicit pinning reserves space.
    val dockVisible = !editor && (dockPinned || dockExpanded)
    val dockReserved = dockVisible && dockPinned
    val floatingDock = dockVisible && !dockReserved
    fun updateDraft(value: TextFieldValue) {
        draft = if (value.text.length <= 128 * 1024) value else TextFieldValue(value.text.take(128 * 1024))
        session.draft = draft.text
    }
    fun terminalKey(keyCode: Int) {
        terminal?.sendInputControlKey(keyCode)
    }
    fun hideKeyboard() {
        // Clear Compose/native focus before hiding so focus transfer cannot reopen the IME.
        focus.clearFocus(force = true)
        terminal?.clearFocus()
        keyboard.hide()
    }
    fun paste(text: String) {
        if ((text.contains('\n') || text.contains('\r')) && !session.getEmulator().isBracketedPasteEnabled) confirmPaste = text
        else { terminal?.scrollToBottom(); session.paste(text) }
    }
    fun showClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let {
            // Stage clipboard content in the editor; reading clipboard requires a user gesture.
            val text = it.take(128 * 1024)
            updateDraft(TextFieldValue(text, TextRange(text.length))); editor = true; hideKeyboard()
        }
    }
    DisposableEffect(session) {
        session.copyToClipboard = { text -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("终端选区", text)) }
        session.pasteFromClipboard = { showClipboard() }
        onDispose {
            session.onScreenChanged = null; session.copyToClipboard = null; session.pasteFromClipboard = null
        }
    }
    LaunchedEffect(fontSize) { prefs.edit().putInt("fontSize", fontSize).apply() }
    LaunchedEffect(session.id) { hideKeyboard() }
    BackHandler(editor || imeVisible) { editor = false; hideKeyboard() }
    val dock: @Composable () -> Unit = {
        TerminalDock(floatingDock, sessionControl = { sessionModifier ->
            Box(sessionModifier) {
                TextButton(onClick = { hideKeyboard(); sessionMenu = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "切换会话：${session.host.name}" }.testTag("session-switcher"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("●", color = if (state.phase == Phase.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontSize = 9.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("▤", fontSize = 18.sp)
                }
                DropdownMenu(sessionMenu, { sessionMenu = false }) {
                    DropdownMenuItem(text = { Text("返回主机列表") }, onClick = { sessionMenu = false; onHosts() })
                    HorizontalDivider()
                    sessions.forEach { other ->
                        val otherState by other.state.collectAsStateWithLifecycle()
                        DropdownMenuItem(text = { Text("${if (other.id == session.id) "✓ " else ""}${other.host.name} · ${phaseLabel(otherState.phase)}") }, onClick = { sessionMenu = false; onSelectSession(other.id) })
                    }
                }
            }
        }, controls = {
            IconButton(onClick = {
                // Query native window state at click time, including IMEs with no bottom inset.
                val visible = ViewCompat.getRootWindowInsets(composeView)?.isVisible(WindowInsetsCompat.Type.ime()) ?: imeVisible
                if (visible) hideKeyboard()
                else terminal?.let { view ->
                    view.requestFocus()
                    SoftwareKeyboardControllerCompat(view).show()
                }
            }, modifier = Modifier.testTag("keyboard-toggle")) { Icon(painterResource(R.drawable.ic_keyboard), contentDescription = if (imeVisible) "收起键盘" else "键盘", modifier = Modifier.size(22.dp)) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "终端菜单" }.testTag("terminal-menu")) { Text("⋯", fontSize = 26.sp) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("编辑文本") }, onClick = { menu = false; editor = true; hideKeyboard() })
                    DropdownMenuItem(text = { Text(if (dockPinned) "取消固定操作栏" else "固定操作栏") }, onClick = {
                        menu = false; dockPinned = !dockPinned; dockExpanded = true
                        prefs.edit().putBoolean("dockPinned", dockPinned).apply()
                    })
                    DropdownMenuItem(text = { Text("隐藏操作栏") }, onClick = {
                        menu = false; dockPinned = false; dockExpanded = false; editor = false; hideKeyboard()
                        prefs.edit().putBoolean("dockPinned", false).apply()
                    })
                    DropdownMenuItem(text = { Text("粘贴") }, onClick = { menu = false; showClipboard() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("增大字号") }, onClick = { fontSize = (fontSize + 1).coerceAtMost(28) })
                    DropdownMenuItem(text = { Text("减小字号") }, onClick = { fontSize = (fontSize - 1).coerceAtLeast(8) })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("关闭连接") }, onClick = { menu = false; if (state.active) closeConfirm = true else onClose() })
                }
            }
            InputControlButton("←", "光标左移", "terminal-left", enabled = state.phase == Phase.CONNECTED,
                onClick = { terminalKey(KeyEvent.KEYCODE_DPAD_LEFT) })
            InputControlButton("→", "光标右移", "terminal-right", enabled = state.phase == Phase.CONNECTED,
                onClick = { terminalKey(KeyEvent.KEYCODE_DPAD_RIGHT) })
            InputControlButton("⌫", "删除字符，长按清空当前输入", "terminal-delete", enabled = state.phase == Phase.CONNECTED,
                onLongClick = { terminal?.clearCurrentInput() }, onClick = { terminalKey(KeyEvent.KEYCODE_DEL) })
        })
    }
    Box(modifier.fillMaxWidth().testTag("terminal-workspace")) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (state.phase != Phase.CONNECTED) {
                    Column(Modifier.fillMaxWidth().heightIn(max = if (landscape) 120.dp else 224.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(state.message.ifBlank { when (state.phase) { Phase.VERIFYING -> "等待核对主机指纹"; Phase.AUTHENTICATING -> "正在进行 SSH 认证"; else -> "正在连接 ${session.host.address}…" } }, fontSize = 13.sp)
                        if (state.phase == Phase.HOST_KEY_CHANGED) {
                            SelectionContainer { Text("原指纹：${state.oldFingerprint}\n新指纹：${state.newFingerprint}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                            TextButton(onClick = onReverify) { Text("重新核验…") }
                        } else if (state.active) TextButton(onClick = { session.disconnect() }) { Text("取消连接") }
                        else Row {
                            TextButton(onClick = onReconnect) { Text("手动重连") }
                            if (state.phase == Phase.TMUX_MISSING) TextButton(onClick = onShell) { Text("改用普通 SSH") }
                        }
                    }
                }
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color.Black).testTag("terminal"), factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        terminal = this
                        setTextSize((fontSize * ctx.resources.displayMetrics.scaledDensity).toInt())
                        setTerminalViewClient(MobileTerminalClient(this, onScale = { factor -> fontSize = (fontSize * factor).toInt().coerceIn(8, 28) }))
                        attachSession(session)
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        session.onScreenChanged = { onScreenUpdated() }
                        isFocusableInTouchMode = true
                    }
                }, update = { view -> view.setTextSize((fontSize * view.resources.displayMetrics.scaledDensity).toInt()) })
                warning?.let { message -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { session.inputWarning.value = null }) { Text("知道了") }
                } }
                if (editor && landscape) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(draft, ::updateDraft,
                            placeholder = { Text("输入文本后，在这里确认", maxLines = 1) },
                            minLines = 1, maxLines = if (imeVisible) 2 else 3,
                            modifier = Modifier.weight(1f).testTag("prompt-editor"))
                        DraftInputControls(draft, ::updateDraft)
                        TextButton(onClick = { editor = false; hideKeyboard() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("收起") }
                        TextButton(onClick = { session.write("\r") }, enabled = state.phase == Phase.CONNECTED, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("回车 ↵") }
                        Button(onClick = { paste(draft.text) }, enabled = draft.text.isNotEmpty() && state.phase == Phase.CONNECTED, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("发送文本") }
                    }
                } else if (editor) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                        OutlinedTextField(draft, ::updateDraft, placeholder = { Text("输入文本后，在这里确认") }, minLines = 1, maxLines = if (imeVisible) 2 else 4, modifier = Modifier.fillMaxWidth().testTag("prompt-editor"))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            DraftInputControls(draft, ::updateDraft)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { editor = false; hideKeyboard() }, modifier = Modifier.width(48.dp), contentPadding = PaddingValues(4.dp)) { Text("收起") }
                            TextButton(onClick = { session.write("\r") }, enabled = state.phase == Phase.CONNECTED, modifier = Modifier.width(48.dp).semantics { contentDescription = "回车" }, contentPadding = PaddingValues(4.dp)) { Text("↵", fontSize = 22.sp) }
                            Button(onClick = { paste(draft.text) }, enabled = draft.text.isNotEmpty() && state.phase == Phase.CONNECTED, modifier = Modifier.width(88.dp), contentPadding = PaddingValues(8.dp)) { Text("发送文本") }
                        }
                    }
                }
            }
            if (dockReserved) dock()
        }
        if (floatingDock) {
            // Offset (not end-padding) keeps the dock 24dp clear of the scrollbar hit strip
            // without an invisible padded hit target covering that strip.
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).displayCutoutPadding().padding(4.dp).offset(x = (-20).dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
                shadowElevation = 4.dp
            ) { dock() }
        } else if (!dockVisible && !editor) {
            FilledTonalIconButton(
                onClick = { dockExpanded = true },
                modifier = Modifier.align(Alignment.TopEnd).displayCutoutPadding().padding(4.dp).offset(x = (-20).dp).size(48.dp).testTag("restore-dock"),
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
            ) { Text("⋯", fontSize = 26.sp, modifier = Modifier.semantics { contentDescription = "显示操作栏" }) }
        }
    }
    pending?.let { request -> PromptDialog(request) }
    confirmPaste?.let { text -> AlertDialog(onDismissRequest = { confirmPaste = null }, title = { Text("发送多行文本？") }, text = { Text("远端尚未启用 bracketed paste。文本中的换行可能立即执行命令，请确认当前程序能安全接收多行输入。") }, confirmButton = { TextButton(onClick = { session.paste(text); confirmPaste = null }) { Text("发送") } }, dismissButton = { TextButton(onClick = { confirmPaste = null }) { Text("取消") } }) }
    if (closeConfirm) AlertDialog(onDismissRequest = { closeConfirm = false }, title = { Text("关闭此连接？") }, text = { Text(if (session.mode == SessionMode.TMUX) "远端 tmux 会话会继续运行，可稍后重连。" else "普通 SSH shell 会结束；需要保留的任务请先放入 tmux。") }, confirmButton = { TextButton(onClick = { closeConfirm = false; onClose() }) { Text("关闭连接") } }, dismissButton = { TextButton(onClick = { closeConfirm = false }) { Text("取消") } })
}

@Composable private fun TerminalDock(
    floating: Boolean,
    sessionControl: @Composable (Modifier) -> Unit,
    controls: @Composable () -> Unit
) {
    // Match the terminal's vertical scrollbar in both orientations.
    Column(Modifier.width(48.dp).then(if (floating) Modifier else Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Vertical))).verticalScroll(rememberScrollState()).testTag("terminal-dock"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        sessionControl(Modifier.size(48.dp))
        controls()
    }
}

@Composable private fun PromptDialog(request: PendingPrompt) {
    val prompt = request.prompt
    var answers by remember(request) { mutableStateOf<List<String>>(if (prompt is Prompt.Keyboard) List(prompt.labels.size) { "" } else emptyList()) }
    AlertDialog(onDismissRequest = { request.answer.complete(null) }, title = { Text(if (prompt is Prompt.VerifyHost) "核对主机指纹" else "交互式认证") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (prompt) {
                is Prompt.VerifyHost -> {
                    Text("首次连接此地址。请通过可信渠道核对服务器的 SHA-256 指纹，再决定是否信任。")
                    SelectionContainer { Text("${prompt.pin.endpoint}\n${prompt.pin.algorithm}\n\n${prompt.pin.fingerprint}", fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                }
                is Prompt.Keyboard -> {
                    if (prompt.instruction.isNotBlank()) Text(prompt.instruction)
                    prompt.labels.forEachIndexed { i, label -> OutlinedTextField(answers[i], { value -> answers = answers.toMutableList().also { it[i] = value } }, label = { Text(label.ifBlank { "认证输入 ${i + 1}" }) }, visualTransformation = if (prompt.echo.getOrElse(i) { false }) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { request.answer.complete(if (prompt is Prompt.VerifyHost) listOf("trust") else answers) }) { Text(if (prompt is Prompt.VerifyHost) "信任并连接" else "提交") } }, dismissButton = { TextButton(onClick = { request.answer.complete(null) }) { Text("取消") } })
}

private class MobileTerminalClient(private val view: TerminalView, private val onScale: (Float) -> Unit) : TerminalViewClient {
    override fun onScale(scale: Float): Float { if (scale < 0.9f || scale > 1.1f) onScale(scale); return 1f }
    override fun onSingleTapUp(e: MotionEvent?) {
        view.requestFocus()
        SoftwareKeyboardControllerCompat(view).show()
    }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = view.hasFocus()
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false
    override fun readControlKey() = false
    override fun readAltKey() = false
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}
