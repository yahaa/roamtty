package io.itermux.app

import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.app.session.TerminalConnection
import io.itermux.app.ui.DarkColors
import io.itermux.app.ui.HostDetailPage
import io.itermux.app.ui.HostReachability
import io.itermux.ssh.Credentials
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The host detail's compact session rows carry the session card's rename and close buttons. */
@RunWith(AndroidJUnit4::class)
class HostDetailSessionActionsUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun renameAndCloseFromHostDetail() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        // A password host with nothing saved, so the page does not start its monitor login.
        val host = Host(name = "详情主机", address = "127.0.0.1", port = 1, username = "test")
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials("test-only".toByteArray())) }
        val closed = mutableListOf<TerminalConnection>()
        try {
            compose.setContent {
                MaterialTheme(colorScheme = DarkColors) {
                    HostDetailPage(host, HostReachability.Offline, listOf(session), onBack = {}, onOpenSession = {},
                        onCloseSession = { closed += it }, onForgetSession = { _, _ -> },
                        onLaunch = { _, _ -> }, onBrowseFiles = {}, onEdit = {}, onManagePlugins = {}, onDelete = {})
                }
            }
            compose.onNodeWithTag("host-session-rename-${session.id}").performClick()
            compose.onNodeWithTag("session-title-input").performTextInput("部署脚本")
            compose.onNodeWithText("保存").performClick()
            assertEquals("部署脚本", session.title.value)
            compose.onNodeWithTag("host-session-${session.id}").assertTextContains("部署脚本", substring = true)

            compose.onNodeWithTag("host-session-close-${session.id}").performClick()
            compose.onNodeWithText("关闭连接").performClick()
            assertEquals(listOf(session), closed)
        } finally {
            withContext(Dispatchers.Main) { service.close(session.id) }
        }
        Unit
    }
}
