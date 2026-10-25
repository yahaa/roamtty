package io.itermux.app.desktop

import io.itermux.desktop.ServerMessage
import java.util.ArrayDeque

/** Bounds encoded data before it reaches the decoder thread. Dropped dependencies need a new keyframe. */
internal class VideoFrameQueue(private val maxFrames: Int = 4, private val maxBytes: Int = 8 * 1024 * 1024) {
    private val frames = ArrayDeque<ServerMessage.Video>()
    private var active = false
    private var bytes = 0
    private var waitingForKeyframe = true
    private var keyframeRequested = false

    @Synchronized fun activate() { active = true; reset() }
    @Synchronized fun deactivate() { active = false; reset() }

    @Synchronized fun reset() {
        clearFrames()
        waitingForKeyframe = true
        keyframeRequested = false
    }

    /** True if the decoder needs waking, including when an overflow needs a keyframe request. */
    @Synchronized fun offer(video: ServerMessage.Video): Boolean {
        if (!active || (waitingForKeyframe && !video.keyframe)) return false
        if (video.keyframe) {
            // A keyframe has no dependency on earlier queued pictures: always favor the freshest one.
            clearFrames()
            waitingForKeyframe = false
            keyframeRequested = false
        }
        // Count the entire backing array, including the protocol header before video.offset.
        if (frames.size >= maxFrames || video.data.size > maxBytes - bytes) {
            clearFrames()
            waitingForKeyframe = true
            keyframeRequested = true
            return true
        }
        frames.addLast(video)
        bytes += video.data.size
        return true
    }

    @Synchronized fun peek(): ServerMessage.Video? = frames.peekFirst()

    /** Configure may run while new frames arrive; never remove a head that has since been replaced. */
    @Synchronized fun remove(video: ServerMessage.Video): Boolean {
        if (frames.peekFirst() !== video) return false
        frames.removeFirst()
        bytes -= video.data.size
        return true
    }

    @Synchronized fun takeKeyframeRequest(): Boolean = keyframeRequested.also { keyframeRequested = false }

    private fun clearFrames() {
        frames.clear()
        bytes = 0
    }
}
