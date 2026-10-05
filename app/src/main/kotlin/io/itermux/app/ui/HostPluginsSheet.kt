package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.ItermuxApp
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.session.PendingPrompt
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

/** Per-CLI outcome of installing or removing the AI reminder hooks; see [AgentNotify.cliResults]. */
internal data class NotifyReport(val removed: Boolean, val clis: Map<String, String>, val codexTrust: String?)

/** Host and the one-time secrets typed for it; the sheet's SSH login lives while the sheet is open. */
internal data class PluginTarget(val host: Host, val password: String = "", val passphrase: String = "")

/**
 * One authenticated SSH login used to probe and install plugins. Install requests run one at a
 * time; the probe result is saved to the host so the list and new sessions pick it up.
 */
internal class HostPluginsController(private val app: ItermuxApp, private val target: PluginTarget, private val scope: CoroutineScope) {
    val host get() = target.host
    val pending = MutableStateFlow<PendingPrompt?>(null)
    val connection = MutableStateFlow(ConnectionState(Phase.CONNECTING))
    /** null while probing, or when the host cannot be probed (e.g. Windows cmd or PowerShell). */
    val plugins = MutableStateFlow<Set<HostPlugin>?>(null)
    /** Picks the install hints shown for this host. */
    val system = MutableStateFlow<HostSystem?>(null)
    val probed = MutableStateFlow(false)
    val installing = MutableStateFlow<HostPlugin?>(null)
    val log = MutableStateFlow<List<String>>(emptyList())
    val result = MutableStateFlow<Pair<HostPlugin, HostPluginInstaller.Result>?>(null)
    /** What the last AI reminder install or removal did for each CLI. */
    val notifyReport = MutableStateFlow<NotifyReport?>(null)
    private var requests = Channel<Request>(Channel.CONFLATED)
    private var session: JschSession? = null
    private var job: Job? = null
    /** A saved or typed login password doubles as the sudo password. */
    private var loginPassword: String? = null

    fun start() {
        job?.cancel()
        session?.disconnect()
        requests.close()
        requests = Channel(Channel.CONFLATED)
        val queue = requests
        probed.value = false
        connection.value = ConnectionState(Phase.CONNECTING)
        job = scope.launch {
            var credentials: Credentials? = null
            try {
                credentials = withContext(Dispatchers.IO) {
                    Credentials(
                        password = if (host.auth == AuthMethod.PASSWORD.name) host.encryptedPassword?.let { app.vault.decrypt(it, "${host.id}:password") } ?: target.password.toByteArray() else null,
                        privateKey = host.encryptedKey?.takeIf { host.auth == AuthMethod.KEY.name }?.let { app.vault.decrypt(it, "${host.id}:key") },
                        passphrase = target.passphrase.takeIf { it.isNotEmpty() }?.toByteArray())
                }
                loginPassword = credentials.password?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
                val ssh = JschSession(host.target(SessionMode.SHELL), credentials, app.pins, interaction = { prompt ->
                    val request = PendingPrompt(prompt)
                    withContext(Dispatchers.Main.immediate) { pending.value = request }
                    try { request.answer.await() }
                    finally { withContext(NonCancellable + Dispatchers.Main.immediate) { pending.compareAndSet(request, null) } }
                }, connectTimeoutMs = 15_000)
                session = ssh
                val state = launch { ssh.state.collect { connection.value = it } }
                ssh.administer { remote ->
                    system.value = ssh.detectedSystem.value
                    save(remote.probePlugins())
                    for (request in queue) install(remote, request)
                }
                state.cancel()
                connection.value = ssh.state.value
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                credentials?.clear()
                connection.value = ConnectionState(Phase.FAILED, "无法读取凭据，请重新导入密钥或保存密码。")
            }
        }
    }

    private suspend fun save(found: Set<HostPlugin>?) {
        plugins.value = found
        probed.value = true
        if (found != null) app.database.hosts().updatePlugins(host.id, host.address, host.port, host.username, HostPlugin.encode(found))
    }

    private suspend fun install(remote: RemoteHost, request: Request) {
        val plugin = request.plugin
        installing.value = plugin
        result.value = null
        notifyReport.value = null
        log.value = emptyList()
        val output = StringBuilder()
        val script = if (request.remove) AgentNotify.uninstallScript() else HostPluginInstaller.script(plugin, request.sudo ?: loginPassword)
        val status = remote.run(script, 15 * 60_000L) { line ->
            output.appendLine(line)
            HostPluginInstaller.displayLine(line)?.let { shown -> log.value = (log.value + shown).takeLast(300) }
        }
        result.value = plugin to HostPluginInstaller.classify(status, output.toString())
        if (plugin == HostPlugin.NOTIFY) notifyReport.value = NotifyReport(request.remove,
            AgentNotify.cliResults(output.toString()), AgentNotify.codexTrust(output.toString()))
        remote.probePlugins()?.let { save(it) }
        installing.value = null
    }

    fun install(plugin: HostPlugin, sudoPassword: String? = null) {
        if (installing.value != null) return
        requests.trySend(Request(plugin, sudoPassword?.takeIf { it.isNotEmpty() }))
    }

