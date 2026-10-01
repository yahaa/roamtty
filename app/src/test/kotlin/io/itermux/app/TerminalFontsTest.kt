package io.itermux.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalFontsTest {
    @Test
    fun defaultIsBundledJetBrainsMono() {
        assertEquals(TerminalFontId.JETBRAINS_MONO, TerminalFontId.DEFAULT)
    }

    @Test
    fun fromFallsBackToDefault() {
        assertEquals(TerminalFontId.DEFAULT, TerminalFontId.from(null))
        assertEquals(TerminalFontId.DEFAULT, TerminalFontId.from("nope"))
        assertEquals(TerminalFontId.SYSTEM, TerminalFontId.from("system"))
        assertEquals(TerminalFontId.CUSTOM, TerminalFontId.from("custom"))
    }

    @Test
    fun bundledFontsHaveAssetPaths() {
        TerminalFontId.entries.filter { it != TerminalFontId.SYSTEM && it != TerminalFontId.CUSTOM }.forEach {
            require(!it.assetFile.isNullOrBlank()) { it.id }
            require(it.assetFile!!.startsWith("fonts/")) { it.assetFile!! }
        }
    }
}
