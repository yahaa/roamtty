package io.itermux.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentUsageOrderTest {
    @Test fun recentUseWinsAndUnseenEntriesKeepTheirOrder() {
        assertEquals(listOf("c", "a", "b", "d"), listOf("a", "b", "c", "d").recentFirst(listOf("gone", "c", "a")) { it })
    }

    @Test fun noHistoryKeepsLegacyOrder() {
        assertEquals(listOf("b", "a"), listOf("b", "a").recentFirst(emptyList()) { it })
        assertEquals(emptyList<String>(), emptyList<String>().recentFirst(listOf("gone")) { it })
    }
}
