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
import io.itermux.app.data.installedPlugins
import io.itermux.app.login.PendingPrompt
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import io.itermux.app.data.credentialsFor
import io.itermux.app.login.pageLogin

/** Host and the one-time secrets typed for it; the sheet's SSH login lives while the sheet is open. */
internal data class PluginTarget(val host: Host, val password: String = "", val passphrase: String = "")

/**
 * One authenticated SSH login used to probe and install plugins. Install requests run one at a
 * time; the probe result is saved to the host so the list and new sessions pick it up.
 */
private const val MAX_INSTALL_OUTPUT = 256 * 1024

internal class HostPluginsController(private val app: ItermuxApp, private val target: PluginTarget, private val scope: CoroutineScope) {
    val host get() = target.host
    val pending = MutableStateFlow<PendingPrompt?>(null)
    val connection = MutableStateFlow(ConnectionState(Phase.CONNECTING))
    /** null while probing, or when the host cannot be probed (e.g. Windows cmd or PowerShell). */
    val plugins = MutableStateFlow<Set<HostPlugin>?>(null)
    /** Picks the install hints shown for this host. */
    val system = MutableStateFlow<HostSystem?>(null)
    /** cmd.exe or PowerShell answered instead of a POSIX shell: psmux replaces tmux and mosh. */
    val nativeWindows = MutableStateFlow(host.installedPlugins?.contains(HostPlugin.PSMUX) == true)
    val probed = MutableStateFlow(false)
    val installing = MutableStateFlow<HostPlugin?>(null)
    val log = MutableStateFlow<List<String>>(emptyList())
    val result = MutableStateFlow<Pair<HostPlugin, HostPluginInstaller.Result>?>(null)
    private var requests = Channel<Pair<HostPlugin, String?>>(Channel.CONFLATED)
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
                credentials = withContext(Dispatchers.IO) { app.vault.credentialsFor(host, target.password, target.passphrase) }
                loginPassword = credentials.password?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
                val ssh = pageLogin(host, credentials, app.pins, pending)
                session = ssh
                val state = launch { ssh.state.collect { connection.value = it } }
                ssh.administer { remote ->
                    system.value = ssh.detectedSystem.value
                    val found = remote.probePlugins()
                    ssh.nativeWindows.value?.let { windows ->
                        nativeWindows.value = windows
                        if (windows) system.value = HostSystem.WINDOWS
                    }
                    save(found)
                    for ((plugin, sudo) in queue) install(remote, plugin, sudo)
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

    private suspend fun install(remote: RemoteHost, plugin: HostPlugin, sudo: String?) {
        installing.value = plugin
        result.value = null
        log.value = emptyList()
        val output = StringBuilder()
        val onLine: (String) -> Unit = { line ->
            if (output.length < MAX_INSTALL_OUTPUT) {
                val room = MAX_INSTALL_OUTPUT - output.length
                output.append(line.take(room)).append('\n')
            }
            HostPluginInstaller.displayLine(line)?.let { shown -> log.value = (log.value + shown).takeLast(300) }
        }
        val status = if (plugin.windows) remote.runCommand(Psmux.installCommand, 15 * 60_000L, onLine)
            else remote.run(HostPluginInstaller.script(plugin, sudo ?: loginPassword), 15 * 60_000L, onLine)
        result.value = plugin to HostPluginInstaller.classify(status, output.toString())
        remote.probePlugins()?.let { save(it) }
        installing.value = null
    }

    fun install(plugin: HostPlugin, sudoPassword: String? = null) {
        if (installing.value != null) return
        requests.trySend(plugin to sudoPassword?.takeIf { it.isNotEmpty() })
    }

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
    val nativeWindows by controller.nativeWindows.collectAsStateWithLifecycle()
    val installing by controller.installing.collectAsStateWithLifecycle()
    val log by controller.log.collectAsStateWithLifecycle()
    val result by controller.result.collectAsStateWithLifecycle()
    val pending by controller.pending.collectAsStateWithLifecycle()
    var sudoPassword by remember { mutableStateOf("") }
    val host = controller.host
    val ready = connection.phase == Phase.CONNECTED && probed
    val landscape = isLandscape()
    AppSheet(onDismiss = onDismiss, fullScreen = landscape, wide = landscape) {
        if (landscape) {
            // Landscape, where height is short: one flat bar like the launch sheet's, check again as its refresh button.
            LandscapeBar(tr("安装插件"), detail = host.name, leading = {
                ToolbarIcon(R.drawable.ic_close, tr("关闭"), onClick = onDismiss)
            }) {
                ToolbarIcon(R.drawable.ic_refresh, tr("重新检测"), Modifier.testTag("host-plugins-refresh"),
                    enabled = installing == null && (ready || !connection.active), onClick = controller::start)
            }
        } else SheetHeader(tr("安装插件"), "${host.name} · ${host.username}@${host.address}:${host.port}", onDismiss)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = if (landscape) Spacing.Row else Spacing.Tight)
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
                probed && plugins == null -> Text(if (system == HostSystem.WINDOWS) tr("无法通过 PowerShell 检测插件。请确认主机可运行 powershell.exe，或把 OpenSSH 的默认 shell 设为 MSYS2 或 Cygwin 的 bash 后重试。")
                    else tr("无法检测插件。目前支持 Linux、macOS、BSD 和 Windows 主机。"),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Spacing.Page))
            }
            val footer = if (nativeWindows) tr("通过 winget 安装，无需管理员权限；没有 winget 时从 GitHub 下载。新建会话在 psmux 中运行，断网后可恢复。")
                else trf("%s新建会话优先使用 mosh；装有 tmux 时在 tmux 中运行。", installHint(system))
            // Landscape: the plugins as cards side by side, each showing its state where the install button goes.
            if (landscape) Column {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.Page)) {
                    HostPlugin.offered(nativeWindows).forEach { plugin ->
                        PluginCard(plugin, Modifier.weight(1f).fillMaxHeight()) {
                            when {
                                plugins?.contains(plugin) == true -> CardState(Modifier.testTag("plugin-installed-${plugin.id}")) {
                                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary)
                                    Text(tr("已安装"), color = MaterialTheme.colorScheme.primary)
                                }
                                installing == plugin -> CardState {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text(tr("正在安装…"))
                                }
                                ready && plugins != null -> FilledTonalButton(onClick = { controller.install(plugin, sudoPassword) },
                                    enabled = installing == null, modifier = Modifier.fillMaxWidth().testTag("plugin-install-${plugin.id}")) { Text(tr("安装")) }
                                ready -> {}
                                else -> CardState {
                                    if (connection.active) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text(tr("检测中…"))
                                }
                            }
                        }
                    }
                }
                Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.Page, end = Spacing.Page, top = Spacing.Tight))
            } else GroupedSection(header = tr("插件"), footer = footer) {
                HostPlugin.offered(nativeWindows).forEachIndexed { index, plugin ->
                    if (index > 0) GroupedDivider()
                    GroupedRow(plugin.id, Modifier.testTag("plugin-${plugin.id}"), subtitle = pluginDescription(plugin),
                        leading = {
                            Icon(painterResource(if (plugin.keepsSessions) R.drawable.ic_sessions else R.drawable.ic_live),
                                contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        },
                        trailing = {
                            when {
                                plugins?.contains(plugin) == true -> MetaTag(tr("已安装"), Modifier.testTag("plugin-installed-${plugin.id}"))
                                installing == plugin -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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
                Text(installMessage(plugin, outcome, system), style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = Spacing.Page).testTag("plugin-result"))
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

/** A plugin as a card: what it does on top, and at its foot [action], its state or the install button. */
@Composable
private fun PluginCard(plugin: HostPlugin, modifier: Modifier, action: @Composable () -> Unit) {
    Surface(modifier.testTag("plugin-${plugin.id}"), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.groupedContainer) {
        Column(Modifier.padding(Spacing.Page), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(if (plugin.keepsSessions) R.drawable.ic_sessions else R.drawable.ic_live),
                    contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(plugin.id, style = MaterialTheme.typography.titleMedium)
            }
            Text(pluginDescription(plugin), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            action()
        }
    }
}

/** A card's state where its install button would be: a muted full-width box. */
@Composable
private fun CardState(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp).clip(MaterialTheme.shapes.small)
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)) { content() }
    }
}

private fun pluginDescription(plugin: HostPlugin) = when (plugin) {
    HostPlugin.TMUX -> tr("会话保持：断网或关闭 App 后远端任务继续运行，重连即可恢复原会话。")
    HostPlugin.MOSH -> tr("移动网络优化：基于 UDP，切换网络或锁屏后自动恢复。需放行 UDP 60000–61000。")
    HostPlugin.PSMUX -> tr("Windows 版 tmux：断网或关闭 App 后远端任务继续运行，重连即可恢复原会话。")
    HostPlugin.DESKTOP -> tr("远程桌面：在会话卡片上点桌面按钮即可安装。")
}

/** Which package manager installs plugins on this kind of host, and whether it needs sudo. */
private fun installHint(system: HostSystem?) = when (system) {
    HostSystem.MACOS -> tr("通过 Homebrew 安装，无需 sudo；也支持 MacPorts。")
    HostSystem.WINDOWS -> tr("通过 MSYS2 的 pacman 或 Cygwin 的 apt-cyg 安装，无需管理员权限。")
    else -> tr("使用主机的包管理器安装（apt、dnf、yum、pacman、zypper、apk、pkg），需要 root 或 sudo 权限；也支持 Homebrew。")
}

private fun installMessage(plugin: HostPlugin, result: HostPluginInstaller.Result, system: HostSystem?) = when (result) {
    HostPluginInstaller.Result.INSTALLED -> trf("%s 安装完成。", plugin.id)
    HostPluginInstaller.Result.SUDO_PASSWORD -> tr("需要 sudo 密码，或密码不正确。")
    HostPluginInstaller.Result.NO_PRIVILEGE -> tr("当前用户没有 root 或 sudo 权限，请联系管理员安装。")
    HostPluginInstaller.Result.NO_PACKAGE_MANAGER -> when (system) {
        HostSystem.MACOS -> tr("未找到 Homebrew 或 MacPorts。请先在 Mac 上安装 Homebrew（brew.sh），再重试。")
        HostSystem.WINDOWS -> tr("未找到 pacman 或 apt-cyg。Git Bash 无法安装软件，请改用 MSYS2，或为 Cygwin 安装 apt-cyg。")
        else -> tr("未找到支持的包管理器，请手动安装。")
    }
    HostPluginInstaller.Result.FAILED -> trf("%s 安装失败，请查看下方输出。", plugin.id)
}

private const val LOG_LINES = 14
