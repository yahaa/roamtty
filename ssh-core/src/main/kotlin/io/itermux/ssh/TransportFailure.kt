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

internal fun isTimeoutFailure(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause }.any { it is SocketTimeoutException || it.message?.contains("timeout", ignoreCase = true) == true }

/**
 * Wait before retrying a first connection that never reached the server (VPN reconnecting, Wi-Fi
 * handover), or null once the 2 s, 4 s, 6 s budget is spent. Timeouts already waited and are not retried.
 */
internal fun firstConnectRetryDelayMs(retry: Int): Long? = if (retry in 1..3) 2_000L * retry else null

/**
 * The innermost cause as "Type: message", shown beside a failure so it can be told apart from a
 * misconfiguration. JSch and socket messages carry no credentials.
 */
internal fun failureDetail(failure: Throwable): String {
    val root = generateSequence(failure) { it.cause }.last()
    val message = root.message?.takeIf { it.isNotBlank() }?.take(200)
    return listOfNotNull(root.javaClass.simpleName, message).joinToString(": ")
}
