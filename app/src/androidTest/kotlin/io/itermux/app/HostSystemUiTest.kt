package io.itermux.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.app.ui.HostEditorDraft
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Requires host-system-server.py ubuntu on loopback:22238, forwarded with adb reverse. */
@RunWith(AndroidJUnit4::class)
class HostSystemUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as ItermuxApp
    private val endpoint = "[127.0.0.1]:22238"

    @Before fun prepare() {
        compose.runOnUiThread { AppLocale.set(context, AppLanguage.ZH_CN) }
    }

    private fun fillEditor(name: String, password: String = "", port: String = "22238") {
        compose.onNodeWithTag("home-tab-hosts").performClick()
        compose.onNodeWithTag("add-host").performClick()
        compose.onNodeWithTag("host-name").performTextInput(name)
        compose.onNodeWithTag("host-address").performTextInput("127.0.0.1")
        compose.onNodeWithTag("host-username").performTextInput("test")
        compose.onNodeWithTag("host-port").performTextReplacement(port)
        if (password.isNotEmpty()) compose.onNodeWithTag("host-password").performScrollTo().performTextInput(password)
        compose.onNodeWithTag("save-host").performClick()
    }

    private fun awaitSaved() = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("host-editor").fetchSemanticsNodes().isEmpty()
    }

    private suspend fun savedHost(name: String) = app.database.hosts().observe().first().single { it.name == name }
    private suspend fun removeHost(name: String) {
        app.database.hosts().observe().first().filter { it.name == name }.forEach { app.database.hosts().delete(it.id) }
    }

    @Test fun saveDetectsUbuntuSurvivesRotationAndDoesNotStoreTemporaryPassword() = runBlocking<Unit> {
        val name = "Automatic Ubuntu"
        app.database.hosts().forgetPin(endpoint)
        try {
            fillEditor(name, "fixture-only")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("信任并连接").fetchSemanticsNodes().isNotEmpty() }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("信任并连接").assertIsDisplayed().performClick()
            awaitSaved()
            val host = savedHost(name)
            assertEquals("UBUNTU", host.os)
            assertNull(host.encryptedPassword)
            compose.onNodeWithTag("host-card-${host.id}").assert(hasContentDescription("Ubuntu"))
            compose.runOnUiThread {
                assertEquals("", ViewModelProvider(compose.activity)[HostEditorDraft::class.java].password.value)
            }
        } finally { removeHost(name); app.database.hosts().forgetPin(endpoint) }
    }

    @Test fun offlineHostCanBeSavedWithoutCredentials() = runBlocking<Unit> {
        val name = "Offline host"
        try {
            fillEditor(name, port = "1")
            awaitSaved()
            assertEquals("UNKNOWN", savedHost(name).os)
            compose.onNodeWithText("待检测系统").assertExists()
        } finally { removeHost(name) }
    }

    @Test fun rejectedFingerprintStillSavesHostForLaterConnection() = runBlocking<Unit> {
        val name = "Deferred detection"
        app.database.hosts().forgetPin(endpoint)
        try {
            fillEditor(name, "fixture-only")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("信任并连接").fetchSemanticsNodes().isNotEmpty() }
            // The editor's cancel action is disabled during saving; cancel the trust prompt.
            compose.onAllNodesWithText("取消").filter(isEnabled()).onFirst().performClick()
            awaitSaved()
            assertEquals("UNKNOWN", savedHost(name).os)
            assertNull(app.database.hosts().pin(endpoint))
        } finally { removeHost(name) }
    }

    @Test fun existingHostUpdatesAfterAuthenticatedTerminalConnection() = runBlocking<Unit> {
        val host = Host(name = "Old Linux host", address = "127.0.0.1", port = 22238, username = "test", mode = "SHELL", os = "LINUX")
        app.database.hosts().save(host)
        app.database.hosts().forgetPin(endpoint)
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials("fixture-only".toByteArray())) }
        try {
            val request = withTimeout(10_000) { session.pending.first { it != null }!! }
            assertTrue(request.prompt is Prompt.VerifyHost)
            compose.onNodeWithTag("brand-logo").performClick()
            // Sessions wear the tile they were created from (a plain terminal's), not the host's system.
            compose.onNode(hasTestTag("terminal-icon") and hasAnyAncestor(hasTestTag("drawer-session-${session.id}")),
                useUnmergedTree = true).assertExists()
            request.answer.complete(listOf("trust"))
            withTimeout(10_000) { app.database.hosts().observe().first { hosts -> hosts.any { it.id == host.id && it.os == "UBUNTU" } } }
            assertEquals(HostSystem.UBUNTU, session.detectedSystem.value)
            compose.onNodeWithTag("drawer-hosts").performClick()
            compose.onNodeWithTag("host-card-${host.id}").assert(hasContentDescription("Ubuntu"))
            withTimeout(10_000) { session.state.first { it.phase == Phase.CONNECTED } }
            withContext(Dispatchers.Main) {
                service.pendingSharedImages.value = listOf(Uri.parse("content://io.itermux.test/shared-image"))
            }
            compose.onNode(hasTestTag("terminal-icon") and hasAnyAncestor(hasTestTag("share-session-${session.id}")),
                useUnmergedTree = true).assertExists()
        } finally {
            withContext(Dispatchers.Main) {
                service.pendingSharedImages.value = emptyList()
                service.close(session.id)
            }
            app.database.hosts().delete(host.id)
            app.database.hosts().forgetPin(endpoint)
        }
    }

    @Test fun supportedLogosAndGenericPenguinRenderInHostList() = runBlocking<Unit> {
        val preferences = context.getSharedPreferences("app-settings", android.content.Context.MODE_PRIVATE)
        val previousTheme = preferences.getString("theme", null)
        val hosts = listOf("UBUNTU", "DEBIAN", "CENTOS", "LINUX", "WINDOWS", "MACOS", "REDHAT", "FEDORA").map {
            Host(name = HostSystem.fromStored(it).label, address = "${it.lowercase()}.example", username = "dev", os = it)
        }
        // The list sorts by name; wait for the first row, since later rows may be off screen.
        val first = hosts.minBy { it.name.lowercase() }
        try {
            hosts.forEach { app.database.hosts().save(it) }
            compose.onNodeWithTag("home-tab-hosts").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("host-card-${first.id}").fetchSemanticsNodes().isNotEmpty() }
            hosts.forEach {
                compose.onNodeWithTag("host-grid").performScrollToNode(hasTestTag("host-card-${it.id}"))
                compose.onNodeWithTag("host-card-${it.id}").assert(hasContentDescription(HostSystem.fromStored(it.os).label))
            }
            for (theme in listOf("DARK", "LIGHT")) {
                preferences.edit().putString("theme", theme).commit()
                compose.activityRule.scenario.recreate()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("host-grid").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("host-grid").performScrollToNode(hasTestTag("host-card-${first.id}"))
                File(context.getExternalFilesDir(null), "host-system-icons-${theme.lowercase()}.png").outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally {
            preferences.edit().apply { if (previousTheme == null) remove("theme") else putString("theme", previousTheme) }.commit()
            hosts.forEach { app.database.hosts().delete(it.id) }
        }
    }
}
