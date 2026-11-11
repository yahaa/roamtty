package io.itermux.app.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** A card's name, as the rename dialog and the journal keep it. */
class SessionNameTest {
    @Test fun trimsAndKeepsOneLine() {
        assertEquals("release 打包", sessionName("  release 打包 \n"))
        assertEquals("build the apk", sessionName("build\r\n  the\napk"))
    }

    @Test fun blankStaysBlank() {
        assertEquals("", sessionName(" \n\t "))
    }

    @Test fun cutAtTitleLengthWithoutSplittingAPair() {
        assertEquals(80, sessionName("a".repeat(120)).length)
        assertEquals("a".repeat(79), sessionName("a".repeat(79) + "😀"))
    }
}
