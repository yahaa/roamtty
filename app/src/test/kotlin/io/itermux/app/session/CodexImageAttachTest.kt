package io.itermux.app.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexImageAttachTest {
    @Test fun successiveImagesRemainSeparateWithoutSubmittingThePrompt() {
        val paths = List(3) { CodexImageAttach.newRemotePngPath() }
        val input = "compare" + paths.joinToString("") { CodexImageAttach.pathForPaste(it) } + "please"
        assertEquals(listOf("compare") + paths + "please", input.split(Regex("\\s+")))
        assertFalse(input.contains('\r') || input.contains('\n'))
    }
    @Test fun remotePathIsUniquePngUnderTmp() {
        val a = CodexImageAttach.newRemotePngPath()
        val b = CodexImageAttach.newRemotePngPath()
        assertTrue(a.startsWith("/tmp/itermux-img-") && a.endsWith(".png"))
        assertTrue(b.startsWith("/tmp/itermux-img-") && b.endsWith(".png"))
        assertTrue(a != b)
    }
}
