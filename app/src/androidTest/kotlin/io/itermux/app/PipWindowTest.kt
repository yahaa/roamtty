package io.itermux.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.ui.PipState
import io.itermux.app.ui.PipWindow
import io.itermux.app.desktop.DesktopSettings
import io.itermux.desktop.PipAnchor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The small window's gestures: a tap changes places, a drag rests in a corner or closes on ✕, a pinch resizes. */
class PipWindowTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = "test-${System.nanoTime()}"
    private lateinit var state: PipState
    private var taps = 0
    private var closes = 0
    private var avoid by mutableStateOf(emptyList<Rect>())

    @Before fun setUp() {
        state = PipState(key, DesktopSettings(context), PipAnchor.TOP_END, 0.5f)
        compose.setContent { MaterialTheme {
            Box(Modifier.size(320.dp, 560.dp).testTag("stage")) {
                val px = with(LocalDensity.current) { 1.dp.toPx() }
                Window(Rect(0f, 0f, 320 * px, 560 * px), 320 * px)
            }
        } }
    }

    @androidx.compose.runtime.Composable
    private fun BoxScope.Window(room: Rect, reference: Float) =
        PipWindow(state, room, reference, aspect = 1.6f, avoid = avoid, tag = "pip", description = "pip",
            onTap = { taps++ }, onClose = { closes++ }) {}

    private fun bounds() = compose.onNodeWithTag("pip").fetchSemanticsNode().boundsInRoot
    /** The area the window floats in. */
    private fun root() = compose.onNodeWithTag("stage").fetchSemanticsNode().boundsInRoot

    @Test fun restsInItsCornerAtItsSize() {
        val b = bounds()
        assertEquals(root().width * 0.5f, b.width, 2f)
        assertEquals(b.width / 1.6f, b.height, 2f)
        assertEquals(root().right, b.right, 2f)
        assertEquals(root().top, b.top, 2f)
    }

    @Test fun aTapChangesPlaces() {
        compose.onNodeWithTag("pip").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, taps); assertEquals(0, closes) }
    }

    @Test fun aDragRestsInTheNearestCornerAndIsRemembered() {
        compose.onNodeWithTag("pip").performTouchInput { swipe(center, center + Offset(-width * 1.2f, height * 4f), 400) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(PipAnchor.BOTTOM_START, state.anchor); assertEquals(0, taps); assertEquals(0, closes) }
        assertEquals(PipAnchor.BOTTOM_START.name, DesktopSettings(context).pipAnchor(key))
        compose.mainClock.advanceTimeBy(2000)
        val b = bounds()
        assertEquals(root().left, b.left, 2f)
        assertEquals(root().bottom, b.bottom, 2f)
    }

    @Test fun aDragOntoTheCloseTargetCloses() {
        val target = Offset(root().center.x, root().bottom - with(compose.density) { 40.dp.toPx() })
        val start = bounds().center
        compose.onNodeWithTag("pip").performTouchInput {
            down(center)
            moveTo(center + Offset(-40f, 40f))
            // In the window's own coordinates: from its centre to the target.
            moveTo(center + (target - start), delayMillis = 100)
        }
        compose.onNodeWithTag("pip-close-target").assertExists()
        compose.onNodeWithTag("pip").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, closes); assertEquals(0, taps) }
    }

    @Test fun aPinchResizesWithinItsRange() {
        val before = bounds().width
        compose.onNodeWithTag("pip").performTouchInput {
            pinch(center - Offset(20f, 0f), center - Offset(width / 2f, 0f), center + Offset(20f, 0f), center + Offset(width / 2f, 0f), 300)
        }
        compose.runOnIdle {
            assertTrue("grew: ${state.fraction}", state.fraction > 0.5f)
            assertTrue(state.fraction <= 1f)
            assertEquals(0, taps)
        }
        assertTrue(bounds().width > before)
        assertEquals(state.fraction, DesktopSettings(context).pipFraction(key)!!, 0.001f)
    }

    @Test fun itMovesOffWhatItWouldCover() {
        val row = with(compose.density) { Rect(0f, 20.dp.toPx(), 320.dp.toPx(), 40.dp.toPx()) }
        compose.runOnIdle { avoid = listOf(row) }
        compose.mainClock.advanceTimeBy(2000)
        assertEquals(root().bottom, bounds().bottom, 2f)
        // The remembered corner is still the one it was put in: it comes back once nothing is covered.
        compose.runOnIdle { assertEquals(PipAnchor.TOP_END, state.anchor); avoid = emptyList() }
        compose.mainClock.advanceTimeBy(2000)
        assertEquals(root().top, bounds().top, 2f)
    }
}