    /** Only the AI reminder plugin is removed by Roamtty; tmux and mosh belong to the system. */
    fun uninstall(plugin: HostPlugin) {
        if (installing.value != null || plugin != HostPlugin.NOTIFY) return
        requests.trySend(Request(plugin, null, remove = true))
    }

    private data class Request(val plugin: HostPlugin, val sudo: String?, val remove: Boolean = false)

    fun close() {
        requests.close()
        pending.value?.answer?.complete(null)
        session?.disconnect()
        job?.cancel()
        loginPassword = null
    }
}

@Composable
internal fun HostPluginsSheet(controller: HostPluginsController, onDismiss: () -> Unit) {
    val connection by controller.connection.collectAsStateWithLifecycle()
    val plugins by controller.plugins.collectAsStateWithLifecycle()
    val probed by controller.probed.collectAsStateWithLifecycle()
    val system by controller.system.collectAsStateWithLifecycle()
    val installing by controller.installing.collectAsStateWithLifecycle()
    val log by controller.log.collectAsStateWithLifecycle()
    val result by controller.result.collectAsStateWithLifecycle()
    val notifyReport by controller.notifyReport.collectAsStateWithLifecycle()
    val pending by controller.pending.collectAsStateWithLifecycle()
    var sudoPassword by remember { mutableStateOf("") }
    val host = controller.host
    val ready = connection.phase == Phase.CONNECTED && probed
    AppSheet(onDismiss = onDismiss) {
        SheetHeader(tr("安装插件"), "${host.name} · ${host.username}@${host.address}:${host.port}", onDismiss)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.Tight)
            .testTag("host-plugins-sheet"), verticalArrangement = Arrangement.spacedBy(Spacing.Section)) {
            when {
                connection.active && !ready -> Row(Modifier.padding(horizontal = Spacing.Page), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.Tight))
                    Text(if (connection.phase == Phase.CONNECTED) tr("正在检测已安装的插件…") else tr("正在连接主机…"),
                        style = MaterialTheme.typography.bodyMedium)
                }
                !connection.active -> Row(Modifier.padding(horizontal = Spacing.Page), verticalAlignment = Alignment.CenterVertically) {
                    Text(connection.message.takeIf { it.isNotBlank() }?.let { tr(it) } ?: phaseLabel(connection.phase),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = controller::start, modifier = Modifier.testTag("host-plugins-retry")) { Text(tr("重新连接")) }
                }
                probed && plugins == null -> Text(if (system == HostSystem.WINDOWS) tr("Windows 的 cmd 和 PowerShell 无法运行 tmux 与 mosh。请把 OpenSSH 的默认 shell 设为 MSYS2 或 Cygwin 的 bash 后重试，或在 WSL 中开启 SSH 并单独添加为主机。")
                    else tr("无法检测插件。目前支持 Linux、macOS、BSD，以及使用 MSYS2 或 Cygwin shell 的 Windows 主机。"),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Spacing.Page))
            }
            GroupedSection(header = tr("插件"),
                footer = trf("%s新建会话优先使用 mosh；装有 tmux 时在 tmux 中运行。", installHint(system))) {
                HostPlugin.entries.forEachIndexed { index, plugin ->
                    if (index > 0) GroupedDivider()
                    GroupedRow(plugin.id, Modifier.testTag("plugin-${plugin.id}"), subtitle = pluginDescription(plugin),
                        leading = {
                            Icon(painterResource(when (plugin) {
                                HostPlugin.TMUX -> R.drawable.ic_sessions
                                HostPlugin.MOSH -> R.drawable.ic_live
                                HostPlugin.NOTIFY -> R.drawable.ic_agent
                            }),
                                contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        },
                        trailing = {
                            when {
                                installing == plugin -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                plugins?.contains(plugin) == true && !plugin.packaged -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    MetaTag(tr("已安装"), Modifier.testTag("plugin-installed-${plugin.id}"))
                                    TextButton(onClick = { controller.uninstall(plugin) }, enabled = ready && installing == null,
                                        modifier = Modifier.testTag("plugin-uninstall-${plugin.id}")) { Text(tr("卸载")) }
                                }
                                plugins?.contains(plugin) == true -> MetaTag(tr("已安装"), Modifier.testTag("plugin-installed-${plugin.id}"))
                                ready && plugins != null -> FilledTonalButton(onClick = { controller.install(plugin, sudoPassword) },
                                    enabled = installing == null, modifier = Modifier.testTag("plugin-install-${plugin.id}")) { Text(tr("安装")) }
                                ready -> {}
                                else -> Text(tr("检测中…"), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        })
                }
            }
            result?.let { (plugin, outcome) ->
                val system = system
                val failed = outcome != HostPluginInstaller.Result.INSTALLED
                Text(installMessage(plugin, outcome, system, notifyReport?.removed == true), style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = Spacing.Page).testTag("plugin-result"))
                notifyReport?.takeIf { plugin == HostPlugin.NOTIFY }?.let { report ->
                    Column(Modifier.padding(horizontal = Spacing.Page).testTag("plugin-notify-report"),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        notifyReportLines(report).forEach { (line, warning) ->
                            Text(line, style = MaterialTheme.typography.bodySmall,
                                color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (outcome == HostPluginInstaller.Result.SUDO_PASSWORD) {
                    GroupedSection(header = tr("sudo 密码"), footer = tr("仅用于本次安装，不会保存。")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(sudoPassword, { sudoPassword = it }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), placeholder = { Text(tr("输入 sudo 密码")) },
                                modifier = Modifier.weight(1f).padding(Spacing.Tight).testTag("plugin-sudo-password"))
                            TextButton(onClick = { controller.install(plugin, sudoPassword) }, enabled = sudoPassword.isNotEmpty() && installing == null,
                                modifier = Modifier.padding(end = Spacing.Tight)) { Text(tr("重试")) }
                        }
                    }
                }
            }
            if (log.isNotEmpty()) GroupedSection(header = tr("安装输出")) {
                SelectionContainer {
                    // The newest lines matter while installing; earlier ones scroll out.
                    Text(log.takeLast(LOG_LINES).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)).padding(Spacing.Row)
                            .testTag("plugin-log"))
                }
            }
            Spacer(Modifier.height(Spacing.Page))
        }
    }
    pending?.let { PromptDialog(it) }
}

