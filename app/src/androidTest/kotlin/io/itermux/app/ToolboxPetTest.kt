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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.itermux.app.ui.ToolboxPet
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
}
