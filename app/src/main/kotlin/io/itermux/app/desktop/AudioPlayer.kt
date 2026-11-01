package io.itermux.app.desktop

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import io.itermux.desktop.AudioJitter
import io.itermux.desktop.ServerMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import android.os.Build

/**
 * The host's sound (AUDIO frames): Opus packets through the platform's decoder onto a low-latency
 * AudioTrack, on a thread of its own that starts with the first packet. [AudioJitter] decides per
 * packet (from when it arrived and its number) whether a cushion of silence goes first, whether it is
 * dropped to catch up, and how fast the track plays to absorb the two clocks' drift.
 */
internal class AudioPlayer {
    /** Packets with the time they arrived (ms): the jitter is the network's, not this thread's. */
    private val queue = LinkedBlockingDeque<Pair<Long, ServerMessage.Audio>>()
    @Volatile private var thread: Thread? = null
    @Volatile var packets = 0L
        private set
    @Volatile var dropped = 0L
        private set
    /** Times a cushion of silence went in (the first start included). */
    @Volatile var cushions = 0L
        private set
    /** The track's sample rate as the drift control set it last. */
    @Volatile var playbackRate = RATE
        private set
    /** Sound waiting to be heard, in ms: what the track still holds. */
    @Volatile var queuedMs = 0
        private set
    /** The backlog the jitter buffer aims for now. */
    @Volatile var targetMs = 0
        private set
    /** The last full second of decoded sound: the left channel's RMS level and its zero crossings (twice its pitch). */
    @Volatile var level = Level(0, 0)
        private set
    data class Level(val rms: Int, val crossings: Int)
    private var sumSquares = 0.0
    private var crossings = 0
    private var samples = 0
    private var lastLeft = 0

    fun submit(packet: ServerMessage.Audio) {
        queue.offerLast(System.nanoTime() / 1_000_000 to packet)
        while (queue.size > MAX_PACKETS) { queue.pollFirst(); dropped++ }
        if (thread == null) synchronized(this) { if (thread == null) thread = Thread(::run, "desktop-audio").apply { start() } }
    }

    /** Stops playing and lets go of the decoder and the track; the next packet starts them again. */
    fun stop() {
        val t = synchronized(this) { thread.also { thread = null } } ?: return
        t.interrupt()
        queue.clear()
        // Join off the caller: closing the desktop page must not hitch on the audio thread.
        Thread({ runCatching { t.join(500) } }, "desktop-audio-join").apply { isDaemon = true; start() }
    }

