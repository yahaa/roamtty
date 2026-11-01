package io.itermux.app.desktop

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import io.itermux.desktop.ServerMessage
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer

/**
 * H.264 straight to a Surface (plan §8): a hardware decoder (the emulator's goldfish one counts),
 * low-latency keys on, one access unit per input buffer, every output rendered at once.
 * [onDecoded] reports each frame number as the decoder hands it over, [onRendered] when it reached
 * the display; [onNeedKeyframe] asks the host for a keyframe after a (re)configure or a decoder error.
 */
internal class VideoDecoder(private val onDecoded: (frame: Long, decodedUs: Long) -> Unit,
    private val onRendered: (frame: Long, renderedUs: Long) -> Unit, private val onNeedKeyframe: () -> Unit) {
    private val thread = HandlerThread("desktop-decoder").apply { start() }
    private val handler = Handler(thread.looper)
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var size = 0 to 0
    private var askLowLatency = true
    private val pending = VideoFrameQueue()
    private val freeInputs = ArrayDeque<Int>()
    // The queue monitor also orders lifecycle requests; codec calls and user callbacks stay outside it.
    private var requestedSurface: Surface? = null
    private var closed = false
    private val feedPosted = AtomicBoolean()
    private val feedTask = Runnable { feedPosted.set(false); feed() }
    @Volatile var decoderName: String = ""
        private set
    @Volatile var decodedFrames = 0L
        private set

    fun attach(surface: Surface) {
        synchronized(pending) {
            if (closed) return
            if (requestedSurface !== surface) pending.deactivate()
            requestedSurface = surface
            handler.post {
                if (this.surface === surface && codec != null) return@post
                release()
                val attached = synchronized(pending) {
                    if (closed || requestedSurface !== surface) false else {
                        this.surface = surface
                        pending.activate()
                        true
                    }
                }
                if (attached) onNeedKeyframe()
            }
        }
    }

    fun detach() {
        synchronized(pending) {
            if (closed) return
            requestedSurface = null
            pending.deactivate()
            handler.post { release(); surface = null }
        }
    }

    fun submit(video: ServerMessage.Video) {
        // The Handler carries one wakeup, never one closure retaining every encoded video packet.
        if (pending.offer(video) && feedPosted.compareAndSet(false, true)) {
            if (!handler.post(feedTask)) feedPosted.set(false)
        }
    }

    private fun configure(video: ServerMessage.Video): Boolean {
        release()
        // Some decoders (the emulator's goldfish among them) refuse a format asking for low latency: retry
        // without, and don't ask again.
        if (askLowLatency && configure(video, lowLatency = true)) return true
        return configure(video, lowLatency = false).also { if (it) askLowLatency = false }
    }

    private fun configure(video: ServerMessage.Video, lowLatency: Boolean): Boolean {
        val s = surface ?: return false
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, video.width, video.height)
        // SPS and PPS as codec-specific data: some decoders (the emulator's among them) never start without them.
        nalUnits(video.data, video.offset).let { units ->
            units.firstOrNull { it.second == 7 }?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it.first)) }
            units.firstOrNull { it.second == 8 }?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it.first)) }
        }
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
        if (lowLatency) {
            if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            // Vendor low-latency switches (Qualcomm, Exynos, HiSilicon, MediaTek); unknown keys are ignored.
            format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            format.setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
            format.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
            format.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1)
            format.setInteger("vendor.low-latency.enable", 1)
        }
        val name = pickDecoder()
        return try {
            val c = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            // Set before configure fails, so release() frees it.
            codec = c
            c.setCallback(callback, handler)
            // The frame number rides in the timestamp; the render time is on System.nanoTime's clock.
            c.setOnFrameRenderedListener({ codec, frame, nanos -> if (codec === this.codec) onRendered(frame, nanos / 1000) }, handler)
            c.configure(format, s, null, 0)
            c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            c.start()
            decoderName = c.name
            size = video.width to video.height
            Log.i(TAG, "decoder ${c.name} ${video.width}x${video.height}" + if (lowLatency) "" else " (no low-latency)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "decoder configure failed (low latency $lowLatency)", e)
            release()
            false
        }
    }

    private fun feed() {
        while (true) {
            if (pending.takeKeyframeRequest()) onNeedKeyframe()
            val video = pending.peek() ?: return
            if (codec == null || (video.keyframe && size != (video.width to video.height))) {
                if (!video.keyframe || !configure(video)) { reset(); return }
            }
            val c = codec ?: return
            // Keep the head in the bounded queue while the codec has no input buffer available.
            if (freeInputs.isEmpty()) return
            if (!pending.remove(video)) continue
            val index = freeInputs.removeFirst()
            try {
                val buffer = c.getInputBuffer(index)
                val length = video.data.size - video.offset
                if (buffer == null || length > buffer.capacity()) { reset(); return }
                buffer.clear()
                buffer.put(video.data, video.offset, length)
                // The frame number rides in the timestamp, so the output callback knows which frame it rendered.
                c.queueInputBuffer(index, 0, length, video.frame, if (video.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            } catch (e: Exception) {
                Log.w(TAG, "queue failed", e)
                reset()
                return
            }
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (codec !== this@VideoDecoder.codec) return
            freeInputs.addLast(index)
            feed()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== this@VideoDecoder.codec) return
            try { codec.releaseOutputBuffer(index, true) } catch (_: Exception) { return }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
            decodedFrames++
            onDecoded(info.presentationTimeUs, System.nanoTime() / 1000)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "decoder error", e)
            if (codec === this@VideoDecoder.codec) reset()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
    }

    /** Starts over at the next keyframe. */
    private fun reset() {
        pending.reset()
        release()
        onNeedKeyframe()
    }

    private fun release() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        size = 0 to 0
        freeInputs.clear()
    }

    fun close() {
        synchronized(pending) {
            if (closed) return
            closed = true
            requestedSurface = null
            pending.deactivate()
            handler.post { release(); surface = null }
        }
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "RoamttyDesktop"

        /** The Annex-B NAL units of an access unit, each with its start code, and its type. */
        fun nalUnits(data: ByteArray, offset: Int): List<Pair<ByteArray, Int>> {
            val starts = mutableListOf<Int>()
            var i = offset
            while (i + 3 <= data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                    starts += if (i > offset && data[i - 1] == 0.toByte()) i - 1 else i
                    i += 3
                } else i++
            }
            return starts.mapIndexed { n, start ->
                val end = starts.getOrElse(n + 1) { data.size }
                val bytes = data.copyOfRange(start, end)
                val header = bytes.indexOfFirst { it == 1.toByte() } + 1
                bytes to (bytes.getOrElse(header) { 0 }.toInt() and 0x1F)
            }
        }

        /** Hardware first; software decoders only when nothing else decodes AVC. */
        fun pickDecoder(): String? {
            val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
            }
            fun software(info: MediaCodecInfo): Boolean {
                val n = info.name.lowercase()
                if (n.startsWith("c2.goldfish")) return false
                if (Build.VERSION.SDK_INT >= 29) return info.isSoftwareOnly
                return n.startsWith("omx.google.") || n.startsWith("c2.android.")
            }
            return (decoders.firstOrNull { !software(it) } ?: decoders.firstOrNull())?.name
        }
    }
}
