package io.itermux.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartDirectoryTest {
    @Test fun homeNeedsNoCommand() {
        listOf("", "  ", "~", "~/", " ~ ").forEach { assertNull(it, StartDirectory.command(it)) }
    }
    @Test fun absolutePathIsQuoted() {
        assertEquals("cd '/srv/it'\\''s \$(x) dir'", StartDirectory.command("/srv/it's \$(x) dir/"))
        assertEquals("cd '/'", StartDirectory.command("/"))
    }
    @Test fun tildeStaysExpandable() {
        assertEquals("cd ~/'work/my app'", StartDirectory.command(" ~/work/my app/ "))
    }
    @Test fun recentListPutsNewestFirstWithoutDuplicatesOrHome() {
        val recent = listOf("/a", "/b", "/c")
        assertEquals(listOf("/b", "/a", "/c"), StartDirectory.remember(recent, "/b/"))
        assertEquals(recent, StartDirectory.remember(recent, "~"))
        assertEquals(6, (1..10).fold(emptyList<String>()) { list, i -> StartDirectory.remember(list, "/d$i") }.size)
    }
}
