package io.itermux.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.itermux.app.ui.TerminalConnectionNotice
import io.itermux.app.ui.TerminalInputNotice
import io.itermux.app.ui.TerminalNoticeOverlay
import io.itermux.ssh.ConnectionState
import io.itermux.ssh.Phase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalNoticeOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noticesRepositionAndKeepActionsVisibleInShortViewports() {
        var height by mutableStateOf(540.dp)
        var phase by mutableStateOf(Phase.DISCONNECTED)
        var reconnects = 0
        compose.setContent { MaterialTheme {
            Box(Modifier.width(360.dp).height(height)) {
                TerminalNoticeOverlay {
                    TerminalConnectionNotice(ConnectionState(phase, "会话记录已恢复，未发送的输入不会重发。"),
                        { reconnects++ }, {}, {})
                }
            }
        } }
        fun assertPlacement(expectGolden: Boolean) {
            val viewport = compose.onNodeWithTag("terminal-notice-viewport").fetchSemanticsNode().boundsInRoot
            val card = compose.onNodeWithTag("terminal-connection-notice").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(card.top > viewport.top && card.bottom < viewport.bottom)
            assertEquals(viewport.center.x, card.center.x, 1f)
            if (expectGolden) assertEquals(viewport.top + viewport.height * 0.382f, card.center.y, 1f)
            compose.onNodeWithText(tr(if (phase == Phase.DISCONNECTED) "已断开" else if (phase == Phase.AUTH_FAILED) "认证失败" else "连接失败"))
                .assertIsDisplayed()
            compose.onNodeWithText(tr("重新连接")).assertIsDisplayed().performClick()
        }
        assertPlacement(true)
        compose.runOnIdle { height = 144.dp; phase = Phase.AUTH_FAILED }
        assertPlacement(false)
        compose.runOnIdle { height = 540.dp; phase = Phase.FAILED }
        assertPlacement(true)
        assertEquals(3, reconnects)
    }

    @Test fun longFingerprintScrollsAndOffersOnlyReverification() {
        var verified = false
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp).height(200.dp)) {
            TerminalNoticeOverlay {
                TerminalConnectionNotice(ConnectionState(Phase.HOST_KEY_CHANGED, "指纹发生变化。".repeat(50),
                    oldFingerprint = "SHA256:old", newFingerprint = "SHA256:new"), {}, {}, { verified = true })
            }
        } } }
        compose.onNodeWithText(tr("重新连接")).assertDoesNotExist()
        compose.onNodeWithText(tr("重新核验…")).assertIsDisplayed().performClick()
        assertTrue(verified)
        compose.onNodeWithTag("terminal-connection-notice").performTouchInput { swipeUp() }
        compose.onNodeWithText("SHA256:new", substring = true).assertExists()
    }

    @Test fun missingTmuxKeepsShellFallbackAndInputWarningCanBeDismissed() {
        var shell = false
        var warning by mutableStateOf(false)
        compose.setContent { MaterialTheme { Box(Modifier.width(360.dp).height(260.dp)) {
            TerminalNoticeOverlay {
                if (warning) TerminalInputNotice("请稍后重试") { warning = false }
                else TerminalConnectionNotice(ConnectionState(Phase.TMUX_MISSING), {}, { shell = true; warning = true }, {})
            }
        } } }
        compose.onNodeWithText(tr("改用普通 SSH")).assertIsDisplayed().performClick()
        assertTrue(shell)
        compose.onNodeWithTag("terminal-input-notice").assertIsDisplayed()
        compose.onNodeWithText(tr("知道了")).performClick()
        compose.onNodeWithTag("terminal-input-notice").assertDoesNotExist()
    }
}
