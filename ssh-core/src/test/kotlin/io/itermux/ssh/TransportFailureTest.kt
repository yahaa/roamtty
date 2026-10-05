package io.itermux.ssh

import com.jcraft.jsch.JSchException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TransportFailureTest {
    @Test fun wrappedNetworkErrorsCanBeRetried() {
        for (cause in listOf(ConnectException("Connection refused"), SocketTimeoutException("Read timed out"),
            UnknownHostException("offline"), EOFException(), IOException("End of IO Stream Read"))) {
            assertTrue(cause.toString(), isRetryableTransportFailure(JSchException("Session.connect", cause)))
        }
    }

    @Test fun jschHandshakeEofAndTimeoutCanBeRetried() {
        for (message in listOf("connection is closed by foreign host", "session is down", "timeout: socket is not established")) {
            assertTrue(message, isRetryableTransportFailure(JSchException(message)))
        }
    }

    @Test fun authenticationAndConfigurationFailuresAreFatal() {
        for (failure in listOf(JSchException("Auth fail for methods 'password'"), JSchException("Auth cancel"),
            JSchException("Algorithm negotiation fail"), JSchException("invalid privatekey"),
            IllegalArgumentException("Missing key"), IOException("Invalid key data"))) {
            assertFalse(failure.toString(), isRetryableTransportFailure(failure))
        }
        assertFalse(isRetryableTransportFailure(JSchException("Auth fail", SocketTimeoutException())))
    }

    @Test fun trustFailuresAndCancellationAreNeverRetried() {
        val pin = HostPin("test", "ssh-ed25519", "dGVzdA==")
        for (failure in listOf(HostKeyChanged(pin, pin), HostKeyRejected(), CancellationException("canceled"))) {
            assertFalse(isRetryableTransportFailure(JSchException("timeout", failure)))
        }
    }

    @Test fun firstConnectRetriesThreeTimesWithGrowingWaits() {
        assertEquals(listOf(2_000L, 4_000L, 6_000L, null), (1..4).map(::firstConnectRetryDelayMs))
    }

    @Test fun timeoutsAreToldApartFromUnreachableNetworks() {
        assertTrue(isTimeoutFailure(JSchException("Session.connect", SocketTimeoutException("connect timed out"))))
        assertTrue(isTimeoutFailure(JSchException("timeout: socket is not established")))
        assertFalse(isTimeoutFailure(JSchException("Session.connect", NoRouteToHostException("No route to host"))))
        assertFalse(isTimeoutFailure(JSchException("Session.connect", SocketException("Network is unreachable"))))
    }

    @Test fun detailNamesTheInnermostCause() {
        val failure = JSchException("java.net.SocketException: connect failed", SocketException("connect failed: ENETUNREACH (Network is unreachable)"))
        assertEquals("SocketException: connect failed: ENETUNREACH (Network is unreachable)", failureDetail(failure))
        assertEquals("EOFException", failureDetail(JSchException("Session.connect", EOFException())))
        assertEquals(200, failureDetail(IOException("x".repeat(500))).length - "IOException: ".length)
    }
}
