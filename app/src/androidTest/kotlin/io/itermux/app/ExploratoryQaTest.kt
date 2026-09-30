package io.itermux.app

import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exploratory regressions, 2026-09-27. Run only on the disposable QA emulator. */
@RunWith(AndroidJUnit4::class)
class ExploratoryQaTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as ItermuxApp
    private val hosts = mutableListOf<Host>()

    @Before fun setup() {
        Assume.assumeTrue("Use the disposable QA emulator", android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
        compose.activityRule.scenario.onActivity {
            AppLocale.set(it, AppLanguage.ZH_CN)
            it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @After fun cleanup() = runBlocking {
        hosts.forEach { app.database.hosts().delete(it.id) }
    }

    private suspend fun addHost(name: String): Host = Host(name = name, address = "127.0.0.1",
        port = 22228, username = "itermux_test", mode = "SHELL").also {
        hosts += it
        app.database.hosts().save(it)
        compose.waitUntil(8_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "qa-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        File(context.getExternalFilesDir(null), "qa-$name-tree.txt").writeText(
            compose.onAllNodes(isRoot()).onFirst().printToString())
    }

    @Test fun collapsingSearchOnSessionsMustNotLeaveHiddenHostFilter() = runBlocking {
        addHost("QA Alpha")
        addHost("QA Beta")
        compose.onNodeWithTag("search-toggle").performClick()
        compose.onNodeWithTag("host-search").performTextInput("Alpha")
        compose.onNodeWithText("QA Beta").assertDoesNotExist()
        compose.onNodeWithTag("sessions-tab").performClick()
        compose.onNodeWithTag("search-toggle").performClick()
        compose.onNodeWithTag("hosts-tab").performClick()
        compose.onNodeWithTag("host-search").assertDoesNotExist()
        capture("hidden-host-filter")
        compose.onNodeWithText("QA Beta").assertIsDisplayed()
        Unit
    }

    @Test fun existingHostDraftSurvivesActivityRecreation() = runBlocking {
        val host = addHost("QA Edit")
        compose.onNodeWithTag("host-menu-${host.id}").performClick()
        compose.onNodeWithText("编辑主机").performClick()
        compose.onNodeWithTag("host-name").performTextReplacement("QA Edited Draft")
        compose.onNodeWithTag("host-address").performTextReplacement("draft.example")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("host-name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("host-name").assertTextContains("QA Edited Draft")
        compose.onNodeWithTag("host-address").assertTextContains("draft.example")
        compose.onNodeWithText("取消").performClick()
        Unit
    }

    @Test fun downloadPathDialogSurvivesActivityRecreation() = runBlocking {
        val host = addHost("QA Transfer Rotation")
        app.database.hosts().forgetPin(host.target().endpoint)
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val session = withContext(Dispatchers.Main) {
            service.open(host, SessionMode.SHELL, Credentials("test-only".toByteArray()))
        }
        val prompts = launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) {
                session.state.first { it.phase == Phase.CONNECTED || !it.active }
            }.phase)
            compose.onNodeWithTag("sessions-tab").performClick()
            compose.onNodeWithTag("session-${session.id}").performClick()
            compose.onNodeWithTag("restore-dock").performClick()
            compose.onNodeWithTag("file-transfer").performClick()
            compose.onNodeWithText("下载文件").performClick()
            compose.onNodeWithTag("download-remote-path").performTextReplacement("/phone-a/important.bin")
            capture("download-dialog-before-recreation")
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            compose.activityRule.scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            capture("download-dialog-after-recreation")
            assertEquals("The SSH session remains connected", Phase.CONNECTED, session.state.value.phase)
            compose.onNodeWithTag("download-remote-path").assertTextContains("/phone-a/important.bin")
        } finally {
            prompts.cancel()
            withContext(Dispatchers.Main) { service.close(session.id) }
        }
        Unit
    }

}

/** Opt-in manual harness without Compose's test clock, so ADB input uses real frames. */
@RunWith(AndroidJUnit4::class)
class InteractiveQaHarness {
    @get:Rule val activity = androidx.test.ext.junit.rules.ActivityScenarioRule(MainActivity::class.java)
    @Test fun inspect() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("qaInteractive") == "true")
        check(android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        activity.scenario.onActivity {
            it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val stop = File(context.getExternalFilesDir(null), "qa-stop")
        stop.delete()
        println("QA_INTERACTIVE_READY")
        val deadline = System.currentTimeMillis() + 15 * 60_000
        while (!stop.exists() && System.currentTimeMillis() < deadline) Thread.sleep(500)
    }
}
