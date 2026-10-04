package io.itermux.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.view.TerminalRenderer
import com.termux.terminal.SilentTerminalClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalIconShapeTest {
    @Test fun nerdFontIconsKeepTheirShapeAndPlainTextKeepsItsHeight() {
        assumeTrue("Custom fallback chains need API 29", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = TerminalFonts.selection
        try {
            TerminalFonts.setSelection(context, TerminalFontId.JETBRAINS_MONO)
            val renderer = TerminalRenderer(45, TerminalFonts.resolveTypeface(context))
            // Apple logo is 1720x2048 font units, so it should stay clearly taller than wide but not a sliver.
            val apple = glyphBounds(renderer, "")
            val ratio = apple.width().toFloat() / apple.height()
            assertTrue("apple logo w/h=$ratio ($apple)", ratio in 0.75f..0.95f)
            assertTrue("apple logo fits one cell ($apple)", apple.width() <= renderer.fontWidth + 1)
            // Plain letters are not touched: 'M' cap height stays 730/1000 of the 45px size.
            val letter = glyphBounds(renderer, "M")
            assertEquals(33f, letter.height().toFloat(), 2f)
        } finally {
            TerminalFonts.setSelection(context, previous)
        }
    }

    private fun glyphBounds(renderer: TerminalRenderer, text: String): android.graphics.Rect {
        val emulator = TerminalEmulator(object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) = Unit
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }, 10, 2, renderer.fontWidth.toInt(), renderer.fontLineSpacing, 10, SilentTerminalClient())
        // Hide the cursor so only the glyph is drawn.
        val bytes = "\u001b[?25l$text".toByteArray()
        emulator.append(bytes, bytes.size)
        val bitmap = Bitmap.createBitmap((renderer.fontWidth * 10).toInt(), renderer.fontLineSpacing * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        renderer.render(emulator, canvas, 0, -1, -1, -1, -1)
        var left = Int.MAX_VALUE; var top = Int.MAX_VALUE; var right = -1; var bottom = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val p = bitmap.getPixel(x, y)
            if (Color.red(p) + Color.green(p) + Color.blue(p) > 200) {
                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        return android.graphics.Rect(left, top, right + 1, bottom + 1)
    }
}
