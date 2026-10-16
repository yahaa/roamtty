package io.itermux.app.ui

import io.itermux.app.session.breadcrumbSegments
import io.itermux.ssh.RemoteEntry
import io.itermux.ssh.RemoteFileKind
import org.junit.Assert.*
import org.junit.Test

class FileBrowserModelTest {
    @Test fun languageFilesGetBadgesAndMediaKeepsGlyphs() {
        assertEquals("KT", (fileIconSpec("Main.kt") as FileIconSpec.Badge).label)
        assertEquals("TS", (fileIconSpec("types.d.ts") as FileIconSpec.Badge).label)
        assertEquals("NPM", (fileIconSpec("package.json") as FileIconSpec.Badge).label)
        assertEquals("{}", (fileIconSpec("data.JSON") as FileIconSpec.Badge).label)
        assertEquals("DKR", (fileIconSpec("Dockerfile.dev") as FileIconSpec.Badge).label)
        assertEquals("ENV", (fileIconSpec(".env.production") as FileIconSpec.Badge).label)
        assertEquals(RemoteFileKind.IMAGE, (fileIconSpec("photo.png") as FileIconSpec.Glyph).kind)
        assertEquals(FileIconSpec.Android(), fileIconSpec("Roamtty-release.APK"))
        assertEquals(FileIconSpec.Android(), fileIconSpec("bundle.aab"))
        assertEquals(RemoteFileKind.EXECUTABLE, (fileIconSpec("runner", permissions = "-rwxr-xr-x") as FileIconSpec.Glyph).kind)
        assertEquals(RemoteFileKind.FILE, (fileIconSpec("current", permissions = "lrwxrwxrwx") as FileIconSpec.Glyph).kind)
        assertEquals(RemoteFileKind.FILE, (fileIconSpec("unknown.zzz") as FileIconSpec.Glyph).kind)
    }

    @Test fun wellKnownFoldersAreColoredAndOthersShareTheDefault() {
        val src = fileIconSpec("src", directory = true) as FileIconSpec.Folder
        val git = fileIconSpec(".git", directory = true) as FileIconSpec.Folder
        val plain = fileIconSpec("random", directory = true) as FileIconSpec.Folder
        val other = fileIconSpec("other", directory = true) as FileIconSpec.Folder
        assertNotEquals(src.color, plain.color)
        assertNotEquals(git.color, plain.color)
        assertEquals(plain.color, other.color)
    }

    private val entries = listOf(
        RemoteEntry("b.txt", false, size = 10, mtimeSeconds = 300),
        RemoteEntry("A.md", false, size = 500, mtimeSeconds = 100),
        RemoteEntry("zeta", true, mtimeSeconds = 50),
        RemoteEntry("alpha", true, mtimeSeconds = 400),
        RemoteEntry("c.kt", false, size = 200, mtimeSeconds = 200)
    )

    @Test fun foldersStayFirstForEverySortOrder() {
        RemoteFileSort.entries.forEach { sort ->
            listOf(false, true).forEach { descending ->
                val sorted = sortRemoteEntries(entries, sort, descending)
                assertEquals("$sort $descending", listOf(true, true, false, false, false), sorted.map { it.isDirectory })
            }
        }
        assertEquals(listOf("alpha", "zeta", "A.md", "b.txt", "c.kt"), sortRemoteEntries(entries, RemoteFileSort.NAME, false).map { it.name })
        assertEquals(listOf("zeta", "alpha", "c.kt", "b.txt", "A.md"), sortRemoteEntries(entries, RemoteFileSort.NAME, true).map { it.name })
        assertEquals(listOf("alpha", "zeta", "b.txt", "c.kt", "A.md"), sortRemoteEntries(entries, RemoteFileSort.MODIFIED, true).map { it.name })
        assertEquals(listOf("alpha", "zeta", "A.md", "c.kt", "b.txt"), sortRemoteEntries(entries, RemoteFileSort.SIZE, true).map { it.name })
        assertEquals(listOf("alpha", "zeta", "c.kt", "A.md", "b.txt"), sortRemoteEntries(entries, RemoteFileSort.TYPE, false).map { it.name })
    }

    @Test fun breadcrumbsLeadFromRootToEachAncestor() {
        assertEquals(listOf("/" to "/"), breadcrumbSegments("/"))
        assertEquals(listOf("/" to "/", "home" to "/home", "v" to "/home/v"), breadcrumbSegments("/home/v/"))
        assertEquals(emptyList<Pair<String, String>>(), breadcrumbSegments(""))
        assertEquals(listOf("docs" to "docs"), breadcrumbSegments("docs"))
    }

    @Test fun searchFindsEveryCaseInsensitiveHit() {
        val document = io.itermux.app.code.SyntaxHighlighter.highlight("Alpha alpha\nbeta ALPHA", io.itermux.app.code.Languages.PLAIN)
        assertEquals(listOf(CodeMatch(0, 0, 5), CodeMatch(0, 6, 11), CodeMatch(1, 5, 10)), findCodeMatches(document, "alpha"))
        assertTrue(findCodeMatches(document, "").isEmpty())
    }

    @Test fun relativeImagePathsResolveAgainstTheDocumentFolder() {
        assertEquals("/srv/app/img/a.png", resolveRelativeRemotePath("/srv/app", "img/a.png"))
        assertEquals("/srv/img/a.png", resolveRelativeRemotePath("/srv/app", "./../img/a.png?raw=1#x"))
        assertEquals("/etc/logo.svg", resolveRelativeRemotePath("/srv/app", "/etc/logo.svg"))
        assertEquals("/a.png", resolveRelativeRemotePath("/", "../../a.png"))
    }
}
