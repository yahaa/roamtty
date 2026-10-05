package io.itermux.app

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Against a loopback sshd whose account has the AI reminder plugin installed (pass its port as
 * notifyPort): a hook run in the session raises a notification for it, and the resolved hook
 * withdraws it.
 */
@RunWith(AndroidJUnit4::class)
class AgentNotifyDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun hookEventsRaiseAndWithdrawTheSessionReminder() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val key = File(context.filesDir, "tmux_client_key")
        assumeTrue("Push the isolated test key into the test app first", key.exists())
        val username = InstrumentationRegistry.getArguments().getString("tmuxUser")
        val port = InstrumentationRegistry.getArguments().getString("notifyPort")?.toIntOrNull()
        assumeTrue("Pass tmuxUser and notifyPort", !username.isNullOrBlank() && port != null)
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName,
            android.Manifest.permission.POST_NOTIFICATIONS)
        val app = context.applicationContext as ItermuxApp
        app.database.hosts().forgetPin("[127.0.0.1]:$port")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val host = Host(name = "AI 提醒测试", address = "127.0.0.1", port = port!!, username = username!!,
            auth = AuthMethod.KEY.name, mode = SessionMode.SHELL.name)
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials(privateKey = key.readBytes())) }
        val prompts = launch {
            session.pending.collect { request ->
                request?.answer?.complete(if (request.prompt is Prompt.VerifyHost) listOf("trust") else null)
            }
        }
        val notifications = context.getSystemService(NotificationManager::class.java)
        fun reminder() = notifications.activeNotifications.firstOrNull { it.tag == session.id }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) {
                session.state.first { it.phase == Phase.CONNECTED || !it.active }
            }.phase)
            withTimeout(15_000) { while (!withContext(Dispatchers.Main) { session.followsAgentEvents }) delay(100) }
            // Give the follower a moment to attach before the event is written.
            delay(1_500)
            withContext(Dispatchers.Main) {
                session.write("printf '%s' '{\"session_id\":\"dev-test\",\"hook_event_name\":\"Notification\"," +
                    "\"notification_type\":\"permission_prompt\",\"message\":\"Allow Bash: make test\"}' | ~/.roamtty/bin/roamtty-notify attention\r")
            }
            val shown = withTimeout(15_000) { var found = reminder(); while (found == null) { delay(200); found = reminder() }; found }
            val text = shown.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
            assertTrue(text, "Allow Bash: make test" in text && "Claude" in text)
            assertEquals("agent-attention", shown.notification.channelId)

            withContext(Dispatchers.Main) {
                session.write("printf '%s' '{\"session_id\":\"dev-test\",\"hook_event_name\":\"Stop\"}' | ~/.roamtty/bin/roamtty-notify resolved\r")
            }
            withTimeout(15_000) { while (reminder() != null) delay(200) }
        } finally {
            prompts.cancel()
            withContext(Dispatchers.Main) { service.close(session.id); service.forget(session.id) }
        }
    }
}
