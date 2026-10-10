package io.itermux.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StartDirectoryTest {
    @Test fun windowsDriveRootsAndBreadcrumbsStayAbsolute() {
        assertEquals("C:/", StartDirectory.normalize("C:\\"))
        assertEquals("C:/", StartDirectory.normalize("C:/"))
        assertEquals(listOf("C:" to "C:/", "Users" to "C:/Users", "测试" to "C:/Users/测试"),
            StartDirectory.segments("C:\\Users\\测试"))
    }

    @Test fun nativeWindowsLaunchUsesAnEncodedLiteralPath() {
        val command = StartDirectory.command("C:\\Users\\it's 中文 \$(whoami) %TEMP% [work]")!!
        assertTrue("Windows launch must work from CMD and PowerShell", command.startsWith("powershell.exe "))
        val script = String(java.util.Base64.getDecoder().decode(command.substringAfter("-EncodedCommand ")), Charsets.UTF_16LE)
        assertTrue(script.contains("Set-Location -LiteralPath"))
    }

    @Test fun unixDriveLikeNamesKeepTheirLiteralBackslashes() {
        assertEquals("/C:/a\\b", StartDirectory.normalize("/C:/a\\b"))
        assertEquals("cd -- '/C:/a\\b'", StartDirectory.command("/C:/a\\b"))
    }


    @Test fun homeNeedsNoCommand() {
        listOf("", "  ", "~", "~/", " ~ ").forEach { assertNull(it, StartDirectory.command(it)) }
    }
    @Test fun absolutePathIsQuoted() {
        assertEquals("cd -- '/srv/it'\\''s \$(x) dir'", StartDirectory.command("/srv/it's \$(x) dir/"))
        assertEquals("cd -- '/'", StartDirectory.command("/"))
        assertEquals("cd -- '-workspace'", StartDirectory.command("-workspace"))
    }
    @Test fun tildeStaysExpandable() {
        assertEquals("cd -- ~/'work/my app'", StartDirectory.command(" ~/work/my app/ "))
    }
    @Test fun recentListPutsNewestFirstWithoutDuplicatesOrHome() {
        val recent = listOf("/a", "/b", "/c")
        assertEquals(listOf("/b", "/a", "/c"), StartDirectory.remember(recent, "/b/"))
        assertEquals(recent, StartDirectory.remember(recent, "~"))
        assertEquals(6, (1..10).fold(emptyList<String>()) { list, i -> StartDirectory.remember(list, "/d$i") }.size)
    }
    @Test fun segmentsLeadFromHomeOrRoot() {
        assertEquals(listOf("~" to "~"), StartDirectory.segments("~"))
        assertEquals(listOf("~" to "~", "work" to "~/work", "app" to "~/work/app"), StartDirectory.segments("~/work/app/"))
        assertEquals(listOf("/" to "/"), StartDirectory.segments("/"))
        assertEquals(listOf("/" to "/", "srv" to "/srv", "it" to "/srv/it"), StartDirectory.segments("/srv/it"))
    }
    @Test fun controlsAreRejectedBeforeWhitespaceCanBeTrimmed() {
        ((0..31) + (127..159)).forEach { code ->
            val control = code.toChar()
            listOf("$control/srv/app", "/srv/$control/app", "/srv/app$control").forEach { path ->
                assertThrows("U+${code.toString(16)}", IllegalArgumentException::class.java) { StartDirectory.command(path) }
                assertThrows(IllegalArgumentException::class.java) { StartDirectory.remember(emptyList(), path) }
            }
        }
    }
    @Test fun cachedUnsupportedPathsAreDiscardedAndSafeEntriesNormalized() {
        assertEquals(listOf("/safe", "/other"), StartDirectory.sanitizeRecent(listOf(
            "/bad\u0015printf injected #", "/bad\r", "/bad\u0085", "/safe/", "/safe", "~", "", "/other")))
        assertTrue(StartDirectory.isSupported("/it's 中文 \$(x) dir"))
    }
}
