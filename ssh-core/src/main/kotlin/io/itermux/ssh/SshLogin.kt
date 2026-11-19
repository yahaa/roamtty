package io.itermux.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Security
import java.util.Base64

/**
 * How a session logs in to its [target]: the socket it connects with, the pinned host key it insists on and the
 * credentials it offers. JSch calls back on its own thread, so questions for the user ([ask]) block that thread
 * under [interactionJob]; a host key that is not trusted is handed to [onTrustFailure] before JSch wraps it.
 */
internal class SshLogin(
    private val target: Target,
    private val credentials: Credentials,
    private val pins: PinStore,
    private val connectTimeoutMs: Int,
    private val interactionJob: Job,
    private val ask: (Prompt) -> List<String>?,
    private val onPhase: (Phase, String) -> Unit,
    private val onTrustFailure: (Exception) -> Unit,
    /** The address the connection reached: mosh-server binds to it (mosh-server -s). */
    private val onAddress: (InetAddress) -> Unit,
) {
    private fun tcpKeepaliveFactory() = object : SocketFactory {
        override fun createSocket(host: String, port: Int): Socket {
            val socket = Socket()
            try {
                socket.keepAlive = true
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                // mosh-server binds to the address this SSH connection reached (mosh-server -s).
                onAddress(socket.inetAddress)
                return socket
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }
        override fun getInputStream(socket: Socket) = socket.getInputStream()
        override fun getOutputStream(socket: Socket) = socket.getOutputStream()
    }

    /**
     * Builds an unconnected session. The terminal login reports progress; an auxiliary login
     * (files beside a live mosh terminal) must not touch the terminal's state.
     */
    fun newSession(jsch: JSch, auxiliary: Boolean = false): Session {
        configureCrypto()
        jsch.hostKeyRepository = repository(reportPhase = !auxiliary)
        val ssh = jsch.getSession(target.username, target.hostname, target.port)
        ssh.setSocketFactory(tcpKeepaliveFactory())
        ssh.setConfig("StrictHostKeyChecking", "yes")
        ssh.setConfig("PreferredAuthentications", when (target.auth) {
            AuthMethod.PASSWORD -> "password,keyboard-interactive"
            AuthMethod.KEY -> "publickey"
            AuthMethod.INTERACTIVE -> "keyboard-interactive"
        })
        ssh.setConfig("NumberOfPasswordPrompts", "1")
        ssh.userInfo = object : UserInfo, UIKeyboardInteractive {
            override fun getPassword(): String? = credentials.password?.toString(Charsets.UTF_8)
            override fun getPassphrase(): String? = null
            // setPassword supplies the initial attempt; never silently retry a rejected password.
            override fun promptPassword(message: String?) = false
            override fun promptPassphrase(message: String?) = false
            override fun promptYesNo(message: String?) = false
            override fun showMessage(message: String?) { /* Remote messages never go to logs. */ }
            override fun promptKeyboardInteractive(destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?): Array<String>? {
                if (!auxiliary) onPhase(Phase.AUTHENTICATING, "等待认证输入")
                val labels = prompt?.toList() ?: emptyList()
                require(labels.size <= 16) { "Too many authentication prompts" }
                val answers = ask(Prompt.Keyboard(instruction.orEmpty().take(2048), labels.map { it.take(512) }, echo?.toList() ?: List(labels.size) { false }))
                return answers?.takeIf { it.size == labels.size }?.toTypedArray()
            }
        }
        if (target.auth == AuthMethod.KEY) {
            val key = requireNotNull(credentials.privateKey) { "Missing key" }
            // Keep originals for tmux auto-reattach; JSch receives copies.
            jsch.addIdentity("session-key", key.copyOf(), null, credentials.passphrase?.copyOf())
        } else if (target.auth == AuthMethod.PASSWORD) {
            ssh.setPassword(credentials.password?.copyOf())
        }
        // SSH-level keepalive (~15s × 4 ≈ 1 min of silence before drop).
        ssh.setServerAliveInterval(15_000)
        ssh.setServerAliveCountMax(4)
        return ssh
    }

    private fun repository(reportPhase: Boolean = true) = object : HostKeyRepository {
        override fun check(host: String?, key: ByteArray): Int {
            try {
                val pin = HostPin(target.endpoint, HostKey(host, key).type, Base64.getEncoder().encodeToString(key))
                runBlocking(interactionJob) {
                    TrustPolicy(pins).verify(pin) {
                        onPhase(Phase.VERIFYING, "")
                        ask(Prompt.VerifyHost(it)) == listOf("trust")
                    }
                }
                if (reportPhase) onPhase(Phase.AUTHENTICATING, "")
                return HostKeyRepository.OK
            } catch (e: Exception) { onTrustFailure(e); throw e }
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "Roamtty pinned hosts"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private companion object {
        @Synchronized private fun configureCrypto() {
            // Android ships a reduced BC provider. Use the full library for modern SSH algorithms.
            if (Security.getProvider("BC") !is BouncyCastleProvider) {
                Security.removeProvider("BC")
                Security.addProvider(BouncyCastleProvider())
            }
            JSch.setConfig("signature.ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("signature.ed448", "com.jcraft.jsch.bc.SignatureEd448")
        }
    }
}
