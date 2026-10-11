package io.itermux.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.ui.DarkColors
import io.itermux.app.ui.MarqueeTitle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A session card's title scrolls like a subtitle when too long, and keeps going. Real time and a plain activity: under
 * the compose test rule's virtual clock the marquee never moves.
 */
@RunWith(AndroidJUnit4::class)
class SessionTitleMarqueeTest {
    @Test fun aLongTitleKeepsScrollingAndAShortOneStaysStill() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            var density = 1f
            scenario.onActivity { activity ->
                density = activity.resources.displayMetrics.density
                activity.setContent {
                    MaterialTheme(colorScheme = DarkColors) {
                        Surface(Modifier.fillMaxSize()) {
                            Column {
                                MarqueeTitle("a-long-session-title-that-does-not-fit", Modifier.width(200.dp).height(40.dp))
                                MarqueeTitle("短标题", Modifier.width(200.dp).height(40.dp))
                            }
                        }
                    }
                }
            }
            // Each title's row, below the status bar the content is drawn under.
            fun row(shot: Bitmap, index: Int): IntArray {
                val top = IntArray(2).also { scenario.onActivity { a -> a.window.decorView.findViewById<android.view.View>(android.R.id.content).getLocationOnScreen(it) } }[1]
                val width = (200 * density).toInt(); val height = (40 * density).toInt()
                return IntArray(width * height).also { shot.getPixels(it, 0, width, 0, top + index * height, width, height) }
            }
            fun shot(): Bitmap = instrumentation.uiAutomation.takeScreenshot()
            Thread.sleep(500)
            val first = shot()
            Thread.sleep(3_000)
            val second = shot()
            assertFalse("a long title should scroll", row(first, 0).contentEquals(row(second, 0)))
            assertTrue("a short title should stay still", row(first, 1).contentEquals(row(second, 1)))
            first.compress(Bitmap.CompressFormat.PNG, 100,
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "marquee-rest.png").outputStream())
            // A plain marquee stops after three passes (about 35 s for this title); this one is still going after that.
            Thread.sleep(45_000)
            val late = shot()
            Thread.sleep(700)
            assertFalse("a long title should keep scrolling", row(late, 0).contentEquals(row(shot(), 0)))
        }
    }
}
