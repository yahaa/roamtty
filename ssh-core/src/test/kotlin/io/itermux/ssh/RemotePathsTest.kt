package io.itermux.ssh

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RemotePathsTest {
    @Test fun fuzzyNamesMatchSubstringsAndOrderedAbbreviationsIgnoringCase() {
        assertEquals(0, remoteNameMatchRank("APP.APK", "app.apk"))
        assertEquals(1, remoteNameMatchRank("release-debug.apk", "rel"))
        assertEquals(2, remoteNameMatchRank("release-debug.apk", "debug"))
        assertEquals(3, remoteNameMatchRank("iTermux-debug.apk", "ITMDBGAPK"))
        assertNotNull(remoteNameMatchRank("发布-测试版.apk", "发测apk"))
        assertNotNull(remoteNameMatchRank("a[1]?.apk", "[1]?"))
        assertNull(remoteNameMatchRank("dist", "dtis"))
        assertNull(remoteNameMatchRank("release.apk", "missing"))
    }

    private val tree = mapOf(
        "/work" to listOf(RemoteEntry("dist", true), RemoteEntry("dist-backup", true),
            RemoteEntry(".dist", true), RemoteEntry("docs", true)),
        "/work/dist" to listOf(RemoteEntry("build-output", true), RemoteEntry("app-debug.apk", false)),
        "/work/dist-backup" to listOf(RemoteEntry("build-output", true), RemoteEntry("app-debug.apk", false)),
        "/work/.dist" to listOf(RemoteEntry("secret.apk", false)),
        "/work/dist/build-output" to listOf(RemoteEntry("release.apk", false)),
        "/work/dist-backup/build-output" to listOf(RemoteEntry("release.apk", false))
    )
    private suspend fun list(path: String) = RemoteDirectory(path.ifEmpty { "/work" }, tree.getValue(path.ifEmpty { "/work" }))

    @Test fun abbreviationsSearchEveryMatchingDirectoryAtEachRequestedLevel() = runBlocking {
        val result = resolveRemoteSearchDirectories("/work", "DST/bld/", false, listDirectory = ::list)
        assertEquals(listOf("/work/dist/build-output", "/work/dist-backup/build-output"), result.directories.map { it.path })
        assertFalse(result.limited)
        assertFalse(result.unreadable)
        assertEquals(listOf("/work/dist", "/work/dist-backup"),
            resolveRemoteSearchDirectories("/work", "dist/", false, listDirectory = ::list).directories.map { it.path })
    }

    @Test fun fuzzyDirectoriesHonorHiddenFilesAndHomeAndParentPaths() = runBlocking {
        val home = resolveRemoteSearchDirectories("/other", "~/dst/", true, listDirectory = ::list)
        assertEquals(setOf("/work/dist", "/work/dist-backup", "/work/.dist"), home.directories.map { it.path }.toSet())
        val parent = resolveRemoteSearchDirectories("/work/dist", "../dst/", false, listDirectory = ::list)
        assertEquals(setOf("/work/dist", "/work/dist-backup"), parent.directories.map { it.path }.toSet())
    }

    @Test fun unreadableBranchesDoNotDiscardOtherMatchesAndBroadQueriesHaveABudget() = runBlocking {
        val partial = resolveRemoteSearchDirectories("/work", "dst/", false) { path ->
            if (path == "/work/dist") error("Permission denied") else list(path)
        }
        assertTrue(partial.unreadable)
        assertEquals(listOf("/work/dist-backup"), partial.directories.map { it.path })
        var reads = 0
        val limited = resolveRemoteSearchDirectories("/work", "dst/", false, maxDirectories = 2) { reads++; list(it) }
        assertEquals(2, reads)
        assertTrue(limited.limited)
        assertEquals(listOf("/work/dist"), limited.directories.map { it.path })
    }

    @Test fun cancelledSearchDoesNotPublishPartialResults() = runBlocking {
        try {
            resolveRemoteSearchDirectories("/work", "dst/", false) { path ->
                if (path == "/work/dist") throw CancellationException("query changed") else list(path)
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { /* expected */ }
    }

    @Test fun pathQueriesResolveAgainstTheBrowsedDirectory() {
        val base = "/home/v/work/itermux"
        assertEquals(RemotePathSearch(base, "apk"), remotePathSearch(base, "apk"))
        assertEquals(RemotePathSearch("$base/dist", "xxx"), remotePathSearch(base, "dist/xxx"))
        assertEquals(RemotePathSearch("$base/dist/build", ""), remotePathSearch(base, "./dist/build/"))
        assertEquals(RemotePathSearch("/home/v/work/assets", "图"), remotePathSearch(base, "../assets/图"))
        assertEquals(RemotePathSearch("/tmp/a b", "release.apk"), remotePathSearch(base, "/tmp/a b/release.apk"))
        assertEquals(RemotePathSearch("work/itermux/dist", ""), remotePathSearch(base, "~/work/itermux/dist/"))
        assertEquals(RemotePathSearch("/", "tmp"), remotePathSearch(base, "/../../tmp"))
        assertEquals(RemotePathSearch("../dist", "x"), remotePathSearch("", "../dist/x"))
    }

    @Test fun workingDirectoryMetadataMustBeASingleAbsolutePath() {
        assertEquals("/home/a b/project ", validWorkingDirectory("/home/a b/project \n"))
        listOf(null, "", "relative/path", "/one\n/two", "/tmp\u0000x").forEach {
            assertNull(validWorkingDirectory(it))
        }
    }

    @Test fun reportedDirectoryIsNeverNormalized() {
        assertTrue(isValidRemoteDirectory("/home/a b/project "))
        // A decoded OSC 7 path ending in %0A or %0D must be rejected, not trimmed to another directory.
        listOf("/tmp/project\n", "/tmp/project\r", "/tmp/project\r\n", "relative", "").forEach {
            assertFalse(it, isValidRemoteDirectory(it))
        }
    }
}
