package io.itermux.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Start in a fresh test process: no activity or bound service exists while the journal is seeded. */
@RunWith(AndroidJUnit4::class)
class RecentUsageRestorationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun initialEmptyServiceDoesNotErasePersistedSessionOrder() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ItermuxApp
        val host = Host(name = "Restored order", address = "127.0.0.1", port = 1, username = "test")
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val journal = context.getSharedPreferences("session-journal", Context.MODE_PRIVATE)
        val previous = journal.getString("sessions", "[]")
        app.database.hosts().save(host)
        val entries = JSONArray(previous)
        listOf(first, second).forEachIndexed { index, id ->
            entries.put(JSONObject().put("id", id).put("hostId", host.id).put("mode", "SHELL")
                .put("title", "Restored $index").put("sessionNumber", index + 1))
        }
        journal.edit().putString("sessions", entries.toString()).commit()
        withContext(Dispatchers.Main) { app.recentUsage.useSession(second, host.id) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val firstTag = "home-session-$first"
                val secondTag = "home-session-$second"
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag(firstTag).fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodesWithTag(secondTag).fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue("the initial empty list must not erase saved usage",
                    compose.onNodeWithTag(secondTag).getUnclippedBoundsInRoot().top <
                        compose.onNodeWithTag(firstTag).getUnclippedBoundsInRoot().top)
            }
        } finally {
            val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
            service.awaitRestoration()
            withContext(Dispatchers.Main) { service.close(first); service.close(second) }
            app.database.hosts().delete(host.id)
        }
    }
}
