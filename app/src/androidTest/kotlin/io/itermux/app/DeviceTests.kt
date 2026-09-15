package io.itermux.app

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.*
import io.itermux.app.session.*
import io.itermux.ssh.*
import com.termux.view.TerminalView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceCryptoSshTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture(name: String) = instrumentation.context.assets.open(name).use { it.readBytes() }
    private class Pins : PinStore {
        var value: HostPin? = null
        override suspend fun get(endpoint: String) = value
        override suspend fun putIfAbsent(pin: HostPin): HostPin { if (value == null) value = pin; return value!! }
    }
    @Test fun keystoreRoundTripRejectsTamperingAndWrongHost() {
        val vault = SecretVault(); val bytes = "私钥与密码".toByteArray()
        val a = vault.encrypt(bytes, "test-a:key"); val b = vault.encrypt(bytes, "test-a:key")
        assertFalse(a.contentEquals(b)); assertArrayEquals(bytes, vault.decrypt(a, "test-a:key"))
        assertThrows(Exception::class.java) { vault.decrypt(a, "test-b:key") }
        a[a.lastIndex] = (a.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { vault.decrypt(a, "test-a:key") }
    }
    @Test fun hostMetadataAndPinsSurviveDatabaseReopen() = runBlocking {
        val name = "test-${UUID.randomUUID()}.db"
        val host = Host(name = "测试工作站", address = "::1", username = "dev")
        val first = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        first.hosts().save(host)
        RoomPinStore(first).putIfAbsent(HostPin("[::1]:22", "ssh-ed25519", "YWJj"))
        first.close()
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        assertEquals("测试工作站", reopened.hosts().find(host.id)?.name)
        assertEquals("YWJj", reopened.hosts().pin("[::1]:22")?.key)
        reopened.close(); context.deleteDatabase(name); Unit
    }
    private fun authenticate(auth: AuthMethod, key: String = "ed25519") = runBlocking {
        val password = fixture("test_password").toString(Charsets.UTF_8).trim()
        val credentials = if (auth == AuthMethod.KEY) Credentials(privateKey = fixture("test_$key"), passphrase = "itermux-test-passphrase".toByteArray()) else Credentials(password.toByteArray())
        val ssh = JschSession(Target("127.0.0.1", 22228, "itermux_test", auth, SessionMode.SHELL), credentials, Pins(), { prompt ->
            when (prompt) { is Prompt.VerifyHost -> listOf("trust"); is Prompt.Keyboard -> prompt.labels.map { password } }
        })
        val output = StringBuffer()
        val job = launch { ssh.connect { output.append(it.toString(Charsets.UTF_8)) } }
        try {
            val state = withTimeout(20_000) { ssh.state.first { it.phase in listOf(Phase.CONNECTED, Phase.FAILED, Phase.AUTH_FAILED) } }
            assertEquals(state.message, Phase.CONNECTED, state.phase)
            ssh.write("printf '\\nANDROID_%s\\n' 'SSH_OK'\r".toByteArray())
            withTimeout(8000) { while (!output.contains("ANDROID_SSH_OK")) delay(30) }
        } finally { ssh.disconnect(); withTimeout(5000) { job.join() } }
    }
    @Test fun passwordOverRealOpenSsh() = authenticate(AuthMethod.PASSWORD)
    @Test fun interactiveOverRealOpenSsh() = authenticate(AuthMethod.INTERACTIVE)
    @Test fun encryptedEd25519OverRealOpenSsh() = authenticate(AuthMethod.KEY)
    @Test fun encryptedRsaOverRealOpenSsh() = authenticate(AuthMethod.KEY, "rsa")
}

