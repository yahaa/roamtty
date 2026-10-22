package io.itermux.app.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.MainActivity
import io.itermux.ssh.AuthMethod
import io.itermux.app.data.Host
import io.itermux.app.data.StoredPin
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.desktop.DesktopSettings
import io.itermux.app.e2e.E2e.app
import io.itermux.app.session.PendingPrompt
import io.itermux.app.session.SessionService
import io.itermux.app.ui.credentialsFor
import io.itermux.app.ui.pageLogin
import io.itermux.desktop.DesktopPhase
import io.itermux.desktop.DesktopProtocol
import io.itermux.desktop.HidUsage
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * A real Windows PC with the plugin's service (an administrator's SSH login installs it): the
 * desktop, then the console's session disconnected, which is how the lock screen and a restart's
 * sign-in screen look to the plugin. The app has to come back by itself to the lock screen, and
 * typing the password from the phone signs back in.
 *
 * Runs only with `-e winHost <address> -e winUser <user> -e winPassword <password>
 * -e winHostKey <ssh-ed25519 blob>`; skipped otherwise. The plugin comes from the pinned release.
 */
@RunWith(AndroidJUnit4::class)
class E2eWindowsServiceTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val serviceRule = ServiceTestRule()
    private lateinit var service: SessionService
    private val args get() = InstrumentationRegistry.getArguments()
    private val address get() = args.getString("winHost").orEmpty()
    private val password get() = args.getString("winPassword").orEmpty()
    private var hostId = ""

    @Before fun setUp() {
        assumeTrue("no Windows host (winHost)", address.isNotEmpty() && password.isNotEmpty())
        service = E2e.bind(serviceRule)
        cleanup()
        val endpoint = "[$address]:22"
        val host = runBlocking {
            app.database.hosts().forgetPin(endpoint)
            app.database.hosts().insertPin(StoredPin(endpoint, "ssh-ed25519", args.getString("winHostKey").orEmpty()))
            val id = UUID.randomUUID().toString()
            E2e.save(Host(id, E2e.PREFIX + "windows", address, 22, args.getString("winUser") ?: "desktop", AuthMethod.PASSWORD.name,
                SessionMode.SHELL.name, encryptedPassword = app.vault.encrypt(password.toByteArray(), "$id:password"), os = "WINDOWS"))
        }
        hostId = host.id
        // The released plugin, as a user gets it: the fixture's copy is on this device's loopback only.
        DesktopSettings(E2e.context).apply { enabled = true; keepAliveMinutes = 10; pluginBaseUrl = "" }
        E2e.prepareActivity(compose)
        E2e.waitTag(compose, "home-pager")
    }

    @After fun tearDown() {
        if (address.isEmpty()) return
        cleanup()
        DesktopSettings(E2e.context).apply { enabled = false; pluginBaseUrl = "" }
    }

    private fun cleanup() {
        runBlocking(Dispatchers.Main) {
            service.desktops.connections.value.values.filter { it.host.name.startsWith(E2e.PREFIX) }.forEach { service.desktops.close(it.host.id) }
        }
        E2e.cleanup(service)
    }

    private fun desktop(): DesktopConnection = service.desktops[hostId] ?: error("no desktop")

    private fun waitPhase(timeoutMs: Long, label: String, predicate: (DesktopPhase) -> Boolean) {
        try { compose.waitUntil(timeoutMs) { service.desktops[hostId]?.phase?.value?.let(predicate) == true } }
        catch (e: Throwable) { throw AssertionError("$label: phase ${service.desktops[hostId]?.phase?.value}; log ${service.desktops[hostId]?.log?.value?.takeLast(8)}", e) }
    }

    private fun idle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        runCatching { compose.waitUntil(ms + 1000) { System.currentTimeMillis() >= end } }
    }

    /** PowerShell on the PC over a login of its own. */
    private fun remote(script: String): String = runBlocking {
        val host = app.database.hosts().find(hostId)!!
        val ssh = app.pageLogin(host, app.credentialsFor(host, "", ""), MutableStateFlow<PendingPrompt?>(null))
        val out = StringBuilder()
        withTimeout(40_000) { ssh.administer { it.runCommand(io.itermux.ssh.PowerShell.command(script), 30_000) { line -> out.appendLine(line) } } }
        ssh.disconnect()
        out.toString()
    }

    private fun key(hid: Int) { desktop().input(DesktopProtocol.key(hid, true)); desktop().input(DesktopProtocol.key(hid, false)) }

    @Test fun w01_signInScreenThroughTheService() {
        compose.onNodeWithTag("home-tab-hosts").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("host-card-$hostId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("host-card-$hostId").performClick()
        E2e.waitTag(compose, "host-detail-desktop")
        compose.onNodeWithTag("host-detail-desktop", useUnmergedTree = true).performClick()
        E2e.waitTag(compose, "desktop-page")
        waitPhase(120_000, "first frame") { it is DesktopPhase.Connected || it is DesktopPhase.Failed }
        assertTrue("${desktop().phase.value} ${desktop().log.value.takeLast(8)}", desktop().phase.value is DesktopPhase.Connected)
        assertEquals("windows", desktop().hello.value!!.os)
        compose.waitUntil(10_000) { desktop().decoder.decodedFrames > 0 }
        assertTrue("service running", "Running" in remote("(Get-Service RoamttyDesktop).Status"))
        E2e.capture(compose, "win-01-desktop")

        // The console's session disconnected: the PC is at its lock screen, the helper moves sessions.
        remote("tsdiscon (Get-Process explorer | Select-Object -First 1).SessionId")
        idle(4_000)
        val before = desktop().decoder.decodedFrames
        waitPhase(60_000, "back after the session switch") { it is DesktopPhase.Connected && !it.paused }
        compose.waitUntil(30_000) { desktop().decoder.decodedFrames > before }
        assertTrue("locked", remote("@(Get-Process LogonUI -ErrorAction SilentlyContinue).Count").trim().toInt() > 0)
        E2e.capture(compose, "win-02-lock-screen")

        // Signed back in from the phone: a key for the curtain, the password, Enter.
        key(HidUsage.SPACE)
        idle(2_000)
        desktop().input(DesktopProtocol.text(password))
        idle(500)
        key(HidUsage.ENTER)
        compose.waitUntil(30_000) { remote("@(Get-Process LogonUI -ErrorAction SilentlyContinue).Count").trim() == "0" }
        val after = desktop().decoder.decodedFrames
        waitPhase(60_000, "back on the desktop") { it is DesktopPhase.Connected && !it.paused }
        compose.waitUntil(30_000) { desktop().decoder.decodedFrames > after }
        idle(2_000)
        E2e.capture(compose, "win-03-signed-in")
    }
}
