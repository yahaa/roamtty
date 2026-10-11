package io.itermux.app

import android.content.Intent
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A session that drops while the home session list is on screen reconnects by itself. Needs the loopback SSH fixture;
 * pass its password with `-e fixturePassword …`.
 */
@RunWith(AndroidJUnit4::class)
class HomeSessionsReconnectTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun aDroppedSessionReconnectsOnTheList() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val password = InstrumentationRegistry.getArguments().getString("fixturePassword") ?: "test-only"
        val app = context.applicationContext as ItermuxApp
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val id = "home-reconnect-test-host"
        // A saved password, so the list may reconnect without asking.
        val host = Host(id = id, name = "Reconnect", address = "127.0.0.1", port = 22228, username = "itermux_test",
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
            // The server ends the login, as a dropped network would: a plain shell then stays disconnected.
            withContext(Dispatchers.Main) { session.write("kill -9 \$PPID\r") }
            withTimeout(20_000) { session.state.first { it.phase == Phase.DISCONNECTED } }
            // The list reconnects the same entry over a new connection, once its host answers a probe. The list's waits
            // follow the compose clock, which only moves while the test waits on compose.
            compose.waitUntil(30_000) {
                service.sessions.value.any { it.id == session.id && it !== session && it.state.value.phase == Phase.CONNECTED }
            }
        } finally {
            trust.cancel()
            withContext(Dispatchers.Main) { service.close(session.id); service.forget(session.id) }
            app.database.hosts().delete(id)
        }
        Unit
    }
}
