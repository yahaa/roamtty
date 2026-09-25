package io.itermux.app.ui

import io.itermux.app.data.Host
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode

internal data class ConnectRequest(val host: Host, val mode: SessionMode, val title: String = "")

internal fun authLabel(auth: String) = when (auth) {
    "KEY" -> "私钥"
    "INTERACTIVE" -> "交互式认证"
    else -> "密码"
}

internal fun phaseLabel(phase: Phase) = when (phase) {
    Phase.CONNECTING -> "连接中"
    Phase.VERIFYING -> "待验证"
    Phase.AUTHENTICATING -> "认证中"
    Phase.CONNECTED -> "已连接"
    Phase.DISCONNECTED -> "已断开"
    Phase.AUTH_FAILED -> "认证失败"
    Phase.HOST_KEY_CHANGED -> "指纹变化"
    Phase.TMUX_MISSING -> "缺少 tmux"
    Phase.FAILED -> "连接失败"
}
