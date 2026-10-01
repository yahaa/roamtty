package io.itermux.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FontImportDeviceTest {
    @Test fun invalidFontDoesNotReplaceThePreviouslyImportedFont() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val valid = File(context.cacheDir, "valid-test.ttf")
        val invalid = File(context.cacheDir, "invalid-test.ttf")
        val previous = TerminalFonts.selection
        try {
            context.assets.open("fonts/JetBrainsMono-Regular.ttf").use { input -> valid.outputStream().use(input::copyTo) }
            invalid.writeBytes(ByteArray(4096) { 42 })
            assertTrue(TerminalFonts.importCustomFont(context, Uri.fromFile(valid), "valid-test.ttf"))
            val original = TerminalFonts.customFontFile(context).readBytes()
            assertFalse(TerminalFonts.importCustomFont(context, Uri.fromFile(invalid), "invalid-test.ttf"))
            assertArrayEquals(original, TerminalFonts.customFontFile(context).readBytes())
        } finally {
            valid.delete(); invalid.delete()
            withContext(Dispatchers.Main) {
                TerminalFonts.clearCustomFont(context)
                TerminalFonts.setSelection(context, previous)
            }
        }
    }
}