private fun pluginDescription(plugin: HostPlugin) = when (plugin) {
    HostPlugin.TMUX -> tr("会话保持：断网或关闭 App 后远端任务继续运行，重连即可恢复原会话。")
    HostPlugin.MOSH -> tr("移动网络优化：基于 UDP，切换网络或锁屏后自动恢复。需放行 UDP 60000–61000。")
    HostPlugin.NOTIFY -> tr("AI 交互提醒：Claude Code、Codex、Grok 请求权限或向你提问时推送通知。只写入用户目录，无需 sudo。")
}

private val cliNames = mapOf("claude" to "Claude Code", "codex" to "Codex", "grok" to "Grok")

/** One line per CLI, flagged when the user has something to do. */
private fun notifyReportLines(report: NotifyReport): List<Pair<String, Boolean>> {
    if (report.removed) return report.clis.filterValues { it == AgentNotify.NO_PYTHON }.keys.map { path ->
        trf("未安装 python3，请手动删除 %s 中 roamtty-notify 的条目。", path) to true
    }
    return cliNames.mapNotNull { (cli, name) ->
        val status = report.clis[cli] ?: return@mapNotNull null
        when (status) {
            "ok" -> if (cli == "codex" && report.codexTrust != "trusted" && report.codexTrust != "ok")
                trf("%s：已接入。从 Roamtty 启动 Codex 时会自动信任；手动启动时请在 Codex 中执行一次 /hooks 并信任 roamtty，否则收不到提醒。", name) to true
                else trf("%s：已接入", name) to false
            "absent" -> trf("%s：未安装，已跳过", name) to false
            "invalid" -> trf("%s：配置文件不是有效的 JSON，已跳过", name) to true
            else -> trf("%s：已有配置，合并需要 python3，已跳过", name) to true
        }
    }
}

/** Which package manager installs plugins on this kind of host, and whether it needs sudo. */
private fun installHint(system: HostSystem?) = when (system) {
    HostSystem.MACOS -> tr("通过 Homebrew 安装，无需 sudo；也支持 MacPorts。")
    HostSystem.WINDOWS -> tr("通过 MSYS2 的 pacman 或 Cygwin 的 apt-cyg 安装，无需管理员权限。")
    else -> tr("使用主机的包管理器安装（apt、dnf、yum、pacman、zypper、apk、pkg），需要 root 或 sudo 权限；也支持 Homebrew。")
}

private fun installMessage(plugin: HostPlugin, result: HostPluginInstaller.Result, system: HostSystem?, removed: Boolean) = when (result) {
    HostPluginInstaller.Result.INSTALLED -> if (plugin != HostPlugin.NOTIFY) trf("%s 安装完成。", plugin.id)
        else if (removed) tr("AI 交互提醒已卸载。") else tr("AI 交互提醒已安装。")
    HostPluginInstaller.Result.SUDO_PASSWORD -> tr("需要 sudo 密码，或密码不正确。")
    HostPluginInstaller.Result.NO_PRIVILEGE -> tr("当前用户没有 root 或 sudo 权限，请联系管理员安装。")
    HostPluginInstaller.Result.NO_PACKAGE_MANAGER -> when (system) {
        HostSystem.MACOS -> tr("未找到 Homebrew 或 MacPorts。请先在 Mac 上安装 Homebrew（brew.sh），再重试。")
        HostSystem.WINDOWS -> tr("未找到 pacman 或 apt-cyg。Git Bash 无法安装软件，请改用 MSYS2，或为 Cygwin 安装 apt-cyg。")
        else -> tr("未找到支持的包管理器，请手动安装。")
    }
    HostPluginInstaller.Result.FAILED -> if (removed) trf("%s 卸载失败，请查看下方输出。", plugin.id) else trf("%s 安装失败，请查看下方输出。", plugin.id)
}

private const val LOG_LINES = 14
