package io.itermux.desktop

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal
import java.util.TreeMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Real-time Opus VOIP codec, adaptive RED + bitrate controller, speech jitter buffer,
 * WSOLA time-scale convergence, and capture DSP preprocessor for Android `agy /voice`.
 * Mirrors `roamtty-desktop/bin/roamtty-desktop/src/voice_codec.rs`.
 */
object VoiceOpusMath {
    const val FRAME_MS = 20
    const val SILENCE_RMS_THRESHOLD = 0.004f
    const val MAX_CONSECUTIVE_PLC = 5
    const val STATS_WINDOW_MS = 1_000L
    const val JITTER_WINDOW = 150
    const val STEP_DOWN_HOLD_MS = 3_000L
    const val DTX_HANGOVER_FRAMES = 10 // 200 ms speech hangover before entering DTX
    const val DTX_KEEPALIVE_INTERVAL = 10 // 200 ms between DTX keepalive packets

    fun isOpusSampleRate(rate: Int): Boolean =
        rate == 8_000 || rate == 12_000 || rate == 16_000 || rate == 24_000 || rate == 48_000

    fun frameSamples(rate: Int): Int = rate * FRAME_MS / 1000

    fun pcmRms(samples: ShortArray, length: Int = samples.size): Float {
        if (length <= 0) return 0f
        var sum = 0.0
        for (i in 0 until length) {
            val f = samples[i] / 32768.0
            sum += f * f
        }
        return sqrt(sum / length).toFloat()
    }

    fun bytesToShorts(pcm: ByteArray, offset: Int, length: Int, dst: ShortArray = ShortArray(length / 2)): ShortArray {
        val n = minOf(length / 2, dst.size)
        var bi = offset
        for (i in 0 until n) {
            val lo = pcm[bi].toInt() and 0xFF
            val hi = pcm[bi + 1].toInt()
            dst[i] = ((hi shl 8) or lo).toShort()
            bi += 2
        }
        return dst
    }

