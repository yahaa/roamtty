package io.itermux.ssh

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException

/**
 * The short-lived channels a login carries beside its terminal: commands run to completion, a command kept open as
 * a byte stream, and connections the server makes for the phone. Each call opens a channel of its own on the
 * [Session] it is given and closes it, so none of them touches the PTY. Blocking work runs on IO.
 */
internal class SshChannels(private val connectTimeoutMs: Int, private val isStopped: () -> Boolean) {
    /**
     * Runs a command to completion and returns (exit status, output). Output past [maxBytes] is
     * dropped from the result but still streamed line by line to [onLine].
     */
    suspend fun execute(ssh: Session, command: String, input: String?, timeoutMs: Long,
        maxBytes: Int = 64 * 1024, onLine: ((String) -> Unit)? = null): Pair<Int, String>? = withContext(Dispatchers.IO) {
        val exec = try { ssh.openChannel("exec") as ChannelExec } catch (_: Exception) { return@withContext null }
        try {
            exec.setCommand(command)
            exec.setInputStream(input?.byteInputStream(Charsets.UTF_8))
            val output = exec.inputStream
            exec.connect(connectTimeoutMs)
            val kept = ByteArrayOutputStream()
            val line = ByteArrayOutputStream()
            var lineOverflow = false
            fun emit() {
                if (lineOverflow) line.write("…".toByteArray(Charsets.UTF_8))
                onLine?.invoke(line.toString(Charsets.UTF_8.name()).trimEnd('\r')); line.reset(); lineOverflow = false
            }
            val complete = withTimeoutOrNull(timeoutMs) {
                val buffer = ByteArray(4096)
                while (!exec.isClosed || output.available() > 0) {
                    while (output.available() > 0) {
                        val count = output.read(buffer)
                        if (count < 0) break
                        if (kept.size() + count <= maxBytes) kept.write(buffer, 0, count)
                        for (i in 0 until count) {
                            if (buffer[i] == '\n'.code.toByte()) emit()
                            else if (line.size() < MAX_LINE_BYTES) line.write(buffer[i].toInt())
                            else lineOverflow = true
                        }
                    }
                    if (isStopped()) return@withTimeoutOrNull false
                    if (!exec.isClosed) delay(30)
                }
                true
            } ?: false
            if (line.size() > 0 || lineOverflow) emit()
            if (complete) exec.exitStatus to kept.toString(Charsets.UTF_8.name()) else null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        finally { exec.disconnect() }
    }

    /**
     * The output of a quick command that succeeded: null when it failed, took over [QUICK_COMMAND_MS], or printed more
     * than [QUICK_COMMAND_BYTES]. For the small questions asked beside a live terminal (a folder, a tmux option).
     */
    suspend fun capture(ssh: Session, command: String, input: String? = null): String? = withContext(Dispatchers.IO) {
        val exec = try { ssh.openChannel("exec") as ChannelExec } catch (_: Exception) { return@withContext null }
        try {
            exec.setCommand(command)
            exec.setInputStream(input?.byteInputStream(Charsets.UTF_8))
            val output = exec.inputStream
            exec.connect(QUICK_COMMAND_MS)
            val bytes = ByteArrayOutputStream()
            val complete = withTimeoutOrNull(QUICK_COMMAND_MS.toLong()) {
                val buffer = ByteArray(256)
                while (!exec.isClosed || output.available() > 0) {
                    while (output.available() > 0) {
                        val count = output.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count > QUICK_COMMAND_BYTES) return@withTimeoutOrNull false
                        bytes.write(buffer, 0, count)
                    }
                    if (!exec.isClosed) delay(20)
                }
                true
            } ?: false
            if (complete && exec.exitStatus == 0) bytes.toString(Charsets.UTF_8.name()) else null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        finally { exec.disconnect() }
    }

    /** A long-running command whose stdin and stdout carry a protocol of its own; null when it could not start. */
    suspend fun openStream(ssh: Session, command: String): RemoteStream? = withContext(Dispatchers.IO) {
        val exec = try { ssh.openChannel("exec") as ChannelExec } catch (_: Exception) { return@withContext null }
        try {
            exec.setCommand(command)
            val output = exec.inputStream
            val input = exec.outputStream
            val errors = exec.errStream
            exec.connect(connectTimeoutMs)
            object : RemoteStream {
                override val input: InputStream = output
                override val output: OutputStream = input
                override val errors: InputStream = errors
                override val closed: Boolean get() = exec.isClosed
                override val exitStatus: Int? get() = exec.exitStatus.takeIf { exec.isClosed && it >= 0 }
                override fun close() { exec.disconnect() }
            }
        } catch (e: CancellationException) { exec.disconnect(); throw e }
        catch (_: Exception) { exec.disconnect(); null }
    }

    /**
     * A direct-tcpip channel to [host]:[port] as seen from the server; [ConnectException] when it was refused,
     * [ForwardingProhibitedException] when the server forwards nothing there.
     */
    suspend fun openDirect(ssh: Session, host: String, port: Int): RemoteSocket = withContext(Dispatchers.IO) {
        val channel = try { ssh.openChannel("direct-tcpip") as ChannelDirectTCPIP }
            catch (e: Exception) { throw SshUnavailableException(e) }
        try {
            channel.setHost(host)
            channel.setPort(port)
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(connectTimeoutMs)
            object : RemoteSocket {
                override val input: InputStream = input
                override val output: OutputStream = output
                override fun close() { channel.disconnect() }
            }
        } catch (e: CancellationException) { channel.disconnect(); throw e }
        catch (e: Exception) {
            channel.disconnect()
            if (!ssh.isConnected) throw SshUnavailableException(e)
            // JSch keeps a refused open's reason code as the channel's exit status.
            if (channel.exitStatus == SSH_OPEN_ADMINISTRATIVELY_PROHIBITED) throw ForwardingProhibitedException("$host:$port", e)
            throw ConnectException("$host:$port").apply { initCause(e) }
        }
    }

    private companion object {
        /** Cap for a single onLine emission; longer runs are truncated with an ellipsis. */
        const val MAX_LINE_BYTES = 64 * 1024

        const val QUICK_COMMAND_MS = 5000
        const val QUICK_COMMAND_BYTES = 8192

        /** RFC 4254's reason for a channel the server refuses to open on principle, not for want of a listener. */
        const val SSH_OPEN_ADMINISTRATIVELY_PROHIBITED = 1
    }
}
