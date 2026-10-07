package io.itermux.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick

/** Opens [sessionId]'s terminal from the navigation drawer, which lists every session. */
fun AndroidComposeTestRule<*, MainActivity>.openSession(sessionId: String) {
    waitUntil(10_000) { runCatching { onAllNodes(hasTestTag("brand-logo")).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
    onNodeWithTag("brand-logo").performClick()
    onNodeWithTag("drawer-session-$sessionId").performClick()
}
