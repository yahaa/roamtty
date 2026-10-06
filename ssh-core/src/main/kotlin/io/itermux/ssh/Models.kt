package io.itermux.ssh

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID

enum class AuthMethod { PASSWORD, KEY, INTERACTIVE }
enum class SessionMode { SHELL, TMUX }
enum class Phase { CONNECTING, VERIFYING, AUTHENTICATING, CONNECTED, DISCONNECTED, AUTH_FAILED, HOST_KEY_CHANGED, FAILED, TMUX_MISSING }
/** [detail] names the underlying error of a failure ("SocketException: …"); it is never translated. */
data class ConnectionState(val phase: Phase, val message: String = "", val oldFingerprint: String? = null, val newFingerprint: String? = null, val detail: String? = null) {
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

/** Owned by a connection, which clears it once the secrets are no longer needed. */
class Credentials(val password: ByteArray? = null, val privateKey: ByteArray? = null, val passphrase: ByteArray? = null) {
    fun clear() { password?.fill(0); privateKey?.fill(0); passphrase?.fill(0) }
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
    const val LEGACY_SESSION = "roamtty"
    /**
     * sshd runs commands in a non-login shell, which misses the Homebrew and MacPorts paths where
     * tmux and mosh land on macOS (and Linuxbrew or snap on Linux).
     */
    const val EXTRA_PATH = "/opt/homebrew/bin:/usr/local/bin:/opt/local/bin:/home/linuxbrew/.linuxbrew/bin:/snap/bin"
    const val PATH_SETUP = "PATH=\"\$PATH:$EXTRA_PATH\"; export PATH"
    const val PROBE = "/bin/sh -c '$PATH_SETUP; command -v tmux >/dev/null 2>&1'"

    fun sanitizeSessionName(name: String): String =
        name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifEmpty { LEGACY_SESSION }

    fun newSessionName(): String = "roamtty-${UUID.randomUUID().toString().replace("-", "").take(12)}"

    /**
     * Runs tmux through /bin/sh with [EXTRA_PATH], whatever the login shell (fish, csh, nu…).
     * [args] must not contain single quotes; session names are sanitized, so they need none.
     */
    fun command(args: String): String {
        require('\'' !in args)
        return "/bin/sh -c '$PATH_SETUP; exec tmux $args'"
    }

    /**
     * First command of a new pane: blank lines push the first prompt onto the pane's last row,
     * next to the keyboard, then the shell starts as tmux would (default-command, else a login shell).
     */
    internal const val BOTTOM_PROMPT_PANE = "set -- \$(stty size 2>/dev/null); n=\${1:-0}; " +
        "while [ \"\$n\" -gt 1 ]; do echo; n=\$((n - 1)); done; s=\${SHELL:-/bin/sh}; " +
        "c=\$(tmux show-options -gv default-command 2>/dev/null); [ -n \"\$c\" ] && exec \"\$s\" -c \"\$c\"; exec \"\$s\" -l"

    /**
     * Attaches [sessionName], creating it with [BOTTOM_PROMPT_PANE] when missing. tmux before 3.0
     * joins a multi-word command into one string for the default shell, so it keeps a plain pane.
     */
    fun attachShellCommand(sessionName: String): String {
        val name = sanitizeSessionName(sessionName)
        val pane = BOTTOM_PROMPT_PANE.replace("\"", "\\\"").replace("$", "\\$")
        // A lost client leaves pane copy-mode and, with mosh, its old tmux attachment behind.
        // Reconcile the app's own session before accepting keys; -D detaches clients, not the task.
        // A failed cancel must not block the attach itself.
        return "/bin/sh -c '$PATH_SETUP; " +
            "if [ \"\$(tmux display-message -p -t $name \"#{pane_mode}\" 2>/dev/null)\" = copy-mode ]; then " +
            "tmux send-keys -t $name -X cancel || true; fi; " +
            "case \"\$(tmux -V 2>/dev/null)\" in \"tmux \"[0-2].*) exec tmux new-session -A -D -s $name;; esac; " +
            "exec tmux new-session -A -D -s $name /bin/sh -c \"$pane\"'"
    }

    fun attachCommand(sessionName: String): String = "exec " + attachShellCommand(sessionName)
}

enum class TerminalTransport { SSH, MOSH }

/** A live, authenticated host for administration tasks (see [JschSession.administer]). */
interface RemoteHost {
    suspend fun probePlugins(): Set<HostPlugin>?
    /** Runs a POSIX shell script; returns its exit status, or null on timeout or a lost connection. */
    suspend fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int?
}
