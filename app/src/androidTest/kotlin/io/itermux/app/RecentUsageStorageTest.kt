package io.itermux.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.RecentUsage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecentUsageStorageTest {
    @Test fun recentOrderSurvivesReloadAndDeletedEntriesArePruned() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "recent-usage-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val usage = RecentUsage(preferences)
            usage.useSession("first", "alpha")
            usage.useSession("second", "zulu")
            usage.useSession("first", "alpha")
            usage.useHost("zulu")
            val restored = RecentUsage(preferences)
            assertEquals(listOf("first", "second"), restored.sessionIds.value)
            assertEquals(listOf("zulu", "alpha"), restored.hostIds.value)
            restored.retain(setOf("alpha"), setOf("first"))
            val pruned = RecentUsage(preferences)
            assertEquals(listOf("first"), pruned.sessionIds.value)
            assertEquals(listOf("alpha"), pruned.hostIds.value)
            pruned.retain(emptySet(), emptySet())
            assertEquals(emptyList<String>(), RecentUsage(preferences).hostIds.value)
            assertEquals(emptyList<String>(), RecentUsage(preferences).sessionIds.value)
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun invalidHistoryFallsBackWithoutLosingTheOtherList() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "recent-usage-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            preferences.edit().putString("hosts", "broken").putString("sessions", "[\"first\",\"first\",\"second\"]").commit()
            val usage = RecentUsage(preferences)
            assertEquals(emptyList<String>(), usage.hostIds.value)
            assertEquals(listOf("first", "second"), usage.sessionIds.value)
            usage.useHost("alpha")
            assertEquals(listOf("alpha"), RecentUsage(preferences).hostIds.value)
        } finally { context.deleteSharedPreferences(name) }
    }
}
