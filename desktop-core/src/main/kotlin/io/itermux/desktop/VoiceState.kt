package io.itermux.desktop

import kotlin.math.pow
import kotlin.math.sqrt

/** Voice dialogue mode (`agy /voice live` with full-duplex barge-in). */
enum class VoiceMode {
    /** `/voice live` triggered via F6: full-duplex conversation with barge-in. */
    LIVE,
}

/** Phone audio output route for live voice calls. */
enum class AudioRoute {
    SPEAKER,
    EARPIECE,
    BLUETOOTH,
}

enum class VoiceStep { LOGIN, INSTALL, START, HANDSHAKE }

sealed interface VoiceCallPhase {
    data object Idle : VoiceCallPhase
    data class Connecting(val step: VoiceStep = VoiceStep.LOGIN) : VoiceCallPhase
    data object Listening : VoiceCallPhase
    data object Speaking : VoiceCallPhase
    data object Muted : VoiceCallPhase
    data class Failed(val message: String) : VoiceCallPhase
}

/** UI-facing state of an active or idle voice call for one host/session. */
data class VoiceUiState(
    val phase: VoiceCallPhase = VoiceCallPhase.Idle,
    val mode: VoiceMode = VoiceMode.LIVE,
    val audioRoute: AudioRoute = AudioRoute.SPEAKER,
    val bluetoothAvailable: Boolean = false,
    val micLevel: Float = 0f,
    val speakerLevel: Float = 0f,
    val elapsedSeconds: Int = 0,
    val micPort: Int? = null,
    val activeMicStreamId: Long? = null,
    val activeSpkStreamId: Long? = null,
    val link: String = "SSH",
    val resuming: Boolean = false,
    val networkPoor: Boolean = false,
) {
    val inCall: Boolean get() = phase !is VoiceCallPhase.Idle && phase !is VoiceCallPhase.Failed
    val isMuted: Boolean get() = phase is VoiceCallPhase.Muted
    val isSpeaking: Boolean get() = phase is VoiceCallPhase.Speaking
    val isConnecting: Boolean get() = phase is VoiceCallPhase.Connecting
    val networkUnstable: Boolean get() = networkPoor || resuming
}

/** Pure events driving [VoiceStateMachine] transitions. */
sealed interface VoiceEvent {
    data class Start(val mode: VoiceMode = VoiceMode.LIVE, val route: AudioRoute, val bluetoothAvailable: Boolean = false) : VoiceEvent
    data class StepChanged(val step: VoiceStep) : VoiceEvent
    data class Ready(val micPort: Int, val link: String = "SSH") : VoiceEvent
    data class Resuming(val active: Boolean) : VoiceEvent
    data class NetworkQualityChanged(val poor: Boolean) : VoiceEvent
    data class LinkChanged(val link: String) : VoiceEvent
    data class MicStarted(val streamId: Long) : VoiceEvent
    data class MicStopped(val streamId: Long) : VoiceEvent
    data class SpkStarted(val streamId: Long) : VoiceEvent
    data class SpkAudio(val streamId: Long, val level: Float) : VoiceEvent
    data class SpkCleared(val streamId: Long) : VoiceEvent
    data class SpkStopped(val streamId: Long) : VoiceEvent
    data class MicLevel(val level: Float) : VoiceEvent
    data class SpeakerLevel(val level: Float) : VoiceEvent
    data object ToggleMute : VoiceEvent
    data class SetRoute(val route: AudioRoute) : VoiceEvent
    data class BluetoothChanged(val available: Boolean) : VoiceEvent
    data object CycleRoute : VoiceEvent
    data object TickSecond : VoiceEvent
    data object Hangup : VoiceEvent
    data class Fail(val message: String) : VoiceEvent
}

/**
 * Deterministic state reducer for a live voice session: handles full-duplex lifecycles,
 * concurrent mic+speaker streams, barge-in (`SPK_CLEAR`), mute toggles, network stability, and audio route cycling.
 */
