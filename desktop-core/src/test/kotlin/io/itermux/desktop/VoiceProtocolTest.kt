package io.itermux.desktop

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceProtocolTest {
    @Test fun cliArgsBuildAndSanitize() {
        assertEquals("--proto 1 --port 24713", VoiceProtocol.voiceArgs())
        assertEquals(
            "--proto 1 --port 24713 --resume abc123 --token AbC+12/3= --idle-timeout 300",
            VoiceProtocol.voiceArgs(
                proto = 1,
                port = 24713,
                resumeSession = "abc123;\$()'\" ",
                resumeToken = "AbC+12/3='\"",
                idleTimeoutSec = 300,
            ),
        )
        assertEquals("--proto 1 --idle-timeout 0", VoiceProtocol.voiceArgs(port = null, idleTimeoutSec = 0))
    }

    @Test fun helloJsonParsesWithAndWithoutUdp() {
        val line = """{"proto":1,"ver":"0.9.7","os":"macos","mode":"voice","mic_port":24713,"session":"deadbeef","resume_token":"AAAAAAAAAAAAAAAAAAAAAA==","udp":{"port":60123,"key":"AAAAAAAAAAAAAAAAAAAAAA=="},"codecs":["pcm16"]}"""
        val ready = VoiceProtocol.parseStart(line) as VoiceStart.Ready
        assertEquals(1, ready.hello.proto)
        assertEquals("0.9.7", ready.hello.version)
        assertEquals("macos", ready.hello.os)
        assertEquals("voice", ready.hello.mode)
        assertEquals(24713, ready.hello.micPort)
        assertEquals("deadbeef", ready.hello.session)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA==", ready.hello.resumeToken)
        assertEquals(60123, ready.hello.udp?.port)
        assertEquals(16, ready.hello.udp?.key?.size)
        assertEquals(listOf("pcm16"), ready.hello.codecs)

        val sshOnly = """{"proto":1,"ver":"0.9.7","os":"linux","mode":"voice","mic_port":41234,"session":"s2","resume_token":"tok","codecs":["pcm16"]}"""
        val readySsh = VoiceProtocol.parseStart(sshOnly) as VoiceStart.Ready
        assertNull(readySsh.hello.udp)
        assertEquals(41234, readySsh.hello.micPort)
    }

    @Test fun startErrorsAndBadHelloFailCleanly() {
        assertEquals(
            VoiceStart.Failed("proto_unsupported", null, 2, 3),
            VoiceProtocol.parseStart("""{"error":"proto_unsupported","min":2,"max":3}"""),
        )
        assertEquals(
            VoiceStart.Failed("bind_failed", "address in use"),
            VoiceProtocol.parseStart("""{"error":"bind_failed","detail":"address in use"}"""),
        )
        assertEquals("internal", (VoiceProtocol.parseStart("not json") as VoiceStart.Failed).code)
        // Invalid mic_port and malformed Base64 udp.key must fail cleanly without throwing IllegalArgumentException.
        assertEquals(
            "internal",
            (VoiceProtocol.parseStart("""{"proto":1,"ver":"0.9.9","os":"linux","mode":"voice","mic_port":0,"session":"s","resume_token":"t"}""") as VoiceStart.Failed).code,
        )
        assertEquals(
            "internal",
            (VoiceProtocol.parseStart("""{"proto":1,"ver":"0.9.9","os":"linux","mode":"voice","mic_port":70000,"session":"s","resume_token":"t"}""") as VoiceStart.Failed).code,
        )
        assertEquals(
            "internal",
            (VoiceProtocol.parseStart("""{"proto":1,"ver":"0.9.9","os":"linux","mode":"voice","mic_port":24713,"session":"s","resume_token":"t","udp":{"port":60000,"key":"!!!bad-base64!!!"}}""") as VoiceStart.Failed).code,
        )
        val badUdpPort = VoiceProtocol.parseStart("""{"proto":1,"ver":"0.9.9","os":"linux","mode":"voice","mic_port":24713,"session":"s","resume_token":"t","udp":{"port":0,"key":"AAAAAAAAAAAAAAAAAAAAAA=="}}""") as VoiceStart.Ready
        assertNull(badUdpPort.hello.udp)
    }

    @Test fun micAudioEncodesLittleEndianStreamIdAndRawPcm() {
        val pcm = byteArrayOf(0x01, 0x02, 0x7F, 0x80.toByte())
        val raw = VoiceProtocol.micAudio(0x01020304L, pcm)
        val frame = DesktopProtocol.read(raw.inputStream())!!
        assertEquals(VoiceProtocol.MIC_AUDIO, frame.type)
        assertEquals(8, frame.payload.size)
        // stream_id u32 LE = 04 03 02 01
        assertArrayEquals(byteArrayOf(0x04, 0x03, 0x02, 0x01, 0x01, 0x02, 0x7F, 0x80.toByte()), frame.payload)
        assertFalse(VoiceProtocol.isReliable(raw))
        assertFalse(UdpLink.reliable(raw))
    }

    @Test fun spkAckEncodesOkAndSanitizedTruncatedErrors() {
        val okRaw = VoiceProtocol.spkAck(7L, ok = true, error = "ignored")
        val okFrame = DesktopProtocol.read(okRaw.inputStream())!!
        assertEquals(VoiceProtocol.SPK_ACK, okFrame.type)
        assertArrayEquals(byteArrayOf(7, 0, 0, 0, 1), okFrame.payload)
        assertTrue(VoiceProtocol.isReliable(okRaw))
        assertTrue(UdpLink.reliable(okRaw))

        val errRaw = VoiceProtocol.spkAck(42L, ok = false, error = "bad\r\nrate")
        val errFrame = DesktopProtocol.read(errRaw.inputStream())!!
        val errBuf = ByteBuffer.wrap(errFrame.payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(42, errBuf.int)
        assertEquals(0, errBuf.get().toInt())
        val msg = String(errFrame.payload, 5, errFrame.payload.size - 5, Charsets.UTF_8)
        assertEquals("bad  rate", msg)

        // Blank error falls back to default "speaker error"
        val defaultErr = DesktopProtocol.read(VoiceProtocol.spkAck(1L, ok = false, error = "   ").inputStream())!!
        assertEquals("speaker error", String(defaultErr.payload, 5, defaultErr.payload.size - 5, Charsets.UTF_8))

        // Multi-byte UTF-8 error safely truncated to <= 235 bytes without splitting characters
        val longUtf8 = "错误".repeat(100) // 600 UTF-8 bytes
        val longFrame = DesktopProtocol.read(VoiceProtocol.spkAck(9L, ok = false, error = longUtf8).inputStream())!!
        val msgBytes = longFrame.payload.copyOfRange(5, longFrame.payload.size)
        assertTrue("size=${msgBytes.size}", msgBytes.size <= VoiceProtocol.SPK_ERR_MAX_BYTES)
        assertEquals(0, msgBytes.size % 3)
        assertFalse(String(msgBytes, Charsets.UTF_8).contains('\uFFFD'))
    }

    @Test fun serverVoiceFramesDecodeExactLittleEndianLayouts() {
        fun le(size: Int, fill: ByteBuffer.() -> Unit): ByteArray =
            ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

        val micStart = VoiceProtocol.decode(Frame(VoiceProtocol.MIC_START, le(9) {
            putInt(0x11223344); putInt(16_000); put(1)
        })) as VoiceServerMessage.MicStart
        assertEquals(VoiceServerMessage.MicStart(0x11223344L, 16_000, 1), micStart)

        val micStop = VoiceProtocol.decode(Frame(VoiceProtocol.MIC_STOP, le(4) { putInt(5) }))
        assertEquals(VoiceServerMessage.MicStop(5L), micStop)

        val spkStart = VoiceProtocol.decode(Frame(VoiceProtocol.SPK_START, le(9) {
            putInt(99); putInt(24_000); put(1)
        })) as VoiceServerMessage.SpkStart
        assertEquals(VoiceServerMessage.SpkStart(99L, 24_000, 1), spkStart)

        val spkAudio = VoiceProtocol.decode(Frame(VoiceProtocol.SPK_AUDIO, le(8) {
            putInt(99); put(0x10); put(0x20); put(0x30); put(0x40)
        })) as VoiceServerMessage.SpkAudio
        assertEquals(99L, spkAudio.streamId)
        assertEquals(4, spkAudio.offset)
        assertEquals(4, spkAudio.length)
        assertArrayEquals(byteArrayOf(0x10, 0x20, 0x30, 0x40), spkAudio.data.copyOfRange(spkAudio.offset, spkAudio.offset + spkAudio.length))

        assertEquals(VoiceServerMessage.SpkClear(99L), VoiceProtocol.decode(Frame(VoiceProtocol.SPK_CLEAR, le(4) { putInt(99) })))
        assertEquals(VoiceServerMessage.SpkStop(99L), VoiceProtocol.decode(Frame(VoiceProtocol.SPK_STOP, le(4) { putInt(99) })))

        val state = VoiceProtocol.decode(Frame(DesktopProtocol.STATE, """{"kicked":true}""".toByteArray())) as VoiceServerMessage.State
        assertTrue(state.kicked)

        val ping = VoiceProtocol.decode(DesktopProtocol.read(VoiceProtocol.ping(123456L).inputStream())!!)
        assertEquals(VoiceServerMessage.Ping(123456L), ping)

        val pong = VoiceProtocol.decode(DesktopProtocol.read(VoiceProtocol.pong(1L, 2L, 3L).inputStream())!!)
        assertEquals(VoiceServerMessage.Pong(1L, 2L, 3L), pong)

        // Truncated & unknown frames return null safely
        assertNull(VoiceProtocol.decode(Frame(VoiceProtocol.MIC_START, byteArrayOf(1, 2, 3))))
        assertNull(VoiceProtocol.decode(Frame(VoiceProtocol.SPK_CLEAR, byteArrayOf(1, 2))))
        assertNull(VoiceProtocol.decode(Frame(0xFE, byteArrayOf(1, 2, 3, 4))))
    }

    @Test fun liveVoiceStateMachineHandlesBargeInMuteAndRouteCycling() {
        var s = VoiceUiState()
        assertFalse(s.inCall)

        s = VoiceStateMachine.reduce(s, VoiceEvent.Start(VoiceMode.LIVE, AudioRoute.SPEAKER, bluetoothAvailable = true))
        assertTrue(s.inCall)
        assertTrue(s.isConnecting)
        // Timer does not advance while connecting
        s = VoiceStateMachine.reduce(s, VoiceEvent.TickSecond)
        assertEquals(0, s.elapsedSeconds)

        s = VoiceStateMachine.reduce(s, VoiceEvent.Ready(micPort = 24713, link = "UDP"))
        assertEquals(VoiceCallPhase.Listening, s.phase)
        assertEquals(24713, s.micPort)
        assertEquals("UDP", s.link)

        s = VoiceStateMachine.reduce(s, VoiceEvent.TickSecond)
        assertEquals(1, s.elapsedSeconds)
        assertEquals("00:01", VoiceStateMachine.formatDuration(s.elapsedSeconds))

        // Concurrent mic + speaker in Live mode
        s = VoiceStateMachine.reduce(s, VoiceEvent.MicStarted(1L))
        s = VoiceStateMachine.reduce(s, VoiceEvent.SpkStarted(2L))
        s = VoiceStateMachine.reduce(s, VoiceEvent.SpkAudio(2L, 0.7f))
        assertEquals(VoiceCallPhase.Speaking, s.phase)
        assertEquals(0.7f, s.speakerLevel, 0.001f)

        // Stale stream IDs are ignored
        s = VoiceStateMachine.reduce(s, VoiceEvent.SpkCleared(999L))
        assertEquals(VoiceCallPhase.Speaking, s.phase)

        // Barge-in SPK_CLEAR immediately returns to Listening with 0 speaker level
        s = VoiceStateMachine.reduce(s, VoiceEvent.SpkCleared(2L))
        assertEquals(VoiceCallPhase.Listening, s.phase)
        assertEquals(0f, s.speakerLevel, 0.001f)

        // Mute / Unmute
        s = VoiceStateMachine.reduce(s, VoiceEvent.MicLevel(0.6f))
        assertEquals(0.6f, s.micLevel, 0.001f)
        s = VoiceStateMachine.reduce(s, VoiceEvent.ToggleMute)
        assertEquals(VoiceCallPhase.Muted, s.phase)
        assertEquals(0f, s.micLevel, 0.001f)
        s = VoiceStateMachine.reduce(s, VoiceEvent.ToggleMute)
        assertEquals(VoiceCallPhase.Listening, s.phase)

        // Audio route cycle: SPEAKER -> EARPIECE -> BLUETOOTH -> SPEAKER
        s = VoiceStateMachine.reduce(s, VoiceEvent.CycleRoute)
        assertEquals(AudioRoute.EARPIECE, s.audioRoute)
        s = VoiceStateMachine.reduce(s, VoiceEvent.CycleRoute)
        assertEquals(AudioRoute.BLUETOOTH, s.audioRoute)
        s = VoiceStateMachine.reduce(s, VoiceEvent.BluetoothChanged(false))
        assertEquals(AudioRoute.SPEAKER, s.audioRoute)
        // Without BT, cycle skips Bluetooth
        s = VoiceStateMachine.reduce(s, VoiceEvent.CycleRoute)
        assertEquals(AudioRoute.EARPIECE, s.audioRoute)
        s = VoiceStateMachine.reduce(s, VoiceEvent.CycleRoute)
        assertEquals(AudioRoute.SPEAKER, s.audioRoute)

        // Hangup restores Idle cleanly
        s = VoiceStateMachine.reduce(s, VoiceEvent.Hangup)
        assertEquals(VoiceCallPhase.Idle, s.phase)
        assertFalse(s.inCall)
        assertNull(s.micPort)
    }

    @Test fun dictateModeAutomaticallyEndsOnMicStop() {
        var s = VoiceStateMachine.reduce(VoiceUiState(), VoiceEvent.Start(VoiceMode.DICTATE, AudioRoute.SPEAKER))
        s = VoiceStateMachine.reduce(s, VoiceEvent.Ready(24713, "UDP"))
        s = VoiceStateMachine.reduce(s, VoiceEvent.MicStarted(10L))
        assertEquals(VoiceCallPhase.Listening, s.phase)
        // Unrelated stream stop ignored
        s = VoiceStateMachine.reduce(s, VoiceEvent.MicStopped(99L))
        assertTrue(s.inCall)
        // Active mic stream stop ends dictation so pet swaps handset back to keyboard
        s = VoiceStateMachine.reduce(s, VoiceEvent.MicStopped(10L))
        assertEquals(VoiceCallPhase.Idle, s.phase)
        assertFalse(s.inCall)
    }

    @Test fun resumingClearsStreamsAndPausesTimerAndGatesToggleMute() {
        var s = VoiceStateMachine.reduce(VoiceUiState(), VoiceEvent.Start(VoiceMode.LIVE, AudioRoute.SPEAKER))
        // ToggleMute is ignored while Connecting
        s = VoiceStateMachine.reduce(s, VoiceEvent.ToggleMute)
        assertTrue(s.isConnecting)
        assertFalse(s.isMuted)

        s = VoiceStateMachine.reduce(s, VoiceEvent.Ready(24713, "UDP"))
        s = VoiceStateMachine.reduce(s, VoiceEvent.MicStarted(1L))
        s = VoiceStateMachine.reduce(s, VoiceEvent.SpkStarted(2L))
        s = VoiceStateMachine.reduce(s, VoiceEvent.SpeakerLevel(0.8f))
        s = VoiceStateMachine.reduce(s, VoiceEvent.TickSecond)
        assertEquals(VoiceCallPhase.Speaking, s.phase)
        assertEquals(1, s.elapsedSeconds)

        // Resuming clears stale stream IDs/levels and pauses duration timer
        s = VoiceStateMachine.reduce(s, VoiceEvent.Resuming(true))
        assertTrue(s.resuming)
        assertNull(s.activeMicStreamId)
        assertNull(s.activeSpkStreamId)
        assertEquals(0f, s.micLevel, 0.001f)
        assertEquals(0f, s.speakerLevel, 0.001f)
        assertEquals(VoiceCallPhase.Listening, s.phase)
        s = VoiceStateMachine.reduce(s, VoiceEvent.TickSecond)
        assertEquals(1, s.elapsedSeconds)

        // ToggleMute is ignored in DICTATE mode even when Listening
        var dictate = VoiceStateMachine.reduce(VoiceUiState(), VoiceEvent.Start(VoiceMode.DICTATE, AudioRoute.SPEAKER))
        dictate = VoiceStateMachine.reduce(dictate, VoiceEvent.Ready(24713, "SSH"))
        dictate = VoiceStateMachine.reduce(dictate, VoiceEvent.ToggleMute)
        assertEquals(VoiceCallPhase.Listening, dictate.phase)
        assertFalse(dictate.isMuted)
    }

    @Test fun audioMathComputesRmsAndSampleAlignedChunks() {
        assertEquals(0f, VoiceAudioMath.rmsLevel(ByteArray(1280)), 0.0001f)
        val loud = ByteArray(1280)
        val bb = ByteBuffer.wrap(loud).order(ByteOrder.LITTLE_ENDIAN)
        while (bb.hasRemaining()) bb.putShort(12_000)
        assertTrue(VoiceAudioMath.rmsLevel(loud) > 0.5f)

        val chunks = VoiceAudioMath.sliceSampleAligned(0, 1280, 640)
        assertEquals(listOf(0 to 640, 640 to 640), chunks)
        // Odd byte length drops trailing partial sample
        assertEquals(listOf(0 to 6), VoiceAudioMath.sliceSampleAligned(0, 7, 640))
    }
}
