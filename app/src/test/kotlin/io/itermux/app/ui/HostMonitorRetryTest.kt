package io.itermux.app.ui

import io.itermux.ssh.ConnectionState
import io.itermux.ssh.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostMonitorRetryTest {
    private val failed = ConnectionState(Phase.FAILED, "连接失败，请检查网络、地址、密钥格式及服务器算法支持。", detail = "JSchException: connection is closed by foreign host")

    @Test fun networkFailuresBackOffThenGiveUp() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, null),
            (1..6).map { monitorRetryDelayMs(failed, it, interactive = false) })
    }

    @Test fun droppedLoginRetries() {
        assertEquals(2_000L, monitorRetryDelayMs(ConnectionState(Phase.DISCONNECTED, "连接已关闭"), 1, interactive = false))
    }

    @Test fun rejectedOrInteractiveLoginsNeverRetry() {
        assertNull(monitorRetryDelayMs(ConnectionState(Phase.AUTH_FAILED, "认证失败"), 1, interactive = false))
        assertNull(monitorRetryDelayMs(ConnectionState(Phase.HOST_KEY_CHANGED), 1, interactive = false))
        assertNull(monitorRetryDelayMs(ConnectionState(Phase.DISCONNECTED, "未信任此主机"), 1, interactive = false))
        assertNull(monitorRetryDelayMs(failed, 1, interactive = true))
    }
}
