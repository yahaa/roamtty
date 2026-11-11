package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.app.AppLocale
import io.itermux.app.AppLanguage
import io.itermux.app.BuildConfig
import io.itermux.code.MarkdownParser

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable internal fun CredentialsDialog(request: ConnectRequest, dismiss: () -> Unit, connect: (String, String) -> Unit) {
    var secret by remember(request) { mutableStateOf("") }
    var visible by remember(request) { mutableStateOf(false) }
    val key = request.host.auth == "KEY"
    AlertDialog(onDismissRequest = dismiss, title = { Text(trf("连接 %s", request.host.name)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (key) tr("私钥口令仅用于本次连接；未加密私钥可留空。") else tr("密码仅用于本次连接，默认不保存。"))
            OutlinedTextField(secret, { secret = it }, label = { Text(if (key) tr("私钥口令（可留空）") else tr("SSH 密码")) }, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.testTag("connection-secret"), trailingIcon = {
                    TextButton(onClick = { visible = !visible }, Modifier.testTag("connection-secret-visibility")) {
                        Text(tr(if (visible) "隐藏密码" else "显示密码"))
                    }
                })
        }
    }, confirmButton = { TextButton(onClick = { if (key) connect("", secret) else connect(secret, ""); secret = "" }) { Text(tr("连接")) } }, dismissButton = { TextButton(onClick = dismiss) { Text(tr("取消")) } })
}

private enum class AboutPage { GUIDE, PRIVACY, LICENSES }

