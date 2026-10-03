package io.itermux.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadTargetPathTest {
    @Test fun bareNamesLandInTheChosenDirectory() {
        assertEquals("/srv/app/report.pdf", uploadTargetPath("/srv/app", "report.pdf"))
        assertEquals("/report.pdf", uploadTargetPath("/", " report.pdf "))
        assertEquals("report.pdf", uploadTargetPath("", "report.pdf"))
        assertEquals("/srv/app/sub/report.pdf", uploadTargetPath("/srv/app", "sub/report.pdf"))
    }

    @Test fun typedPathsAreUsedAsIs() {
        assertEquals("/tmp/x.bin", uploadTargetPath("/srv/app", "/tmp/x.bin"))
        assertEquals("notes/x.bin", uploadTargetPath("/srv/app", "~/notes/x.bin"))
        assertEquals("", uploadTargetPath("/srv/app", "   "))
    }

    @Test fun localNamesNeverSelectAnotherDirectory() {
        assertEquals("a_b_c.txt", safeUploadName("a/b\\c.txt"))
    }
}