    fun shortsToBytes(samples: ShortArray, length: Int = samples.size): ByteArray {
        val out = ByteArray(length * 2)
        var bi = 0
        for (i in 0 until length) {
            val s = samples[i].toInt()
            out[bi++] = (s and 0xFF).toByte()
            out[bi++] = ((s ushr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Pitch-preserving WSOLA time compression (~10-15%) to converge backlog without clicks or pitch shift. */
    fun wsolaCompress(input: ShortArray, targetLen: Int): ShortArray {
        if (targetLen <= 0 || targetLen >= input.size || input.size < 64) return input
        val cut = input.size - targetLen
        val overlap = (targetLen / 3).coerceIn(32, input.size / 2)
        val maxShift = minOf(input.size - overlap, cut + overlap / 2)
        val minShift = maxOf(1, cut - overlap / 3)
        if (minShift >= maxShift) return input.copyOf(targetLen)

        val anchor = (targetLen - overlap) / 2
        var bestShift = cut.coerceIn(minShift, maxShift)
        var bestScore = Long.MIN_VALUE
        val step = if (overlap > 120) 2 else 1
        for (shift in minShift..maxShift) {
            if (anchor + shift + overlap > input.size) break
            var corr = 0L
            var i = 0
            while (i < overlap) {
                corr += input[anchor + i].toLong() * input[anchor + shift + i].toLong()
                i += step
            }
            if (corr > bestScore) {
                bestScore = corr
                bestShift = shift
            }
        }
        val outLen = input.size - bestShift
        val out = ShortArray(outLen)
        System.arraycopy(input, 0, out, 0, anchor)
        for (i in 0 until overlap) {
            val w = i.toFloat() / overlap.toFloat()
            val mixed = input[anchor + i] * (1f - w) + input[anchor + bestShift + i] * w
            out[anchor + i] = mixed.roundToInt().coerceIn(-32768, 32767).toShort()
        }
        val tailStart = anchor + bestShift + overlap
        val tailCount = input.size - tailStart
        if (tailCount > 0) {
            System.arraycopy(input, tailStart, out, anchor + overlap, tailCount)
        }
        return out
    }

    /** 5 ms raised-cosine crossfade between previous tail and new frame head after fast-forward jumps. */
    fun crossfadeHead(prevTail: ShortArray, frame: ShortArray) {
        val n = minOf(prevTail.size, frame.size)
        if (n <= 0) return
        for (i in 0 until n) {
            val w = (0.5 * (1.0 - cos(PI * i / n))).toFloat()
            val mixed = prevTail[i] * (1f - w) + frame[i] * w
            frame[i] = mixed.roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
}

/**
 * Lightweight capture audio front-end: 80 Hz 1st-order high-pass filter (removes handling rumble & DC bias)
 * plus soft-knee limiter (prevents harsh clipping distortion before Opus encoding).
 */
class VoiceAudioPreprocessor(
    sampleRate: Int = VoiceProtocol.MIC_SAMPLE_RATE,
    startupMuteMs: Int = 180,
    fadeInMs: Int = 20,
) {
    private val rate: Int = sampleRate.coerceAtLeast(8_000)
    // RC = 1 / (2 * pi * 80 Hz); alpha = RC / (RC + dt)
    private val alpha: Float = run {
        val rc = 1.0 / (2.0 * PI * 80.0)
        val dt = 1.0 / rate.toDouble()
        (rc / (rc + dt)).toFloat()
    }
    private val muteSamples: Int = (rate.toLong() * startupMuteMs.coerceAtLeast(0) / 1000L).toInt()
    private val fadeSamples: Int = (rate.toLong() * fadeInMs.coerceAtLeast(0) / 1000L).toInt()
    private var initialized = false
    private var processedSamples = 0
    private var prevX = 0f
    private var prevY = 0f

    fun processInPlace(pcm: ByteArray, offset: Int, length: Int) {
        val samples = length / 2
        if (samples <= 0) return
        var bi = offset
        var xPrev = prevX
        var yPrev = prevY
        var isInit = initialized
        var seen = processedSamples
        val a = alpha
        val muteEnd = muteSamples
        val fadeEnd = muteSamples + fadeSamples
        for (i in 0 until samples) {
            val lo = pcm[bi].toInt() and 0xFF
            val hi = pcm[bi + 1].toInt()
            val raw = ((hi shl 8) or lo).toShort() / 32768f
            val hp = if (!isInit) {
                xPrev = raw
                yPrev = 0f
                isInit = true
                0f
            } else {
                val filtered = a * (yPrev + raw - xPrev)
                xPrev = raw
                yPrev = filtered
                filtered
            }
            // Soft-knee limiter above 0.85 full-scale
            var out = if (abs(hp) > 0.85f) {
                val sign = if (hp >= 0f) 1f else -1f
                sign * (0.85f + 0.15f * tanh(((abs(hp) - 0.85f) / 0.15f).toDouble()).toFloat())
            } else {
                hp
            }
            if (seen < muteEnd) {
                out = 0f
            } else if (seen < fadeEnd && fadeSamples > 0) {
                out *= (seen - muteEnd + 1).toFloat() / fadeSamples.toFloat()
            }
            seen++
            val s = (out * 32767f).roundToInt().coerceIn(-32768, 32767)
            pcm[bi] = (s and 0xFF).toByte()
            pcm[bi + 1] = ((s ushr 8) and 0xFF).toByte()
            bi += 2
        }
        initialized = isInit
        processedSamples = seen
        prevX = xPrev
        prevY = yPrev
    }

    fun reset() {
        initialized = false
        processedSamples = 0
        prevX = 0f
        prevY = 0f
    }
}

/** Concentus Opus VOIP encoder with In-band FEC, DTX, and dynamic bitrate/packet-loss tuning. */
class VoiceOpusEncoderWrapper(
    val sampleRate: Int,
    initialBitrate: Int,
    dtx: Boolean = true,
) {
    val frameLen: Int = VoiceOpusMath.frameSamples(sampleRate)
    private val encoder: OpusEncoder = OpusEncoder(sampleRate, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        bitrate = initialBitrate
        signalType = OpusSignal.OPUS_SIGNAL_VOICE
        complexity = 5
        useInbandFEC = true
        packetLossPercent = 5
        useDTX = dtx
    }
    private var curBitrate = initialBitrate
    private var curLossPerc = 5
    private val outBuf = ByteArray(512)

    fun applyParams(bitrate: Int, lossPerc: Int) {
        if (bitrate != curBitrate) {
            curBitrate = bitrate
            encoder.bitrate = bitrate
        }
        val clamped = lossPerc.coerceIn(0, 100)
        if (clamped != curLossPerc) {
            curLossPerc = clamped
            encoder.packetLossPercent = clamped
        }
    }

    fun reset() {
        encoder.resetState()
    }

    fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == frameLen) { "expected $frameLen samples, got ${pcm.size}" }
        val n = encoder.encode(pcm, 0, frameLen, outBuf, 0, outBuf.size)
        return if (n > 0) outBuf.copyOf(n) else ByteArray(0)
    }
}

/**
 * Concentus Opus VOIP decoder supporting normal decode, In-band FEC (LBRR), and native PLC.
 * Always runs Concentus at canonical 48 kHz (avoiding Concentus 1.0.2's broken 24 kHz Hybrid/SWB path)
 * and decimates cleanly by `48000 / sampleRate` (exact integer for 8/12/16/24/48 kHz).
 */
class VoiceOpusDecoderWrapper(val sampleRate: Int) {
    val frameLen: Int = VoiceOpusMath.frameSamples(sampleRate)
    private val decim: Int = (48_000 / sampleRate).coerceAtLeast(1)
    private val decoder: OpusDecoder = OpusDecoder(48_000, 1)
    private val scratch48 = ShortArray(960)

    private fun decimate(decoded48: Int): ShortArray {
        if (decoded48 <= 0) return ShortArray(frameLen)
        if (decim == 1) return scratch48.copyOf(decoded48)
        val outLen = decoded48 / decim
        val out = ShortArray(outLen)
        var si = 0
        for (i in 0 until outLen) {
            var sum = 0
            for (k in 0 until decim) sum += scratch48[si++]
            out[i] = (sum / decim).toShort()
        }
        return out
    }

    fun decode(packet: ByteArray, decodeFec: Boolean = false): ShortArray {
        val n = decoder.decode(packet, 0, packet.size, scratch48, 0, scratch48.size, decodeFec)
        return decimate(n)
    }

    fun decodePlc(): ShortArray {
        val n = decoder.decode(null, 0, 0, scratch48, 0, scratch48.size, false)
        return decimate(n)
    }

    fun reset() {
        decoder.resetState()
    }
}

data class VoiceLinkProfile(val bitrate: Int, val redCount: Int, val opusLossPerc: Int)

/**
 * Adapts Opus bitrate, temporal RED depth (0..3), and Opus in-band FEC percentage from peer [VoiceStats].
 * Fast escalation on loss/burst; 3 s clean hold before stepping down.
 */
class VoiceLinkController(sampleRate: Int) {
    private val baseBitrate: Int = when {
        sampleRate <= 12_000 -> 18_000
        sampleRate == 16_000 -> 28_000
        sampleRate == 24_000 -> 36_000
        else -> 48_000
    }
    private var tier = 0
    private var smoothedLoss = 0.0
    private var smoothedBurst = 0.0
    private var cleanSinceMs: Long? = null

    val currentLoss: Double get() = smoothedLoss

    fun profile(): VoiceLinkProfile {
        val (scale, redCount, lossPerc) = when (tier) {
            0 -> Triple(1.00, 0, 5)
            1 -> Triple(0.86, 1, 10)
            2 -> Triple(0.66, 2, 20)
            else -> Triple(0.45, 3, 30)
        }
        val bitrate = (baseBitrate * scale).toInt().coerceIn(12_000, 64_000)
        return VoiceLinkProfile(bitrate, redCount, lossPerc)
    }

    fun onStats(st: VoiceStats, nowMs: Long): VoiceLinkProfile {
        val loss = st.lossPermille / 1000.0
        val burst = st.maxBurstLoss.toDouble()
        smoothedLoss = if (loss > smoothedLoss) smoothedLoss * 0.4 + loss * 0.6 else smoothedLoss * 0.85 + loss * 0.15
        smoothedBurst = if (burst > smoothedBurst) burst else smoothedBurst * 0.80 + burst * 0.20

        val desired = when {
            smoothedLoss >= 0.15 || smoothedBurst >= 3.0 -> 3
            smoothedLoss >= 0.05 || smoothedBurst >= 2.0 -> 2
            smoothedLoss >= 0.01 || smoothedBurst >= 1.0 || st.jitterMs >= 35 -> 1
            else -> 0
        }
        if (desired > tier) {
            tier = desired
            cleanSinceMs = null
        } else if (desired < tier) {
            val since = cleanSinceMs ?: nowMs.also { cleanSinceMs = it }
            if (nowMs - since >= VoiceOpusMath.STEP_DOWN_HOLD_MS) {
                tier = (tier - 1).coerceAtLeast(0)
                cleanSinceMs = nowMs
            }
        } else {
            cleanSinceMs = null
        }
        return profile()
    }
}

/**
 * Encodes 20 ms mic PCM frames into sequenced [VoiceOpusPacket]s with adaptive RED history and DTX suppression.
 */
class VoiceRedSender(
    val streamId: Long,
    val sampleRate: Int = VoiceProtocol.MIC_SAMPLE_RATE,
    private val enableDtx: Boolean = true,
) {
    val controller = VoiceLinkController(sampleRate)
    private val encoder = VoiceOpusEncoderWrapper(sampleRate, controller.profile().bitrate, dtx = enableDtx)
    val frameBytes: Int get() = encoder.frameLen * 2
    private val recent = ArrayDeque<Pair<Long, ByteArray>>()
    private val shortScratch = ShortArray(encoder.frameLen)
    private var seq = 0L
    private var silentRun = 0
    private var dtxTicksSinceKeepalive = 0
    private var sentFirst = false

    fun onPeerStats(stats: VoiceStats, nowMs: Long) {
        val p = controller.onStats(stats, nowMs)
        encoder.applyParams(p.bitrate, p.opusLossPerc)
    }

    /**
     * Encodes one 20 ms s16le PCM frame (`frameLen * 2` bytes).
     * Returns `null` when suppressed by DTX during sustained silence (emits a tiny keepalive every 200 ms).
     */
    fun encodePcm20ms(pcm: ByteArray, offset: Int, length: Int): VoiceOpusPacket? {
        if (length < encoder.frameLen * 2) return null
        VoiceOpusMath.bytesToShorts(pcm, offset, encoder.frameLen * 2, shortScratch)
        return encodeShorts20ms(shortScratch)
    }

    fun encodeShorts20ms(samples: ShortArray): VoiceOpusPacket? {
        val curSeq = seq
        seq = (seq + 1) and 0xFFFFFFFFL
        val tsSamples = (curSeq * encoder.frameLen.toLong()) and 0xFFFFFFFFL
        val rms = VoiceOpusMath.pcmRms(samples, encoder.frameLen)
        val isSpeech = rms >= VoiceOpusMath.SILENCE_RMS_THRESHOLD

        if (isSpeech) {
            silentRun = 0
            dtxTicksSinceKeepalive = 0
        } else {
            silentRun++
        }

        // Suppress silent packets after hangover, sending a 0-byte DTX keepalive every 200 ms so seq/stats stay live.
        if (enableDtx && sentFirst && silentRun > VoiceOpusMath.DTX_HANGOVER_FRAMES) {
            dtxTicksSinceKeepalive++
            if (dtxTicksSinceKeepalive < VoiceOpusMath.DTX_KEEPALIVE_INTERVAL) {
                return null
            }
            dtxTicksSinceKeepalive = 0
            return VoiceOpusPacket(
                streamId = streamId,
                seq = curSeq,
                tsSamples = tsSamples,
                flags = VoiceProtocol.VOICE_FLAG_DTX,
                red = emptyList(),
                primary = ByteArray(0),
            )
        }

        val profile = controller.profile()
        encoder.applyParams(profile.bitrate, profile.opusLossPerc)
        val encoded = encoder.encode(samples)
        if (encoded.isEmpty()) return null
        // Opus encoder itself can return a 1-byte TOC-only DTX packet during silence.
        if (enableDtx && sentFirst && encoded.size <= 2 && !isSpeech) {
            dtxTicksSinceKeepalive++
            if (dtxTicksSinceKeepalive < VoiceOpusMath.DTX_KEEPALIVE_INTERVAL) return null
            dtxTicksSinceKeepalive = 0
            return VoiceOpusPacket(
                streamId = streamId,
                seq = curSeq,
                tsSamples = tsSamples,
                flags = VoiceProtocol.VOICE_FLAG_DTX,
                red = emptyList(),
                primary = ByteArray(0),
            )
        }

        val red = buildRed(curSeq, profile.redCount)
        recordRecent(curSeq, encoded)
        var flags = VoiceProtocol.VOICE_FLAG_FEC
        if (isSpeech) flags = flags or VoiceProtocol.VOICE_FLAG_SPEECH
        if (!sentFirst) {
            flags = flags or VoiceProtocol.VOICE_FLAG_RESET
            sentFirst = true
        }
        return VoiceOpusPacket(
            streamId = streamId,
            seq = curSeq,
            tsSamples = tsSamples,
            flags = flags,
            red = red,
            primary = encoded,
        )
    }

    private fun buildRed(curSeq: Long, maxCount: Int): List<VoiceRedEntry> {
        if (maxCount <= 0 || recent.isEmpty()) return emptyList()
        val want = minOf(maxCount, VoiceProtocol.VOICE_MAX_RED_ENTRIES)
        val out = ArrayList<VoiceRedEntry>(want)
        for (i in recent.size - 1 downTo 0) {
            val (prevSeq, data) = recent[i]
            val delta = ((curSeq - prevSeq) and 0xFFFFFFFFL).toInt()
            if (delta <= 0 || delta > VoiceProtocol.VOICE_MAX_RED_DELTA) continue
            out.add(VoiceRedEntry(delta, data))
            if (out.size >= want) break
        }
        return out
    }

    private fun recordRecent(curSeq: Long, data: ByteArray) {
        recent.addLast(curSeq to data)
        while (recent.size > 4) recent.removeFirst()
    }
}

/** Receiver-side raw primary packet loss, burst length, and P95/EWMA jitter estimator. */
class VoiceRxStats {
    private var maxSeq: Long? = null
    private val windowEvents = ArrayDeque<Pair<Long, Boolean>>()
    private val burstEvents = ArrayDeque<Pair<Long, Int>>()
    private var currentLossStreak = 0
    private val seenSeqs = ArrayDeque<Long>()
    private val arrivalAt = ArrayDeque<Long>()
    private val arrivalOffset = ArrayDeque<Long>()
    private var ewmaJitterMs = 0.0
    private var lastTransitMs: Long? = null

    /** Resets sequence cursor after `SPK_CLEAR` barge-in so skipped turn sequences are not counted as loss. */
    fun resetSeqCursor() {
        maxSeq = null
        currentLossStreak = 0
        resetTiming()
    }

    /** Resets jitter arrival baseline when a stream idles between utterances so pauses aren't measured as jitter. */
    fun resetTiming() {
        lastTransitMs = null
        arrivalAt.clear()
        arrivalOffset.clear()
    }

    fun onPrimary(seq: Long, nowMs: Long, resetDiscontinuity: Boolean = false) {
        if (seenSeqs.contains(seq)) return
        seenSeqs.addLast(seq)
        while (seenSeqs.size > 256) seenSeqs.removeFirst()

        val transit = nowMs - seq * VoiceOpusMath.FRAME_MS
        if (resetDiscontinuity) {
            lastTransitMs = transit
        } else {
            lastTransitMs?.let { prev ->
                val d = abs(transit - prev).toDouble()
                ewmaJitterMs += (d - ewmaJitterMs) / 16.0
            }
            lastTransitMs = transit
            arrivalOffset.lastOrNull()?.let { lastO ->
                if (abs(transit - lastO) > 2_000L) {
                    arrivalAt.clear()
                    arrivalOffset.clear()
                }
            }
            arrivalAt.addLast(nowMs)
            arrivalOffset.addLast(transit)
            while (arrivalAt.size > VoiceOpusMath.JITTER_WINDOW) {
                arrivalAt.removeFirst()
                arrivalOffset.removeFirst()
            }
        }

        val prevMax = maxSeq
        if (prevMax == null || resetDiscontinuity) {
            maxSeq = seq
            windowEvents.addLast(nowMs to true)
            currentLossStreak = 0
        } else {
            val diff = ((seq - prevMax).toInt())
            when {
                diff in 1..200 -> {
                    val missing = diff - 1
                    repeat(missing) {
                        windowEvents.addLast(nowMs to false)
                        currentLossStreak = (currentLossStreak + 1).coerceAtMost(255)
                    }
                    if (currentLossStreak > 0) {
                        burstEvents.addLast(nowMs to currentLossStreak)
                        currentLossStreak = 0
                    }
                    windowEvents.addLast(nowMs to true)
                    maxSeq = seq
                }
                diff > 200 -> {
                    maxSeq = seq
                    windowEvents.clear()
                    burstEvents.clear()
                    windowEvents.addLast(nowMs to true)
                    currentLossStreak = 0
                }
                diff in -64..-1 -> {
                    for (i in windowEvents.size - 1 downTo 0) {
                        if (!windowEvents[i].second) {
                            windowEvents[i] = windowEvents[i].first to true
                            break
                        }
                    }
                }
            }
        }
        prune(nowMs)
    }

    private fun prune(nowMs: Long) {
        while (windowEvents.isNotEmpty() && nowMs - windowEvents.first().first > VoiceOpusMath.STATS_WINDOW_MS) {
            windowEvents.removeFirst()
        }
        while (burstEvents.isNotEmpty() && nowMs - burstEvents.first().first > VoiceOpusMath.STATS_WINDOW_MS * 2) {
            burstEvents.removeFirst()
        }
    }

    fun p95JitterMs(): Double {
        val count = arrivalAt.size
        if (count < 4) return ewmaJitterMs
        var meanT = 0.0
        var meanO = 0.0
        for (i in 0 until count) {
            meanT += arrivalAt[i]
            meanO += arrivalOffset[i]
        }
        meanT /= count
        meanO /= count
        var num = 0.0
        var den = 0.0
        for (i in 0 until count) {
            val dt = arrivalAt[i] - meanT
            num += dt * (arrivalOffset[i] - meanO)
            den += dt * dt
        }
        val slope = if (den > 0.0) num / den else 0.0
        val residuals = DoubleArray(count) { i -> arrivalOffset[i] - slope * (arrivalAt[i] - meanT) }
        val minR = residuals.min()
        for (i in residuals.indices) residuals[i] -= minR
        residuals.sort()
        return residuals[minOf(count - 1, count * 95 / 100)]
    }

    fun snapshot(streamId: Long, bufferMs: Int, nowMs: Long): VoiceStats {
        prune(nowMs)
        val total = windowEvents.size
        val lost = windowEvents.count { !it.second }
        val lossPermille = if (total == 0) 0 else ((lost * 1000) / total).coerceIn(0, 1000)
        val maxBurst = burstEvents.maxOfOrNull { it.second } ?: 0
        val jitter = maxOf(p95JitterMs(), ewmaJitterMs).roundToInt().coerceIn(0, 2000)
        return VoiceStats(streamId, lossPermille, jitter, bufferMs, maxBurst, 0)
    }
}

/**
 * Adaptive Speech Jitter Buffer:
 * - Sequence reordering + temporal RED recovery + Opus LBRR (in-band FEC) + native Opus PLC
 * - NetEQ-style adaptive buffer expansion on loss/jitter & silence-compression/WSOLA convergence
 * - `turnGen` defense against late pre-barge-in UDP packets after `SPK_CLEAR`
 */
class VoiceJitterBuffer(
    val streamId: Long,
    val sampleRate: Int,
    private val maxSpanAboveTarget: Int = 50,
) {
    private class Slot(val data: ByteArray, val flags: Int)

    private val decoder = VoiceOpusDecoderWrapper(sampleRate)
    private val slots = TreeMap<Long, Slot>()
    private var playoutSeq: Long? = null
    private var highestSeq = 0L
    private var started = false
    private var firstArrivalMs: Long? = null
    private var turnGen = 0
    private var turnGenLocked = false
    private var pendingClearSkips = 0
    private var lastImplicitAdvanceMs = 0L
    private var targetFrames = 1
    private var underrunPenaltyMs = 0.0
    private var lastDecayMs = 0L
    private var consecutivePlc = 0
    private var expandCredits = 0
    private var inDtx = false
    private var lastTail = ShortArray(0)
    private val rxStats = VoiceRxStats()

    companion object {
        private const val LATE_CLEAR_WINDOW_MS = 5_000L
    }

    var plcFrames: Long = 0L
        private set
    var redRecovered: Long = 0L
        private set
    var lbrrRecovered: Long = 0L
        private set

    fun clear(nextTurnGen: Int? = null, resetPendingSkips: Boolean = true) {
        slots.clear()
        playoutSeq = null
        started = false
        firstArrivalMs = null
        consecutivePlc = 0
        expandCredits = 0
        inDtx = false
        lastTail = ShortArray(0)
        decoder.reset()
        rxStats.resetSeqCursor()
        if (resetPendingSkips) {
            pendingClearSkips = 0
            lastImplicitAdvanceMs = 0L
        }
        if (nextTurnGen != null) {
            turnGen = nextTurnGen and 0x0F
            turnGenLocked = true
        }
    }

    /**
     * Called on explicit `SPK_CLEAR` to advance expected turn generation and reject stale in-flight UDP packets.
     * Returns `false` when a newer-turn UDP packet already implicitly advanced `turnGen` ahead of this late `SPK_CLEAR`.
     */
    fun clearForBargeIn(nowMs: Long = 0L): Boolean {
        if (pendingClearSkips > 0) {
            val withinWindow = nowMs <= 0L || lastImplicitAdvanceMs <= 0L || (nowMs - lastImplicitAdvanceMs) <= LATE_CLEAR_WINDOW_MS
            if (withinWindow) {
                pendingClearSkips--
                return false
            }
            pendingClearSkips = 0
        }
        val nextGen = if (turnGenLocked) (turnGen + 1) and 0x0F else null
        clear(nextGen)
        return true
    }

    fun push(pkt: VoiceOpusPacket, nowMs: Long) {
        val pktGen = VoiceProtocol.turnGenOf(pkt.flags)
        if (turnGenLocked && pktGen != turnGen) {
            val ahead = (pktGen - turnGen) and 0x0F
            if (ahead == 0 || ahead >= 8) return
            pendingClearSkips = (pendingClearSkips + ahead).coerceAtMost(7)
            lastImplicitAdvanceMs = nowMs
            clear(pktGen, resetPendingSkips = false)
        } else if (!turnGenLocked) {
            turnGen = pktGen
            turnGenLocked = true
        }

        val isDtxPkt = (pkt.flags and VoiceProtocol.VOICE_FLAG_DTX) != 0
        val isResetPkt = (pkt.flags and VoiceProtocol.VOICE_FLAG_RESET) != 0
        rxStats.onPrimary(pkt.seq, nowMs, resetDiscontinuity = isDtxPkt || isResetPkt || inDtx)
        if (firstArrivalMs == null) firstArrivalMs = nowMs

        // Self-heal if playoutSeq drifted ahead during DTX or sustained underrun while empty.
        val curPlayout = playoutSeq
        if (slots.isEmpty() && curPlayout != null) {
            val behind = (pkt.seq - curPlayout).toInt()
            if ((inDtx && !isDtxPkt) || (behind < 0 && behind >= -64 && (inDtx || consecutivePlc >= 2))) {
                playoutSeq = pkt.seq
                highestSeq = pkt.seq
                inDtx = false
                consecutivePlc = 0
            }
        }

        fun acceptSeq(seq: Long): Boolean {
            val p = playoutSeq ?: return true
            return ((seq - p).toInt()) >= 0
        }

        if (acceptSeq(pkt.seq)) {
            slots.putIfAbsent(pkt.seq, Slot(pkt.primary, pkt.flags))
            if (playoutSeq == null) {
                playoutSeq = pkt.seq
                highestSeq = pkt.seq
            } else if (((pkt.seq - highestSeq).toInt()) > 0) {
                highestSeq = pkt.seq
            }
        }

        for (red in pkt.red) {
            val rSeq = (pkt.seq - red.deltaSeq.toLong()) and 0xFFFFFFFFL
            if (acceptSeq(rSeq) && !slots.containsKey(rSeq)) {
                slots[rSeq] = Slot(red.data, VoiceProtocol.VOICE_FLAG_FEC or VoiceProtocol.VOICE_FLAG_SPEECH)
                redRecovered++
                if (playoutSeq == null) playoutSeq = rSeq
            }
        }

        while (slots.size > 64) {
            slots.pollFirstEntry()
        }
        updateTarget(nowMs)
    }

    private fun updateTarget(nowMs: Long) {
        if (lastDecayMs > 0L && nowMs > lastDecayMs) {
            val elapsedS = (nowMs - lastDecayMs) / 1000.0
            underrunPenaltyMs = (underrunPenaltyMs - elapsedS * 2.0).coerceAtLeast(0.0)
        }
        lastDecayMs = nowMs

        val stats = rxStats.snapshot(streamId, bufferMs(), nowMs)
        val p95 = rxStats.p95JitterMs()
        val lossFloorMs = when {
            stats.lossPermille >= 150 || stats.maxBurstLoss >= 3 -> 60.0
            stats.lossPermille >= 20 || stats.maxBurstLoss >= 1 -> 40.0
            else -> 20.0
        }
        val prevTarget = targetFrames
        val desiredMs = (p95 + 15.0 + underrunPenaltyMs).coerceIn(lossFloorMs, 180.0)
        targetFrames = ceil(desiredMs / VoiceOpusMath.FRAME_MS).toInt().coerceIn(1, 9)
        if (targetFrames > prevTarget) {
            expandCredits = (expandCredits + (targetFrames - prevTarget)).coerceAtMost(4)
        }
    }

    fun queuedFrames(): Int {
        if (slots.isEmpty()) return 0
        val p = playoutSeq ?: return 0
        val span = (highestSeq - p).toInt()
        return if (span < 0) 0 else minOf(span + 1, slots.size + 4)
    }

    fun bufferMs(): Int = queuedFrames() * VoiceOpusMath.FRAME_MS

    fun statsSnapshot(nowMs: Long): VoiceStats = rxStats.snapshot(streamId, bufferMs(), nowMs)

    /**
     * Pulls the next playout PCM frame (normally 20 ms, or WSOLA-compressed ~17 ms during backlog convergence).
     * Returns `null` while still waiting for initial pre-roll or if no audio has arrived yet.
     */
    fun popFrame(nowMs: Long): ShortArray? {
        val p = playoutSeq ?: return null
        if (!started) {
            val waited = firstArrivalMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
            val ready = slots.size >= targetFrames || waited >= 100L
            if (!ready) return null
            started = true
        }

        // Fast-forward only extreme backlogs (> target + maxSpanAboveTarget) with 5 ms crossfade.
        var jumped = false
        val span = (highestSeq - p).toInt()
        val maxSpan = targetFrames + maxSpanAboveTarget
        val curSeq = if (span > maxSpan) {
            val jumpTo = (highestSeq - targetFrames.toLong()) and 0xFFFFFFFFL
            val it = slots.keys.iterator()
            while (it.hasNext()) {
                if (((it.next() - jumpTo).toInt()) < 0) it.remove()
            }
            playoutSeq = jumpTo
            jumped = true
            jumpTo
        } else {
            p
        }

        val frameLen = decoder.frameLen
        var pcm: ShortArray
        var isSilence = false
        var advanceSeq = true

        val entry = slots.remove(curSeq)
        val nextSeq = (curSeq + 1L) and 0xFFFFFFFFL
        val nextEntry = if (entry == null) slots[nextSeq] else null

        if (entry != null) {
            consecutivePlc = 0
            if ((entry.flags and VoiceProtocol.VOICE_FLAG_DTX) != 0 && entry.data.isEmpty()) {
                inDtx = true
                pcm = ShortArray(frameLen)
                isSilence = true
            } else {
                inDtx = false
                pcm = runCatching { decoder.decode(entry.data, decodeFec = false) }.getOrElse { ShortArray(frameLen) }
                isSilence = VoiceOpusMath.pcmRms(pcm) < VoiceOpusMath.SILENCE_RMS_THRESHOLD
            }
        } else if (nextEntry != null && (nextEntry.flags and VoiceProtocol.VOICE_FLAG_DTX) == 0 && nextEntry.data.isNotEmpty()) {
            // Recover missing frame via next frame's embedded Opus LBRR (in-band FEC).
            consecutivePlc = 0
            inDtx = false
            pcm = runCatching {
                decoder.decode(nextEntry.data, decodeFec = true).also { lbrrRecovered++ }
            }.getOrElse {
                plcFrames++
                runCatching { decoder.decodePlc() }.getOrElse { ShortArray(frameLen) }
            }
        } else if (nextEntry != null && (nextEntry.flags and VoiceProtocol.VOICE_FLAG_DTX) != 0) {
            inDtx = true
            pcm = ShortArray(frameLen)
            isSilence = true
        } else if (inDtx) {
            pcm = ShortArray(frameLen)
            isSilence = true
            // Never let playoutSeq drift ahead of the sender during DTX silence.
            if (slots.isEmpty()) advanceSeq = false
        } else {
            consecutivePlc++
            if (consecutivePlc == 1) {
                underrunPenaltyMs = (underrunPenaltyMs + 20.0).coerceAtMost(120.0)
                updateTarget(nowMs)
            }
            if (consecutivePlc <= VoiceOpusMath.MAX_CONSECUTIVE_PLC) {
                plcFrames++
                pcm = runCatching { decoder.decodePlc() }.getOrElse { ShortArray(frameLen) }
                if (consecutivePlc >= 3) {
                    val gain = (1f - (consecutivePlc - 2) * 0.28f).coerceAtLeast(0f)
                    for (i in pcm.indices) pcm[i] = (pcm[i] * gain).toInt().toShort()
                }
                // NetEQ expand: while no newer frame has arrived, hold playoutSeq at curSeq so
                // clock drift or delayed RED packets are never rejected as late.
                if (slots.isEmpty()) {
                    advanceSeq = false
                    if (expandCredits > 0) expandCredits--
                }
            } else {
                // Past 100 ms silence without packets: clean idle between utterances so next talkspurt starts fast.
                if (slots.isEmpty()) {
                    playoutSeq = null
                    started = false
                    firstArrivalMs = null
                    consecutivePlc = 0
                    underrunPenaltyMs = 0.0
                    rxStats.resetTiming()
                    return null
                }
                pcm = ShortArray(frameLen)
                isSilence = true
            }
        }

        if (advanceSeq) {
            playoutSeq = nextSeq
        }

        val excess = ((highestSeq - curSeq).toInt()) - targetFrames
        if (excess >= 3) {
            if (isSilence && slots.isNotEmpty()) {
                return popFrame(nowMs)
            } else if (!isSilence) {
                val targetSamples = (pcm.size * 90) / 100
                pcm = VoiceOpusMath.wsolaCompress(pcm, targetSamples)
            }
        }

        if (jumped && lastTail.isNotEmpty()) {
            VoiceOpusMath.crossfadeHead(lastTail, pcm)
        }
        val tailLen = minOf(sampleRate * 5 / 1000, pcm.size)
        lastTail = if (tailLen > 0) pcm.copyOfRange(pcm.size - tailLen, pcm.size) else ShortArray(0)
        return pcm
    }
}
