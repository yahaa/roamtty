package io.itermux.app

import android.graphics.Paint
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalFontFallbackTest {
    @Test fun bundledFacesRenderPromptIconsAndToolSymbols() {
        assumeTrue("Custom fallback chains need API 29", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = TerminalFonts.selection
        try {
            TerminalFontId.entries.filter { it.assetFile != null }.forEach { id ->
                TerminalFonts.setSelection(context, id)
                val paint = Paint().apply { typeface = TerminalFonts.resolveTypeface(context) }
                // Nerd Font folder/Linux icons (oh-my-bash, starship) and Claude Code's "⏵⏵ auto mode".
                listOf("", "", "", "⏵", "⏺", "✦", "A").forEach { glyph ->
                    assertTrue("${id.label} renders U+%04X".format(glyph.codePointAt(0)), paint.hasGlyph(glyph))
                }
            }
        } finally {
            TerminalFonts.setSelection(context, previous)
        }
    }
}
