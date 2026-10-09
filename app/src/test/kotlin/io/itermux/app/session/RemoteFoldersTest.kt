package io.itermux.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RemoteFoldersTest {
    @Test fun windowsPathsSupportHomeChildrenAndDriveRoot() {
        val home = RemoteFolders.parse(listOf("C:\\Users\\测试", "C:\\Users\\测试", "My projects", "工作"))
        org.junit.Assert.assertNotNull("Native Windows paths must be accepted", home)
        assertEquals("~", home!!.display)
        assertEquals("~/My projects", home.child("My projects"))
        assertEquals("C:/Users", home.parent)
        val root = RemoteFolders.parse(listOf("C:\\Users\\测试", "D:\\", "Projects"))!!
        assertEquals("D:/", root.path)
        assertNull(root.parent)
        assertEquals("D:/Projects", root.child("Projects"))
    }

    @Test fun windowsHomeRelativeSelectionResolvesBeforeLaunch() {
        val folder = RemoteFolders.parse(listOf("C:/Users/dev", "C:/Users/dev/My projects"))!!
        assertEquals("C:/Users/dev/My projects", folder.startDirectory("~/My projects"))
        assertEquals("~", folder.startDirectory("~"))
        assertEquals("~", RemoteFolders.parse(listOf("C:/Users/Dev", "c:/users/dev"))!!.display)
    }

    @Test fun uncShareStopsAtItsRoot() {
        val folder = RemoteFolders.parse(listOf("C:/Users/dev", "\\\\server\\share\\projects"))!!
        assertEquals("//server/share/projects", folder.path)
        assertEquals("//server/share", folder.parent)
        assertNull(RemoteFolders.parse(listOf("C:/Users/dev", "\\\\server\\share"))!!.parent)
    }

    @Test fun windowsFolderNamesCannotContainBackslashSeparators() {
        val folder = RemoteFolders.parse(listOf("C:/Users/dev", "C:/Users/dev", "..\\escape", "safe"))!!
        assertEquals(listOf("safe"), folder.folders)
        assertThrows(IllegalArgumentException::class.java) { folder.child("..\\escape") }
    }

    @Test fun parsesAndSortsFolders() {
        val f = RemoteFolders.parse(listOf("/home/v", "/home/v/work", "roamtty", "Api", "*"))!!
        assertEquals(listOf("Api", "roamtty"), f.folders)
        assertEquals("~/work", f.display)
        assertEquals("~/work/roamtty", f.child("roamtty"))
        assertEquals("~", f.parent)
    }
    @Test fun pathsOutsideHomeStayAbsolute() {
        val f = RemoteFolders.parse(listOf("/home/v", "/srv"))!!
        assertEquals("/srv", f.display)
        assertEquals("/", f.parent)
        assertEquals("/srv/www", f.child("www"))
        val root = RemoteFolders.parse(listOf("/home/v", "/", "etc"))!!
        assertNull(root.parent)
        assertEquals("/etc", root.child("etc"))
    }
    @Test fun failedListingIsNull() {
        assertNull(RemoteFolders.parse(emptyList()))
        assertNull(RemoteFolders.parse(listOf("garbage")))
    }
    @Test fun scriptQuotesTheTarget() {
        assert(RemoteFolders.script("~").startsWith("cd -- \"\$HOME\" "))
        assert(RemoteFolders.script("~/my dir").startsWith("cd -- \"\$HOME\"/'my dir' "))
        assert(RemoteFolders.script("/it's").startsWith("cd -- '/it'\\''s' "))
    }
    @Test fun malformedNamesAndUnsupportedProtocolHeadersAreRejected() {
        val folder = RemoteFolders.parse(listOf("/home/v", "/srv", ".", "..", "a/b", "bad\u0015", "bad\u0085", "safe"))!!
        assertEquals(listOf("safe"), folder.folders)
        listOf(".", "..", "a/b", "bad\nname", "bad\u0085").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { folder.child(name) }
        }
        assertNull(RemoteFolders.parse(listOf("/home/\u0015v", "/srv", "safe")))
        assertNull(RemoteFolders.parse(listOf("/home/v", "/srv\u0085", "safe")))
        assertNull(RemoteFolders.parse(listOf("relative", "/srv", "safe")))
    }
    @Test fun backslashIsALiteralPartOfAUnixFolderName() {
        val folder = RemoteFolders.parse(listOf("/home/v", "/srv", "a\\b"))!!
        assertEquals(listOf("a\\b"), folder.folders)
        assertEquals("/srv/a\\b", folder.child("a\\b"))
        assertEquals("cd -- '/srv/a\\b'", StartDirectory.command(folder.child("a\\b")))
    }
    @Test fun actualScriptDoesNotSplitControlCharacterNamesIntoOtherFolders() {
        assumeTrue(File("/bin/sh").canExecute())
        val directory = Files.createTempDirectory("remote-folders-test-").toFile()
        try {
            listOf("proj\n..", "a\nb", "carriage\r", "kill\u0015", "delete\u007f", "a\\b", "it's a 中文 folder").forEach {
                assertTrue(File(directory, it).mkdir())
            }
            val process = ProcessBuilder("/bin/sh", "-c", RemoteFolders.script(directory.path)).apply {
                environment()["HOME"] = directory.path
            }.start()
            val lines = process.inputStream.bufferedReader().readLines()
            assertEquals(0, process.waitFor())
            val listing = RemoteFolders.parse(lines)!!
            assertEquals(listOf("a\\b", "it's a 中文 folder"), listing.folders)
            assertEquals("~/a\\b", listing.child(listing.folders.first()))
            assertEquals("~/it's a 中文 folder", listing.child(listing.folders.last()))
        } finally { directory.deleteRecursively() }
    }
    @Test fun actualScriptRejectsUnsafeHomeAndPhysicalWorkingDirectory() {
        assumeTrue(File("/bin/sh").canExecute())
        val directory = Files.createTempDirectory("remote-folders-home-").toFile()
        try {
            val unsafe = File(directory, "trailing\n").apply { assertTrue(mkdir()) }
            // Use a safe symlink so the requested input is valid but pwd reveals the unsupported physical path.
            val link = directory.toPath().resolve("safe-link")
            Files.createSymbolicLink(link, unsafe.toPath())
            val physical = ProcessBuilder("/bin/sh", "-c", RemoteFolders.script(link.toString())).start()
            assertEquals(3, physical.waitFor())
            val home = ProcessBuilder("/bin/sh", "-c", RemoteFolders.script(directory.path)).apply {
                environment()["HOME"] = "/home/a\nb"
            }.start()
            assertEquals(3, home.waitFor())
        } finally { directory.deleteRecursively() }
    }
}
