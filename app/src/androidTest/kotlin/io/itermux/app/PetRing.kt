package io.itermux.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput

/**
 * Holds the collapsed toolbox pet until its shortcut ring opens, runs [whileOpen], then lets go over the item
 * tagged [tag]. The ring is gone afterwards.
 */
internal fun ComposeTestRule.dropOnPetShortcut(tag: String, whileOpen: () -> Unit = {}) {
    onNodeWithTag("restore-dock").performTouchInput {
        down(center)
        advanceEventTime(viewConfiguration.longPressTimeoutMillis + 150)
        moveBy(Offset(-2f, 0f))
    }
    waitUntil(3_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
    whileOpen()
    val pet = onNodeWithTag("restore-dock").fetchSemanticsNode().boundsInRoot
    val item = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    onNodeWithTag("restore-dock").performTouchInput {
        val target = item.center - pet.topLeft
        moveTo((center + target) / 2f)
        moveTo(target)
        up()
    }
    waitForIdle()
    onNodeWithTag("pet-shortcut-arc").assertDoesNotExist()
}
