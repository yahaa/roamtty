package io.itermux.app.desktop

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import io.itermux.desktop.ServerMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * The host's sound (AUDIO frames): Opus packets through the platform's decoder onto a low-latency
 * AudioTrack, on a thread of its own that starts with the first packet. Each start after a silence
 * gets [CUSHION_MS] of silence in front, which absorbs the network's jitter; whenever more than
 * [MAX_QUEUED_MS] is waiting (a stall that then arrived in one burst) the oldest packets are dropped,
 * so the sound stays within a few tens of milliseconds of the picture.
 */
internal class AudioPlayer {
    private val queue = LinkedBlockingDeque<ServerMessage.Audio>()
    @Volatile private var thread: Thread? = null
    @Volatile var packets = 0L
        private set
    @Volatile var dropped = 0L
        private set
    /** Times the track ran dry and got a fresh cushion (the first start included). */
    @Volatile var cushions = 0L
        private set
    /** The track's sample rate as the drift control set it last. */
    @Volatile var playbackRate = RATE
        private set
    /** Sound waiting to be heard, in ms: the queue plus what the track still holds. */
    @Volatile var queuedMs = 0
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
        queue.offerLast(packet)
        while (queue.size > MAX_PACKETS) { queue.pollFirst(); dropped++ }
        if (thread == null) synchronized(this) { if (thread == null) thread = Thread(::run, "desktop-audio").apply { start() } }
    }

    /** Stops playing and lets go of the decoder and the track; the next packet starts them again. */
    fun stop() {
        val t = synchronized(this) { thread.also { thread = null } } ?: return
        t.interrupt()
        queue.clear()
        runCatching { t.join(500) }
    }

    private fun run() {
        val codec = try { openDecoder() } catch (e: Exception) { Log.w(TAG, "no Opus decoder", e); return }
        val minBuffer = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minBuffer, msToBytes(MAX_QUEUED_MS)))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        Log.i(TAG, "audio started: ${codec.name}, track buffer ${track.bufferSizeInFrames} frames")
        val info = MediaCodec.BufferInfo()
        var written = 0L // frames written to the track
        var lastSeq = -1L
        var lastArrival = 0L
        var underruns = 0
        var smoothed = CUSHION_MS.toDouble()
        var rate = RATE
        var pcm = ByteArray(0)
        try {
            track.play()
            while (!Thread.currentThread().isInterrupted) {
                val p = queue.pollFirst(200, TimeUnit.MILLISECONDS) ?: continue
                // Starting, or the track ran dry (the host fell silent, or the network stalled): a cushion
                // first. Not whenever the play head reaches what was written: it moves in the mixer's steps,
                // which reads as empty for a moment in normal playback.
                val now = System.nanoTime() / 1_000_000
                val ran = track.underrunCount
                if (lastSeq < 0 || ran > underruns || now - lastArrival > GAP_MS) {
                    val silence = ByteArray(msToBytes(CUSHION_MS))
                    track.write(silence, 0, silence.size)
                    written += silence.size / 4
                    cushions++
                }
                underruns = ran
                lastArrival = now
                // Too far behind: drop the oldest until the backlog is down to the cushion again.
                var backlog = (written - (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL)).coerceAtLeast(0) * 1000 / RATE + queue.size * PACKET_MS
                var next: ServerMessage.Audio = p
                if (backlog > MAX_QUEUED_MS) {
                    while (backlog > CUSHION_MS + PACKET_MS) {
                        next = queue.pollFirst() ?: break
                        dropped++
                        backlog -= PACKET_MS
                    }
                }
                queuedMs = backlog.toInt()
                // The two clocks never quite agree (a virtual machine's sink was 1.3% fast against the
                // emulator): the track plays a little faster or slower to hold the backlog near its
                // target, at most 3%, instead of dropping a packet every second or two.
                smoothed += (backlog - smoothed) * 0.05
                val want = (RATE * (1 + ((smoothed - TARGET_MS) * 0.0005).coerceIn(-0.03, 0.03))).toInt()
                if (kotlin.math.abs(want - rate) >= RATE / 1000 && track.setPlaybackRate(want) == AudioTrack.SUCCESS) rate = want
                playbackRate = rate
                lastSeq = next.seq
                packets++
                val input = codec.dequeueInputBuffer(20_000)
                if (input >= 0) {
                    codec.getInputBuffer(input)!!.apply { clear(); put(next.data, next.offset, next.data.size - next.offset) }
                    codec.queueInputBuffer(input, 0, next.data.size - next.offset, next.seq * PACKET_MS * 1000, 0)
                }
                while (true) {
                    val out = codec.dequeueOutputBuffer(info, if (input >= 0) 5_000 else 0)
                    if (out < 0) break
                    if (info.size > 0) {
                        if (pcm.size < info.size) pcm = ByteArray(info.size)
                        codec.getOutputBuffer(out)!!.apply { position(info.offset); get(pcm, 0, info.size) }
                        track.write(pcm, 0, info.size)
                        written += info.size / 4
                        measure(pcm, info.size)
                    }
                    codec.releaseOutputBuffer(out, false)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "audio stopped", e)
        } finally {
            val underruns = runCatching { track.underrunCount }.getOrDefault(-1)
            runCatching { track.pause(); track.flush(); track.release() }
            runCatching { codec.stop(); codec.release() }
            Log.i(TAG, "audio ended: $packets packets, $dropped dropped, $underruns underruns, last second $level")
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
        const val PACKET_MS = 20
        /** The encoder's look-ahead the decoder drops at the start (the plugin's libopus in AUDIO mode). */
        const val PRE_SKIP = 312
        const val CUSHION_MS = 60
        const val MAX_QUEUED_MS = 200
        /** Where the drift control holds the backlog: the cushion plus the packet being decoded. */
        private const val TARGET_MS = CUSHION_MS + PACKET_MS
        /** No packet for this long: the host went silent (it sends nothing then), or the network stalled. */
        private const val GAP_MS = 100
        private const val MAX_PACKETS = 50
    }
}