object VoiceStateMachine {
    fun reduce(state: VoiceUiState, event: VoiceEvent): VoiceUiState = when (event) {
        is VoiceEvent.Start -> VoiceUiState(
            phase = VoiceCallPhase.Connecting(VoiceStep.LOGIN),
            mode = event.mode,
            audioRoute = event.route,
            bluetoothAvailable = event.bluetoothAvailable,
        )
        is VoiceEvent.StepChanged -> if (state.phase is VoiceCallPhase.Connecting) {
            state.copy(phase = VoiceCallPhase.Connecting(event.step))
        } else state
        is VoiceEvent.Ready -> if (state.inCall) {
            val nextPhase = when (state.phase) {
                is VoiceCallPhase.Connecting -> VoiceCallPhase.Listening
                else -> state.phase
            }
            state.copy(phase = nextPhase, micPort = event.micPort, link = event.link, resuming = false, networkPoor = false)
        } else state
        is VoiceEvent.Resuming -> if (state.inCall) {
            if (event.active) {
                state.copy(
                    resuming = true,
                    activeMicStreamId = null,
                    activeSpkStreamId = null,
                    micLevel = 0f,
                    speakerLevel = 0f,
                    phase = if (state.phase is VoiceCallPhase.Speaking) VoiceCallPhase.Listening else state.phase,
                )
            } else {
                state.copy(resuming = false)
            }
        } else state
        is VoiceEvent.NetworkQualityChanged -> if (state.inCall) state.copy(networkPoor = event.poor) else state
        is VoiceEvent.LinkChanged -> if (state.inCall) state.copy(link = event.link) else state
        is VoiceEvent.MicStarted -> if (state.inCall) {
            val nextPhase = when (state.phase) {
                is VoiceCallPhase.Connecting -> VoiceCallPhase.Listening
                else -> state.phase
            }
            state.copy(phase = nextPhase, activeMicStreamId = event.streamId)
        } else state
        is VoiceEvent.MicStopped -> {
            if (!state.inCall || state.activeMicStreamId != event.streamId) state
            else state.copy(activeMicStreamId = null, micLevel = 0f)
        }
        is VoiceEvent.SpkStarted -> if (state.inCall) {
            state.copy(activeSpkStreamId = event.streamId)
        } else state
        is VoiceEvent.SpkAudio -> {
            if (!state.inCall || (state.activeSpkStreamId != null && state.activeSpkStreamId != event.streamId)) state
            else {
                val level = event.level.coerceIn(0f, 1f)
                val nextPhase = when (state.phase) {
                    is VoiceCallPhase.Muted -> VoiceCallPhase.Muted
                    else -> VoiceCallPhase.Speaking
                }
                state.copy(phase = nextPhase, activeSpkStreamId = event.streamId, speakerLevel = level)
            }
        }
        is VoiceEvent.SpkCleared -> {
            if (!state.inCall || (state.activeSpkStreamId != null && state.activeSpkStreamId != event.streamId)) state
            else {
                val nextPhase = if (state.phase is VoiceCallPhase.Muted) VoiceCallPhase.Muted else VoiceCallPhase.Listening
                state.copy(phase = nextPhase, speakerLevel = 0f)
            }
        }
        is VoiceEvent.SpkStopped -> {
            if (!state.inCall || state.activeSpkStreamId != event.streamId) state
            else {
                val nextPhase = if (state.phase is VoiceCallPhase.Muted) VoiceCallPhase.Muted else VoiceCallPhase.Listening
                state.copy(phase = nextPhase, activeSpkStreamId = null, speakerLevel = 0f)
            }
        }
        is VoiceEvent.MicLevel -> if (state.inCall && !state.isMuted) {
            state.copy(micLevel = event.level.coerceIn(0f, 1f))
        } else state.copy(micLevel = 0f)
        is VoiceEvent.SpeakerLevel -> if (state.inCall) {
            val level = event.level.coerceIn(0f, 1f)
            val nextPhase = when {
                state.phase is VoiceCallPhase.Muted -> VoiceCallPhase.Muted
                level > 0.01f && state.activeSpkStreamId != null -> VoiceCallPhase.Speaking
                state.phase is VoiceCallPhase.Speaking && level <= 0.01f -> VoiceCallPhase.Listening
                else -> state.phase
            }
            state.copy(phase = nextPhase, speakerLevel = level)
        } else state
        VoiceEvent.ToggleMute -> if (!state.inCall || state.phase is VoiceCallPhase.Connecting) state else when (state.phase) {
            is VoiceCallPhase.Muted -> {
                val resumed = if (state.activeSpkStreamId != null && state.speakerLevel > 0.01f) {
                    VoiceCallPhase.Speaking
                } else {
                    VoiceCallPhase.Listening
                }
                state.copy(phase = resumed)
            }
            else -> state.copy(phase = VoiceCallPhase.Muted, micLevel = 0f)
        }
        is VoiceEvent.SetRoute -> state.copy(audioRoute = event.route)
        is VoiceEvent.BluetoothChanged -> {
            val fallbackRoute = if (!event.available && state.audioRoute == AudioRoute.BLUETOOTH) AudioRoute.SPEAKER else state.audioRoute
            state.copy(bluetoothAvailable = event.available, audioRoute = fallbackRoute)
        }
        VoiceEvent.CycleRoute -> {
            val next = nextAudioRoute(state.audioRoute, state.bluetoothAvailable)
            state.copy(audioRoute = next)
        }
        VoiceEvent.TickSecond -> if (state.inCall && !state.isConnecting && !state.resuming) {
            state.copy(elapsedSeconds = state.elapsedSeconds + 1)
        } else state
        VoiceEvent.Hangup -> VoiceUiState(
            phase = VoiceCallPhase.Idle,
            mode = state.mode,
            audioRoute = state.audioRoute,
            bluetoothAvailable = state.bluetoothAvailable,
        )
        is VoiceEvent.Fail -> VoiceUiState(
            phase = VoiceCallPhase.Failed(event.message),
            mode = state.mode,
            audioRoute = state.audioRoute,
            bluetoothAvailable = state.bluetoothAvailable,
        )
    }

