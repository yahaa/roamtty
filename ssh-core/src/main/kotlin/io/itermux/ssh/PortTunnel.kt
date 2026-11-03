package io.itermux.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** The SSH login a tunnel would ride on is not up (or not yet back after a drop). */
class SshUnavailableException(cause: Throwable? = null) : IOException("SSH 连接未就绪", cause)

/**
 * A web page on the server, by the address the server reaches it at: usually its own loopback, as a dev server prints
 * it (`http://localhost:5173/`, `127.0.0.1`, `0.0.0.0` or `[::1]`). Only plain http: a dev server's TLS certificate
 * would not be trusted on the phone anyway.
 */
data class LoopbackUrl(val host: String, val port: Int, val rest: String) {
    /** Where the server connects: a name it resolves itself, as dev servers on `localhost` may listen on IPv6 only. */
    val target: String get() = when {
        host == "localhost" || host == "0.0.0.0" -> "localhost"
        host.startsWith("[") -> host.removeSurrounding("[", "]")
        else -> host
    }

    /** The same page through a tunnel on the phone's [localPort]. */
    fun through(localPort: Int): String = "http://${if (host.startsWith("127.")) host else "localhost"}:$localPort$rest"

    /** What the address bar shows: the server's address, as the terminal printed it, without the scheme. */
    val display: String get() = "$host:$port${rest.takeIf { it != "/" }.orEmpty()}"

    companion object {
        private val PATTERN = Regex("""^http://(localhost|127(?:\.\d{1,3}){3}|0\.0\.0\.0|\[::1])(?::(\d{1,5}))?([/?#].*)?$""",
            RegexOption.IGNORE_CASE)

        fun parse(url: String): LoopbackUrl? {
            val match = PATTERN.matchEntire(url.trim()) ?: return null
            val port = match.groupValues[2].ifEmpty { "80" }.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val rest = match.groupValues[3].ifEmpty { "/" }.let { if (it.startsWith('/')) it else "/$it" }
            return LoopbackUrl(match.groupValues[1].lowercase(Locale.ROOT), port, rest)
        }

        /** [url] as loaded through a tunnel on [localPort], back in terms of the server's [port]; null for other pages. */
        fun fromTunnel(url: String, localPort: Int, port: Int): LoopbackUrl? =
            parse(url)?.takeIf { it.port == localPort }?.copy(port = port)
    }
}

/**
 * Serves 127.0.0.1 on the phone and carries each connection to [target]:[port] on the server through [open], one SSH
 * channel per connection. Every connection asks [open] afresh, so the tunnel outlives a dropped and restored login.
 * It takes the server's own port number when that is free on the phone, so pages that name their port still work.
 */
class PortTunnel(
    val target: String,
    val port: Int,
    private val open: suspend (host: String, port: Int) -> RemoteSocket,
    preferredLocalPort: Int = port,
) : Closeable {
    private val server = bind(preferredLocalPort)
    val localPort: Int = server.localPort
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val links = ConcurrentHashMap.newKeySet<Closeable>()
    @Volatile private var closed = false

    init {
        Thread({
            while (!closed) {
                val client = try { server.accept() } catch (_: IOException) { break }
                scope.launch { relay(client) }
            }
        }, "port-tunnel-$port").apply { isDaemon = true }.start()
    }

    /**
     * Whether something on the server accepts connections on the port. Throws [SshUnavailableException] when the login
     * is not up, so a caller can tell "nothing listens" from "cannot ask".
     */
    suspend fun listening(): Boolean = try { withContext(Dispatchers.IO) { open(target, port).close() }; true }
        catch (e: CancellationException) { throw e }
        catch (e: SshUnavailableException) { throw e }
        catch (_: IOException) { false }

    private suspend fun relay(client: Socket) {
        track(client)
        val remote = try { open(target, port) } catch (e: CancellationException) { forget(client); throw e }
            catch (_: Exception) { forget(client); return }
        track(remote)
        client.tcpNoDelay = true
        // The page's requests: an end of them is passed on, and the server's answer still comes back.
        val upload = scope.launch {
            copy(client.getInputStream(), remote.output)
            runCatching { remote.output.close() }
        }
        copy(remote.input, client.getOutputStream())
        upload.cancel()
        forget(remote)
        forget(client)
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val count = from.read(buffer)
                if (count < 0) break
                to.write(buffer, 0, count)
                // JSch holds channel output until a flush.
                to.flush()
            }
        } catch (_: IOException) { }
    }

    private fun track(link: Closeable) {
        links += link
        if (closed) forget(link)
    }

    private fun forget(link: Closeable) {
        links -= link
        runCatching { link.close() }
    }

    /** Stops listening and ends every connection still open. */
    override fun close() {
        closed = true
        runCatching { server.close() }
        scope.cancel()
        links.toList().forEach(::forget)
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))

        fun bind(preferred: Int): ServerSocket {
            if (preferred in 1024..65535) try {
                return ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(LOOPBACK, preferred)) }
            } catch (_: IOException) { }
            return ServerSocket(0, 50, LOOPBACK)
        }
    }
}
