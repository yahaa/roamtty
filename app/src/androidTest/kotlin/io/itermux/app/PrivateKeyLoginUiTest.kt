package io.itermux.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.ssh.AuthMethod
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A private key without a passphrase logs in like a saved password: files and plugins open straight away, with no
 * passphrase dialog. The editor's key row keeps its title readable in English.
 */
@RunWith(AndroidJUnit4::class)
class PrivateKeyLoginUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as ItermuxApp

    private fun wake() {
        instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        compose.waitUntil(10_000) { runCatching { compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
    }

    /** The whole screen, so dialogs and sheets in their own windows show too. */
    private fun capture(name: String) {
        compose.waitForIdle()
        File(app.getExternalFilesDir(null), "$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    @Test fun plainKeyOpensFilesAndPluginsWithoutAsking() = runBlocking<Unit> {
        wake()
        val id = "host-plain-key"
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        // The fixture's authorized test_ed25519 with its passphrase taken off; :22228 is the OpenSSH fixture, with SFTP and ~/notes.
        val key = instrumentation.context.assets.open("test_ed25519_plain").use { it.readBytes() }
        app.database.hosts().save(Host(id = id, name = "Plain key box", address = "127.0.0.1", port = 22228,
            username = "itermux_test", auth = AuthMethod.KEY.name, mode = "SHELL",
            encryptedKey = app.vault.encrypt(key, "$id:key"), keyLabel = "plain_ed25519"))
        val asked = hasText("私钥口令（可留空）")
        val trust = hasText("信任并连接")
        // Waits for [done], trusting the host key whenever it is asked. The tap happens outside waitUntil's condition:
        // a click from inside it can land while Compose is measuring, which throws.
        fun awaitTrusting(timeout: Long, done: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeout
            while (true) {
                compose.waitUntil((deadline - System.currentTimeMillis()).coerceAtLeast(1)) { exists(trust) || done() }
                if (!exists(trust)) return
                compose.onAllNodes(trust)[0].performClick()
                compose.waitForIdle()
            }
        }
        try {
            compose.onNodeWithTag("home-tab-hosts").performClick()
            compose.waitUntil(8000) { exists(hasTestTag("host-card-$id")) }
            compose.onNodeWithTag("host-card-$id").performClick()
            compose.waitUntil(8000) { exists(hasTestTag("host-detail-more")) }

            compose.onNodeWithTag("host-detail-more").performClick()
            compose.onNodeWithTag("host-detail-plugins").performClick()
            awaitTrusting(8000) { assertTrue(!exists(asked)); exists(hasTestTag("host-plugins-sheet")) }
            awaitTrusting(20_000) { exists(hasText("tmux", substring = true)) }
            assertTrue(!exists(asked))
            capture("plain-key-plugins")
            androidx.test.espresso.Espresso.pressBack()
            compose.waitUntil(8000) { !exists(hasTestTag("host-plugins-sheet")) }

            compose.onNodeWithTag("host-detail-files").performClick()
            awaitTrusting(20_000) { assertTrue(!exists(asked)); exists(hasTestTag("remote-file-browser")) }
            compose.waitUntil(20_000) { exists(hasText("notes")) }
            capture("plain-key-files")
        } finally {
            val service = (serviceRule.bindService(Intent(instrumentation.targetContext, SessionService::class.java)) as SessionService.LocalBinder).service
            service.sessions.value.filter { it.host.id == id }.forEach { service.close(it.id); service.forget(it.id) }
            app.database.hosts().delete(id)
        }
    }

    @Test fun englishKeyRowKeepsItsTitleOnOneLine() {
        wake()
        val prefs = app.getSharedPreferences("app-settings", Context.MODE_PRIVATE)
        val previous = prefs.getString("language", null)
        compose.runOnUiThread { AppLocale.set(app, AppLanguage.ENGLISH) }
        try {
            compose.onNodeWithTag("home-tab-hosts").performClick()
            compose.onNodeWithTag("add-host").performClick()
            compose.onNodeWithText("Private key").performClick()
            val title = compose.onNodeWithText("No private key imported", useUnmergedTree = true)
            title.assertExists()
            // One line of bodyLarge text: before, the long button squeezed it to one letter per line.
            val bounds = title.fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            assertTrue("title is ${bounds.height / density}dp tall", bounds.height / density < 32f)
            assertTrue("title is ${bounds.width / density}dp wide", bounds.width / density > 150f)
            compose.onNodeWithText("Import").assertExists()
            capture("key-row-english")
            compose.onNodeWithText("Cancel").performClick()
        } finally {
            compose.runOnUiThread {
                AppLocale.set(app, AppLanguage.from(previous))
                if (previous == null) prefs.edit().remove("language").commit()
            }
        }
    }
}