    fun nextAudioRoute(current: AudioRoute, bluetoothAvailable: Boolean): AudioRoute = when (current) {
        AudioRoute.SPEAKER -> AudioRoute.EARPIECE
        AudioRoute.EARPIECE -> if (bluetoothAvailable) AudioRoute.BLUETOOTH else AudioRoute.SPEAKER
        AudioRoute.BLUETOOTH -> AudioRoute.SPEAKER
    }

    fun formatDuration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        val m = safe / 60
        val s = safe % 60
        return "%02d:%02d".format(m, s)
    }
}

/** Pure PCM level metering and chunking helpers for voice audio capture/playback. */
object VoiceAudioMath {
    /**
     * Computes a perceptually scaled RMS amplitude in `0f..1f` from signed 16-bit little-endian mono PCM.
     */
    fun rmsLevel(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): Float {
        if (length < 2 || offset < 0 || offset + length > pcm.size) return 0f
        val samples = length / 2
        var sumSquares = 0.0
        var i = offset
        val end = offset + samples * 2
        while (i < end) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
            i += 2
        }
        val rms = sqrt(sumSquares / samples)
        // Voice speech RMS typically lives in 0.01..0.25; gamma boost gives lively UI animation.
        return (rms * 3.8).coerceIn(0.0, 1.0).pow(0.75).toFloat()
    }

    /** Exponential smoothing with faster attack than release so voice pulses look crisp. */
    fun smoothLevel(previous: Float, current: Float, attack: Float = 0.48f, release: Float = 0.22f): Float {
        val clamped = current.coerceIn(0f, 1f)
        val alpha = if (clamped >= previous) attack else release
        val next = previous + (clamped - previous) * alpha
        return if (next < 0.005f) 0f else next.coerceIn(0f, 1f)
    }

    /** Splits [length] bytes starting at [offset] into sample-aligned (even byte) slices <= [maxChunkBytes]. */
    fun sliceSampleAligned(offset: Int, length: Int, maxChunkBytes: Int = VoiceProtocol.MIC_FRAME_CHUNK_BYTES): List<Pair<Int, Int>> {
        val alignedMax = (maxChunkBytes and 1.inv()).coerceAtLeast(2)
        val alignedLen = length and 1.inv()
        if (alignedLen <= 0) return emptyList()
        val result = ArrayList<Pair<Int, Int>>((alignedLen + alignedMax - 1) / alignedMax)
        var pos = offset
        val end = offset + alignedLen
        while (pos < end) {
            val chunk = minOf(alignedMax, end - pos)
            result += pos to chunk
            pos += chunk
        }
        return result
    }
}
