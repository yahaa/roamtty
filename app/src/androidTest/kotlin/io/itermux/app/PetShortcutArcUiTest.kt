package io.itermux.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.session.ScreenApp
import io.itermux.app.ui.PetAction
import io.itermux.app.ui.PetArc
import io.itermux.app.ui.PetShortcutArc
import io.itermux.app.ui.ToolboxPet
import io.itermux.app.ui.petShortcuts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PetShortcutArcUiTest {
    @get:Rule val compose = createComposeRule()

    // Every program's ring shows all its items and reports its layout back for hover tracking; F1–F12 fit as well.
    @Test fun ringShowsEveryShortcut() {
        var shown by mutableStateOf(petShortcuts(ScreenApp.SHELL))
        var hovered by mutableStateOf<Int?>(null)
        var arc: PetArc? = null
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                Box(Modifier.size(360.dp, 640.dp).background(Color(0xFF101014))) {
                    val center = with(LocalDensity.current) { Offset(332.dp.toPx(), 244.dp.toPx()) }
                    PetShortcutArc(shown, center, 0f, hovered = hovered) { arc = it }
                    // The pet itself, at the ring's centre and blown up in a corner to check the drawing.
                    Box(Modifier.offset(308.dp, 220.dp)) { ToolboxPet("pet") {} }
                    Box(Modifier.offset(150.dp, 520.dp).graphicsLayer { scaleX = 4f; scaleY = 4f }) { ToolboxPet("big") {} }
                }
            }
        }
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "pet-arc").apply { mkdirs() }
        val rings = ScreenApp.entries.map { it.name.lowercase() to petShortcuts(it) } +
            ("fn" to (petShortcuts(ScreenApp.SHELL).first().action as PetAction.Menu).items)
        rings.forEachIndexed { i, (name, items) ->
            shown = items
            hovered = if (i == 1) 3 else null
            compose.waitForIdle()
            items.forEach { compose.onNodeWithTag(it.tag).assertIsDisplayed() }
            assertEquals(items.size, arc?.angles?.size)
            File(dir, "ring-$name.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    // An open ring reports a tapped item by index and a tap beside the items as null.
    @Test fun openRingReportsTaps() {
        val taps = mutableListOf<Int?>()
        val shortcuts = petShortcuts(ScreenApp.SHELL)
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                Box(Modifier.size(360.dp, 640.dp)) {
                    val center = with(LocalDensity.current) { Offset(332.dp.toPx(), 244.dp.toPx()) }
                    PetShortcutArc(shortcuts, center, 0f, hovered = null, onTap = { taps += it }) {}
                }
            }
        }
        compose.onNodeWithTag("pet-tab").performClick()
        compose.onNodeWithTag("pet-shortcut-arc").performTouchInput { click(Offset(20f, 20f)) }
        compose.waitForIdle()
        assertEquals(listOf(shortcuts.indexOfFirst { it.tag == "pet-tab" }, null), taps)
    }
}
