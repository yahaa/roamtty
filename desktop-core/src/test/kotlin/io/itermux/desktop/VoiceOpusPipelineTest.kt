package io.itermux.desktop

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoiceOpusPipelineTest {
    private fun sineFrame(rate: Int, freqHz: Double, frameIdx: Int): ShortArray {
        val n = VoiceOpusMath.frameSamples(rate)
        val start = frameIdx * n
        return ShortArray(n) { i ->
            val t = (start + i).toDouble() / rate.toDouble()
            (sin(t * freqHz * 2.0 * PI) * 14000.0).toInt().toShort()
        }
    }

    @Test fun voiceOpusAndStatsFramesRoundTripWireFormat() {
        val flags = VoiceProtocol.withTurnGen(
            VoiceProtocol.VOICE_FLAG_FEC or VoiceProtocol.VOICE_FLAG_SPEECH or VoiceProtocol.VOICE_FLAG_RESET,
            11,
        )
        assertEquals(11, VoiceProtocol.turnGenOf(flags))
        val pkt = VoiceOpusPacket(
            streamId = 42L,
            seq = 123L,
            tsSamples = 123L * 320L,
            flags = flags,
            red = listOf(
                VoiceRedEntry(1, byteArrayOf(0x11, 0x22, 0x33)),
                VoiceRedEntry(2, byteArrayOf(0x44, 0x55)),
            ),
            primary = byteArrayOf(0x01, 0x02, 0x03, 0x04),
        )
        val raw = VoiceProtocol.micOpus(pkt)
        assertFalse(VoiceProtocol.isReliable(raw))
        assertFalse(UdpLink.reliable(raw))
        val frame = DesktopProtocol.read(raw.inputStream())!!
        assertEquals(VoiceProtocol.MIC_OPUS, frame.type)
        assertEquals(pkt, VoiceProtocol.decodeVoiceOpus(frame.payload))

        // Server SPK_OPUS uses exact same payload layout
        val decodedSpk = VoiceProtocol.decode(Frame(VoiceProtocol.SPK_OPUS, frame.payload)) as VoiceServerMessage.SpkOpus
        assertEquals(pkt, decodedSpk.packet)

        // VoiceStats roundtrip
        val st = VoiceStats(streamId = 42L, lossPermille = 175, jitterMs = 38, bufferMs = 60, maxBurstLoss = 3, flags = 1)
        val rawSt = VoiceProtocol.voiceStats(st)
        assertFalse(UdpLink.reliable(rawSt))
        val stFrame = DesktopProtocol.read(rawSt.inputStream())!!
        assertEquals(VoiceProtocol.VOICE_STATS_C2S, stFrame.type)
        assertEquals(12, stFrame.payload.size)
        val decodedSt = VoiceProtocol.decode(Frame(VoiceProtocol.VOICE_STATS_S2C, stFrame.payload)) as VoiceServerMessage.VoiceStatsMsg
        assertEquals(st, decodedSt.stats)
    }

    @Test fun opusRedAndJitterBufferSurvive30PercentLossAnd60msBursts() {
        val rate = 24_000
        val sender = VoiceRedSender(streamId = 7L, sampleRate = rate, enableDtx = false)
        // Arm weak-network RED-2/3 via peer stats report
        sender.onPeerStats(VoiceStats(7L, lossPermille = 180, jitterMs = 55, bufferMs = 80, maxBurstLoss = 3), 1000L)
        assertTrue("expected RED >= 2 under 18% loss", sender.controller.profile().redCount >= 2)

        val jb = VoiceJitterBuffer(streamId = 7L, sampleRate = rate)
        val pkts = (0 until 60).map { seq -> sender.encodeShorts20ms(sineFrame(rate, 440.0, seq))!! }
        // Confirm massive bandwidth compression vs 960 B raw PCM16
        assertTrue("primary opus frame must be tiny (got ${pkts[10].primary.size} B)", pkts[10].primary.size < 120)

        // Drop a 3-packet (60 ms) burst + ~22% periodic loss + reorder seq 24/25
        val dropped = setOf(5L, 10L, 11L, 12L, 18L, 21L, 29L, 33L, 37L, 41L, 45L, 50L, 54L)
        val delivered = pkts
            .filter { it.seq !in dropped }
            .map { pkt -> (pkt.seq * 20L + if (pkt.seq == 24L) 25L else 0L) to pkt }
            .sortedBy { it.first }

        var idx = 0
        var played = 0
        var audible = 0
        for (tick in 0L until 65L) {
            val now = tick * 20L
            while (idx < delivered.size && delivered[idx].first <= now) {
                jb.push(delivered[idx].second, now)
                idx++
            }
            val pcm = jb.popFrame(now)
            if (pcm != null) {
                played++
                if (VoiceOpusMath.pcmRms(pcm) > 0.05f) audible++
            }
        }
        assertTrue("played=$played", played >= 55)
        assertTrue("audible=$audible", audible >= 50)
        assertTrue("RED must recover lost frames (got ${jb.redRecovered})", jb.redRecovered >= 8)
    }

    @Test fun dtxSuppressesSustainedSilenceAndEmitsPeriodicKeepalive() {
        val sender = VoiceRedSender(streamId = 1L, sampleRate = 16_000, enableDtx = true)
        // First 5 frames active speech -> all sent
        val speechSent = (0 until 5).count { sender.encodeShorts20ms(sineFrame(16_000, 350.0, it)) != null }
        assertEquals(5, speechSent)

        // Next 50 frames (1 second) complete silence -> hangover (10) + ~4 DTX keepalives, >70% suppressed!
        val silence = ShortArray(320)
        val silentPkts = (0 until 50).mapNotNull { sender.encodeShorts20ms(silence) }
        assertTrue("expected DTX to suppress >70% of silent frames (sent ${silentPkts.size}/50)", silentPkts.size <= 16)
        assertTrue(silentPkts.any { (it.flags and VoiceProtocol.VOICE_FLAG_DTX) != 0 })
    }

    @Test fun turnGenDropsStalePreBargeInPacketsWithoutFalseLoss() {
        val enc = VoiceOpusEncoderWrapper(24_000, 32_000, dtx = false)
        val frame = enc.encode(sineFrame(24_000, 440.0, 0))
        val jb = VoiceJitterBuffer(streamId = 9L, sampleRate = 24_000)

        jb.push(VoiceOpusPacket(9L, 1L, 480L, VoiceProtocol.withTurnGen(VoiceProtocol.VOICE_FLAG_SPEECH, 1), emptyList(), frame), 0L)
        assertNotNull(jb.popFrame(20L))

        // Barge-in advances turn_gen to 2
        jb.clearForBargeIn()
        // Late gen=1 packet arrives -> MUST be ignored
        jb.push(VoiceOpusPacket(9L, 2L, 960L, VoiceProtocol.withTurnGen(VoiceProtocol.VOICE_FLAG_SPEECH, 1), emptyList(), frame), 40L)
        assertNull("stale pre-barge-in packet must be ignored", jb.popFrame(60L))

        // Fresh gen=2 packet with RESET arrives -> plays immediately without false loss penalty
        jb.push(
            VoiceOpusPacket(
                9L,
                3L,
                1440L,
                VoiceProtocol.withTurnGen(VoiceProtocol.VOICE_FLAG_SPEECH or VoiceProtocol.VOICE_FLAG_RESET, 2),
                emptyList(),
                frame,
            ),
            80L,
        )
        assertNotNull(jb.popFrame(100L))
    }

    @Test fun preprocessorRemovesDcAndSoftLimitsPeaks() {
        val pp = VoiceAudioPreprocessor(16_000, startupMuteMs = 0, fadeInMs = 0)
        val dc = ShortArray(320) { 20_000 } // pure DC offset
        val bytes = VoiceOpusMath.shortsToBytes(dc)
        pp.processInPlace(bytes, 0, bytes.size)
        val filtered = VoiceOpusMath.bytesToShorts(bytes, 0, bytes.size)
        // DC-step-free HPF emits zero for pure constant DC with no startup click
        assertEquals(0, filtered.first().toInt())
        assertEquals(0, filtered.last().toInt())

        // Default startup mute suppresses 180 ms cold-start clicks, then passes speech cleanly after 20 ms fade-in.
        val startupPp = VoiceAudioPreprocessor(16_000)
        val clickBytes = VoiceOpusMath.shortsToBytes(sineFrame(16_000, 400.0, 0))
        startupPp.processInPlace(clickBytes, 0, clickBytes.size)
        val mutedFirst = VoiceOpusMath.bytesToShorts(clickBytes, 0, clickBytes.size)
        assertTrue("first 20 ms must be muted", mutedFirst.all { it == 0.toShort() })
        for (idx in 1 until 10) {
            val b = VoiceOpusMath.shortsToBytes(sineFrame(16_000, 400.0, idx))
            startupPp.processInPlace(b, 0, b.size)
        }
        val speechBytes = VoiceOpusMath.shortsToBytes(sineFrame(16_000, 400.0, 10))
        startupPp.processInPlace(speechBytes, 0, speechBytes.size)
        val speechOut = VoiceOpusMath.bytesToShorts(speechBytes, 0, speechBytes.size)
        assertTrue("speech passes cleanly after 200 ms warmup", VoiceOpusMath.pcmRms(speechOut) > 0.25f)
    }

    @Test fun wsolaCompressesWithoutDestroyingEnergy() {
        val pcm = sineFrame(24_000, 300.0, 0)
        val out = VoiceOpusMath.wsolaCompress(pcm, 420)
        assertTrue("len=${out.size}", out.size in 380..479)
        assertTrue("energy preserved", VoiceOpusMath.pcmRms(out) > 0.2f)
    }

    @Test fun clockDriftPauseAndTtsBurstsNeverDropSpeech() {
        val rate = 24_000
        val sender = VoiceRedSender(streamId = 3L, sampleRate = rate, enableDtx = false)
        val jb = VoiceJitterBuffer(streamId = 3L, sampleRate = rate)

        // 1. 600 ms TTS burst (30 frames arriving at once) must NOT be fast-forward-dropped!
        val burstPkts = (0 until 30).map { sender.encodeShorts20ms(sineFrame(rate, 440.0, it))!! }
        for (pkt in burstPkts) jb.push(pkt, 0L)
        var burstPlayed = 0
        for (tick in 0L until 35L) {
            val pcm = jb.popFrame(tick * 20L)
            if (pcm != null && VoiceOpusMath.pcmRms(pcm) > 0.05f) burstPlayed++
        }
        assertTrue("all 30 frames of 600 ms TTS burst must play intact (got $burstPlayed)", burstPlayed >= 28)

        // 2. 400 ms pause (receiver ticks 20x with no packets) then sender resumes at seq 30.
        for (tick in 35L until 55L) jb.popFrame(tick * 20L)
        var resumedAudible = 0
        for (i in 0 until 15) {
            val now = (55L + i) * 20L
            val pkt = sender.encodeShorts20ms(sineFrame(rate, 440.0, 30 + i))!!
            jb.push(pkt, now)
            val pcm = jb.popFrame(now)
            if (pcm != null && VoiceOpusMath.pcmRms(pcm) > 0.05f) resumedAudible++
        }
        assertTrue("resumed speech after pause must not be dropped (got $resumedAudible/15)", resumedAudible >= 13)
        assertEquals(0, jb.statsSnapshot(70L * 20L).lossPermille)
    }

    @Test fun lateSpkClearAfterNewTurnPacketDoesNotDoubleAdvanceTurnGen() {
        val enc = VoiceOpusEncoderWrapper(24_000, 32_000, dtx = false)
        val frame = enc.encode(sineFrame(24_000, 440.0, 0))
        val jb = VoiceJitterBuffer(streamId = 9L, sampleRate = 24_000)

        jb.push(VoiceOpusPacket(9L, 1L, 480L, VoiceProtocol.withTurnGen(VoiceProtocol.VOICE_FLAG_SPEECH, 1), emptyList(), frame), 100L)
        assertNotNull(jb.popFrame(120L))

        // New-turn gen=2 packet arrives over UDP BEFORE delayed SPK_CLEAR arrives over SSH.
        jb.push(
            VoiceOpusPacket(
                9L,
                2L,
                960L,
                VoiceProtocol.withTurnGen(VoiceProtocol.VOICE_FLAG_SPEECH or VoiceProtocol.VOICE_FLAG_RESET, 2),
                emptyList(),
                frame,
            ),
            140L,
        )
        // Delayed SPK_CLEAR must be absorbed without advancing turnGen to 3 or dropping gen=2 packets.
        assertFalse(jb.clearForBargeIn(160L))
        jb.push(VoiceOpusPacket(9L, 3L, 1440L, VoiceProtocol.withTurnGen(VoiceProtocol.VOICE_FLAG_SPEECH, 2), emptyList(), frame), 180L)
        assertNotNull(jb.popFrame(180L))
        assertNotNull(jb.popFrame(200L))

        // Next genuine barge-in SPK_CLEAR still advances turnGen 2 -> 3 and drops stale gen=2 packets.
        assertTrue(jb.clearForBargeIn(220L))
        jb.push(VoiceOpusPacket(9L, 4L, 1920L, VoiceProtocol.withTurnGen(VoiceProtocol.VOICE_FLAG_SPEECH, 2), emptyList(), frame), 240L)
        assertNull(jb.popFrame(260L))

        // Negotiated 24 kHz mic sender exposes 960 B frame size.
        val sender24 = VoiceRedSender(streamId = 2L, sampleRate = 24_000, enableDtx = false)
        assertEquals(960, sender24.frameBytes)
        val bytes24 = VoiceOpusMath.shortsToBytes(sineFrame(24_000, 440.0, 0))
        assertNotNull(sender24.encodePcm20ms(bytes24, 0, bytes24.size))
    }
}
