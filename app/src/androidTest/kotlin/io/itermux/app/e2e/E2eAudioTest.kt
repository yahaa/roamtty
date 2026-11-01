package io.itermux.app.e2e

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.MainActivity
import io.itermux.app.data.Host
import io.itermux.app.data.StoredPin
import io.itermux.app.data.credentialsFor
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.desktop.DesktopSettings
import io.itermux.app.e2e.E2e.app
import io.itermux.app.session.PendingPrompt
import io.itermux.app.session.SessionService
import io.itermux.app.session.pageLogin
import io.itermux.desktop.DesktopPhase
import io.itermux.ssh.AuthMethod
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
 * The host's sound on the phone: a Linux desktop with PulseAudio (the Ubuntu VM's null sink will do)
 * plays a 440 Hz / 660 Hz tone, and the phone must decode that tone, near live; 声音 off stops the
 * packets and on brings them back, and leaving the page stops them too.
 *
 * Runs only with `-e audioHost <address> -e audioUser <user> -e audioPassword <password>
 * -e audioHostKey <ssh-ed25519 blob>`; the plugin comes from `-e audioPluginUrl` (the local dist where the host can
 * reach it), else e2eDesktopUrl.
 */
@RunWith(AndroidJUnit4::class)
class E2eAudioTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val serviceRule = ServiceTestRule()
    private lateinit var service: SessionService
    private val args get() = InstrumentationRegistry.getArguments()
    private val address get() = args.getString("audioHost").orEmpty()
    private val password get() = args.getString("audioPassword").orEmpty()
    private var hostId = ""

    @Before fun setUp() {
        assumeTrue("no audio host (audioHost)", address.isNotEmpty() && password.isNotEmpty())
        service = E2e.bind(serviceRule)
        cleanup()
        val endpoint = "[$address]:22"
        val host = runBlocking {
            app.database.hosts().forgetPin(endpoint)
            app.database.hosts().insertPin(StoredPin(endpoint, "ssh-ed25519", args.getString("audioHostKey").orEmpty()))
            val id = UUID.randomUUID().toString()
            E2e.save(Host(id, E2e.PREFIX + "audio", address, 22, args.getString("audioUser") ?: "desktop", AuthMethod.PASSWORD.name,
                SessionMode.SHELL.name, encryptedPassword = app.vault.encrypt(password.toByteArray(), "$id:password"), os = "UBUNTU"))
        }
        hostId = host.id
        DesktopSettings(E2e.context).apply {
            keepAliveMinutes = 10; showStats = true
            pluginBaseUrl = args.getString("audioPluginUrl") ?: args.getString("e2eDesktopUrl").orEmpty()
            setAudio(hostId, true)
        }
        E2e.prepareActivity(compose)
        E2e.waitTag(compose, "home-pager")
    }

    @After fun tearDown() {
        if (address.isEmpty()) return
        runCatching { remote("pkill -f [r]oamtty-tone; pkill paplay; true") }
        cleanup()
        DesktopSettings(E2e.context).apply { pluginBaseUrl = ""; showStats = false }
    }

    private fun cleanup() {
        runBlocking(Dispatchers.Main) {
            service.desktops.connections.value.values.filter { it.host.name.startsWith(E2e.PREFIX) }.forEach { service.desktops.close(it.host.id) }
        }
        E2e.cleanup(service)
    }

    private fun desktop(): DesktopConnection = service.desktops[hostId] ?: error("no desktop")

    private fun idle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        runCatching { compose.waitUntil(ms + 1000) { System.currentTimeMillis() >= end } }
    }

    private fun remote(script: String): String = runBlocking {
        val host = app.database.hosts().find(hostId)!!
        val ssh = app.pageLogin(host, app.credentialsFor(host, "", ""), MutableStateFlow<PendingPrompt?>(null))
        val out = StringBuilder()
        withTimeout(40_000) { ssh.administer { it.run(script, 30_000) { line -> out.appendLine(line) } } }
        ssh.disconnect()
        out.toString()
    }

    private fun click(tag: String) {
        E2e.waitTag(compose, tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    private fun toggleSound() {
        click("desktop-rail-operations")
        E2e.waitTag(compose, "desktop-operations")
        compose.onNodeWithTag("desktop-audio").performScrollTo()
        click("desktop-audio")
        click("desktop-op-close")
        E2e.waitGone(compose, hasTestTag("desktop-operations"))
    }

    /** The tone: 440 Hz on the left, 660 Hz on the right, looped on the host's default output. */
    private fun playTone() {
        remote("""
            mkdir -p ~/.cache/roamtty-e2e && cd ~/.cache/roamtty-e2e
            [ -f tone.wav ] || python3 -c '
import wave, math, struct
w = wave.open("tone.wav", "wb"); w.setnchannels(2); w.setsampwidth(2); w.setframerate(48000)
w.writeframes(b"".join(struct.pack("<hh", int(8000 * math.sin(i * 440 * 2 * math.pi / 48000)), int(8000 * math.sin(i * 660 * 2 * math.pi / 48000))) for i in range(48000 * 8)))'
            printf 'while :; do paplay tone.wav; done\n' > roamtty-tone.sh
            nohup sh roamtty-tone.sh >/dev/null 2>&1 &
            echo started
        """.trimIndent())
    }

    @Test fun a01_theHostsToneIsHeardNearLive() {
        playTone()
        compose.onNodeWithTag("home-tab-hosts").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("host-card-$hostId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("host-card-$hostId").performClick()
        E2e.waitTag(compose, "host-detail-desktop")
        compose.onNodeWithTag("host-detail-desktop", useUnmergedTree = true).performClick()
        E2e.waitTag(compose, "desktop-page")
        compose.waitUntil(180_000) { service.desktops[hostId]?.phase?.value.let { it is DesktopPhase.Connected || it is DesktopPhase.Failed } }
        assertTrue("${desktop().phase.value} ${desktop().log.value.takeLast(8)}", desktop().phase.value is DesktopPhase.Connected)
        assertEquals(listOf("opus"), desktop().hello.value!!.audio)

        // 440 Hz crosses zero 880 times a second; the tone's RMS is 8000 / √2.
        val player = desktop().audioPlayer
        try { compose.waitUntil(20_000) { player.level.crossings in 860..900 && player.level.rms in 5000..6300 } }
        catch (e: Throwable) { throw AssertionError("level ${player.level}, packets ${player.packets}", e) }
        // Long enough for the drift control to settle.
        idle(10_000)
        val dropsBefore = player.dropped
        val packetsBefore = player.packets
        val queued = (1..10).map { idle(300); player.queuedMs }
        E2e.metric("desktop_audio_backlog_ms", queued.max(), "ms")
        E2e.metric("desktop_audio_target_ms", player.targetMs, "ms")
        E2e.metric("desktop_audio_dropped", player.dropped - dropsBefore, "count")
        val state = "queued $queued, dropped ${player.dropped} of ${player.packets}, cushions ${player.cushions}, target ${player.targetMs}, rate ${player.playbackRate}"
        // As deep as the network's jitter, never past the drop line above it.
        assertTrue(state, queued.max() <= player.targetMs + 120 && player.targetMs <= 300)
        assertTrue(state, (player.dropped - dropsBefore) * 50 <= player.packets - packetsBefore)
        android.util.Log.i("RoamttyE2e", state)
        E2e.capture(compose, "audio-01-playing")

        // 声音 off: the host stops sending; on again: it comes back.
        toggleSound()
        assertFalse(desktop().audio.value)
        idle(1_500)
        val stopped = player.packets
        idle(2_000)
        assertEquals("no packets while off", stopped, player.packets)
        toggleSound()
        compose.waitUntil(15_000) { player.packets > stopped + 50 }

        // Leaving the page pauses the desktop and its sound; coming back resumes both.
        click("desktop-rail-operations")
        click("desktop-op-exit")
        compose.waitUntil(10_000) { service.desktops[hostId]?.phase?.value.let { it is DesktopPhase.Connected && it.paused } }
        idle(1_500)
        val paused = player.packets
        idle(2_000)
        assertEquals("no packets while paused", paused, player.packets)
    }
}
