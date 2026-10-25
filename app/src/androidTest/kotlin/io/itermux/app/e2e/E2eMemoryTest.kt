package io.itermux.app.e2e

import android.os.Debug
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import io.itermux.app.MainActivity
import io.itermux.app.session.SessionService
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.Credentials
import io.itermux.ssh.Phase
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File
import java.lang.ref.WeakReference

/** Removal differs from explicit close: the transport has already ended, but its observers still need disposing. */
@RunWith(AndroidJUnit4::class)
class E2eMemoryTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val serviceRule = ServiceTestRule()
    @get:Rule(order = 2) val cleanupAfter = object : ExternalResource() {
        override fun after() { if (::service.isInitialized) E2e.cleanup(service) }
    }
    private lateinit var service: SessionService

    @Before fun setUp() {
        E2e.requireFixture()
        service = E2e.bind(serviceRule)
        E2e.cleanup(service)
        runBlocking { E2e.trustFixture() }
        E2e.prepareActivity(compose)
        E2e.waitTag(compose, "home-pager")
    }

    /** A separate stack frame avoids the test itself keeping the connection alive. */
    private fun removedSession(failedLogin: Boolean): WeakReference<TerminalConnection> = runBlocking {
        val host = E2e.save(E2e.host("removed session"))
        val password = if (failedLogin) "deliberately-wrong" else E2e.password
        val session = withContext(Dispatchers.Main) {
            service.open(host, SessionMode.SHELL, Credentials(password.toByteArray()))
        }
        if (failedLogin) {
            assertEquals(Phase.AUTH_FAILED, E2e.connected(session))
        } else {
            assertEquals(Phase.CONNECTED, E2e.connected(session))
            withContext(Dispatchers.Main) { session.write("seq 1 20000; exit\r") }
            withTimeout(30_000) { session.state.first { !it.active } }
        }
        withContext(Dispatchers.Main) { service.forget(session.id) }
        WeakReference(session)
    }

    private fun assertCollected(label: String, refs: List<WeakReference<TerminalConnection>>) {
        // Removing a card must suffice: neither the Activity nor the bound service is destroyed here.
        E2e.waitTag(compose, "home-pager")
        val deadline = System.nanoTime() + 10_000_000_000
        var retained: Int
        do {
            E2e.gc()
            retained = refs.count { it.get() != null }
            if (retained == 0) break
            Thread.sleep(100)
        } while (System.nanoTime() < deadline)
        E2e.metric(label, retained, "count")
        if (retained != 0) Debug.dumpHprofData(File(E2e.outputDir, "$label.hprof").path)
        assertEquals("Removed connections retained while the service is still bound", 0, retained)
    }

    @Test fun naturallyEndedSessionsAreCollectedAfterRemoval() {
        assertCollected("retained_ended_sessions", List(3) { removedSession(failedLogin = false) })
    }

    @Test fun failedSessionsAreCollectedAfterRemoval() {
        assertCollected("retained_failed_sessions", List(3) { removedSession(failedLogin = true) })
    }
}
