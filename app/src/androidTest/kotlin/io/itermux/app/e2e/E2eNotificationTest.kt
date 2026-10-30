package io.itermux.app.e2e

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.itermux.app.AppLanguage
import io.itermux.app.AppLocale
import io.itermux.app.MainActivity
import io.itermux.app.e2e.E2e.app
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.SessionService
import io.itermux.ssh.Credentials
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Agent notifications as a user meets them: the app in the background, a banner over another app, a tap in the shade.
 * The activity is started and finished by hand: ActivityScenario loses an activity that the system sent to the background
 * and that then took a new intent, and cannot close it.
 */
@RunWith(AndroidJUnit4::class)
class E2eNotificationTest {
    @get:Rule val serviceRule = ServiceTestRule()
    private lateinit var service: SessionService
    private val shell get() = E2e.instrumentation.uiAutomation

    private fun sh(command: String) = ParcelFileDescriptor.AutoCloseInputStream(shell.executeShellCommand(command))
        .use { String(it.readBytes()) }

    private fun activities(stage: Stage): List<Activity> {
        var found = emptyList<Activity>()
        E2e.instrumentation.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).toList()
        }
        return found
    }

    private fun waitFor(what: String, timeoutMs: Long, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(200)
        }
    }

    @Before fun setUp() {
        E2e.requireFixture()
        AppLocale.set(app, AppLanguage.ZH_CN)
        service = E2e.bind(serviceRule)
        E2e.cleanup(service)
        runBlocking { E2e.trustFixture() }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("the app", 10_000) { activities(Stage.RESUMED).isNotEmpty() }
    }

    @After fun tearDown() {
        if (!E2e.enabled) return
        E2e.cleanup(service)
        val open = listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).flatMap(::activities)
        E2e.instrumentation.runOnMainSync { open.forEach(Activity::finish) }
        waitFor("the app to close", 10_000) { listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).all { activities(it).isEmpty() } }
    }

    /**
     * With the app in the background, an agent that starts waiting pops up a banner; tapping the notification in the
     * shade brings the same app back on that session, which takes the notification away.
     */
    @Test fun n01_backgroundBannerAndTapOpensTheSession() = runBlocking<Unit> {
        val host = E2e.save(E2e.host("后台通知", mode = SessionMode.TMUX))
        val hook = "sh ~/.roamtty/agent-hook.sh claude"
        // It asks only once the app is in the background.
        val session = withContext(Dispatchers.Main) {
            service.open(host, SessionMode.TMUX, Credentials(E2e.password.toByteArray())).also {
                it.runWhenConnected("sleep 15; printf '{\"prompt\":\"deploy\"}' | $hook prompt; " +
                    "printf '{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"git push\"}}' | $hook tool; " +
                    "printf '{\"notification_type\":\"permission_prompt\",\"message\":\"Claude needs your permission\"}' | $hook notify; sleep 600",
                    agentHooks = CodingAgent.CLAUDE)
            }
        }
        assertEquals(Phase.CONNECTED, E2e.connected(session))
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        fun posted() = manager.activeNotifications.firstOrNull { it.tag == session.id }
        val original = activities(Stage.RESUMED).single()
        sh("input keyevent KEYCODE_HOME")
        waitFor("the app to leave the screen", 10_000) { activities(Stage.STOPPED).isNotEmpty() }
        waitFor("the notification", 60_000) { posted() != null }
        // The banner slides in after it is posted.
        Thread.sleep(1_000)
        E2e.screenshot("n01-banner")
        // Tap it in the shade, where the user finds it. SystemUI's own text search misses it; walk the tree.
        sh("cmd statusbar expand-notifications")
        Thread.sleep(1_500)
        E2e.screenshot("n01-shade")
        shell.serviceInfo = shell.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        Thread.sleep(500)
        fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).mapNotNull(node::getChild).flatMap(::nodes)
        val all = (shell.windows.mapNotNull { it.root } + listOfNotNull(shell.rootInActiveWindow)).flatMap(::nodes)
        val title = all.firstOrNull { it.text?.toString() == "Claude 等你确认" }
        assertNotNull("the notification is not in the shade: ${all.mapNotNull { it.text }}", title)
        val bounds = Rect().also { title!!.getBoundsInScreen(it) }
        sh("input tap ${bounds.centerX()} ${bounds.centerY()}")
        // The same activity comes back, not a second copy; the notification goes only once its session is on screen.
        waitFor("the app back on screen", 10_000) { activities(Stage.RESUMED).isNotEmpty() }
        assertEquals(listOf(original), activities(Stage.RESUMED))
        waitFor("the notification to go as its session shows", 10_000) { posted() == null }
        Thread.sleep(500)
        E2e.screenshot("n01-opened-session")
    }
}