@RunWith(AndroidJUnit4::class)
class TerminalUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }
    private fun foreground() {
        shell("am start -W --activity-single-top -n io.itermux.app/.MainActivity")
        compose.waitUntil(10_000) { runCatching { compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @Before fun keepTestVisible() = foreground()
    private fun capture(name: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun terminal(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) terminal(view.getChildAt(i))?.let { return it }
        return null
    }
    @Test fun chineseCompositionTerminalEditingAndActivityRecreation() = runBlocking {
        val context = instrumentation.targetContext
        // Only the dedicated loopback test endpoint is reset after recreating its temporary server.
        (context.applicationContext as ItermuxApp).database.hosts().forgetPin("[127.0.0.1]:22228")
        // Notification permission is optional for FGS startup. OEMs may reject shell grants.
        val binder = serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder
        val service = binder.service
        val password = instrumentation.context.assets.open("test_password").bufferedReader().use { it.readText().trim() }
        val host = Host(name = "Linux 验证环境", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "SHELL")
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials(password.toByteArray())) }
        val prompts = launch { session.pending.collect { it?.answer?.complete(if (it.prompt is Prompt.VerifyHost) listOf("trust") else listOf(password)) } }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            serviceRule.unbindService()
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            delay(1200)
            assertEquals(Phase.CONNECTED, session.state.value.phase)
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            assertTrue(manager.getRunningServices(20).any { it.service.className == SessionService::class.java.name && it.foreground })
            // Bring the stopped task forward through the shell, like a launcher tap.
            // Some OEMs block ActivityScenario's background app launch on resume.
            foreground()
            compose.waitUntil(10_000) { runCatching { compose.onAllNodesWithText("会话", substring = true).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
            compose.onNodeWithText("会话", substring = true).performClick()
            compose.waitForIdle()
            if (android.os.Build.HARDWARE in listOf("ranchu", "goldfish")) {
                // AndroidView measurement follows Compose idle on the next frame.
                delay(300)
                val columns = withContext(Dispatchers.Main) { session.getEmulator().mColumns }
                compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                foreground()
                compose.waitUntil(8000) { session.getEmulator().mColumns > columns }
                assertEquals(Phase.CONNECTED, session.state.value.phase)
                val wideColumns = withContext(Dispatchers.Main) { session.getEmulator().mColumns }
                compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                foreground()
                compose.waitUntil(8000) { session.getEmulator().mColumns < wideColumns }
                shell("input keyevent 223")
                delay(1200)
                assertEquals(Phase.CONNECTED, session.state.value.phase)
                shell("input keyevent 224")
                shell("wm dismiss-keyguard")
                foreground()
            }
            withContext(Dispatchers.Main) {
                // Disable secure capture only within this instrumentation test, never in production.
                compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                session.write("stty -echo; cat\r")
            }
            delay(500)
            withContext(Dispatchers.Main) {
                val view = requireNotNull(terminal(compose.activity.window.decorView))
                view.requestFocus()
                val connection = view.onCreateInputConnection(EditorInfo())
                connection.setComposingText("zhong", 1)
                connection.deleteSurroundingText(1, 0)
                connection.setComposingText("中文输入", 1)
            }
            delay(250)
            assertFalse(withContext(Dispatchers.Main) { session.getEmulator().screen.transcriptText }.contains("zhong"))
            withContext(Dispatchers.Main) {
                val view = requireNotNull(terminal(compose.activity.window.decorView))
                // Use one persistent input connection to model an actual IME sequence.
                val ic = view.onCreateInputConnection(EditorInfo())
                ic.setComposingText("中文输入", 1)
                ic.commitText("中文输入", 1)
                ic.finishComposingText()
                ic.commitText("\n", 1)
            }
            withTimeout(8000) { while (!withContext(Dispatchers.Main) { session.getEmulator().screen.transcriptText }.contains("中文输入")) delay(30) }
            withContext(Dispatchers.Main) { session.write("\u0003"); session.write("stty echo\r") }
            compose.onNodeWithTag("terminal-menu").performClick()
            compose.onNodeWithText("编辑文本").performClick()
            compose.onNodeWithTag("prompt-editor").performTextInput("请帮我检查这段代码\n保留现有行为")
            compose.onNodeWithText("发送文本").assertIsEnabled()
            capture("terminal-editor")
            compose.activityRule.scenario.recreate()
            foreground()
            compose.waitForIdle()
            assertEquals(Phase.CONNECTED, session.state.value.phase)
            assertTrue(service.sessions.value.any { it.id == session.id })
            withContext(Dispatchers.Main) { session.write("printf '\\nRECREATE_%s\\n' 'OK'\r") }
            withTimeout(8000) { while (!withContext(Dispatchers.Main) { session.getEmulator().screen.transcriptText }.contains("RECREATE_OK")) delay(30) }
            withContext(Dispatchers.Main) { session.write("/tmp/itermux-ssh-test/codex --version\r") }
            withTimeout(8000) { while (!withContext(Dispatchers.Main) { session.getEmulator().screen.transcriptText }.contains("codex-cli 0.154.0")) delay(30) }
            withContext(Dispatchers.Main) { session.write("/tmp/itermux-ssh-test/codex --no-alt-screen\r") }
            withTimeout(15_000) {
                while (true) {
                    val text = withContext(Dispatchers.Main) { session.getEmulator().screen.transcriptText }
                    if (text.contains("Welcome to Codex") || text.contains("Sign in with") || text.contains("Choose your theme")) break
                    delay(50)
                }
            }
            withContext(Dispatchers.Main) {
                compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                val view = requireNotNull(terminal(compose.activity.window.decorView))
                view.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            }
            compose.onNodeWithText("收起", substring = false).performClick()
            compose.waitForIdle(); delay(500); capture("codex-terminal")
            withContext(Dispatchers.Main) { session.write("\u0003\u0003") }
            // Text committed by the IME's voice feature must only stage a draft.
            val voiceText = "语音测试 ${UUID.randomUUID().toString().take(6)}"
            compose.onNodeWithText("语音", substring = false).performClick()
            compose.waitUntil(8000) { compose.onAllNodesWithContentDescription("收起键盘").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("prompt-editor").performTextInput(voiceText)
            compose.onNodeWithTag("prompt-editor").assertTextContains(voiceText, substring = true)
            assertFalse(withContext(Dispatchers.Main) { session.getEmulator().screen.transcriptText }.contains(voiceText))
            compose.onNodeWithText("收起", substring = false).performClick()
            compose.waitUntil(8000) { compose.onAllNodesWithContentDescription("键盘", substring = false).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("terminal").performTouchInput { click() }
            compose.waitForIdle()
            assertFalse("Keyboard must be hidden after speech", withContext(Dispatchers.Main) {
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            })
            val terminalBounds = compose.onNodeWithTag("terminal").fetchSemanticsNode().boundsInRoot
            val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("Terminal should occupy at least 80% of the connected screen", terminalBounds.height / rootBounds.height > 0.8f)
        } finally { withContext(Dispatchers.Main) { service.close(session.id) }; prompts.cancel() }
    }
    @Test fun hostEditorCreatesAndSearchesHost() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as ItermuxApp
        // Remove only this test's uniquely shaped leftovers from an interrupted run.
        app.database.hosts().observe().first().filter {
            it.name.matches(Regex("UI 测试 [0-9a-f]{6}")) && it.address == "dev.example.com" && it.username == "developer"
        }.forEach { app.database.hosts().delete(it.id) }
        val label = "UI 测试 ${UUID.randomUUID().toString().take(6)}"
        compose.onNodeWithText("搜索名称、地址或用户名").performTextClearance()
        compose.onNodeWithText("＋ 主机").performClick()
        compose.onNodeWithText("名称").performTextInput(label)
        compose.onNodeWithText("地址 / IP").performTextInput("dev.example.com")
        compose.onNodeWithText("用户名").performTextInput("developer")
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.waitUntil(8000) { compose.onAllNodesWithText("添加主机").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("搜索名称、地址或用户名").performTextInput(label)
        compose.waitUntil(8000) { compose.onAllNodes(hasText(label) and hasSetTextAction().not()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(label) and hasSetTextAction().not()).assertIsDisplayed()
        compose.activity.runOnUiThread { compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        compose.activity.runOnUiThread { (compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0) }
        compose.waitForIdle()
        capture("hosts")
        val saved = app.database.hosts().observe().first().first { it.name == label }
        assertNull(saved.encryptedPassword)
        app.database.hosts().delete(saved.id)
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "host-count.txt").writeText(app.database.hosts().observe().first().size.toString())
    }
}
