package io.itermux.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.itermux.app.data.Host
import io.itermux.app.ui.ConnectRequest
import io.itermux.app.ui.CredentialsDialog
import io.itermux.app.ui.HostCard
import io.itermux.app.ui.HostEditor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import io.itermux.app.ui.TerminalConnectionNotice
import io.itermux.ssh.ConnectionState
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Regressions from PrimeTestLab report 9904; no SSH server or saved credentials required. */
class ExternalFeedbackUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun addingAMalformedHostShowsValidationWithoutSaving() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ItermuxApp
        val previousHosts = app.database.hosts().observe().first()
        var saved = false
        compose.setContent { MaterialTheme { HostEditor(app, null, {}, { saved = true }) } }
        compose.onNodeWithTag("host-name").performTextInput("Malformed feedback regression")
        compose.onNodeWithTag("host-address").performTextInput("server..example")
        compose.onNodeWithTag("host-username").performTextInput("dev")
        compose.onNodeWithTag("save-host").performClick()
        compose.onNodeWithText(tr("请输入主机地址，不含协议、路径或空格")).performScrollTo().assertIsDisplayed()
        assertEquals(false, saved)
        assertEquals(previousHosts, app.database.hosts().observe().first())
    }

    @Test fun unprobedHostIsNotLabelledAsHavingNoDesktop() {
        val unknown = Host(name = "Unchecked", address = "unprobed.invalid", username = "dev")
        val headless = Host(name = "Headless", address = "headless.invalid", username = "dev", desktop = "no")
        var opened = 0
        compose.setContent { MaterialTheme { Column {
            HostCard(unknown, { opened++ }, {}, {}, {})
            HostCard(headless, { opened++ }, {}, {}, {})
        } } }
        compose.onNodeWithTag("host-screen-${unknown.id}")
            .assert(hasText(tr("桌面尚未检测"))).performClick()
        compose.onNodeWithTag("host-screen-${headless.id}")
            .assert(hasText(tr("没有图形桌面")))
        assertEquals(1, opened)
    }

    @Test fun credentialVisibilityIsOptionalAndResetsForEachPrompt() {
        var request by mutableStateOf(ConnectRequest(Host(name = "First", address = "first.invalid", username = "dev"), SessionMode.SHELL))
        val submitted = mutableListOf<Pair<String, String>>()
        compose.setContent { MaterialTheme { CredentialsDialog(request, {}, { password, phrase -> submitted += password to phrase }) } }
        val input = hasSetTextAction()
        fun renderedText(): String {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNode(input).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single().layoutInput.text.text
        }
        compose.onNode(input).performTextInput("test-only")
        assertEquals("•".repeat(9), renderedText())
        compose.onNodeWithText(tr("显示密码")).performClick()
        assertEquals("test-only", renderedText())
        compose.onNodeWithText(tr("隐藏密码")).performClick()
        assertEquals("•".repeat(9), renderedText())
        compose.onNodeWithText(tr("连接"), useUnmergedTree = true).performClick()
        assertEquals(listOf("test-only" to ""), submitted)
        compose.onNode(input).performTextInput("must-not-carry-over")
        compose.onNodeWithText(tr("显示密码")).performClick()
        compose.runOnIdle { request = ConnectRequest(Host(name = "Second", address = "second.invalid", username = "dev"), SessionMode.SHELL) }
        assertEquals("", renderedText())
        compose.onNode(input).performTextInput("new-secret")
        assertEquals("•".repeat(10), renderedText())
    }

    @Test fun rawFailureDetailsAreCollapsedAndResetForANewFailure() {
        var state by mutableStateOf(ConnectionState(Phase.FAILED, "连接超时，请检查网络、VPN 和主机端口。", detail = "SocketTimeoutException: raw-error"))
        compose.setContent { MaterialTheme { TerminalConnectionNotice(state, {}, {}, {}) } }
        compose.onNodeWithText(tr("连接超时，请检查网络、VPN 和主机端口。")).assertIsDisplayed()
        compose.onNodeWithText("SocketTimeoutException: raw-error").assertDoesNotExist()
        compose.onNodeWithText(tr("查看详情")).performClick()
        compose.onNodeWithText("SocketTimeoutException: raw-error").assertIsDisplayed()
        compose.onNodeWithText(tr("收起详情")).performClick()
        compose.onNodeWithText("SocketTimeoutException: raw-error").assertDoesNotExist()
        compose.onNodeWithText(tr("查看详情")).performClick()
        compose.runOnIdle { state = state.copy(detail = "ConnectException: another-error") }
        compose.onNodeWithText("ConnectException: another-error").assertDoesNotExist()
        compose.onNodeWithText(tr("重新连接")).assertIsDisplayed()
        compose.onNodeWithText(tr("关闭")).assertIsDisplayed()
    }
}
