package io.itermux.app.desktop

import io.itermux.desktop.DesktopProtocol
import io.itermux.desktop.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameQueueTest {
    private fun video(frame: Long, key: Boolean = false, bytes: Int = 1, offset: Int = 0) =
        ServerMessage.Video(frame, if (key) DesktopProtocol.VIDEO_KEY else 0,
            0, 0, 64, 64, ByteArray(bytes), offset)

    private fun VideoFrameQueue.drain(): List<Long> = buildList {
        while (true) {
            val video = peek() ?: break
            assertTrue(this@drain.remove(video))
            add(video.frame)
        }
    }

    @Test fun preservesDependenciesInOrderWithinTheBudget() {
        val queue = VideoFrameQueue(maxFrames = 3, maxBytes = 12).apply { activate() }
        queue.offer(video(1, key = true, bytes = 4))
        queue.offer(video(2, bytes = 4))
        queue.offer(video(3, bytes = 4))

        assertEquals(listOf(1L, 2L, 3L), queue.drain())
        assertFalse(queue.takeKeyframeRequest())
        queue.offer(video(4, bytes = 12))
        assertEquals(listOf(4L), queue.drain())
    }

    @Test fun exceedingFrameBudgetDropsTheBrokenChainAndRequestsOneKeyframe() {
        val queue = VideoFrameQueue(maxFrames = 3, maxBytes = 100).apply { activate() }
        queue.offer(video(1, key = true))
        queue.offer(video(2))
        queue.offer(video(3))
        assertTrue(queue.offer(video(4)))

        assertNull(queue.peek())
        assertTrue(queue.takeKeyframeRequest())
        assertFalse(queue.takeKeyframeRequest())
        repeat(100) { assertFalse(queue.offer(video(5L + it))) }
        assertNull(queue.peek())
        assertFalse(queue.takeKeyframeRequest())
    }

    @Test fun exceedingByteBudgetDropsFramesEvenWhenCountIsSmall() {
        val queue = VideoFrameQueue(maxFrames = 10, maxBytes = 10).apply { activate() }
        queue.offer(video(1, key = true, bytes = 6))
        queue.offer(video(2, bytes = 5))

        assertNull(queue.peek())
        assertTrue(queue.takeKeyframeRequest())
    }

    @Test fun byteBudgetIncludesTheWholeRetainedArray() {
        val queue = VideoFrameQueue(maxFrames = 10, maxBytes = 10).apply { activate() }
        queue.offer(video(1, key = true, bytes = 9, offset = 8))
        queue.offer(video(2, bytes = 2))

        assertNull(queue.peek())
        assertTrue(queue.takeKeyframeRequest())
    }

    @Test fun newestKeyframeReplacesStaleQueuedFrames() {
        val queue = VideoFrameQueue(maxFrames = 4, maxBytes = 100).apply { activate() }
        queue.offer(video(1, key = true))
        queue.offer(video(2))
        queue.offer(video(3, key = true))
        queue.offer(video(4))

        assertEquals(listOf(3L, 4L), queue.drain())
        assertFalse(queue.takeKeyframeRequest())
    }

    @Test fun keyframeRecoversAfterOverflowAndCancelsAnUndeliveredRequest() {
        val queue = VideoFrameQueue(maxFrames = 2, maxBytes = 100).apply { activate() }
        queue.offer(video(1, key = true))
        queue.offer(video(2))
        queue.offer(video(3))
        queue.offer(video(4))
        queue.offer(video(5, key = true))
        queue.offer(video(6))

        assertEquals(listOf(5L, 6L), queue.drain())
        assertFalse(queue.takeKeyframeRequest())
    }

    @Test fun oversizeKeyframeIsNotRetainedAndLaterKeyframeRecovers() {
        val queue = VideoFrameQueue(maxFrames = 4, maxBytes = 10).apply { activate() }
        queue.offer(video(1, key = true, bytes = 11))

        assertNull(queue.peek())
        assertTrue(queue.takeKeyframeRequest())
        assertFalse(queue.offer(video(2)))
        queue.offer(video(3, key = true, bytes = 10))
        assertEquals(listOf(3L), queue.drain())
    }

    @Test fun detachedQueueReleasesFramesAndRequiresANewKeyframeOnAttach() {
        val queue = VideoFrameQueue()
        assertFalse(queue.offer(video(1, key = true)))
        queue.activate()
        assertFalse(queue.offer(video(2)))
        queue.offer(video(3, key = true))
        queue.deactivate()

        assertNull(queue.peek())
        assertFalse(queue.offer(video(4, key = true)))
        queue.activate()
        assertFalse(queue.offer(video(5)))
        queue.offer(video(6, key = true))
        assertEquals(listOf(6L), queue.drain())
    }

    @Test fun resetDropsQueuedDependenciesAndWaitsForANewKeyframe() {
        val queue = VideoFrameQueue().apply { activate() }
        queue.offer(video(1, key = true))
        queue.offer(video(2))
        queue.reset()

        assertNull(queue.peek())
        assertFalse(queue.offer(video(3)))
        queue.offer(video(4, key = true))
        queue.offer(video(5))
        assertEquals(listOf(4L, 5L), queue.drain())
    }

    @Test fun replacingAHeadDuringConfigureDoesNotRemoveTheNewKeyframe() {
        val queue = VideoFrameQueue().apply { activate() }
        val old = video(1, key = true)
        queue.offer(old)
        assertEquals(old, queue.peek())
        queue.offer(video(2, key = true))

        assertFalse(queue.remove(old))
        assertEquals(listOf(2L), queue.drain())
    }
}
