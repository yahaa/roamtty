package io.itermux.app

import android.content.Intent
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.ssh.Credentials
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A session whose host goes offline is left alone on the session list, marked "host offline", instead of showing
 * "connecting" again and again; once the host answers its probe again, the list reconnects it. The host is a relay to
 * the loopback SSH fixture that the test switches off and on; pass the fixture password with `-e fixturePassword …`.
 */
@RunWith(AndroidJUnit4::class)
class HomeSessionsOfflineHostTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    /** Relays [port] to the fixture while up; [down] refuses new connections and cuts the open ones. */
    private class Relay(val port: Int) : Closeable {
        private val loopback = InetAddress.getByName("127.0.0.1")
        private val open = CopyOnWriteArrayList<Socket>()
        private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(loopback, port)) }
        init {
            thread(isDaemon = true) {
                while (true) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    val upstream = runCatching { Socket(loopback, 22228) }.getOrNull() ?: run { client.close(); return@thread }
                    open += client; open += upstream
                    pump(client, upstream); pump(upstream, client)
                }
            }
        }
        private fun pump(from: Socket, to: Socket) = thread(isDaemon = true) {
            runCatching { from.getInputStream().copyTo(to.getOutputStream()) }
            runCatching { from.close() }; runCatching { to.close() }
        }
        override fun close() {
            runCatching { server.close() }
            open.forEach { runCatching { it.close() } }
        }
    }

    @Test fun anOfflineHostIsNotRetriedUntilItAnswersAgain() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val password = InstrumentationRegistry.getArguments().getString("fixturePassword") ?: "test-only"
        val app = context.applicationContext as ItermuxApp
        val port = ServerSocket(0).use { it.localPort }
        var relay = Relay(port)
        app.database.hosts().forgetPin("[127.0.0.1]:$port")
        val id = "home-offline-test-host"
        val host = Host(id = id, name = "Offline", address = "127.0.0.1", port = port, username = "itermux_test",
            mode = "SHELL", encryptedPassword = app.vault.encrypt(password.toByteArray(), "$id:password"))
        app.database.hosts().save(host)
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials(password.toByteArray())) }
        val trust = CoroutineScope(Dispatchers.Main).launch {
            service.sessions.collect { all -> all.filter { it.id == session.id }.forEach { s ->
                launch { s.pending.collect { it?.answer?.complete(listOf("trust")) } }
            } }
        }
        try {
            withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("home-session-${session.id}").fetchSemanticsNodes().isNotEmpty() }
            // The host goes away: the connection drops and nothing answers on its port.
            relay.close()
            withTimeout(20_000) { session.state.first { it.phase == Phase.DISCONNECTED } }
            val offline = hasAnyDescendant(hasText(tr("主机离线")))
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("home-session-status-${session.id}", useUnmergedTree = true)
                    .fetchSemanticsNodes().any { offline.matches(it) }
            }
            // Well past the list's ten-second reconnect interval, the entry is still the same, untried one. The list's
            // waits follow the compose clock, which only moves while the test waits on compose.
            val retried = runCatching {
                compose.waitUntil(15_000) { service.sessions.value.first { it.id == session.id } !== session || session.state.value.active }
            }.isSuccess
            assertFalse(retried)

            // Back online: the next probe (30 s after the first failed one) finds it and the list reconnects.
            relay = Relay(port)
            compose.waitUntil(60_000) {
                service.sessions.value.any { it.id == session.id && it !== session && it.state.value.phase == Phase.CONNECTED }
            }
        } finally {
            trust.cancel()
            relay.close()
            withContext(Dispatchers.Main) { service.close(session.id); service.forget(session.id) }
            app.database.hosts().delete(id)
        }
        Unit
    }
}
