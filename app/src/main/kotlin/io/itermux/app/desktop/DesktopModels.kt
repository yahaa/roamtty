package io.itermux.app.desktop

import android.graphics.Bitmap
import io.itermux.desktop.LatencyMeter

/** The cursor the plugin reports, in native pixels of the selected display; [shape] null until its image came. */
internal data class RemoteCursor(val x: Int, val y: Int, val visible: Boolean, val shape: CursorImage?)
internal class CursorImage(val bitmap: Bitmap, val hotX: Int, val hotY: Int)

/** What the network badge and the statistics show. */
internal data class DesktopStats(val rttMs: Int? = null, val fps: Int = 0, val kbps: Int = 0, val width: Int = 0, val height: Int = 0,
    val frames: Long = 0, val decoder: String = "", val link: String = "SSH", val encoder: String = "",
    /** UDP only: share of shards lost before FEC, frames FEC rebuilt, frames lost outright. */
    val lossPercent: Double? = null, val fecRecovered: Long = 0, val lostFrames: Long = 0,
    /** Where the last second's frames spent their time, host to screen. */
    val latency: LatencyMeter.Latency? = null,
    /** Sound waiting to be heard on the phone, while sound plays. */
    val audioMs: Int? = null)
