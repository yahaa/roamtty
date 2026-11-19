package io.itermux.app.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Who shows a desktop's picture now (the desktop page, the small window over the terminal), each with whether it
 * is small. The page and the small window hand the picture to each other in one frame, so [onHidden] runs only
 * when nothing has shown it for [DesktopConnection.HIDE_GRACE_MS]: a switch between them never pauses the host or
 * asks it for a new keyframe.
 */
internal class DesktopViewers(
    private val scope: CoroutineScope,
    /** The first viewer came. */
    private val onShown: () -> Unit,
    /** The last viewer left and none came back. */
    private val onHidden: () -> Unit,
    /** [smallOnly] changed. */
    private val onSmallOnlyChanged: (Boolean) -> Unit,
) {
    private val shownBy = mutableMapOf<String, Boolean>()
    private var hideJob: Job? = null
    /** Only small windows show the picture: the host can send fewer, smaller frames. */
    var smallOnly = false
        private set

    /** [owner] shows the picture, [small] or full. */
    fun shown(owner: String, small: Boolean) {
        hideJob?.cancel()
        hideJob = null
        val wasShown = shownBy.isNotEmpty()
        shownBy[owner] = small
        if (!wasShown) onShown()
        updateSmallOnly()
    }

    /** [owner] no longer shows the picture. */
    fun hidden(owner: String) {
        if (shownBy.remove(owner) == null) return
        if (shownBy.isNotEmpty()) { updateSmallOnly(); return }
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(DesktopConnection.HIDE_GRACE_MS)
            hideJob = null
            if (shownBy.isEmpty()) onHidden()
        }
    }

    private fun updateSmallOnly() {
        val small = shownBy.isNotEmpty() && shownBy.values.all { it }
        if (small == smallOnly) return
        smallOnly = small
        onSmallOnlyChanged(small)
    }
}
