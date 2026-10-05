package io.itermux.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteFoldersTest {
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
}
