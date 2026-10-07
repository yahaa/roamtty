package io.itermux.app

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.itermux.app.ui.ToolboxPet
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolboxPetTest {
    @get:Rule val compose = createComposeRule()

    // Both themes compose; the pet keeps the old 48dp button target and still opens the dock.
    @Test fun petKeepsAFullTouchTargetAndOpensTheDock() {
        var clicks = 0
        compose.setContent {
            Row {
                MaterialTheme(lightColorScheme()) { ToolboxPet("显示操作栏") { clicks++ } }
                MaterialTheme(darkColorScheme()) { Surface { ToolboxPet("dark") {} } }
            }
        }
        compose.onNodeWithContentDescription("显示操作栏").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    // A long press starts a hold instead of a click; dragging reports the finger offset and lifting ends the hold.
    @Test fun longPressDragReportsTheHold() {
        var clicks = 0
        var start: Offset? = null
        var last = Offset.Zero
        var ended: Boolean? = null
        compose.setContent {
            MaterialTheme(lightColorScheme()) {
                ToolboxPet("pet", onHoldStart = { start = it }, onHoldMove = { last = it }, onHoldEnd = { ended = it }) { clicks++ }
            }
        }
        compose.onNodeWithTag("restore-dock").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(-60f, -20f))
            moveBy(Offset(-140f, -20f))
            up()
        }
        compose.waitForIdle()
        assertNotNull(start)
        assertTrue("dragged left: $last", last.x < -150f && last.y < -30f)
        assertEquals(true, ended)
        assertEquals(0, clicks)
    }
}
