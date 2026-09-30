package io.itermux.ssh

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID

enum class AuthMethod { PASSWORD, KEY, INTERACTIVE }
enum class SessionMode { SHELL, TMUX }
enum class Phase { CONNECTING, VERIFYING, AUTHENTICATING, CONNECTED, DISCONNECTED, AUTH_FAILED, HOST_KEY_CHANGED, FAILED, TMUX_MISSING }
data class ConnectionState(val phase: Phase, val message: String = "", val oldFingerprint: String? = null, val newFingerprint: String? = null) {
    val active: Boolean get() = phase in setOf(Phase.CONNECTING, Phase.VERIFYING, Phase.AUTHENTICATING, Phase.CONNECTED)
}

data class Target(val address: String, val port: Int, val username: String, val auth: AuthMethod, val mode: SessionMode) {
    init {
        require(address.isNotBlank() && address.length <= 253 && address.none { it.isWhitespace() || it.isISOControl() || it in "/\\@" }) { "请输入主机地址，不含协议、路径或空格" }
        require(port in 1..65535) { "端口必须为 1–65535" }
        require(username.isNotBlank() && username.length <= 128 && username.none { it.isISOControl() || it.isWhitespace() }) { "请输入有效用户名" }
    }
    val hostname: String get() = address.removeSurrounding("[", "]")
    val endpoint: String get() = "[${hostname.lowercase(Locale.ROOT).removeSuffix(".")}]:$port"
}

/** Owned by a connection. Prefer [copy] before handing a working copy to transport code that may clear it. */
class Credentials(val password: ByteArray? = null, val privateKey: ByteArray? = null, val passphrase: ByteArray? = null) {
    fun clear() { password?.fill(0); privateKey?.fill(0); passphrase?.fill(0) }
    fun copy() = Credentials(password?.copyOf(), privateKey?.copyOf(), passphrase?.copyOf())
}
data class HostPin(val endpoint: String, val algorithm: String, val key: String) {
    val fingerprint: String get() = fingerprint(Base64.getDecoder().decode(key))
}
fun fingerprint(key: ByteArray): String = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

interface PinStore {
    suspend fun get(endpoint: String): HostPin?
    /** Atomically insert if absent; return existing pin on conflict. */
    suspend fun putIfAbsent(pin: HostPin): HostPin
}

sealed class Prompt {
    data class VerifyHost(val pin: HostPin) : Prompt()
    data class Keyboard(val instruction: String, val labels: List<String>, val echo: List<Boolean>) : Prompt()
}

interface SshSession {
    val state: kotlinx.coroutines.flow.StateFlow<ConnectionState>
    suspend fun connect(onOutput: suspend (ByteArray) -> Unit)
    /** Returns false when disconnected or the bounded queue is full. Never replays input. */
    fun write(bytes: ByteArray): Boolean
    fun resize(columns: Int, rows: Int, pixelWidth: Int = 0, pixelHeight: Int = 0)
    fun disconnect()
}

class HostKeyChanged(val old: HostPin, val received: HostPin) : Exception("Host key changed")
class HostKeyRejected : Exception("Host key rejected")

class TrustPolicy(private val store: PinStore) {
    suspend fun verify(pin: HostPin, approve: suspend (HostPin) -> Boolean) {
        val existing = store.get(pin.endpoint)
        if (existing != null) {
            if (existing.key != pin.key) throw HostKeyChanged(existing, pin)
            return
        }
        if (!approve(pin)) throw HostKeyRejected()
        // Another session may have verified this endpoint while this dialog was open.
        val winner = store.putIfAbsent(pin)
        if (winner.key != pin.key) throw HostKeyChanged(winner, pin)
    }
}

object Tmux {
    /** Legacy fixed remote name used by journal entries created before multi-session support. */
    const val LEGACY_SESSION = "itermux"
    @Deprecated("Use LEGACY_SESSION or a per-connection name", ReplaceWith("LEGACY_SESSION"))
    const val SESSION = LEGACY_SESSION
    const val PROBE = "command -v tmux >/dev/null 2>&1"
    @Deprecated("Use attachCommand(sessionName)", ReplaceWith("attachCommand(LEGACY_SESSION)"))
    const val ATTACH = "exec tmux new-session -A -s '$LEGACY_SESSION'"

    fun sanitizeSessionName(name: String): String =
        name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifEmpty { LEGACY_SESSION }

    fun newSessionName(): String = "itermux-${UUID.randomUUID().toString().replace("-", "").take(12)}"

    fun attachCommand(sessionName: String): String =
        "exec tmux new-session -A -s '${sanitizeSessionName(sessionName)}'"

    fun targetFlag(sessionName: String): String = "'${sanitizeSessionName(sessionName)}'"
}
