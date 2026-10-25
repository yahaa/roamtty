package io.itermux.app.e2e

import android.content.pm.ActivityInfo
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import io.itermux.app.MainActivity
import io.itermux.app.data.Host
import io.itermux.app.dropOnPetShortcut
import io.itermux.app.e2e.E2e.app
import io.itermux.app.e2e.E2e.isOn
import io.itermux.app.e2e.E2e.text
import io.itermux.app.session.SessionService
import io.itermux.app.session.TerminalConnection
import io.itermux.app.session.sessionListPath
import io.itermux.ssh.HostPlugin
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Every user-facing feature, driven through the real UI against a real OpenSSH + SFTP server.
 * Run with scripts/e2e/run.sh; each test stands alone (its own hosts, cleaned up afterwards).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class E2eFeatureTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val serviceRule = ServiceTestRule()
    /** Runs after the failure screenshot below, so the screenshot still shows the failing screen. */
    @get:Rule(order = 2) val cleanupAfter = object : org.junit.rules.ExternalResource() {
        override fun after() {
            if (!E2e.enabled) return
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            E2e.cleanup(service)
        }
    }
    @get:Rule(order = 3) val screenshotOnFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) = E2e.capture(compose, "FAIL-${description.methodName}")
    }
    private lateinit var service: SessionService

    @Before fun setUp() {
        E2e.requireFixture()
        service = E2e.bind(serviceRule)
        E2e.cleanup(service)
        runBlocking { E2e.trustFixture() }
        E2e.prepareActivity(compose)
        E2e.waitTag(compose, "home-pager")
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private fun click(tag: String) { E2e.waitTag(compose, tag); compose.onNodeWithTag(tag, useUnmergedTree = true).performClick() }
    private fun clickText(text: String) {
        E2e.waitText(compose, text)
        val node = compose.onAllNodes(hasText(text) and hasClickAction()).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun hostsTab() {
        click("home-tab-hosts")
        compose.waitUntil(5_000) { E2e.exists(compose, hasTestTag("home-tab-hosts") and isSelected()) }
    }

    private fun sessionsTab() {
        if (!E2e.exists(compose, hasTestTag("home-pager"))) backToHome()
        click("home-tab-sessions")
        compose.waitUntil(5_000) { E2e.exists(compose, hasTestTag("home-tab-sessions") and isSelected()) }
    }

    private fun backToHome() {
        repeat(4) {
            if (E2e.exists(compose, hasTestTag("home-pager"))) return
            Espresso.pressBackUnconditionally()
            compose.waitForIdle()
        }
        E2e.waitTag(compose, "home-pager")
    }

    private fun hostMenu(host: Host, item: String) {
        hostsTab()
        compose.onNodeWithTag("host-grid").performScrollToNode(hasTestTag("host-card-${host.id}"))
        click("host-menu-${host.id}")
        click(item)
    }

    private fun newSessions(before: Set<String>) = service.sessions.value.filter { it.id !in before }

    /** Opens a terminal on [host] from its card menu and the launch sheet, as a user would; returns the new session. */
    private fun openTerminal(host: Host, metric: String? = null, expect: Phase = Phase.CONNECTED,
        afterLaunch: () -> Unit = {}): TerminalConnection {
        val before = service.sessions.value.map { it.id }.toSet()
        hostMenu(host, "host-open-terminal-${host.id}")
        return launchFromSheet(before, metric, expect, afterLaunch)
    }

    private fun launchFromSheet(before: Set<String>, metric: String? = null, expect: Phase = Phase.CONNECTED,
        afterLaunch: () -> Unit = {}): TerminalConnection {
        E2e.waitTag(compose, "launch-sheet")
        val start = System.nanoTime()
        click("launch-open")
        afterLaunch()
        compose.waitUntil(15_000) { newSessions(before).isNotEmpty() }
        val session = newSessions(before).single()
        val phase = runBlocking { kotlinx.coroutines.withTimeout(25_000) {
            session.state.first { it.phase == expect || it.phase == Phase.CONNECTED || it.phase in FAILURES }
        }.phase }
        assertEquals("session ${session.state.value.message}", expect, phase)
        if (expect == Phase.CONNECTED) {
            metric?.let { E2e.metric(it, (System.nanoTime() - start) / 1_000_000, "ms") }
            E2e.waitTag(compose, "terminal")
        }
        return session
    }

    /** The live connection behind [id]: a reconnect replaces the object but keeps the id. */
    private fun live(id: String) = service.sessions.value.single { it.id == id }

    private fun dismissKeyboard() {
        compose.runOnUiThread {
            (compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
    }

    private fun shellReady(session: TerminalConnection) {
        E2e.run(compose, "echo READY_$((20+22))")
        E2e.waitOutput(compose, session, "READY_42")
    }

    private fun showDock() {
        if (!E2e.exists(compose, hasTestTag("terminal-dock"))) click("restore-dock")
        E2e.waitTag(compose, "terminal-dock")
    }

    // ---- hosts ---------------------------------------------------------------------------------------------

    /** 添加主机: the editor saves a password host, shows the real fingerprint, detects Linux + tmux and probes latency. */
    @Test fun f01_addHostThroughEditorTrustsFingerprintAndDetectsSystem() = runBlocking<Unit> {
        E2e.forgetFixture()
        val name = E2e.PREFIX + "编辑器主机"
        hostsTab()
        click("add-host")
        E2e.waitTag(compose, "host-editor")
        compose.onNodeWithTag("host-name").performTextInput(name)
        compose.onNodeWithTag("host-address").performTextInput(E2e.ADDRESS)
        compose.onNodeWithTag("host-port").performTextReplacement(E2e.port.toString())
        compose.onNodeWithTag("host-username").performTextInput(E2e.USER)
        compose.onNodeWithTag("save-password").performScrollTo()
        if (!compose.isOn("save-password")) compose.onNodeWithTag("save-password").performClick()
        compose.onNodeWithTag("host-password").performScrollTo().performTextInput(E2e.password)
        compose.onNodeWithText("普通 SSH").performScrollTo().performClick()
        E2e.capture(compose, "f01-editor")
        val start = System.nanoTime()
        compose.onNodeWithTag("save-host").performClick()
        // The first connection to the endpoint asks to verify the server's real SHA-256 fingerprint.
        E2e.waitText(compose, "核对主机指纹", 15_000)
        val expected = io.itermux.ssh.HostPin(E2e.endpoint, E2e.hostKeyAlgorithm, E2e.hostKey).fingerprint
        compose.onNodeWithText(expected, substring = true).assertExists()
        E2e.capture(compose, "f01-fingerprint")
        clickText("信任并连接")
        E2e.waitGone(compose, hasTestTag("host-editor"), 20_000)
        E2e.metric("host_save_with_detection_ms", (System.nanoTime() - start) / 1_000_000, "ms")
        val saved = app.database.hosts().observe().first().single { it.name == name }
        assertTrue("Linux family detected: ${saved.os}", io.itermux.ssh.HostSystem.fromStored(saved.os).isLinux)
        assertNotNull("password saved encrypted", saved.encryptedPassword)
        assertTrue("tmux detected: ${saved.plugins}", HostPlugin.TMUX in HostPlugin.decode(saved.plugins).orEmpty())
        assertNotNull(app.database.hosts().pin(E2e.endpoint))
        compose.onNodeWithTag("host-grid").performScrollToNode(hasTestTag("host-card-${saved.id}"))
        compose.onNodeWithTag("host-plugin-tmux-${saved.id}", useUnmergedTree = true).assertExists()
        // The card's reachability probe measures DNS + TCP connect time.
        compose.waitUntil(15_000) {
            "在线" in compose.text("host-status-${saved.id}")
        }
        val status = compose.text("host-status-${saved.id}")
        val latency = if ("<1 ms" in status) 0.5 else Regex("""(\d+) ms""").find(status)?.groupValues?.get(1)?.toDouble()
        assertNotNull("latency shown: $status", latency)
        E2e.metric("host_probe_latency_ms", latency!!, "ms")
        E2e.capture(compose, "f01-host-card")
    }

    /** 搜索主机 filters the host grid; 编辑主机 renames a host. */
    @Test fun f02_searchAndEditHost() = runBlocking<Unit> {
        val a = E2e.save(E2e.host("搜索甲"))
        val b = E2e.save(E2e.host("搜索乙"))
        hostsTab()
        click("search-toggle")
        compose.onNodeWithTag("host-search").performTextInput("搜索乙")
        compose.waitUntil(5_000) { !E2e.exists(compose, hasTestTag("host-card-${a.id}")) }
        compose.onNodeWithTag("host-card-${b.id}").assertExists()
        click("search-toggle")
        E2e.waitTag(compose, "host-card-${a.id}")
        compose.onNodeWithTag("host-grid").performScrollToNode(hasTestTag("host-card-${a.id}"))
        click("host-menu-${a.id}")
        clickText("编辑主机")
        E2e.waitTag(compose, "host-editor")
        compose.onNodeWithTag("host-name").performTextReplacement(E2e.PREFIX + "已改名")
        compose.onNodeWithTag("save-host").performClick()
        E2e.waitGone(compose, hasTestTag("host-editor"), 20_000)
        compose.waitUntil(5_000) { runBlocking { app.database.hosts().find(a.id)?.name } == E2e.PREFIX + "已改名" }
        E2e.waitText(compose, E2e.PREFIX + "已改名")
        Unit
    }

    // ---- terminal ------------------------------------------------------------------------------------------

    /** 打开终端 from the host menu: a plain SSH shell runs commands, colours and Unicode. */
    @Test fun f03_plainSshTerminalRunsCommands() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("普通终端"))
        val session = openTerminal(host, metric = "ui_terminal_open_ms")
        shellReady(session)
        E2e.run(compose, "whoami; echo UNI_中文✓_$((2*21))")
        E2e.waitOutput(compose, session, E2e.USER)
        E2e.waitOutput(compose, session, "UNI_中文✓_42")
        E2e.run(compose, "printf '\\033[31mRED_%s\\033[0m\\n' $((3*3))")
        E2e.waitOutput(compose, session, "RED_9")
        // Long output wraps and scrolls without dropping the end.
        E2e.run(compose, "seq 1 3000 | tail -n 1 | sed 's/^/LAST_/'")
        E2e.waitOutput(compose, session, "LAST_3000")
        E2e.capture(compose, "f03-terminal")
    }

    /** 新建会话 on the sessions tab: pick 终端, then a host, then 打开终端. */
    @Test fun f04_newSessionFromSessionsTab() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("新建会话"))
        sessionsTab()
        val before = service.sessions.value.map { it.id }.toSet()
        click("add-session")
        click("new-session-pick-terminal")
        compose.onNodeWithTag("new-session-sheet").performScrollToNode(hasTestTag("new-session-host-${host.id}"))
        click("new-session-host-${host.id}")
        val session = launchFromSheet(before)
        shellReady(session)
        backToHome()
        sessionsTab()
        E2e.waitTag(compose, "home-session-${session.id}")
        compose.onNodeWithTag("home-session-${session.id}").assertTextContains(host.name, substring = true)
        E2e.capture(compose, "f04-sessions-tab")
    }

    /** The dock: font size, 编辑文本, 常用命令 (remote shell history) and collapsing. */
    @Test fun f05_dockFontEditorAndCommonCommands() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("操作栏"))
        val session = openTerminal(host)
        shellReady(session)
        showDock()
        click("terminal-menu")
        val larger = Regex("""增大字号 · (\d+) sp""")
        E2e.waitText(compose, "增大字号", substring = true)
        val size = compose.onAllNodes(hasText("增大字号", substring = true)).fetchSemanticsNodes().first()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
            .let { larger.find(it)!!.groupValues[1].toInt() }
        clickText("增大字号 · $size sp")
        showDock()
        click("terminal-menu")
        E2e.waitText(compose, "增大字号 · ${size + 1} sp")
        clickText("减小字号 · ${size + 1} sp")
        showDock()
        click("terminal-menu")
        E2e.waitText(compose, "增大字号 · $size sp")
        // 编辑文本 composes a line in a text field and sends it.
        clickText("编辑文本")
        E2e.waitTag(compose, "prompt-editor")
        compose.onNodeWithTag("prompt-editor").performTextInput("echo EDITOR_$((6*6))")
        clickText("发送文本")
        compose.onNode(hasContentDescription("回车") or hasText("回车 ↵")).performClick()
        E2e.waitOutput(compose, session, "EDITOR_36")
        clickText("收起")
        dismissKeyboard()
        // 常用命令 lists the remote shell history and runs a pick.
        showDock()
        click("common-commands")
        E2e.waitTag(compose, "common-command-echo E2E_HISTORY_$((40+2))", 15_000)
        E2e.capture(compose, "f05-common-commands")
        click("common-command-echo E2E_HISTORY_$((40+2))")
        E2e.waitOutput(compose, session, "E2E_HISTORY_42")
        showDock()
        click("collapse-dock")
        E2e.waitGone(compose, hasTestTag("terminal-dock"))
    }

    /** The toolbox pet's shortcut ring (Ctrl, Tab) and the double-tap = Tab gesture reach the shell. */
    @Test fun f06_petShortcutsAndGestures() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("快捷键"))
        val session = openTerminal(host)
        shellReady(session)
        dismissKeyboard()
        // Ctrl+C stops a running command.
        E2e.run(compose, "sleep 60")
        Thread.sleep(500)
        compose.dropOnPetShortcut("pet-ctrl")
        E2e.type(compose, "c")
        E2e.run(compose, "echo CTRLC_$((1+2))")
        E2e.waitOutput(compose, session, "CTRLC_3", 8_000)
        // Tab completes a command name.
        E2e.type(compose, "ech")
        compose.dropOnPetShortcut("pet-tab")
        E2e.run(compose, " TAB_$((2+3))")
        E2e.waitOutput(compose, session, "TAB_5")
        // A double tap on the terminal sends Tab too.
        E2e.type(compose, "ech")
        compose.onNodeWithTag("terminal").performTouchInput { doubleClick() }
        // The Tab goes out once the triple-tap window has passed.
        Thread.sleep(android.view.ViewConfiguration.getDoubleTapTimeout() + 300L)
        E2e.run(compose, " DOUBLE_$((3+4))")
        E2e.waitOutput(compose, session, "DOUBLE_7")
        E2e.capture(compose, "f06-shortcuts")
    }

    /** tmux mode: the shell survives a dropped connection, reattaches on open, and 重启当前会话 restarts the pane. */
    @Test fun f07_tmuxSessionSurvivesDisconnectAndRestarts() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("tmux", mode = SessionMode.TMUX))
        val first = openTerminal(host)
        assertEquals(SessionMode.TMUX, first.mode)
        shellReady(first)
        E2e.run(compose, "E2E_TMUX=kept_$((5*5)); tmux ls")
        E2e.waitOutput(compose, first, first.remoteSessionName)
        val id = first.id
        // The notification's 断开 drops every transport, like a lost network.
        compose.runOnUiThread { service.disconnectAll() }
        compose.waitUntil(10_000) { live(id).state.value.phase != Phase.CONNECTED || live(id) !== first }
        // A deliberate disconnect waits for 重新连接, which reattaches the same tmux session.
        val start = System.nanoTime()
        clickText("重新连接")
        compose.waitUntil(30_000) { live(id) !== first && live(id).state.value.phase == Phase.CONNECTED }
        E2e.metric("tmux_reattach_ms", (System.nanoTime() - start) / 1_000_000, "ms")
        val second = live(id)
        compose.waitUntil(10_000) { E2e.terminalView(compose) != null }
        Thread.sleep(500)
        E2e.run(compose, "echo VAR=\$E2E_TMUX")
        E2e.waitOutput(compose, second, "VAR=kept_25")
        E2e.capture(compose, "f07-reattached")
        // 重启当前会话 starts a fresh shell in the same tmux session.
        showDock()
        click("session-switcher")
        click("restart-session")
        click("restart-session-confirm")
        Thread.sleep(1500)
        E2e.run(compose, "echo AFTER=[\$E2E_TMUX]")
        E2e.waitOutput(compose, live(id), "AFTER=[]")
    }

    /** Sessions appear on the sessions tab and in the drawer, switch from the dock, and close from the card menu. */
    @Test fun f08_sessionsListDrawerSwitchAndClose() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("会话列表"))
        val a = openTerminal(host)
        shellReady(a)
        backToHome()
        val b = openTerminal(host)
        shellReady(b)
        assertEquals(2, b.sessionNumber)
        // The dock's session menu lists both and switches.
        showDock()
        click("session-switcher")
        clickText("${a.displayTitle} · 已连接")
        compose.waitUntil(5_000) { E2e.terminalView(compose)?.let { view -> view.mEmulator === a.getEmulator() } == true }
        backToHome()
        // The drawer lists every session.
        click("brand-logo")
        E2e.waitTag(compose, "drawer-session-${a.id}")
        E2e.waitTag(compose, "drawer-session-${b.id}")
        E2e.capture(compose, "f08-drawer")
        click("drawer-session-${b.id}")
        E2e.waitTag(compose, "terminal")
        backToHome()
        sessionsTab()
        E2e.waitTag(compose, "home-session-${a.id}")
        compose.onNodeWithTag("home-session-${a.id}").performTouchInput { longClick() }
        click("home-session-close-${a.id}")
        E2e.waitGone(compose, hasTestTag("home-session-${a.id}"))
        assertTrue(service.sessions.value.none { it.id == a.id })
        E2e.capture(compose, "f08-after-close")
    }

    // ---- authentication and trust --------------------------------------------------------------------------

    /** An encrypted Ed25519 key asks for its passphrase per connection; keyboard-interactive asks for the password. */
    @Test fun f09_keyAndInteractiveAuthentication() = runBlocking<Unit> {
        val key = E2e.instrumentation.context.assets.open("test_ed25519").use { it.readBytes() }
        val id = java.util.UUID.randomUUID().toString()
        val keyHost = E2e.save(Host(id, E2e.PREFIX + "私钥", E2e.ADDRESS, E2e.port, E2e.USER, io.itermux.ssh.AuthMethod.KEY.name,
            SessionMode.SHELL.name, encryptedKey = app.vault.encrypt(key, "$id:key"), keyLabel = "test_ed25519", os = "UBUNTU",
            plugins = HostPlugin.encode(setOf(HostPlugin.TMUX))))
        val before = service.sessions.value.map { it.id }.toSet()
        hostMenu(keyHost, "host-open-terminal-${keyHost.id}")
        E2e.waitTag(compose, "launch-sheet")
        click("launch-open")
        E2e.waitText(compose, "私钥口令（可留空）")
        compose.onNode(hasSetTextAction()).performTextInput("itermux-test-passphrase")
        clickText("连接")
        compose.waitUntil(15_000) { newSessions(before).isNotEmpty() }
        val keySession = newSessions(before).single()
        assertEquals(keySession.state.value.message, Phase.CONNECTED, E2e.connected(keySession))
        E2e.waitTag(compose, "terminal")
        E2e.run(compose, "echo KEY_$((7*6)) $(whoami)")
        E2e.waitOutput(compose, keySession, "KEY_42 ${E2e.USER}")
        backToHome()

        val interactive = E2e.save(E2e.host("交互式", savePassword = false).copy(auth = io.itermux.ssh.AuthMethod.INTERACTIVE.name))
        val before2 = service.sessions.value.map { it.id }.toSet()
        hostMenu(interactive, "host-open-terminal-${interactive.id}")
        E2e.waitTag(compose, "launch-sheet")
        click("launch-open")
        E2e.waitText(compose, "交互式认证", 15_000)
        compose.onNode(hasSetTextAction()).performTextInput(E2e.password)
        clickText("提交")
        compose.waitUntil(15_000) { newSessions(before2).isNotEmpty() }
        val kbd = newSessions(before2).single()
        assertEquals(kbd.state.value.message, Phase.CONNECTED, E2e.connected(kbd))
        E2e.run(compose, "echo KBD_$((8*8))")
        E2e.waitOutput(compose, kbd, "KBD_64")
    }

    /** A wrong saved password fails authentication visibly instead of connecting. */
    @Test fun f10_wrongPasswordFailsAuthentication() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("错误密码", password = "definitely-wrong"))
        val session = openTerminal(host, expect = Phase.AUTH_FAILED)
        assertEquals(Phase.AUTH_FAILED, session.state.value.phase)
        E2e.capture(compose, "f10-auth-failed")
    }

    /** A changed host key blocks the connection; 重新核验 removes the old record and shows the new fingerprint. */
    @Test fun f11_changedHostKeyIsBlockedUntilReverified() = runBlocking<Unit> {
        app.database.hosts().forgetPin(E2e.endpoint)
        val bogus = java.util.Base64.getEncoder().encodeToString(ByteArray(51) { it.toByte() })
        app.database.hosts().insertPin(io.itermux.app.data.StoredPin(E2e.endpoint, E2e.hostKeyAlgorithm, bogus))
        val host = E2e.save(E2e.host("指纹变化"))
        val session = openTerminal(host, expect = Phase.HOST_KEY_CHANGED)
        assertEquals(Phase.HOST_KEY_CHANGED, session.state.value.phase)
        assertEquals(bogus, app.database.hosts().pin(E2e.endpoint)?.key)
        E2e.capture(compose, "f11-host-key-changed")
        clickText("重新核验…")
        clickText("移除旧记录并重连")
        E2e.waitText(compose, "核对主机指纹", 15_000)
        clickText("信任并连接")
        compose.waitUntil(20_000) { service.sessions.value.any { it.host.id == host.id && it.state.value.phase == Phase.CONNECTED } }
        assertEquals(E2e.hostKey, app.database.hosts().pin(E2e.endpoint)?.key)
    }

    // ---- files ---------------------------------------------------------------------------------------------

    private fun openFiles(host: Host): TerminalConnection {
        val before = service.sessions.value.map { it.id }.toSet()
        val start = System.nanoTime()
        hostMenu(host, "host-browse-files-${host.id}")
        E2e.waitTag(compose, "files-entry-e2e", 20_000)
        E2e.metric("files_open_ms", (System.nanoTime() - start) / 1_000_000, "ms")
        return newSessions(before).single()
    }

    private fun openEntry(name: String, timeoutMs: Long = 10_000) {
        E2e.waitTag(compose, "files-list", timeoutMs)
        compose.onNodeWithTag("files-list").performScrollToNode(hasTestTag("files-entry-$name"))
        click("files-entry-$name")
    }

    /** 查看文件: SFTP browsing and previews of Markdown, highlighted code (with search), images and a large log. */
    @Test fun f12_fileBrowserAndPreviews() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("文件浏览"))
        openFiles(host)
        openEntry("e2e")
        E2e.waitTag(compose, "files-entry-README.md")
        E2e.capture(compose, "f12-files")
        openEntry("README.md")
        E2e.waitTag(compose, "file-preview-markdown")
        E2e.waitText(compose, "E2E Fixture")
        E2e.capture(compose, "f12-markdown")
        click("file-preview-close")
        openEntry("src")
        openEntry("Main.kt")
        E2e.waitTag(compose, "file-preview-text")
        E2e.waitText(compose, "E2E_CODE_PREVIEW", substring = true)
        click("file-preview-search")
        compose.onNodeWithTag("file-preview-search-input").performTextInput("greeting")
        compose.waitUntil(5_000) { compose.text("file-preview-search-count").contains("2") }
        E2e.capture(compose, "f12-code-search")
        // Otherwise system Back can be consumed by the search IME or the departing preview window.
        Espresso.closeSoftKeyboard()
        click("file-preview-search-close")
        click("file-preview-close")
        E2e.waitGone(compose, hasTestTag("file-preview"))
        Espresso.pressBack()
        E2e.waitTag(compose, "files-entry-README.md")
        openEntry("pixel.png")
        E2e.waitTag(compose, "file-preview-image")
        click("file-preview-close")
        openEntry("docs")
        val start = System.nanoTime()
        openEntry("large.log")
        E2e.waitText(compose, "line 1", substring = true, timeoutMs = 20_000)
        E2e.metric("preview_large_log_ms", (System.nanoTime() - start) / 1_000_000, "ms")
        E2e.capture(compose, "f12-large-log")
        click("file-preview-close")
        E2e.waitGone(compose, hasTestTag("file-preview"))
        Espresso.pressBack()
        E2e.waitTag(compose, "files-entry-README.md")
        // A directory of 1,500 files lists and scrolls to its end.
        val listStart = System.nanoTime()
        openEntry("big")
        E2e.waitTag(compose, "files-entry-file-0001.txt", 20_000)
        E2e.metric("files_list_1500_ms", (System.nanoTime() - listStart) / 1_000_000, "ms")
        compose.onNodeWithTag("files-list").performScrollToNode(hasTestTag("files-entry-file-1500.txt"))
    }

    /** Rename, new folder, filter and delete in the file browser, checked against the server's own listing. */
    @Test fun f13_fileOperations() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("文件操作"))
        val session = openFiles(host)
        openEntry("e2e")
        E2e.waitTag(compose, "files-entry-notes.txt")
        click("files-row-menu-notes.txt")
        click("files-action-rename")
        compose.onNodeWithTag("files-rename-input").performTextReplacement("renamed.txt")
        click("files-rename-confirm")
        E2e.waitTag(compose, "files-entry-renamed.txt")
        click("files-options")
        click("files-new-folder")
        compose.onNodeWithTag("files-new-folder-input").performTextInput("made-by-e2e")
        click("files-new-folder-confirm")
        E2e.waitTag(compose, "files-entry-made-by-e2e")
        click("files-row-menu-delete-me.txt")
        click("files-action-delete")
        click("files-delete-confirm")
        E2e.waitGone(compose, hasTestTag("files-entry-delete-me.txt"))
        click("files-search")
        compose.onNodeWithTag("files-filter").performTextInput("renamed")
        compose.waitUntil(5_000) { !E2e.exists(compose, hasTestTag("files-entry-README.md")) }
        E2e.waitTag(compose, "files-entry-renamed.txt")
        E2e.capture(compose, "f13-file-ops")
        val names = session.listRemoteDirectory("e2e").entries.map { it.name }.toSet()
        assertTrue(names.toString(), "renamed.txt" in names && "made-by-e2e" in names)
        assertFalse(names.toString(), "notes.txt" in names || "delete-me.txt" in names)
    }

    /** Upload through the upload sheet, image sharing from the system share sheet, and SFTP download integrity. */
    @Test fun f14_uploadShareImageAndDownload() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("传输"))
        val session = openTerminal(host)
        shellReady(session)
        // The document picker is system UI; the sheet starts from what it returns.
        val uploadName = "e2e-upload-${System.currentTimeMillis()}.bin"
        val local = java.io.File(E2e.context.cacheDir, uploadName)
        val bytes = ByteArray(1 shl 20).also { java.util.Random(42).nextBytes(it) }
        local.writeBytes(bytes)
        compose.runOnUiThread {
            session.transfers.uploadName = uploadName
            session.transfers.uploadSize = local.length()
            session.transfers.uploadPath = uploadName
            session.transfers.uploadDirectory = "e2e/upload"
            session.transfers.uploadUri = android.net.Uri.fromFile(local)
        }
        E2e.waitTag(compose, "upload-confirm")
        compose.onNodeWithTag("upload-directory").assertTextContains("e2e/upload", substring = true)
        E2e.capture(compose, "f14-upload-sheet")
        click("upload-confirm")
        compose.waitUntil(30_000) { session.transfers.status.value.let { !it.running && it.result != null } }
        assertEquals(session.transfers.status.value.result, true, session.transfers.status.value.succeeded)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        E2e.capture(compose, "f14-upload-done")
        session.transfers.dismiss()
        local.delete()
        E2e.run(compose, "sha256sum e2e/upload/$uploadName")
        E2e.waitOutput(compose, session, sha)

        // 系统分享图片: an image shared to Roamtty uploads and its path lands in the prompt.
        val resolver = E2e.context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "roamtty-e2e-share.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RoamttyE2E")
        }
        val image = requireNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            resolver.openOutputStream(image)!!.use {
                android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(android.graphics.Color.MAGENTA) }.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            E2e.run(compose, "clear")
            // The share sheet delivers to the running (singleTop) activity, as the system does.
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/png")
                .setClass(E2e.context, MainActivity::class.java).putExtra(android.content.Intent.EXTRA_STREAM, image)
            // ActivityScenario finds its activity by the launch intent, which onNewIntent replaces: put it back after.
            compose.activityRule.scenario.onActivity {
                val launch = it.intent
                E2e.instrumentation.callActivityOnNewIntent(it, share)
                it.intent = launch
            }
            compose.waitUntil(20_000) { Regex("""\S+\.png""").containsMatchIn(E2e.transcript(session).substringAfterLast("$ ")) }
            E2e.capture(compose, "f14-shared-image")
            val path = Regex("""(\S+\.png)""").find(E2e.transcript(session).substringAfterLast("$ "))!!.groupValues[1].trim('\'', '"')
            E2e.key(compose, KeyEvent.KEYCODE_MOVE_HOME)
            E2e.type(compose, "file ")
            E2e.enter(compose)
            E2e.waitOutput(compose, session, "PNG image data, 64 x 64")
            E2e.log("shared image uploaded to $path")
        } finally { resolver.delete(image, null, null) }

        // 下载文件 lists the remote folder; the bytes themselves are checked over the same SFTP channel.
        showDock()
        click("download-file")
        E2e.waitTag(compose, "download-files-list", 15_000)
        E2e.capture(compose, "f14-download-sheet")
        Espresso.pressBack()
        E2e.waitGone(compose, hasTestTag("download-files-list"))
        val out = java.io.ByteArrayOutputStream()
        session.download("e2e/download.txt", out)
        assertTrue(out.toString(Charsets.UTF_8.name()).startsWith("E2E_DOWNLOAD_PAYLOAD"))
    }

    // ---- host page -----------------------------------------------------------------------------------------

    /** The host page's live monitor (CPU, memory, disk) and the plugin sheet (tmux installed, mosh offered). */
    @Test fun f15_hostDetailMonitorAndPlugins() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("主机详情"))
        hostsTab()
        compose.onNodeWithTag("host-grid").performScrollToNode(hasTestTag("host-card-${host.id}"))
        val start = System.nanoTime()
        click("host-card-${host.id}")
        E2e.waitTag(compose, "host-detail")
        compose.waitUntil(20_000) { "%" in compose.text("host-gauge-cpu") }
        E2e.metric("monitor_first_sample_ms", (System.nanoTime() - start) / 1_000_000, "ms")
        E2e.waitText(compose, "内存")
        E2e.waitText(compose, "磁盘")
        E2e.capture(compose, "f15-host-detail")
        click("host-detail-more")
        val pluginStart = System.nanoTime()
        click("host-detail-plugins")
        E2e.waitTag(compose, "host-plugins-sheet")
        try { E2e.waitTag(compose, "plugin-install-mosh", 30_000) }
        catch (e: AssertionError) { E2e.dumpThreads("f15-plugin-probe-stall"); throw e }
        E2e.metric("plugin_detect_ms", (System.nanoTime() - pluginStart) / 1_000_000, "ms")
        E2e.waitTag(compose, "plugin-installed-tmux")
        E2e.capture(compose, "f15-plugins")
        Espresso.pressBack()
        E2e.waitGone(compose, hasTestTag("host-plugins-sheet"))
        click("host-detail-back")
        E2e.waitTag(compose, "home-pager")
    }

    /** AI Agent: the launch sheet starts the chosen agent (a stand-in on the fixture) in a new session titled after it. */
    @Test fun f16_agentLaunch() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("助手", mode = SessionMode.TMUX))
        hostsTab()
        compose.onNodeWithTag("host-grid").performScrollToNode(hasTestTag("host-card-${host.id}"))
        click("host-card-${host.id}")
        click("host-detail-agent")
        val before = service.sessions.value.map { it.id }.toSet()
        E2e.waitTag(compose, "launch-sheet")
        click("agent-picker")
        click("agent-option-codex")
        E2e.waitGone(compose, hasTestTag("agent-option-claude"))
        E2e.capture(compose, "f16-agent-sheet")
        click("launch-open")
        compose.waitUntil(15_000) { newSessions(before).isNotEmpty() }
        val session = newSessions(before).single()
        assertEquals(Phase.CONNECTED, E2e.connected(session))
        E2e.waitOutput(compose, session, "E2E_FAKE_AGENT codex", 20_000)
        assertTrue(session.displayTitle, session.displayTitle.contains("Codex"))
        E2e.run(compose, "hello-agent")
        E2e.waitOutput(compose, session, "agent got: hello-agent")
        E2e.capture(compose, "f16-agent-running")
        // Its card says where it runs before the agent's hooks ever report, like a session whose hooks do.
        backToHome()
        sessionsTab()
        E2e.waitTag(compose, "home-session-${session.id}")
        compose.waitUntil(20_000) { session.directory.value.isNotEmpty() }
        compose.onNodeWithTag("home-session-host-${session.id}", useUnmergedTree = true).assertTextEquals(host.name)
        compose.onNodeWithTag("home-session-location-${session.id}", useUnmergedTree = true)
            .assertTextEquals(sessionListPath(session.directory.value, host.username))
        E2e.capture(compose, "f16-agent-card")
    }

    // ---- app-wide ------------------------------------------------------------------------------------------

    /** Settings: language, theme and terminal font apply at once and persist. */
    @Test fun f17_settingsLanguageThemeFont() = runBlocking<Unit> {
        val prefs = E2e.context.getSharedPreferences("app-settings", android.content.Context.MODE_PRIVATE)
        val theme = prefs.getString("theme", null)
        try {
            click("brand-logo")
            click("drawer-settings")
            click("settings-language-english")
            click("close-settings")
            E2e.waitText(compose, "Hosts")
            E2e.waitText(compose, "Sessions")
            E2e.capture(compose, "f17-english")
            click("brand-logo")
            click("drawer-settings")
            click("settings-language-zh_cn")
            E2e.waitText(compose, "外观", substring = true)
            click("settings-theme-dark")
            compose.waitUntil(3_000) { prefs.getString("theme", null) == "DARK" }
            E2e.capture(compose, "f17-dark")
            click("settings-theme-light")
            compose.waitUntil(3_000) { prefs.getString("theme", null) == "LIGHT" }
            compose.onNodeWithTag("settings-font-open").performScrollTo().performClick()
            click("settings-font-fira_code")
            E2e.waitText(compose, "Fira Code", substring = true)
            E2e.capture(compose, "f17-font")
        } finally {
            prefs.edit().apply { if (theme == null) remove("theme") else putString("theme", theme) }.commit()
            E2e.context.getSharedPreferences("terminal-font", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            io.itermux.app.AppLocale.set(E2e.context, io.itermux.app.AppLanguage.ZH_CN)
        }
    }

    /** 使用指南: the about dialog shows the version, the privacy policy and the third-party licences. */
    @Test fun f18_guidePrivacyAndLicenses() {
        click("brand-logo")
        click("drawer-guide")
        E2e.waitText(compose, "版本", substring = true)
        compose.onNodeWithText("-e2e", substring = true).assertExists()
        clickText("隐私政策")
        E2e.waitText(compose, "返回指南")
        clickText("返回指南")
        clickText("查看第三方许可")
        E2e.waitText(compose, "Apache", substring = true)
        clickText("关闭")
    }

    /** Rotation and Activity recreation keep the live connection and its screen. */
    @Test fun f19_rotationAndRecreationKeepTheSession() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("旋转"))
        val session = openTerminal(host)
        shellReady(session)
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(8_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        E2e.waitTag(compose, "terminal")
        E2e.run(compose, "echo LAND_$((9*9))")
        E2e.waitOutput(compose, session, "LAND_81")
        E2e.capture(compose, "f19-landscape")
        compose.activityRule.scenario.recreate()
        E2e.waitTag(compose, "terminal")
        assertEquals(Phase.CONNECTED, session.state.value.phase)
        E2e.prepareActivity(compose)
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        E2e.waitTag(compose, "terminal")
        E2e.run(compose, "echo PORT_$((8*9))")
        E2e.waitOutput(compose, session, "PORT_72")
    }

    /** Each host keeps at most 8 sessions. */
    @Test fun f20_sessionLimitPerHost() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("会话上限"))
        val opened = mutableListOf<TerminalConnection>()
        repeat(8) {
            opened += kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                service.open(host, SessionMode.SHELL, io.itermux.ssh.Credentials(E2e.password.toByteArray()))
            }
        }
        opened.forEach { assertEquals(Phase.CONNECTED, E2e.connected(it)) }
        val ninth = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            service.open(host, SessionMode.SHELL, io.itermux.ssh.Credentials(E2e.password.toByteArray()))
        } }
        assertTrue("a 9th session must be refused", ninth.isFailure)
        assertEquals(8, service.sessions.value.count { it.host.id == host.id })
    }

    /**
     * The session list: agents waiting or finished get the full row (finished: the prompt, the answer above a status
     * line of how long it worked and when), plain terminals compact rows (name, folder, connection); every finished
     * agent shows.
     */
    @Test fun f24_sessionListRows() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("会话列表", mode = SessionMode.TMUX))
        val hook = "sh ~/.roamtty/agent-hook.sh claude"
        suspend fun open(command: String?) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            service.open(host, SessionMode.TMUX, io.itermux.ssh.Credentials(E2e.password.toByteArray())).also { session ->
                if (command != null) session.runWhenConnected(command, agentHooks = io.itermux.app.session.CodingAgent.CLAUDE)
            }
        }
        fun done(folder: String, answer: String) = "mkdir -p ~/$folder && cd ~/$folder && " +
            "printf '{\"prompt\":\"tidy $folder\"}' | $hook prompt; " +
            "printf '{\"last_assistant_message\":\"$answer\"}' | $hook stop; sleep 600"
        val waiting = open("mkdir -p ~/work/roamtty && cd ~/work/roamtty && " +
            "printf '{\"prompt\":\"build the release apk\"}' | $hook prompt; " +
            "printf '{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"./gradlew assembleRelease\"}}' | $hook tool; " +
            "printf '{\"notification_type\":\"permission_prompt\",\"message\":\"Claude needs your permission\"}' | $hook notify; sleep 600")
        val older = open(done("docs", "README updated."))
        Thread.sleep(1_500)
        val latest = open(done("blog", "Fixed 12 broken image links."))
        val plain = open(null)
        listOf(waiting, older, latest, plain).forEach { assertEquals(Phase.CONNECTED, E2e.connected(it)) }
        sessionsTab()
        compose.waitUntil(40_000) {
            E2e.exists(compose, hasTestTag("home-session-approve-${waiting.id}")) &&
                E2e.exists(compose, hasTestTag("home-session-group-done"))
        }
        fun tag(name: String) = compose.onNodeWithTag(name, useUnmergedTree = true)
        // Every row has its folder under its name (the fixture's home is not under /home, so it stays whole).
        compose.waitUntil(20_000) { E2e.exists(compose, hasTestTag("home-session-location-${waiting.id}")) }
        assertTrue(compose.text("home-session-location-${waiting.id}").endsWith("/work/roamtty"))
        tag("home-session-prompt-${waiting.id}").assertExists()
        // Both finished agents show: the prompt, the answer, and a status line of how long it worked.
        compose.waitUntil(20_000) { listOf(older, latest).all { E2e.exists(compose, hasTestTag("home-session-reply-${it.id}")) } }
        listOf(older, latest).forEach {
            tag("home-session-prompt-${it.id}").assertExists()
            tag("home-session-done-${it.id}").assertExists()
            tag("home-session-follow-up-${it.id}").assertExists()
        }
        E2e.capture(compose, "f24-session-list")
        compose.onNodeWithTag("home-sessions-list").performScrollToNode(hasTestTag("home-session-${plain.id}"))
        tag("home-session-tag-tmux-${plain.id}").assertExists()
    }

    /**
     * Every agent's card is as tall as the next, whatever it has to say; in landscape the home page has one short bar
     * with the tabs, the filters, search and add, which slides away as the list scrolls on and comes back as it scrolls back.
     */
    @Test fun f25_landscapeCardsShareHeightAndBarSlides() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("等高", mode = SessionMode.TMUX))
        val hook = "sh ~/.roamtty/agent-hook.sh claude"
        suspend fun open(command: String?) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            service.open(host, SessionMode.TMUX, io.itermux.ssh.Credentials(E2e.password.toByteArray())).also { session ->
                if (command != null) session.runWhenConnected(command, agentHooks = io.itermux.app.session.CodingAgent.CLAUDE)
            }
        }
        fun done(folder: String, stop: String) = "mkdir -p ~/$folder && cd ~/$folder && " +
            "printf '{\"prompt\":\"tidy $folder\"}' | $hook prompt; printf '$stop' | $hook stop; sleep 600"
        val long = open(done("long", "{\"last_assistant_message\":\"" + "Posted the review with the cause, the steps, the numbers and a fix. ".repeat(4) + "\"}"))
        val short = open(done("short", "{\"last_assistant_message\":\"Done.\"}"))
        val silent = open("mkdir -p ~/silent && cd ~/silent && printf '{}' | $hook stop; sleep 600")
        val waiting = open("mkdir -p ~/wait && cd ~/wait && printf '{\"prompt\":\"build\"}' | $hook prompt; " +
            "printf '{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"./gradlew assembleRelease\"}}' | $hook tool; " +
            "printf '{\"notification_type\":\"permission_prompt\",\"message\":\"needs permission\"}' | $hook notify; sleep 600")
        val agents = listOf(long, short, silent, waiting)
        val plains = List(2) { open(null) }
        (agents + plains).forEach { assertEquals(Phase.CONNECTED, E2e.connected(it)) }
        sessionsTab()
        // The list only holds the cards on screen, so wait on the agents themselves, then scroll to each card.
        compose.waitUntil(40_000) {
            waiting.agentStatus.value?.state == io.itermux.app.session.AgentStatus.State.WAITING &&
                listOf(long, short, silent).all { it.agentStatus.value?.state == io.itermux.app.session.AgentStatus.State.IDLE }
        }
        listOf("approve" to waiting, "reply" to long, "reply" to short, "no-reply" to silent).forEach { (what, session) ->
            compose.onNodeWithTag("home-sessions-list").performScrollToNode(hasTestTag("home-session-${session.id}"))
            E2e.waitTag(compose, "home-session-$what-${session.id}", 20_000)
        }
        compose.onNodeWithTag("home-sessions-list").performScrollToIndex(0)
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        fun assertSameHeight() {
            val heights = agents.map {
                compose.onNodeWithTag("home-sessions-list").performScrollToNode(hasTestTag("home-session-${it.id}"))
                bounds("home-session-${it.id}").height
            }
            heights.forEach { assertEquals("agent cards $heights", heights.first().value, it.value, 1.5f) }
        }
        E2e.capture(compose, "f25-portrait")
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(8_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { E2e.exists(compose, hasTestTag("home-session-filter-all")) }
        compose.waitForIdle()
        assertSameHeight()
        compose.onNodeWithTag("home-sessions-list").performScrollToIndex(0)
        compose.waitForIdle()
        // One bar: the tabs, filters, search and add on one line, no "会话 · N" line under it.
        assertTrue(compose.onAllNodes(hasText("会话 · ", substring = true)).fetchSemanticsNodes().isEmpty())
        val bar = bounds("brand-logo")
        val middle = (bar.top + bar.bottom) / 2
        listOf("home-tab-sessions", "home-session-filter-all", "search-toggle", "add-session").forEach {
            assertEquals(it, middle.value, ((bounds(it).top + bounds(it).bottom) / 2).value, 2f)
        }
        val listTop = bounds("home-sessions-list").top
        assertTrue("list at $listTop under a bar at ${bar.bottom}", listTop <= bar.bottom + 1.dp)
        E2e.capture(compose, "f25-landscape")
        // Scrolling on slides the bar away and the list takes its place; scrolling back brings it back.
        compose.onNodeWithTag("home-sessions-list").performTouchInput { swipeUp(centerY, centerY - 400f, 300) }
        compose.waitForIdle()
        assertTrue("bar at ${bounds("brand-logo").bottom}", bounds("brand-logo").bottom <= bar.top + 1.dp)
        assertTrue(bounds("home-sessions-list").top < listTop)
        E2e.capture(compose, "f25-landscape-scrolled")
        compose.onNodeWithTag("home-sessions-list").performTouchInput { swipeDown(centerY, centerY + 200f, 300) }
        compose.waitForIdle()
        assertEquals(bar.top.value, bounds("brand-logo").top.value, 1f)
        hostsTab()
        listOf("add-host", "search-toggle").forEach { E2e.waitTag(compose, it) }
        assertTrue(compose.onAllNodes(hasText("主机 · ", substring = true)).fetchSemanticsNodes().isEmpty())
        E2e.capture(compose, "f25-landscape-hosts")
    }

    /** 删除主机 removes the host, its saved credentials and closes its sessions. */
    @Test fun f21_deleteHostClosesItsSessions() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("删除"))
        val session = openTerminal(host)
        shellReady(session)
        backToHome()
        hostsTab()
        compose.onNodeWithTag("host-grid").performScrollToNode(hasTestTag("host-card-${host.id}"))
        click("host-menu-${host.id}")
        clickText("删除主机")
        E2e.waitText(compose, "删除 ${host.name}？")
        compose.onNode(hasText("删除") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        E2e.waitGone(compose, hasTestTag("host-card-${host.id}"))
        assertNull(app.database.hosts().find(host.id))
        compose.waitUntil(5_000) { service.sessions.value.none { it.host.id == host.id } }
    }

    /**
     * 新建会话 › 继续会话: with one tmux host it lists that host's running sessions straight away, one tap opens a
     * detached one, and tapping the one already open switches to its card instead of attaching it twice.
     */
    @Test fun f22_continueSessionAttachesARunningTmuxSession() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("继续会话", mode = SessionMode.TMUX))
        val first = openTerminal(host)
        shellReady(first)
        // Short commands: long typed lines lose keys on the emulator.
        val name = "e2c${System.currentTimeMillis() % 10_000}"
        E2e.run(compose, "tmux new -d -s $name; echo MADE_$((6*7))")
        E2e.waitOutput(compose, first, "MADE_42")
        backToHome()
        sessionsTab()
        val before = service.sessions.value.map { it.id }.toSet()
        click("add-session")
        E2e.waitTag(compose, "new-session-pick-continue")
        E2e.capture(compose, "f22-new-session")
        click("new-session-pick-continue")
        E2e.waitTag(compose, "continue-sheet")
        E2e.waitTag(compose, "launch-tmux-$name", timeoutMs = 20_000)
        E2e.capture(compose, "f22-continue-sheet")
        click("launch-tmux-$name")
        compose.waitUntil(15_000) { newSessions(before).isNotEmpty() }
        val attached = newSessions(before).single()
        assertEquals(Phase.CONNECTED, E2e.connected(attached))
        assertEquals(SessionMode.TMUX, attached.mode)
        E2e.waitTag(compose, "terminal")
        shellReady(attached)
        E2e.run(compose, "tmux display -p 'IN_#S'")
        E2e.waitOutput(compose, attached, "IN_$name")
        E2e.capture(compose, "f22-attached")
        // The first card's session is marked open; tapping it shows that card, no second attach.
        backToHome()
        val count = service.sessions.value.size
        click("add-session")
        click("new-session-pick-continue")
        E2e.waitTag(compose, "launch-tmux-${first.remoteSessionName}", timeoutMs = 20_000)
        click("launch-tmux-${first.remoteSessionName}")
        E2e.waitTag(compose, "terminal")
        assertEquals(count, service.sessions.value.size)
        E2e.run(compose, "tmux kill-session -t $name")
    }

    /** 强制删除 removes a disconnected tmux card at once and also ends its tmux session on the server. */
    @Test fun f23_forceDeleteEndsTheRemoteTmuxSession() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("强制删除", mode = SessionMode.TMUX))
        val tmux = openTerminal(host)
        shellReady(tmux)
        val name = tmux.remoteSessionName
        // A plain shell on the same server watches the tmux session; short commands, as long typed lines lose keys
        // on the emulator.
        backToHome()
        val check = openTerminal(E2e.save(E2e.host("强制删除检查")))
        shellReady(check)
        E2e.run(compose, "tmux has -t =$name && echo ALIVE_$((6*7))")
        E2e.waitOutput(compose, check, "ALIVE_42")
        // A deliberate disconnect leaves the card, which then offers 强制删除.
        compose.runOnUiThread { live(tmux.id).disconnect() }
        compose.waitUntil(10_000) { !live(tmux.id).state.value.active }
        backToHome()
        sessionsTab()
        E2e.waitTag(compose, "home-session-${tmux.id}")
        compose.onNodeWithTag("home-session-${tmux.id}").performTouchInput { longClick() }
        click("home-session-forget-${tmux.id}")
        E2e.capture(compose, "f23-confirm")
        click("home-session-forget-confirm-${tmux.id}")
        E2e.waitGone(compose, hasTestTag("home-session-${tmux.id}"))
        assertTrue(service.sessions.value.none { it.id == tmux.id })
        // The session ends over a login of its own after the card went, so give the server a moment.
        click("home-session-${check.id}")
        E2e.waitTag(compose, "terminal")
        val gone = (1..10).any {
            E2e.run(compose, "tmux has -t =$name || echo GONE_$((6*$it))")
            runCatching { E2e.waitOutput(compose, check, "GONE_${6 * it}", timeoutMs = 3_000) }.isSuccess
        }
        assertTrue("tmux session $name still runs", gone)
    }

    private companion object {
        val FAILURES = setOf(Phase.AUTH_FAILED, Phase.HOST_KEY_CHANGED, Phase.FAILED, Phase.TMUX_MISSING)
    }
}
