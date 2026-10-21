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

/**
 * H.264 straight to a Surface (plan §8): a hardware decoder (the emulator's goldfish one counts),
 * low-latency keys on, one access unit per input buffer, every output rendered at once.
 * [onDecoded] reports each frame number as it reaches the screen; [onNeedKeyframe] asks the host
 * for a keyframe after a (re)configure or a decoder error.
 */
internal class VideoDecoder(private val onDecoded: (frame: Long, decodedUs: Long) -> Unit, private val onNeedKeyframe: () -> Unit) {
    private val thread = HandlerThread("desktop-decoder").apply { start() }
    private val handler = Handler(thread.looper)
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var size = 0 to 0
    private val pending = ArrayDeque<ServerMessage.Video>()
    private val freeInputs = ArrayDeque<Int>()
    @Volatile var decoderName: String = ""
        private set
    @Volatile var decodedFrames = 0L
        private set

    fun attach(surface: Surface) = handler.post {
        if (this.surface === surface && codec != null) return@post
        release()
        this.surface = surface
        onNeedKeyframe()
    }

    fun detach() = handler.post {
        release()
        surface = null
    }

    fun submit(video: ServerMessage.Video) = handler.post {
        if (surface == null) return@post
        if (codec == null || (video.keyframe && size != (video.width to video.height))) {
            // Only a keyframe can start a stream; later frames wait for one.
            if (!video.keyframe) return@post
            if (!configure(video)) return@post
        }
        pending.addLast(video)
        // A backlog means the decoder fell behind: drop to the newest keyframe if one is queued.
        if (pending.size > 4) {
            val lastKey = pending.indexOfLast { it.keyframe }
            if (lastKey > 0) repeat(lastKey) { pending.removeFirst() }
        }
        feed()
    }

    private fun configure(video: ServerMessage.Video): Boolean {
        release()
        val s = surface ?: return false
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, video.width, video.height)
        // SPS and PPS as codec-specific data: some decoders (the emulator's among them) never start without them.
        nalUnits(video.data, video.offset).let { units ->
            units.firstOrNull { it.second == 7 }?.let { format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(it.first)) }
            units.firstOrNull { it.second == 8 }?.let { format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(it.first)) }
        }
        if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        if (Build.VERSION.SDK_INT >= 23) format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
        // Vendor low-latency switches (Qualcomm, Exynos, HiSilicon, MediaTek); unknown keys are ignored.
        format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
        format.setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
        format.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
        format.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1)
        format.setInteger("vendor.low-latency.enable", 1)
        val name = pickDecoder()
        return try {
            val c = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.setCallback(callback, handler)
            c.configure(format, s, null, 0)
            c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            c.start()
            codec = c
            decoderName = c.name
            size = video.width to video.height
            Log.i(TAG, "decoder ${c.name} ${video.width}x${video.height}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "decoder configure failed", e)
            release()
            false
        }
    }

    private fun feed() {
        val c = codec ?: return
        while (pending.isNotEmpty() && freeInputs.isNotEmpty()) {
            val index = freeInputs.removeFirst()
            val video = pending.removeFirst()
            try {
                val buffer = c.getInputBuffer(index) ?: continue
                val length = video.data.size - video.offset
                if (length > buffer.capacity()) { onNeedKeyframe(); freeInputs.addFirst(index); continue }
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
        release()
        onNeedKeyframe()
    }

    private fun release() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        size = 0 to 0
        pending.clear()
        freeInputs.clear()
    }

    fun close() {
        handler.post { release(); surface = null }
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