    private fun run() {
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            codec = try { openDecoder() } catch (e: Exception) {
                Log.w(TAG, "no Opus decoder", e); return
            }
            val startThreshold = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val minBuffer = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minBuffer, msToBytes(if (startThreshold) AudioJitter.MAX_TARGET_MS + AudioJitter.DROP_ABOVE_MS + 100 else 240)))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "no AudioTrack", e)
                runCatching { codec?.stop(); codec?.release() }
                return
            }
            val activeCodec = codec!!
            val activeTrack = track!!
            // A track that ran dry (the host fell silent) leaves the mixer and comes back only once this much
            // is written: by default its whole buffer, which a backlog held under the drop line never reached.
            if (startThreshold) runCatching { activeTrack.setStartThresholdInFrames(RATE / 1000 * AudioJitter.PACKET_MS) }
            Log.i(TAG, "audio started: ${activeCodec.name}, track buffer ${activeTrack.bufferSizeInFrames} frames")
            val jitter = AudioJitter()
            val info = MediaCodec.BufferInfo()
            var written = 0L // frames written to the track
            var underruns = 0
            var rate = RATE
            var pcm = ByteArray(0)
            fun inTrack() = (written - (activeTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL)).coerceAtLeast(0)
            try {
                activeTrack.play()
                while (!Thread.currentThread().isInterrupted) {
                    val (arrived, next) = queue.pollFirst(200, TimeUnit.MILLISECONDS) ?: continue
                    // Ran dry: the track's own count, not the play head reaching what was written (it moves
                    // in the mixer's steps, which reads as empty for a moment in normal playback).
                    val ran = activeTrack.underrunCount
                    val decision = jitter.arrive(arrived, next.seq, (inTrack() * 1000 / RATE).toInt(), ran > underruns)
                    underruns = ran
                    targetMs = jitter.targetMs
                    if (decision == AudioJitter.Decision.Drop) { dropped++; continue }
                    val cushion = (decision as AudioJitter.Decision.Play).cushionMs
                    if (cushion > 0) {
                        val silence = ByteArray(msToBytes(cushion))
                        activeTrack.write(silence, 0, silence.size)
                        written += silence.size / 4
                        cushions++
                    }
                    val want = (RATE * jitter.rate).toInt()
                    if (want != rate && activeTrack.setPlaybackRate(want) == AudioTrack.SUCCESS) rate = want
                    playbackRate = rate
                    packets++
                    val input = activeCodec.dequeueInputBuffer(20_000)
                    if (input >= 0) {
                        activeCodec.getInputBuffer(input)!!.apply { clear(); put(next.data, next.offset, next.data.size - next.offset) }
                        activeCodec.queueInputBuffer(input, 0, next.data.size - next.offset, next.seq * AudioJitter.PACKET_MS * 1000, 0)
                    }
                    while (true) {
                        val out = activeCodec.dequeueOutputBuffer(info, if (input >= 0) 5_000 else 0)
                        if (out < 0) break
                        if (info.size > 0) {
                            if (pcm.size < info.size) pcm = ByteArray(info.size)
                            activeCodec.getOutputBuffer(out)!!.apply { position(info.offset); get(pcm, 0, info.size) }
                            activeTrack.write(pcm, 0, info.size)
                            written += info.size / 4
                            measure(pcm, info.size)
                        }
                        activeCodec.releaseOutputBuffer(out, false)
                    }
                    queuedMs = (inTrack() * 1000 / RATE).toInt()
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Log.w(TAG, "audio stopped", e)
            } finally {
                val underrunCount = runCatching { activeTrack.underrunCount }.getOrDefault(-1)
                runCatching { activeTrack.pause(); activeTrack.flush(); activeTrack.release() }
                runCatching { activeCodec.stop(); activeCodec.release() }
                Log.i(TAG, "audio ended: $packets packets, $dropped dropped, $underrunCount underruns, last second $level")
            }
        } finally {
            // Any init failure that returned early still clears the thread so submit() can retry.
            synchronized(this) { if (thread === Thread.currentThread()) thread = null }
        }
    }

    private fun measure(pcm: ByteArray, size: Int) {
        for (i in 0 until size / 4) {
            val left = (pcm[i * 4].toInt() and 0xFF) or (pcm[i * 4 + 1].toInt() shl 8)
            sumSquares += left.toDouble() * left
            if ((left >= 0) != (lastLeft >= 0)) crossings++
            lastLeft = left
            if (++samples == RATE) {
                level = Level(kotlin.math.sqrt(sumSquares / RATE).toInt(), crossings)
                sumSquares = 0.0; crossings = 0; samples = 0
            }
        }
    }

    private fun openDecoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 2)
        // RFC 7845's identification header, then the pre-skip and the seek pre-roll in nanoseconds.
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).put("OpusHead".toByteArray()).put(1).put(2)
            .putShort(PRE_SKIP.toShort()).putInt(RATE).putShort(0).put(0)
        format.setByteBuffer("csd-0", head.flip() as ByteBuffer)
        format.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(PRE_SKIP * 1_000_000_000L / RATE).flip() as ByteBuffer)
        format.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000L).flip() as ByteBuffer)
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) { codec.release(); throw e }
        return codec
    }

    private fun msToBytes(ms: Int) = RATE / 1000 * ms * 4

    companion object {
        private const val TAG = "RoamttyDesktop"
        const val RATE = 48_000
        /** The encoder's look-ahead the decoder drops at the start (the plugin's libopus in AUDIO mode). */
        const val PRE_SKIP = 312
        private const val MAX_PACKETS = 50
    }
}