@Composable internal fun AboutDialog(context: Context, dismiss: () -> Unit) {
    var page by remember { mutableStateOf(AboutPage.GUIDE) }
    val licenseText = remember { runCatching { context.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() } }.getOrDefault(tr("许可文件请参阅源码中的 THIRD_PARTY_NOTICES.md。")) }
    val privacyPolicy = remember {
        val markdown = runCatching { context.assets.open("PRIVACY.md").bufferedReader().use { it.readText() } }
            .getOrDefault(tr("隐私政策请参阅源码中的 docs/PRIVACY.md。"))
        MarkdownParser.parse(privacyPolicySection(markdown, AppLocale.language == AppLanguage.ENGLISH))
    }
    val title = when (page) {
        AboutPage.GUIDE -> "Roamtty"
        AboutPage.PRIVACY -> tr("隐私政策")
        AboutPage.LICENSES -> tr("第三方许可")
    }
    // Landscape, where height is short: a wider dialog with the guide as two columns, so more of it is in view.
    val landscape = isLandscape()
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        modifier = if (landscape) Modifier.fillMaxWidth(0.86f) else Modifier,
        properties = DialogProperties(usePlatformDefaultWidth = !landscape), text = {
        // The policy scrolls inside its own list, so only the plain-text pages scroll the column.
        val scroll = if (page == AboutPage.PRIVACY) Modifier else Modifier.verticalScroll(rememberScrollState())
        Column(scroll, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (page) {
                AboutPage.GUIDE -> {
                    Text(trf("版本 %s", BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val sections = guideSections()
                    val section: @Composable (Pair<String, String>) -> Unit = { (heading, body) ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(heading, style = MaterialTheme.typography.titleSmall)
                            Text(body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    val half = (sections.size + 1) / 2
                    if (landscape) LandscapeColumns(spacing = 14.dp, left = { sections.take(half).forEach { section(it) } },
                        right = { sections.drop(half).forEach { section(it) } })
                    else sections.forEach { section(it) }
                }
                AboutPage.PRIVACY -> MarkdownView(privacyPolicy, null, Modifier.weight(1f, fill = false).fillMaxWidth(),
                    background = AlertDialogDefaults.containerColor, contentPadding = PaddingValues(0.dp))
                AboutPage.LICENSES -> Text(licenseText)
            }
            if (page == AboutPage.GUIDE) Column {
                TextButton(onClick = { page = AboutPage.PRIVACY }) { Text(tr("隐私政策")) }
                TextButton(onClick = { page = AboutPage.LICENSES }) { Text(tr("查看第三方许可")) }
            } else TextButton(onClick = { page = AboutPage.GUIDE }) { Text(tr("返回指南")) }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text(tr("关闭")) } })
}

/** docs/PRIVACY.md holds the Chinese policy, then the English one, each starting at its own `# ` heading. */
internal fun privacyPolicySection(markdown: String, english: Boolean): String {
    val starts = Regex("^# ", RegexOption.MULTILINE).findAll(markdown).map { it.range.first }.toList()
    if (starts.size < 2) return markdown
    return (if (english) markdown.substring(starts[1]) else markdown.substring(starts[0], starts[1])).trim()
}

/** Help sections as (title, body); each body line is one tip. */
private fun guideSections(): List<Pair<String, String>> = if (AppLocale.language == AppLanguage.ENGLISH) listOf(
    "Hosts" to "• Tap + on the Hosts page to add a host. Sign in with a password, keyboard-interactive login or an Ed25519/RSA private key in OpenSSH or PEM format (encrypted keys work). A password is saved, encrypted with the Android Keystore, only when you tick the option; key passphrases are never saved.\n" +
        "• On the first connection, check the SHA-256 fingerprint before trusting the host. If it later changes, the connection is blocked until you re-verify it.\n" +
        "• Host cards show the system icon, online status and DNS + TCP connection latency (from starting DNS resolution until TCP connects, excluding SSH startup). Tap a card for its details: live CPU, memory, disk, network, GPU and process monitoring. The ⋮ menu offers AI agent, Open terminal, Browse files, Install plugins, Edit and Delete.",
    "Sessions and AI agents" to "• AI agent starts Claude Code, Codex or Grok in a folder you pick. Claude's Plan mode analyses first and starts changing files after you approve the plan. Codex and Grok start Read-only with a sandbox; Grok can still write its state in ~/.grok/ and temporary directories. You can change permissions in the agent. Normal (asks before editing files or running commands) and YOLO (skips confirmations) modes are also available, and your last choice is remembered. Open terminal can also start in a chosen folder.\n" +
        "• Install plugins detects and installs tmux and mosh. With tmux, each new session gets its own remote tmux session that keeps running after a disconnect or closing the app; reconnecting reattaches it. With mosh, sessions use UDP (allow ports 60000–61000) and fall back to SSH if it does not answer.\n" +
        "• The session list on the home page shows every session (up to 8 per host), titled by the agent's task or its folder, with what the agent is doing; the side menu lists them too. A dropped tmux session keeps its card and reconnects. Close in a card's menu ends the remote tmux session and its tasks, logging in again first if the session is not connected. Rename in the same menu (long-press a card) gives the card a name of your own; leave it empty to go back to the automatic one.\n" +
        "• When an agent needs you or finishes a turn, a notification says so; tap it to open that session. To stay connected while the screen is locked, allow background running in Settings.",
    "Terminal gestures" to "• Tap the cursor's line (or within two lines of it) to show the keyboard; taps elsewhere leave it hidden. Double-tap sends Tab (completion) and brings ↑ above the pet, ↓ below it and ← to its left for picking a completion; triple-tap pastes the clipboard, pinch changes the font size.\n" +
        "• Swipe left or right to send Left or Right (arm Shift first for Shift+Left, which answers Codex questions). Swipe left or right and keep your finger down to jump to the start or end of the line.\n" +
        "• Hold the little ghost to open a ring of shortcuts for the program in front (Claude Code, Codex, Grok, Gemini, or keyboard keys in a plain shell); drag onto one and let go to send it. In a shell, rest on Fn to open F1–F12.\n" +
        "• Hold the command line to show a magnifier and drag the cursor, even across lines; hold other output to select and copy it.\n" +
        "• Holding the keyboard's delete key for about a second clears the line; quick taps delete one character each. Clearing needs Ctrl+U/Ctrl+K on the remote side.\n" +
        "• After Enter, the keyboard hides by itself if you type nothing for 15 seconds.\n" +
        "• When you stop typing, an Enter key appears at the end of the cursor row; otherwise an Esc key sits there. On a line starting with / (slash commands), ↑/↓/← keys also appear around the pet.",
    "Toolbox" to "• The controls start hidden. Tap the ghost with the wrench on the right edge to open them; it fades after a few seconds and returns when you touch the screen.\n" +
        "• Toolbox buttons: Upload file, Download file, Insert image (up to 9 photos, their remote paths are typed in), Common commands (from the remote shell history) and the session menu (back to the host, close or restart the session, switch sessions). Shortcut keys live on the little ghost (hold it).\n" +
        "• The settings button has Edit text, Pin/Hide toolbar, Paste and font size. Edited text stays a draft until you send it; Send text and Enter are separate.\n" +
        "• Terminal pages are full screen; swipe from an edge to reveal the system bars for a moment. Swipe up and down on the terminal to read earlier output (tmux sessions scroll tmux's own history); back at the bottom it follows new output again.",
    "Files" to "• Browse files lists the remote folders over SFTP. Preview text, highlighted code, Markdown and images; double-tap a preview (single-tap an image) for full screen. Rename, delete, create folders, copy paths, and download one or many files.\n" +
        "• Its menus can open a terminal or start an AI agent in the current folder.\n" +
        "• Share images from other apps to Roamtty to upload them to a session.",
) else listOf(
    "主机" to "• 在主机页点“+”添加主机。可用密码、交互式认证，或 OpenSSH/PEM 格式的 Ed25519/RSA 私钥（支持加密私钥）。密码仅在勾选后用系统 Keystore 加密保存，私钥口令不保存。\n" +
        "• 首次连接先核对 SHA-256 指纹再信任；指纹之后若变化会直接阻止连接，需确认后重新核验。\n" +
        "• 主机卡片显示系统图标、在线状态与 DNS + TCP 建连延时（从开始 DNS 解析到 TCP 连接成功，不含 SSH 启动等待）。点卡片进入详情页，实时查看 CPU、内存、磁盘、网络、GPU 与进程。右侧 ⋮ 菜单可选 AI Agent、打开终端、查看文件、安装插件、编辑或删除主机。",
    "会话与 AI Agent" to "• “AI Agent”在选定文件夹启动 Claude Code、Codex 或 Grok。Claude 的“计划”模式先分析，批准计划后开始修改；Codex 与 Grok 的“只读”使用沙箱启动，Grok 的 ~/.grok/ 状态目录和临时目录仍可写。你可在 Agent 中调整权限，也可选择普通（改文件或运行命令前先问你）或 YOLO（跳过确认）模式，应用会记住上次选择。“打开终端”同样可以先选起始文件夹。\n" +
        "• “安装插件”检测并安装 tmux 与 mosh。装有 tmux 时每个新会话有独立的远端 tmux 会话，断网或关闭 App 后任务继续运行，重连即附着原会话；装有 mosh 时优先走 UDP（需放行 60000–61000），无回应自动回退 SSH。\n" +
        "• 首页会话列表列出全部会话（每台主机最多 8 个），以 Agent 的任务或文件夹为标题并显示 Agent 的状态，侧边栏同样列出。断线的 tmux 会话保留卡片并可重连；卡片菜单中的“关闭”会结束远端 tmux 会话及其中任务，未连接时先重新登录再结束。长按卡片选“重命名”可自定义卡片名称，留空则恢复自动名称。\n" +
        "• Agent 等你确认或做完一轮时会发通知，点通知直接进入该会话。锁屏后要保持连接，请在设置中允许后台运行。",
    "终端手势" to "• 点击光标所在行（上下各 2 行内都算）弹出键盘，点击其他位置不弹出；双击发送 Tab（补全），同时小精灵上方出现 ↑ 键、下方出现 ↓ 键、左边出现 ← 键用来选择补全项，三击粘贴剪贴板，双指缩放调整字号。\n" +
        "• 左右滑动发送 ← / →（先点亮 Shift 再左滑即发送 Shift+←，用于回答 Codex 的提问）；左右滑动后按住不放，可跳到行首或行尾。\n" +
        "• 长按小精灵，周围弹出当前程序的快捷键（Claude Code、Codex、Grok、Gemini；普通终端为 Ctrl、Shift、Fn 等按键），拖到某个键上松手即发送；在终端里停在 Fn 上会展开 F1–F12。\n" +
        "• 长按当前命令行显示放大镜，可左右或跨行拖动光标；长按其他输出可选择复制。\n" +
        "• 按住输入法删除键约 1 秒清空当前行，快速点按只逐字删除；清空需远端支持 Ctrl+U/Ctrl+K。\n" +
        "• 按回车后 15 秒内没有输入，键盘自动收起。\n" +
        "• 停止输入后，光标行末尾出现回车键，其余时间显示 Esc 键。输入以 / 开头的斜杠命令时，小精灵上下和左边也会出现 ↑/↓/← 键。",
    "操作栏" to "• 操作栏默认隐藏，点击右侧拿着扳手的小幽灵展开；小幽灵闲置几秒后淡出，触摸屏幕即重新出现。\n" +
        "• 操作栏按钮：上传文件、下载文件、插入图片（最多 9 张，自动填入远端路径）、常用命令（来自远端 shell 历史）以及会话菜单（返回主机详情、关闭或重启当前会话、切换会话）。快捷键在小精灵上（长按它）。\n" +
        "• 设置按钮中有编辑文本、固定/隐藏操作栏、粘贴与字号。编辑框中的文字先留作草稿，确认后才发送；发送文本与回车分开。\n" +
        "• 终端页全屏显示，边缘滑动可临时显示系统栏。上下滑动终端回看历史（tmux 会话滚动 tmux 自身的历史），回到底部继续跟随输出。",
    "文件" to "• “查看文件”通过 SFTP 浏览远端目录，可预览文本、代码高亮、Markdown 和图片；双击预览（图片为单击）切换全屏。支持重命名、删除、新建文件夹、复制路径以及单个或批量下载。\n" +
        "• 文件浏览菜单可在当前文件夹打开终端或启动 AI Agent。\n" +
        "• 在其他 App 中把图片分享给 Roamtty，即可上传到某个会话。",
)
