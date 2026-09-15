package io.itermux.app.ui

import android.content.*
import android.content.res.Configuration
import android.widget.Toast
import android.view.*
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalSession
import com.termux.view.*
import io.itermux.app.session.*
import io.itermux.app.R
import io.itermux.ssh.*

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
    var draft by remember { mutableStateOf(session.draft) }
    var ctrl by remember { mutableStateOf(false) }
    var terminal by remember { mutableStateOf<TerminalView?>(null) }
    var confirmPaste by remember { mutableStateOf<String?>(null) }
    var closeConfirm by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sessionMenu by remember { mutableStateOf(false) }
    var keysOpen by rememberSaveable { mutableStateOf(false) }
    var dockHidden by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val editorFocus = remember { FocusRequester() }
    var requestEditorKeyboard by remember { mutableStateOf(false) }
    fun hideKeyboard() {
        // Use the still-attached terminal token before disposing the focused editor (API 26).
        terminal?.let { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0) }
        keyboard?.hide()
        focus.clearFocus(force = true)
    }
    fun paste(text: String) {
        if ((text.contains('\n') || text.contains('\r')) && !session.getEmulator().isBracketedPasteEnabled) confirmPaste = text
        else session.paste(text)
    }
    fun showClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let {
            // Stage clipboard content in the editor; reading clipboard requires a user gesture.
            draft = it.take(128 * 1024); session.draft = draft; editor = true; hideKeyboard()
        }
    }
    DisposableEffect(session) {
        session.copyToClipboard = { text -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("终端选区", text)) }
        session.pasteFromClipboard = { showClipboard() }
        onDispose {
            session.onScreenChanged = null; session.copyToClipboard = null; session.pasteFromClipboard = null
            session.ctrl = false
        }
    }
    LaunchedEffect(fontSize) { prefs.edit().putInt("fontSize", fontSize).apply() }
    LaunchedEffect(session.id) { hideKeyboard() }
    LaunchedEffect(editor, requestEditorKeyboard) {
        if (editor && requestEditorKeyboard) {
            editorFocus.requestFocus()
            keyboard?.show()
            requestEditorKeyboard = false
        }
    }
    BackHandler(editor || keysOpen || dockHidden) { editor = false; keysOpen = false; dockHidden = false; hideKeyboard() }
    val dock: @Composable () -> Unit = {
        TerminalDock(landscape, sessionControl = { sessionModifier ->
            Box(sessionModifier) {
                TextButton(onClick = { hideKeyboard(); sessionMenu = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "切换会话：${session.host.name}" }.testTag("session-switcher"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("●", color = if (state.phase == Phase.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontSize = 9.sp)
                    Spacer(Modifier.width(6.dp))
                    if (landscape) Text("▤", fontSize = 18.sp)
                    else {
                        Text(session.host.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        Text(" ▴", fontSize = 10.sp)
                    }
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
            val onVoice = {
                editor = true; keysOpen = false; requestEditorKeyboard = true
                Toast.makeText(context, "请点击输入法中的麦克风进行语音输入。", Toast.LENGTH_LONG).show()
            }
            if (landscape) {
                FilledTonalIconButton(onClick = onVoice, enabled = state.phase == Phase.CONNECTED) {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = "语音", modifier = Modifier.size(20.dp))
                }
            } else FilledTonalButton(onClick = onVoice, enabled = state.phase == Phase.CONNECTED, modifier = Modifier.height(40.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("语音")
            }
            IconButton(onClick = {
                if (imeVisible) hideKeyboard()
                else { editor = false; terminal?.let { view -> view.requestFocus(); (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) } }
            }) { Icon(painterResource(R.drawable.ic_keyboard), contentDescription = if (imeVisible) "收起键盘" else "键盘", modifier = Modifier.size(22.dp)) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "终端菜单" }.testTag("terminal-menu")) { Text("⋯", fontSize = 26.sp) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (editor) "收起编辑" else "编辑文本") }, onClick = { menu = false; editor = !editor; hideKeyboard() })
                    DropdownMenuItem(text = { Text("隐藏操作栏") }, onClick = { menu = false; dockHidden = true; editor = false; keysOpen = false; hideKeyboard() })
                    DropdownMenuItem(text = { Text("粘贴") }, onClick = { menu = false; showClipboard() })
                    DropdownMenuItem(text = { Text(if (keysOpen) "收起快捷键" else "快捷键") }, onClick = { menu = false; keysOpen = !keysOpen })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("增大字号") }, onClick = { fontSize = (fontSize + 1).coerceAtMost(28) })
                    DropdownMenuItem(text = { Text("减小字号") }, onClick = { fontSize = (fontSize - 1).coerceAtLeast(8) })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("关闭连接") }, onClick = { menu = false; if (state.active) closeConfirm = true else onClose() })
                }
            }
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
                        setTerminalViewClient(MobileTerminalClient(this, session, onScale = { factor -> fontSize = (fontSize * factor).toInt().coerceIn(8, 28) }, onCtrlUsed = { ctrl = false; session.ctrl = false }))
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
                if (!dockHidden && !editor && (keysOpen || imeVisible)) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val keys = listOf("Esc" to KeyEvent.KEYCODE_ESCAPE, "Tab" to KeyEvent.KEYCODE_TAB, "↑" to KeyEvent.KEYCODE_DPAD_UP, "↓" to KeyEvent.KEYCODE_DPAD_DOWN, "←" to KeyEvent.KEYCODE_DPAD_LEFT, "→" to KeyEvent.KEYCODE_DPAD_RIGHT)
                    FilterChip(ctrl, { ctrl = !ctrl; session.ctrl = ctrl; terminal?.requestFocus() }, label = { Text("Ctrl") })
                    TextButton(onClick = { session.write("\u0003") }, enabled = state.phase == Phase.CONNECTED) { Text("^C") }
                    keys.forEach { (label, code) -> TextButton(onClick = { terminal?.onKeyDown(code, KeyEvent(KeyEvent.ACTION_DOWN, code)); terminal?.onKeyUp(code, KeyEvent(KeyEvent.ACTION_UP, code)) }, enabled = state.phase == Phase.CONNECTED) { Text(label) } }
                    TextButton(onClick = { session.write("\r") }, enabled = state.phase == Phase.CONNECTED) { Text("回车") }
                }
                if (editor && landscape) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(draft, { draft = it.take(128 * 1024); session.draft = draft },
                            placeholder = { Text("输入或语音识别后，在这里确认", maxLines = 1) },
                            minLines = 1, maxLines = if (imeVisible) 2 else 3,
                            modifier = Modifier.weight(1f).focusRequester(editorFocus).testTag("prompt-editor"))
                        TextButton(onClick = { editor = false; hideKeyboard() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("收起") }
                        TextButton(onClick = { draft = ""; session.draft = "" }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("清空") }
                        TextButton(onClick = { session.write("\r") }, enabled = state.phase == Phase.CONNECTED, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("回车 ↵") }
                        Button(onClick = { paste(draft) }, enabled = draft.isNotEmpty() && state.phase == Phase.CONNECTED, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("发送文本") }
                    }
                } else if (editor) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        OutlinedTextField(draft, { draft = it.take(128 * 1024); session.draft = draft }, placeholder = { Text("输入或语音识别后，在这里确认") }, minLines = 1, maxLines = if (imeVisible) 2 else 4, modifier = Modifier.fillMaxWidth().focusRequester(editorFocus).testTag("prompt-editor"))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { editor = false; hideKeyboard() }) { Text("收起") }
                            TextButton(onClick = { draft = ""; session.draft = "" }) { Text("清空") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { session.write("\r") }, enabled = state.phase == Phase.CONNECTED) { Text("回车 ↵") }
                            Button(onClick = { paste(draft) }, enabled = draft.isNotEmpty() && state.phase == Phase.CONNECTED) { Text("发送文本") }
                        }
                    }
                }
                if (!landscape && !dockHidden) dock()
            }
            if (landscape && !dockHidden) dock()
        }
        if (dockHidden) {
            FilledTonalIconButton(
                onClick = { dockHidden = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(48.dp).testTag("restore-dock"),
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
    landscape: Boolean,
    sessionControl: @Composable (Modifier) -> Unit,
    controls: @Composable () -> Unit
) {
    if (landscape) {
        // A side rail preserves terminal rows; scrolling keeps controls reachable in a short IME window.
        Column(Modifier.width(48.dp).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("terminal-dock"),
            verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
            sessionControl(Modifier.size(48.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) { controls() }
        }
    } else {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp).testTag("terminal-dock"), verticalAlignment = Alignment.CenterVertically) {
            sessionControl(Modifier.weight(1f))
            controls()
        }
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

private class MobileTerminalClient(private val view: TerminalView, private val session: TerminalConnection, private val onScale: (Float) -> Unit, private val onCtrlUsed: () -> Unit) : TerminalViewClient {
    override fun onScale(scale: Float): Float { if (scale < 0.9f || scale > 1.1f) onScale(scale); return 1f }
    override fun onSingleTapUp(e: MotionEvent?) { view.requestFocus() }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = view.hasFocus()
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean { if (session.ctrl && keyCode !in listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT)) onCtrlUsed(); return false }
    override fun onLongPress(event: MotionEvent?) = false
    override fun readControlKey(): Boolean { val held = session.ctrl; if (held) onCtrlUsed(); return held }
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
