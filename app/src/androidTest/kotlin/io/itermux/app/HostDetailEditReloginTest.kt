package io.itermux.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.Host
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The host page logs in by itself. Fixing the username while the page stays open (the editor sits on top of it)
 * must log in again with the saved user, not keep retrying the one the page opened with.
 */
@RunWith(AndroidJUnit4::class)
class HostDetailEditReloginTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun editedUsernameLogsInAgain() = runBlocking<Unit> {
        instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        compose.waitUntil(10_000) { runCatching { compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        val app = instrumentation.targetContext.applicationContext as ItermuxApp
        val password = InstrumentationRegistry.getArguments().getString("fixturePassword")
            ?: instrumentation.context.assets.open("test_password").bufferedReader().use { it.readText().trim() }
        app.database.hosts().forgetPin("[127.0.0.1]:22228")
        val id = "host-edit-relogin"
        val wrong = Host(id = id, name = "Relogin box", address = "127.0.0.1", port = 22228, username = "no_such_user",
            mode = "SHELL", encryptedPassword = app.vault.encrypt(password.toByteArray(), "$id:password"))
        app.database.hosts().save(wrong)
        try {
            fun trustIfAsked() {
                if (compose.onAllNodesWithText("信任并连接").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("信任并连接").performClick()
            }
            compose.onNodeWithTag("home-tab-hosts").performClick()
            compose.waitUntil(8000) { compose.onAllNodesWithTag("host-card-$id").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("host-card-$id").performClick()
            compose.waitUntil(20_000) { trustIfAsked(); compose.onAllNodesWithTag("host-monitor-error").fetchSemanticsNodes().isNotEmpty() }

            app.database.hosts().save(wrong.copy(username = "itermux_test"))
            compose.waitUntil(20_000) {
                trustIfAsked()
                compose.onAllNodes(hasAnyAncestor(hasTestTag("host-gauge-cpu")) and hasText("%", substring = true), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("host-detail").assertExists()
        } finally {
            app.database.hosts().delete(id)
        }
    }
}
