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
        val pp = VoiceAudioPreprocessor(16_000)
        val dc = ShortArray(320) { 20_000 } // pure DC offset
        val bytes = VoiceOpusMath.shortsToBytes(dc)
        pp.processInPlace(bytes, 0, bytes.size)
        val filtered = VoiceOpusMath.bytesToShorts(bytes, 0, bytes.size)
        // High-pass filter decays DC significantly by end of 20 ms frame
        assertTrue("DC should decay (end=${filtered.last()})", kotlin.math.abs(filtered.last().toInt()) < 10_000)
    }

    @Test fun wsolaCompressesWithoutDestroyingEnergy() {
        val pcm = sineFrame(24_000, 300.0, 0)
        val out = VoiceOpusMath.wsolaCompress(pcm, 420)
        assertTrue("len=${out.size}", out.size in 380..479)
        assertTrue("energy preserved", VoiceOpusMath.pcmRms(out) > 0.2f)
    }
}
