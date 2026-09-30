package io.itermux.ssh

import com.jcraft.jsch.JSchException
import kotlinx.coroutines.CancellationException
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** JSch wraps socket errors and reports some handshake EOF/timeouts without an IO cause. */
internal fun isRetryableTransportFailure(failure: Throwable): Boolean {
    val causes = generateSequence(failure) { it.cause }.toList()
    if (causes.any {
        it is HostKeyChanged || it is HostKeyRejected || it is CancellationException ||
            (it is JSchException && it.message?.contains("Auth", ignoreCase = true) == true)
    }) return false
    return causes.any {
        when (it) {
            is SocketException, is SocketTimeoutException, is UnknownHostException, is EOFException -> true
            is JSchException -> it.message == "connection is closed by foreign host" ||
                it.message == "session is down" || it.message?.contains("timeout", ignoreCase = true) == true
            is IOException -> it.message == "End of IO Stream Read"
            else -> false
        }
    }
}
