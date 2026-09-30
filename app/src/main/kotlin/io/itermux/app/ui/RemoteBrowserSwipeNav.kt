package io.itermux.app.ui

/**
 * Left-edge right-swipe → go to parent directory in the remote file browser.
 *
 * Edge-only so the gesture does not compete with LazyColumn vertical scrolling
 * across the rest of the list.
 */
internal object RemoteBrowserSwipeNav {
    /** Touch must start within this many dp of the left edge. */
    const val EDGE_WIDTH_DP = 40f

    /** Minimum rightward travel (dp) after horizontal touch-slop to navigate. */
    const val MIN_RIGHT_DX_DP = 48f

    fun canNavigateToParent(path: String?): Boolean =
        !path.isNullOrEmpty() && path != "/"

    fun isLeftEdgeStart(xPx: Float, edgeWidthPx: Float): Boolean =
        xPx >= 0f && xPx <= edgeWidthPx

    /** Rightward swipe past [minRightDxPx] (vertical already filtered by horizontal slop). */
    fun shouldGoToParent(totalDxPx: Float, minRightDxPx: Float): Boolean =
        totalDxPx >= minRightDxPx
}
