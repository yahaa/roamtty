package io.itermux.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode

/** Opens [sessionId]'s terminal from the home page, which lists every session above the hosts. */
fun AndroidComposeTestRule<*, MainActivity>.openSession(sessionId: String) {
    // Settings opens over the home page; close it first.
    if (onAllNodes(hasTestTag("close-settings")).fetchSemanticsNodes().isNotEmpty()) onNodeWithTag("close-settings").performClick()
    // The row sits at the top of the lazy grid, which composes it only once scrolled back into view.
    waitUntil(10_000) {
        runCatching { onNodeWithTag("host-grid").performScrollToNode(hasTestTag("home-session-$sessionId")); true }.getOrDefault(false)
    }
    onNodeWithTag("home-session-$sessionId").performClick()
}
