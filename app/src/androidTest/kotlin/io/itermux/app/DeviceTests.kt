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
    private val previousEmulatorSettings = mutableMapOf<String, String>()
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim()
    }
    private fun foreground() {
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        // Keep the ActivityScenario-owned instance. A shell launch can replace its intent/instance,
        // leaving the rule waiting for a lifecycle transition from an Activity it no longer owns.
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { runCatching { compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @Before fun keepTestVisible() {
        if (android.os.Build.HARDWARE in listOf("ranchu", "goldfish")) {
            // The system's first-use fullscreen lesson takes focus away from the editor.
            mapOf("show_ime_with_hard_keyboard" to "1", "immersive_mode_confirmations" to "confirmed").forEach { (key, value) ->
                previousEmulatorSettings[key] = shell("settings get secure $key")
                shell("settings put secure $key $value")
            }
        }
        foreground()
    }
    @After fun restoreEmulatorSettings() {
        previousEmulatorSettings.forEach { (key, value) ->
            shell(if (value.isBlank() || value == "null") "settings delete secure $key" else "settings put secure $key $value")
        }
    }
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
    private fun showControls() {
        if (compose.onAllNodesWithTag("restore-dock").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("restore-dock").performClick()
        }
        compose.waitUntil(2000) { compose.onAllNodesWithTag("terminal-menu").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun inputButtonsAndDraggableHistory() = runBlocking {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        foreground()
        val context = instrumentation.targetContext
        val app = context.applicationContext as ItermuxApp
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val password = instrumentation.context.assets.open("test_password").bufferedReader().use { it.readText().trim() }
        val host = Host(name = "输入与滚动验证", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "SHELL")
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials(password.toByteArray())) }
        val prompts = launch { session.pending.collect { it?.answer?.complete(if (it.prompt is Prompt.VerifyHost) listOf("trust") else listOf(password)) } }
        suspend fun write(text: String) = withContext(Dispatchers.Main) { session.write(text) }
        suspend fun outputContains(text: String) {
            withTimeout(8000) {
                while (!withContext(Dispatchers.Main) { session.getEmulator().screen.transcriptText.contains(text) }) delay(40)
            }
        }
        // Arrow keys as the cursor-row keys send them, after the composing text.
        suspend fun arrow(keyCode: Int) = withContext(Dispatchers.Main) {
            requireNotNull(terminal(compose.activity.window.decorView)).sendInputControlKey(keyCode)
        }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            compose.openSession(session.id)
            compose.waitUntil(8000) { compose.onAllNodesWithTag("terminal").fetchSemanticsNodes().isNotEmpty() }
            showControls()
            write("IFS= read -er value; printf '\\nNAV_RESULT<%s>\\n' \"${'$'}value\"\r")
            delay(400)
            withContext(Dispatchers.Main) {
                requireNotNull(terminal(compose.activity.window.decorView)).onCreateInputConnection(EditorInfo())!!.setComposingText("ABCD", 1)
            }
            delay(200)
            arrow(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            arrow(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            write("x")
            arrow(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            write("y")
            write("\r")
            outputContains("NAV_RESULT<ABxCyD>")

            compose.onNodeWithTag("terminal-menu").performClick()
            compose.onNodeWithText("编辑文本").performClick()
            compose.onNodeWithTag("prompt-editor").performClick().performTextInput("甲🙂乙")
            compose.onNodeWithTag("draft-left").performTouchInput { click() }
            compose.onNodeWithTag("draft-right").performTouchInput { click() }
            compose.onNodeWithTag("draft-delete").performTouchInput { click() }
            compose.onNodeWithTag("prompt-editor").assertTextContains("甲🙂", substring = false)
            compose.onNodeWithTag("draft-left").performTouchInput { click() }
            compose.onNodeWithTag("draft-delete").performTouchInput { click() }
            compose.onNodeWithTag("prompt-editor").assertTextContains("🙂", substring = false)
            compose.onNodeWithTag("draft-delete").performTouchInput { longClick() }
            compose.onNodeWithTag("prompt-editor").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            assertEquals("", session.draft)
            // Combining marks and emoji sequences are single editing units.
            val complex = "甲e\u0301👨‍👩‍👧‍👦"
            val afterEmoji = io.itermux.app.ui.deleteDraftCharacter(androidx.compose.ui.text.input.TextFieldValue(complex, androidx.compose.ui.text.TextRange(complex.length)))
            assertEquals("甲e\u0301", afterEmoji.text)
            assertEquals("甲", io.itermux.app.ui.deleteDraftCharacter(afterEmoji).text)
            compose.onNodeWithTag("prompt-editor").performTextInput("草稿只留在本地")
            compose.onNodeWithText("发送文本").assertIsDisplayed()
            capture("input-controls-portrait-editor")
            compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            foreground()
            compose.waitUntil(8000) { compose.onRoot().fetchSemanticsNode().boundsInRoot.let { it.width > it.height } }
            compose.onNodeWithTag("prompt-editor").assertTextContains("草稿只留在本地")
            compose.onNodeWithTag("draft-left").assertIsDisplayed()
            compose.onNodeWithTag("draft-delete").assertIsDisplayed()
            compose.onNodeWithText("发送文本").assertIsDisplayed()
            capture("input-controls-landscape-editor")
            assertFalse(withContext(Dispatchers.Main) { session.getEmulator().screen.transcriptText.contains("草稿只留在本地") })
            compose.onNodeWithText("收起", substring = false).performClick()
            compose.waitUntil(8000) { keyboardHidden() }

            write("for i in $(seq 1 160); do printf 'HISTORY %03d\\n' \"${'$'}i\"; done\r")
            outputContains("HISTORY 160")
            val view = withContext(Dispatchers.Main) { requireNotNull(terminal(compose.activity.window.decorView)) }
            val density = context.resources.displayMetrics.density
            compose.onNodeWithTag("terminal").performTouchInput {
                down(androidx.compose.ui.geometry.Offset(width / 2f, 24 * density))
                moveTo(androidx.compose.ui.geometry.Offset(width / 2f, height - 24 * density), delayMillis = 400)
                up()
            }
            compose.waitForIdle()
            assertTrue(withContext(Dispatchers.Main) { view.topRow < -20 })
            fun edgePixel(): Int {
                val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.BLACK)
                view.draw(android.graphics.Canvas(bitmap))
                return bitmap.getPixel((view.width - 6 * density).toInt(), (16 * density).toInt()).also { bitmap.recycle() }
            }
            assertEquals("Reading history draws no scrollbar", 0xFF252728.toInt(), withContext(Dispatchers.Main) { edgePixel() })
            val before = withContext(Dispatchers.Main) { session.getEmulator().screen.getSelectedText(0, view.topRow, 12, view.topRow) }
            write("printf 'NEW_OUTPUT\\n'\r")
            outputContains("NEW_OUTPUT")
            delay(300)
            assertEquals("New output must preserve the history being read", before,
                withContext(Dispatchers.Main) { session.getEmulator().screen.getSelectedText(0, view.topRow, 12, view.topRow) })
            withContext(Dispatchers.Main) { view.scrollToBottom() }
            assertEquals(0, withContext(Dispatchers.Main) { view.topRow })
            capture("input-controls-landscape-terminal")
        } catch (failure: Throwable) {
            java.io.File(context.getExternalFilesDir(null), "input-controls-failure.txt").writeText(compose.onRoot().printToString())
            capture("input-controls-failure")
            throw failure
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            withContext(Dispatchers.Main) { service.close(session.id) }
            prompts.cancel()
        }
    }
    private fun keyboardHidden() = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false

    @Test fun imeBackspaceHoldClearsCurrentLine() = verifyImeDeletion(false)

    // Run explicitly while the external driver holds the real IME key; never touches a user server.
    @Test fun physicalImeBackspaceHoldClearsCurrentLine() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("physicalIme") == "true")
        verifyImeDeletion(true)
    }

    private fun verifyImeDeletion(physical: Boolean) = runBlocking {
        val context = instrumentation.targetContext
        (context.applicationContext as ItermuxApp).database.hosts().forgetPin("[127.0.0.1]:22228")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val password = instrumentation.context.assets.open("test_password").bufferedReader().use { it.readText().trim() }
        val host = Host(name = "输入法长按验证", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "SHELL")
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials(password.toByteArray())) }
        val prompts = launch { session.pending.collect { it?.answer?.complete(if (it.prompt is Prompt.VerifyHost) listOf("trust") else listOf(password)) } }
        suspend fun write(text: String) = withContext(Dispatchers.Main) { session.write(text) }
        suspend fun outputContains(text: String) = withTimeout(8000) {
            while (!withContext(Dispatchers.Main) { session.getEmulator().screen.transcriptText.contains(text) }) delay(40)
        }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            compose.openSession(session.id)
            compose.waitUntil(8000) { compose.onAllNodesWithTag("terminal").fetchSemanticsNodes().isNotEmpty() }
            val view = withContext(Dispatchers.Main) { requireNotNull(terminal(compose.activity.window.decorView)) }
            compose.tapCursorLine()
            compose.waitUntil(8000) { !keyboardHidden() }
            compose.onNodeWithTag("terminal-dock").assertDoesNotExist()
            write("printf 'HISTORY_%s\\n' 'KEPT'\r")
            outputContains("HISTORY_KEPT")
            for (protocol in if (physical) listOf("TOUCH") else listOf("KEY", "UTF16", "CODEPOINT")) {
                write("IFS= read -erp 'HOLD> ' value; printf '\\n${protocol}_RESULT<%s>\\n' \"${'$'}value\"\r")
                delay(400)
                val ic = if (physical) null else withContext(Dispatchers.Main) { view.onCreateInputConnection(EditorInfo()) }
                if (!physical) {
                    withContext(Dispatchers.Main) { ic!!.commitText("甲🙂 乙", 1) }
                    delay(250)
                    withContext(Dispatchers.Main) {
                        val text = ic!!.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                        assertTrue(text.text.isBlank())
                        assertEquals(text.text.length, text.selectionStart)
                        assertTrue(ic.getTextBeforeCursor(20, 0)!!.isBlank())
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                    }
                    delay(350)
                    write("\r")
                    outputContains("${protocol}_RESULT<甲🙂 >")
                    write("IFS= read -erp 'HOLD> ' value; printf '\\n${protocol}_CLEAR<%s>\\n' \"${'$'}value\"\r")
                    delay(400)
                }
                write("x".repeat(60) + "RIGHT")
                delay(300)
                withContext(Dispatchers.Main) { repeat(5) { view.sendInputControlKey(KeyEvent.KEYCODE_DPAD_LEFT) } }
                delay(300)
                if (physical) {
                    capture("ime-hold-ready")
                    java.io.File(context.getExternalFilesDir(null), "ime-hold-state.txt").writeText("READY")
                    withTimeout(60_000) {
                        while (!withContext(Dispatchers.Main) {
                            val em = session.getEmulator()
                            em.screen.getSelectedText(0, em.cursorRow, em.mColumns, em.cursorRow).trimEnd() == "HOLD>"
                        }) delay(100)
                    }
                    // Let the driver release the key before verifying the next input.
                    delay(1500)
                } else {
                    withContext(Dispatchers.Main) {
                        assertTrue("Remote text must not become an IME editing buffer", ic!!.getTextBeforeCursor(100, 0)!!.isBlank())
                    }
                    repeat(44) {
                        withContext(Dispatchers.Main) {
                            when (protocol) {
                                "KEY" -> {
                                    ic!!.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                                }
                                "UTF16" -> ic!!.deleteSurroundingText(1, 0)
                                else -> ic!!.deleteSurroundingTextInCodePoints(1, 0)
                            }
                        }
                        // Auto-repeat cadence; slower deletes are treated as taps.
                        delay(50)
                    }
                    // New input immediately after a hold must release the suppression latch.
                    withContext(Dispatchers.Main) {
                        ic!!.commitText("Q", 1)
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                    }
                    delay(200)
                }
                write("KEPT\r")
                outputContains("${protocol}_${if (physical) "RESULT" else "CLEAR"}<KEPT>")
                outputContains("HISTORY_KEPT")
                if (physical) {
                    capture("ime-hold-passed")
                    java.io.File(context.getExternalFilesDir(null), "ime-hold-state.txt").writeText("PASSED")
                }
            }
        } finally {
            withContext(Dispatchers.Main) { service.close(session.id) }
            prompts.cancel()
        }
    }

    @Test fun keyboardButtonRepeatedlyTogglesNativeTerminal() = runBlocking {
        val context = instrumentation.targetContext
        (context.applicationContext as ItermuxApp).database.hosts().forgetPin("[127.0.0.1]:22228")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val password = instrumentation.context.assets.open("test_password").bufferedReader().use { it.readText().trim() }
        val host = Host(name = "键盘交互验证", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "SHELL")
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials(password.toByteArray())) }
        val prompts = launch { session.pending.collect { it?.answer?.complete(if (it.prompt is Prompt.VerifyHost) listOf("trust") else listOf(password)) } }
        fun keyboardVisible() = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            compose.openSession(session.id)
            compose.waitUntil(8000) { compose.onAllNodesWithTag("terminal").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("terminal-dock").assertDoesNotExist()
            compose.onNodeWithTag("restore-dock").assertIsDisplayed()
            compose.waitUntil(8000) { !keyboardVisible() }
            delay(500)
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val terminalBounds = compose.onNodeWithTag("terminal").fetchSemanticsNode().boundsInRoot
            val windowInsets = requireNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
            val caption = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.captionBar())
            val cutout = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val measurements = "root=$root, terminal=$terminalBounds, caption=$caption, cutout=$cutout"
            java.io.File(context.getExternalFilesDir(null), "fullscreen-measurements.txt").writeText(measurements)
            assertEquals("No blank band above the terminal: $measurements", caption.top.toFloat(), terminalBounds.top, 2f)
            assertEquals("No blank band beside the terminal: $measurements", caption.left.toFloat(), terminalBounds.left, 2f)
            assertEquals("Terminal uses the full available height: $measurements", root.height - caption.top - caption.bottom, terminalBounds.height, 2f)
            compose.tapCursorLine()
            compose.waitUntil(8000) { keyboardVisible() }
            compose.onNodeWithTag("terminal-dock").assertDoesNotExist()
            shell("input keyevent 4")
            compose.waitUntil(8000) { !keyboardVisible() }
            showControls()
            repeat(3) {
                compose.onNodeWithTag("keyboard-toggle").assertDoesNotExist()
                compose.tapCursorLine()
                compose.waitUntil(8000) { keyboardVisible() }
                compose.onNodeWithTag("terminal-dock").assertDoesNotExist()
                shell("input keyevent 4")
                compose.waitUntil(8000) { !keyboardVisible() }
                showControls()
                compose.onNodeWithContentDescription("终端设置").assertIsDisplayed()
            }
        } finally {
            withContext(Dispatchers.Main) { service.close(session.id) }
            prompts.cancel()
        }
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
            compose.openSession(session.id)
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
            showControls()
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
                val view = requireNotNull(terminal(compose.activity.window.decorView))
                view.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            }
            compose.onNodeWithText("收起", substring = false).performClick()
            compose.waitForIdle(); delay(500); capture("codex-terminal")
            withContext(Dispatchers.Main) { session.write("\u0003\u0003") }
            // Text entered in the editor must only stage a draft.
            val draftText = "草稿测试 ${UUID.randomUUID().toString().take(6)}"
            showControls()
            compose.onNodeWithTag("terminal-menu").performClick()
            compose.onNodeWithText("编辑文本").performClick()
            compose.onNodeWithTag("prompt-editor").assertExists()
            compose.onNodeWithTag("prompt-editor").performClick()
            compose.waitUntil(8000) { androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
            compose.onNodeWithTag("prompt-editor").performTextInput(draftText)
            compose.onNodeWithTag("prompt-editor").assertTextContains(draftText, substring = true)
            assertFalse(withContext(Dispatchers.Main) { session.getEmulator().screen.transcriptText }.contains(draftText))
            compose.onNodeWithText("收起", substring = false).performClick()
            compose.waitUntil(8000) { androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false }
            compose.tapCursorLine()
            compose.waitUntil(8000) { !keyboardHidden() }
            shell("input keyevent 4")
            compose.waitUntil(8000) { keyboardHidden() }
            val terminalBounds = compose.onNodeWithTag("terminal").fetchSemanticsNode().boundsInRoot
            val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("Terminal should occupy at least 80% of the connected screen", terminalBounds.height / rootBounds.height > 0.8f)
        } catch (failure: Throwable) {
            java.io.File(context.getExternalFilesDir(null), "terminal-ui-failure.txt").writeText(compose.onRoot().printToString())
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.getExternalFilesDir(null), "terminal-ui-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            throw failure
        } finally { withContext(Dispatchers.Main) { service.close(session.id) }; prompts.cancel() }
    }
    @Test fun hostEditorCreatesAndSearchesHost() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as ItermuxApp
        // Remove only this test's uniquely shaped leftovers from an interrupted run.
        app.database.hosts().observe().first().filter {
            it.name.matches(Regex("UI 测试 [0-9a-f]{6}")) && it.address == "dev.example.com" && it.username == "developer"
        }.forEach { app.database.hosts().delete(it.id) }
        val label = "UI 测试 ${UUID.randomUUID().toString().take(6)}"
        // Search shows only for a long host list, so pad the list past the threshold.
        val fillers = (1..7).map { Host(name = "UI 测试 ${UUID.randomUUID().toString().take(6)}", address = "dev.example.com", username = "developer") }
        fillers.forEach { app.database.hosts().save(it) }
        compose.onNodeWithTag("host-search").assertDoesNotExist()
        capture("hosts-compact")
        compose.onNodeWithTag("add-host").performClick()
        compose.onNodeWithText("名称").performTextInput(label)
        compose.onNodeWithText("地址 / IP").performTextInput("dev.example.com")
        compose.onNodeWithText("用户名").performTextInput("developer")
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.waitUntil(8000) { compose.onAllNodesWithText("添加主机").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("search-toggle").performClick()
        compose.onNodeWithTag("host-search").performTextInput(label)
        compose.waitUntil(8000) { compose.onAllNodes(hasText(label) and hasSetTextAction().not()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(label) and hasSetTextAction().not()).assertIsDisplayed()
        capture("hosts-search")
        compose.onNodeWithTag("search-toggle").performClick()
        compose.onNodeWithTag("host-search").assertDoesNotExist()
        compose.onNodeWithTag("hosts-title").assertIsDisplayed()
        compose.activity.runOnUiThread { (compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0) }
        compose.waitForIdle()
        capture("hosts")
        val saved = app.database.hosts().observe().first().first { it.name == label }
        assertNull(saved.encryptedPassword)
        app.database.hosts().delete(saved.id)
        fillers.forEach { app.database.hosts().delete(it.id) }
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "host-count.txt").writeText(app.database.hosts().observe().first().size.toString())
    }
}
