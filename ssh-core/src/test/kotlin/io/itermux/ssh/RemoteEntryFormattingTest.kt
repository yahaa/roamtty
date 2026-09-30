package io.itermux.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteEntryFormattingTest {
    @Test fun defaultsKeepListingCallSitesCompatible() {
        val entry = RemoteEntry("readme.txt", false)
        assertEquals("readme.txt", entry.name)
        assertFalse(entry.isDirectory)
        assertEquals(0L, entry.size)
        assertEquals(0L, entry.mtimeSeconds)
        assertEquals("", entry.permissions)
    }

    @Test fun joinRemotePathHandlesRootAndNested() {
        assertEquals("/file.txt", joinRemotePath("/", "file.txt"))
        assertEquals("/home/user/file.txt", joinRemotePath("/home/user", "file.txt"))
        assertEquals("/home/user/file.txt", joinRemotePath("/home/user/", "file.txt"))
        assertEquals("/home/user/", joinRemotePath("/home/user", ""))
        assertEquals("/", joinRemotePath("/", ""))
        assertEquals("/home/user/subdir", joinRemotePath("/home/user", "subdir"))
    }

    @Test fun parentRemotePathStopsAtRoot() {
        assertEquals("/", parentRemotePath("/"))
        assertEquals("/", parentRemotePath("/home"))
        assertEquals("/home", parentRemotePath("/home/user"))
        assertEquals("/home/user", parentRemotePath("/home/user/file.txt"))
        assertEquals("/home", parentRemotePath("/home/user/"))
    }

    @Test fun formatRemoteSizeUsesBinaryUnits() {
        assertEquals("0 B", formatRemoteSize(0))
        assertEquals("512 B", formatRemoteSize(512))
        assertEquals("1 KiB", formatRemoteSize(1024))
        assertEquals("1.5 MiB", formatRemoteSize((1024L * 1024) + (512L * 1024)))
        assertTrue(formatRemoteSize(3L * 1024 * 1024 * 1024).endsWith(" GiB"))
    }

    @Test fun formatRemoteMtimeEmptyForUnknown() {
        assertEquals("", formatRemoteMtime(0))
        assertEquals("", formatRemoteMtime(-1))
        assertTrue(formatRemoteMtime(1_700_000_000L).matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""")))
    }
}
