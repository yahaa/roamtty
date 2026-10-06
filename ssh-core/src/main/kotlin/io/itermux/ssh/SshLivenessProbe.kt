package io.itermux.ssh

import com.jcraft.jsch.Session

/** A channel-open round trip needs no exec permission and never starts a remote command. */
internal fun probeSshAlive(ssh: Session, timeoutMs: Int): Boolean {
    require(timeoutMs > 0)
    val probe = try { ssh.openChannel("session") ?: return false } catch (_: Exception) { return false }
    try {
        probe.connect(timeoutMs)
        return true
    } catch (_: Exception) {
        // In JSch 2.28, SSH_MSG_CHANNEL_OPEN_FAILURE records its RFC 4254 reason as exitStatus.
        // This bare session channel runs no process, so it cannot have a process exit status.
        // Local I/O failures and unanswered opens leave it at -1, regardless of their speed/text.
        return ssh.isConnected && probe.exitStatus in 1..4
    } finally {
        probe.disconnect()
    }
}
