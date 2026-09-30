package io.itermux.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBrowserSwipeNavTest {
    @Test
    fun canNavigateToParentRejectsRootAndBlank() {
        assertFalse(RemoteBrowserSwipeNav.canNavigateToParent(null))
        assertFalse(RemoteBrowserSwipeNav.canNavigateToParent(""))
        assertFalse(RemoteBrowserSwipeNav.canNavigateToParent("/"))
        assertTrue(RemoteBrowserSwipeNav.canNavigateToParent("/home"))
        assertTrue(RemoteBrowserSwipeNav.canNavigateToParent("/home/user"))
    }

    @Test
    fun isLeftEdgeStartOnlyNearLeft() {
        assertTrue(RemoteBrowserSwipeNav.isLeftEdgeStart(0f, 40f))
        assertTrue(RemoteBrowserSwipeNav.isLeftEdgeStart(40f, 40f))
        assertFalse(RemoteBrowserSwipeNav.isLeftEdgeStart(41f, 40f))
        assertFalse(RemoteBrowserSwipeNav.isLeftEdgeStart(-1f, 40f))
    }

    @Test
    fun shouldGoToParentNeedsEnoughRightTravel() {
        assertFalse(RemoteBrowserSwipeNav.shouldGoToParent(0f, 48f))
        assertFalse(RemoteBrowserSwipeNav.shouldGoToParent(47.9f, 48f))
        assertTrue(RemoteBrowserSwipeNav.shouldGoToParent(48f, 48f))
        assertTrue(RemoteBrowserSwipeNav.shouldGoToParent(120f, 48f))
        assertFalse(RemoteBrowserSwipeNav.shouldGoToParent(-50f, 48f))
    }
}
