package io.itermux.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import io.itermux.desktop.AudioRoute
import io.itermux.desktop.VoiceAudioMath
import io.itermux.desktop.VoiceProtocol
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Full-duplex Android voice audio engine for `agy /voice`:
 * - Capture: `AudioRecord` (`VOICE_COMMUNICATION`, 16 kHz mono s16le, 1280 B / 40 ms reads) with
 *   hardware [AcousticEchoCanceler] + [NoiseSuppressor] when supported by the device.
 * - Playback: low-latency `AudioTrack` (`USAGE_VOICE_COMMUNICATION`) honoring dynamic `SPK_START`
 *   sample rates (e.g. 24 kHz for Gemini Live), with instantaneous `pause()/flush()/play()` on `SPK_CLEAR`.
 * - Routing: Speaker (default) / Earpiece / Bluetooth via `setCommunicationDevice` (API 31+) or legacy AudioManager.
 */
internal class VoiceAudioEngine(
    private val context: Context,
    private val onMicChunk: (streamId: Long, pcm: ByteArray, offset: Int, length: Int) -> Unit,
    private val onMicLevel: (Float) -> Unit,
    private val onSpeakerLevel: (Float) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val sessionActive = AtomicBoolean(false)
    @Volatile var muted: Boolean = false
        set(value) {
            field = value
            if (value) {
                smoothedMic = 0f
                onMicLevel(0f)
            }
        }

    // Audio mode & focus restoration state
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerphone: Boolean = false
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var currentRoute: AudioRoute = AudioRoute.SPEAKER

    // Capture state
    private val micLock = Any()
    @Volatile private var micStreamId: Long? = null
    @Volatile private var micThread: Thread? = null
    @Volatile private var smoothedMic: Float = 0f

    // Playback state
    private class PlayChunk(val generation: Int, val pcm: ByteArray, val offset: Int, val length: Int)
    private val spkLock = Any()
    private val spkTrackLock = Any()
    private val spkQueue = LinkedBlockingQueue<PlayChunk>(MAX_SPK_QUEUE)
    private val spkGeneration = AtomicInteger(0)
    @Volatile private var spkStreamId: Long? = null
    @Volatile private var spkTrack: AudioTrack? = null
    @Volatile private var spkThread: Thread? = null
    @Volatile private var smoothedSpk: Float = 0f

    /** Enters `MODE_IN_COMMUNICATION`, requests communication audio focus, and applies [route]. */
    fun beginSession(route: AudioRoute) {
        if (!sessionActive.compareAndSet(false, true)) {
            applyRoute(route)
            return
        }
        currentRoute = route
        previousAudioMode = audioManager.mode
        @Suppress("DEPRECATION")
        previousSpeakerphone = audioManager.isSpeakerphoneOn
        runCatching { requestAudioFocus() }
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
        applyRoute(route)
    }

    /** Returns true if a Bluetooth or wired headset communication device is currently available. */
    fun isBluetoothAvailable(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return audioManager.availableCommunicationDevices.any { it.type in BLUETOOTH_TYPES }
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in BLUETOOTH_TYPES }
    }

    /** Switches audio output between Speaker, Earpiece, and Bluetooth. */
    fun applyRoute(route: AudioRoute) {
        currentRoute = route
        if (!sessionActive.get()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val target = when (route) {
                    AudioRoute.SPEAKER -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    AudioRoute.EARPIECE -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    AudioRoute.BLUETOOTH -> devices.firstOrNull { it.type in BLUETOOTH_TYPES }
                        ?: devices.firstOrNull { it.type in WIRED_HEADSET_TYPES }
                }
                if (target != null) {
                    audioManager.setCommunicationDevice(target)
                } else if (route == AudioRoute.EARPIECE) {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                when (route) {
                    AudioRoute.SPEAKER -> {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                        audioManager.isSpeakerphoneOn = true
                    }
                    AudioRoute.EARPIECE -> {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                        audioManager.isSpeakerphoneOn = false
                    }
                    AudioRoute.BLUETOOTH -> {
                        audioManager.isSpeakerphoneOn = false
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                    }
                }
            }
        }.onFailure { Log.w(TAG, "applyRoute($route) failed", it) }
    }

    /** Starts capturing 16 kHz mono s16le PCM (`1280 B` / 40 ms reads) for [streamId]. */
    @SuppressLint("MissingPermission")
    fun startMic(streamId: Long, sampleRate: Int = VoiceProtocol.MIC_SAMPLE_RATE, channels: Int = VoiceProtocol.MIC_CHANNELS): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "startMic denied: missing RECORD_AUDIO")
            return false
        }
        val rate = if (sampleRate in 8000..48000) sampleRate else VoiceProtocol.MIC_SAMPLE_RATE
        if (channels != 1) Log.w(TAG, "startMic: unexpected channels=$channels, forcing mono")
        stopMic()
        synchronized(micLock) {
            micStreamId = streamId
            val t = Thread({ captureLoop(streamId, rate) }, "voice-mic-$streamId").apply {
                isDaemon = true
                start()
            }
            micThread = t
        }
        return true
    }

    fun stopMic(streamId: Long? = null) {
        val threadToStop = synchronized(micLock) {
            if (streamId != null && micStreamId != streamId) return
            micStreamId = null
            micThread.also { micThread = null }
        }
        threadToStop?.interrupt()
        if (threadToStop != null && threadToStop !== Thread.currentThread()) {
            runCatching { threadToStop.join(350) }
        }
        smoothedMic = 0f
        onMicLevel(0f)
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop(streamId: Long, sampleRate: Int) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.w(TAG, "AudioRecord invalid minBufferSize=$minBuf for rate=$sampleRate")
            return
        }
        val chunkBytes = VoiceProtocol.MIC_RECORD_CHUNK_BYTES // 1280 B = 40 ms @ 16 kHz mono s16le
        val bufferSize = maxOf(minBuf * 2, chunkBytes * 4)
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord creation failed", e)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord failed to initialize")
            runCatching { record.release() }
            return
        }
        val aec = if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else null
        val ns = if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else null
        Log.i(TAG, "mic started stream=$streamId rate=$sampleRate aec=${aec?.enabled} ns=${ns?.enabled}")
        val buf = ByteArray(chunkBytes)
        try {
            record.startRecording()
            while (!Thread.currentThread().isInterrupted && micStreamId == streamId) {
                var filled = 0
                while (filled < chunkBytes && !Thread.currentThread().isInterrupted && micStreamId == streamId) {
                    val read = record.read(buf, filled, chunkBytes - filled)
                    if (read <= 0) {
                        if (read < 0) Log.w(TAG, "AudioRecord read error=$read")
                        break
                    }
                    filled += read
                }
                if (filled <= 0 || micStreamId != streamId) continue
                val aligned = filled and 1.inv()
                if (aligned <= 0) continue
                if (muted) {
                    if (smoothedMic != 0f) {
                        smoothedMic = 0f
                        onMicLevel(0f)
                    }
                    continue
                }
                val rawLevel = VoiceAudioMath.rmsLevel(buf, 0, aligned)
                smoothedMic = VoiceAudioMath.smoothLevel(smoothedMic, rawLevel)
                onMicLevel(smoothedMic)
                onMicChunk(streamId, buf, 0, aligned)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "mic capture loop ended", e)
        } finally {
            runCatching { record.stop() }
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            runCatching { record.release() }
            if (micStreamId == streamId) {
                smoothedMic = 0f
                onMicLevel(0f)
            }
            Log.i(TAG, "mic stopped stream=$streamId")
        }
    }

    /**
     * Opens a low-latency `USAGE_VOICE_COMMUNICATION` [AudioTrack] at [sampleRate] Hz mono s16le.
     * Returns null on success, or an error description for `SPK_ACK`.
     */
    fun startSpeaker(streamId: Long, sampleRate: Int, channels: Int): String? {
        if (sampleRate !in VoiceProtocol.SPK_RATE_MIN..VoiceProtocol.SPK_RATE_MAX) {
            return "unsupported sample rate $sampleRate"
        }
        if (channels != 1) {
            return "unsupported channels $channels"
        }
        stopSpeaker()
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return "audio track unavailable for $sampleRate Hz"
        val targetBytes = sampleRate * 2 * 160 / 1000 // ~160 ms low-latency ring
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, targetBytes))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack creation failed", e)
            return "audio track init failed: ${e.message ?: "unknown"}"
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return "audio track not initialized"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { track.setStartThresholdInFrames((sampleRate * 40 / 1000).coerceAtLeast(1)) }
        }
        applyRoute(currentRoute)
        synchronized(spkLock) {
            val gen = spkGeneration.incrementAndGet()
            spkQueue.clear()
            spkStreamId = streamId
            synchronized(spkTrackLock) { spkTrack = track }
            val t = Thread({ playbackLoop(streamId, track, gen) }, "voice-spk-$streamId").apply {
                isDaemon = true
                start()
            }
            spkThread = t
        }
        Log.i(TAG, "speaker started stream=$streamId rate=$sampleRate")
        return null
    }

    /** Enqueues s16le PCM bytes for [streamId]; drops oldest if the queue overflows. */
    fun writeSpeaker(streamId: Long, pcm: ByteArray, offset: Int, length: Int) {
        if (spkStreamId != streamId || length <= 0) return
        val copy = pcm.copyOfRange(offset, offset + length)
        val chunk = PlayChunk(spkGeneration.get(), copy, 0, copy.size)
        while (!spkQueue.offer(chunk)) {
            spkQueue.poll()
        }
    }

    /** Barge-in (`SPK_CLEAR`): drops queued audio and immediately runs `pause() / flush() / play()`. */
    fun clearSpeaker(streamId: Long) {
        if (spkStreamId != streamId) return
        spkGeneration.incrementAndGet()
        spkQueue.clear()
        synchronized(spkTrackLock) {
            spkTrack?.let { track ->
                runCatching {
                    track.pause()
                    track.flush()
                    track.play()
                }.onFailure { Log.w(TAG, "speaker barge-in flush failed", it) }
            }
        }
        smoothedSpk = 0f
        onSpeakerLevel(0f)
    }

    fun stopSpeaker(streamId: Long? = null) {
        val threadToStop: Thread?
        val trackToRelease: AudioTrack?
        synchronized(spkLock) {
            if (streamId != null && spkStreamId != streamId) return
            spkStreamId = null
            spkGeneration.incrementAndGet()
            spkQueue.clear()
            threadToStop = spkThread.also { spkThread = null }
            trackToRelease = synchronized(spkTrackLock) { spkTrack.also { spkTrack = null } }
        }
        threadToStop?.interrupt()
        if (threadToStop != null && threadToStop !== Thread.currentThread()) {
            runCatching { threadToStop.join(350) }
        }
        trackToRelease?.let { track ->
            runCatching { track.pause(); track.flush(); track.release() }
        }
        smoothedSpk = 0f
        onSpeakerLevel(0f)
    }

    private fun playbackLoop(streamId: Long, track: AudioTrack, initialGen: Int) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        var lastAudioAt = 0L
        try {
            synchronized(spkTrackLock) {
                if (spkTrack === track) track.play()
            }
            while (!Thread.currentThread().isInterrupted && spkStreamId == streamId) {
                val chunk = spkQueue.poll(120, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    // Decay speaker indicator cleanly back to listening once playback goes quiet.
                    if (smoothedSpk > 0f && System.nanoTime() - lastAudioAt > SILENCE_DECAY_NS) {
                        smoothedSpk = 0f
                        onSpeakerLevel(0f)
                    }
                    continue
                }
                if (chunk.generation != spkGeneration.get()) continue
                val level = VoiceAudioMath.rmsLevel(chunk.pcm, chunk.offset, chunk.length)
                smoothedSpk = VoiceAudioMath.smoothLevel(smoothedSpk, level)
                lastAudioAt = System.nanoTime()
                onSpeakerLevel(smoothedSpk)
                synchronized(spkTrackLock) {
                    if (spkTrack !== track || chunk.generation != spkGeneration.get()) return@synchronized
                    track.write(chunk.pcm, chunk.offset, chunk.length, AudioTrack.WRITE_BLOCKING)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "speaker playback loop ended (gen=$initialGen)", e)
        } finally {
            if (spkStreamId == streamId) {
                smoothedSpk = 0f
                onSpeakerLevel(0f)
            }
            Log.i(TAG, "speaker stopped stream=$streamId")
        }
    }

    /** Stops capture & playback, restores audio routing, mode, and releases focus. */
    fun release() {
        stopMic()
        stopSpeaker()
        if (!sessionActive.compareAndSet(true, false)) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = previousSpeakerphone
            }
        }
        runCatching { audioManager.mode = previousAudioMode }
        runCatching { abandonAudioFocus() }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    companion object {
        private const val TAG = "RoamttyVoice"
        private const val MAX_SPK_QUEUE = 128
        private const val SILENCE_DECAY_NS = 350_000_000L
        private val BLUETOOTH_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
        private val WIRED_HEADSET_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
        }
    }
}
