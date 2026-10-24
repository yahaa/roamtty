package io.itermux.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.ssh.Credentials
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against refused loopback ports: neither a remote host nor credentials are needed. */
@RunWith(AndroidJUnit4::class)
class RecentUsageUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as ItermuxApp
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun above(first: String, second: String) =
        compose.onNodeWithTag(first).getUnclippedBoundsInRoot().top < compose.onNodeWithTag(second).getUnclippedBoundsInRoot().top
    private fun host(name: String) = Host(name = name, address = "127.0.0.1", port = 1, username = "test")

    @Test fun openingSessionPromotesItAndItsHostButBackgroundReconnectDoesNot() = runBlocking<Unit> {
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val alpha = host("A recency")
        val zulu = host("Z recency")
        app.database.hosts().save(alpha)
        app.database.hosts().save(zulu)
        val (first, second) = withContext(Dispatchers.Main) {
            listOf(service.open(alpha, SessionMode.SHELL, Credentials()), service.open(zulu, SessionMode.SHELL, Credentials()))
                .also { sessions -> sessions.forEach { it.disconnect() } }
        }
        val firstTag = "home-session-${first.id}"
        val secondTag = "home-session-${second.id}"
        try {
            compose.onNodeWithTag("home-tab-sessions").performClick()
            compose.waitUntil(10_000) { exists(firstTag) && exists(secondTag) }
            compose.onNodeWithTag(secondTag).performClick()
            compose.waitUntil(5_000) { !exists("home-pager") }
            Espresso.pressBack()
            compose.waitUntil(5_000) { exists(firstTag) && exists(secondTag) }
            assertTrue("the session just opened comes first", above(secondTag, firstTag))

            compose.activityRule.scenario.recreate()
            compose.waitUntil(8_000) { exists(firstTag) && exists(secondTag) }
            assertTrue("recreating the activity preserves recency", above(secondTag, firstTag))
            withContext(Dispatchers.Main) { service.reconnect(first.id, Credentials()) }
            compose.waitForIdle()
            assertTrue("a background reconnect does not count as use", above(secondTag, firstTag))

            compose.onNodeWithTag("home-tab-hosts").performClick()
            compose.waitUntil(5_000) { exists("host-card-${alpha.id}") && exists("host-card-${zulu.id}") }
            assertTrue("opening the session also promotes its host", above("host-card-${zulu.id}", "host-card-${alpha.id}"))
        } finally {
            withContext(Dispatchers.Main) { service.close(first.id); service.close(second.id) }
            app.database.hosts().delete(alpha.id)
            app.database.hosts().delete(zulu.id)
        }
    }

    @Test fun openingHostDetailsPromotesItAndKeepsOrderAfterRecreation() = runBlocking<Unit> {
        val alpha = host("A details")
        val zulu = host("Z details")
        app.database.hosts().save(alpha)
        app.database.hosts().save(zulu)
        val first = "host-card-${alpha.id}"
        val second = "host-card-${zulu.id}"
        try {
            compose.onNodeWithTag("home-tab-hosts").performClick()
            compose.waitUntil(8_000) { exists(first) && exists(second) }
            assertTrue("unused hosts keep their alphabetical order", above(first, second))
            compose.onNodeWithTag(second).performClick()
            compose.waitUntil(5_000) { !exists("home-pager") }
            Espresso.pressBack()
            compose.waitUntil(5_000) { exists(first) && exists(second) }
            assertTrue("the host just opened comes first", above(second, first))
            compose.activityRule.scenario.recreate()
            compose.waitUntil(8_000) { exists(first) && exists(second) }
            assertTrue("host ordering survives recreation", above(second, first))
        } finally {
            app.database.hosts().delete(alpha.id)
            app.database.hosts().delete(zulu.id)
        }
    }

    @Test fun recreationKeepsHostScrollPositionWhenUsageHasNotChanged() = runBlocking<Unit> {
        val hosts = List(8) { host("Scroll $it") }
        hosts.forEach { app.database.hosts().save(it) }
        val last = "host-card-${hosts.last().id}"
        try {
            compose.onNodeWithTag("home-tab-hosts").performClick()
            compose.waitUntil(8_000) { exists("host-grid") }
            compose.onNodeWithTag("host-grid").performScrollToNode(hasTestTag(last))
            compose.onNodeWithTag(last).assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(8_000) { exists("host-grid") }
            compose.onNodeWithTag(last).assertIsDisplayed()
        } finally { hosts.forEach { app.database.hosts().delete(it.id) } }
    }
}
