package io.itermux.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode

/** Download rows are newest first, so a fixture row can sit below the fold: wait until it scrolls into view. */
internal fun ComposeTestRule.waitForDownloadEntry(name: String, timeoutMillis: Long = 10_000) = waitUntil(timeoutMillis) {
    runCatching {
        onNodeWithTag("download-files-list").performScrollToNode(hasTestTag("download-entry-$name"))
    }.isSuccess
}
