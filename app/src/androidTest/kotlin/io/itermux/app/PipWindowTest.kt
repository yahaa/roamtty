package io.itermux.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.desktop.DesktopSettings
import io.itermux.app.ui.PipState
import io.itermux.app.ui.PipWindow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The small window's gestures: a tap opens the desktop, a drag puts it anywhere or closes it on ✕, its handle or a pinch resizes it. */
class PipWindowTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = "test-${System.nanoTime()}"
    private lateinit var state: PipState
    private var taps = 0
    private var closes = 0
    private var fulls = 0
    private var contentTaps = 0
    private var focused by androidx.compose.runtime.mutableStateOf(false)

    @Before fun setUp() {
        state = PipState(key, DesktopSettings(context), 0.5f)
        compose.setContent { MaterialTheme {
            Box(Modifier.size(320.dp, 560.dp).testTag("stage")) {
                val px = with(LocalDensity.current) { 1.dp.toPx() }
                Window(Rect(0f, 0f, 320 * px, 560 * px), 320 * px)
            }
        } }
    }

    @androidx.compose.runtime.Composable
    private fun BoxScope.Window(room: Rect, reference: Float) =
        PipWindow(state, room, reference, 1.6f, tag = "pip", description = "pip", onTap = { taps++ }, onClose = { closes++ },
            focused = focused, label = "host", onKeyboard = {}, onFull = { fulls++ }) {
            Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { contentTaps++ } })
        }

    private fun bounds() = compose.onNodeWithTag("pip").fetchSemanticsNode().boundsInRoot
    /** The area the window moves in. */
    private fun stage() = compose.onNodeWithTag("stage").fetchSemanticsNode().boundsInRoot

    @Test fun startsTopRightAtItsSize() {
        val b = bounds()
        assertEquals(stage().width * 0.5f, b.width, 2f)
        assertEquals(b.width / 1.6f, b.height, 2f)
        assertEquals(stage().right, b.right, 2f)
        assertEquals(stage().top, b.top, 2f)
    }

    @Test fun aTapOpensTheDesktop() {
        compose.onNodeWithTag("pip").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, taps); assertEquals(0, closes) }
    }

    @Test fun aDragPutsItWhereItIsDroppedAndIsRemembered() {
        val before = bounds()
        val by = Offset(-80f, 300f)
        compose.onNodeWithTag("pip").performTouchInput { swipe(center, center + by, 400) }
        compose.waitForIdle()
        val after = bounds()
        // Where the finger left it (less the touch slop the drag began after), not snapped to a corner.
        assertEquals(before.left + by.x, after.left, 40f)
        assertEquals(before.top + by.y, after.top, 40f)
        assertTrue(after.left > stage().left + 1f && after.bottom < stage().bottom - 1f)
        compose.runOnIdle { assertEquals(0, taps); assertEquals(0, closes) }
        val saved = DesktopSettings(context).pipPosition(key)!!
        assertEquals(state.x, saved.first, 0.001f)
        assertEquals(state.y, saved.second, 0.001f)
    }

    @Test fun aDragPastTheEdgeStaysInside() {
        compose.onNodeWithTag("pip").performTouchInput { swipe(center, center + Offset(2000f, -2000f), 300) }
        compose.waitForIdle()
        assertEquals(stage().right, bounds().right, 2f)
        assertEquals(stage().top, bounds().top, 2f)
    }

    @Test fun aDragOntoTheCloseTargetCloses() {
        val target = Offset(stage().center.x, stage().bottom - with(compose.density) { 40.dp.toPx() })
        val start = bounds().center
        compose.onNodeWithTag("pip").performTouchInput {
            down(center)
            moveTo(center + Offset(-40f, 40f))
            moveTo(center + (target - start), delayMillis = 100)
        }
        compose.onNodeWithTag("pip-close-target").assertExists()
        compose.onNodeWithTag("pip").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, closes); assertEquals(0, taps) }
    }

    @Test fun theHandleResizesKeepingTheFarCorner() {
        val before = bounds()
        // On the right, the handle is in the bottom left corner: out is left and down.
        compose.onNodeWithTag("pip-resize").performTouchInput { swipe(center, center + Offset(-120f, 75f), 400) }
        compose.waitForIdle()
        val after = bounds()
        assertTrue("grew: ${before.width} → ${after.width}", after.width > before.width + 40f)
        assertEquals(after.width / 1.6f, after.height, 2f)
        assertEquals(before.right, after.right, 2f)
        assertEquals(before.top, after.top, 2f)
        compose.runOnIdle { assertEquals(0, taps) }
        assertEquals(state.fraction, DesktopSettings(context).pipFraction(key)!!, 0.001f)
        // And back in: smaller, down to its floor.
        compose.onNodeWithTag("pip-resize").performTouchInput { swipe(center, center + Offset(2000f, -1250f), 400) }
        compose.waitForIdle()
        assertEquals(stage().width * 0.3f, bounds().width, 3f)
    }

    @Test fun aPinchResizes() {
        val before = bounds().width
        compose.onNodeWithTag("pip").performTouchInput {
            pinch(center - Offset(20f, 0f), center - Offset(width / 2f, 0f), center + Offset(20f, 0f), center + Offset(width / 2f, 0f), 300)
        }
        compose.runOnIdle { assertTrue("grew: ${state.fraction}", state.fraction > 0.5f); assertEquals(0, taps) }
        assertTrue(bounds().width > before)
        assertEquals(state.fraction, DesktopSettings(context).pipFraction(key)!!, 0.001f)
    }

    @Test fun unfocusedTheWindowTakesTheTouches() {
        compose.onNodeWithTag("pip").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, taps); assertEquals(0, contentTaps) }
        compose.onNodeWithTag("pip-bar").assertDoesNotExist()
    }

    @Test fun focusedThePictureTakesEveryTouch() {
        compose.runOnIdle { focused = true }
        val before = bounds()
        compose.onNodeWithTag("pip").performTouchInput { click() }
        // A drag on the picture is the picture's too: the window stays where it is.
        compose.onNodeWithTag("pip").performTouchInput { swipe(center, center + Offset(-80f, 300f), 300) }
        compose.runOnIdle { assertEquals(1, contentTaps); assertEquals(0, taps) }
        assertEquals(before, bounds())
    }

    @Test fun focusedTheBarMovesItAndOpensFullScreen() {
        compose.runOnIdle { focused = true }
        compose.onNodeWithTag("pip-bar").assertExists()
        val before = bounds()
        compose.onNodeWithTag("pip-move").performTouchInput { swipe(center, center + Offset(-80f, 300f), 400) }
        compose.waitForIdle()
        val after = bounds()
        assertEquals(before.left - 80f, after.left, 40f)
        assertEquals(before.top + 300f, after.top, 40f)
        compose.onNodeWithTag("pip-full").performClick()
        compose.runOnIdle { assertEquals(1, fulls); assertEquals(0, taps); assertEquals(0, contentTaps) }
        compose.onNodeWithTag("pip-close").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }
}
