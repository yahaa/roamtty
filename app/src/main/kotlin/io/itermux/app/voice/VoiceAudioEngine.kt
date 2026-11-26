package io.itermux.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
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
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.itermux.desktop.AudioRoute
import io.itermux.desktop.VoiceAudioMath
import io.itermux.desktop.VoiceProtocol
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Abstraction over [VoiceAudioEngine] so [VoiceConnection] can be verified in pure JVM unit tests. */
internal interface VoiceAudioBackend {
    var muted: Boolean
    fun isBluetoothAvailable(): Boolean
    fun beginSession(route: AudioRoute)
    fun applyRoute(route: AudioRoute)
    /** Non-blocking immediate silence when hangup begins before async teardown finishes. */
    fun silenceForHangup() {}
    fun startMic(streamId: Long, sampleRate: Int = VoiceProtocol.MIC_SAMPLE_RATE, channels: Int = VoiceProtocol.MIC_CHANNELS): Boolean
    fun stopMic(streamId: Long? = null)
    fun startSpeaker(streamId: Long, sampleRate: Int, channels: Int): String?
    fun writeSpeaker(streamId: Long, pcm: ByteArray, offset: Int, length: Int)
    fun clearSpeaker(streamId: Long)
    fun stopSpeaker(streamId: Long? = null)
    fun release()
}

/**
 * Full-duplex Android voice audio engine for `agy /voice`:
 * - Capture: `AudioRecord` (`VOICE_COMMUNICATION`, 16 kHz mono s16le, 1280 B / 40 ms reads) with
 *   hardware [AcousticEchoCanceler] + [NoiseSuppressor] when supported by the device.
 * - Playback: low-latency `AudioTrack` (`USAGE_VOICE_COMMUNICATION`) honoring dynamic `SPK_START`
 *   sample rates (e.g. 24 kHz for Gemini Live), with instantaneous `pause()/flush()/play()` on `SPK_CLEAR`.
 * - Routing: Speaker (default) / Earpiece / Bluetooth via `setCommunicationDevice` (API 31+) or legacy AudioManager,
 *   with live Bluetooth device / SCO disconnect monitoring and AudioFocus suspension/resume.
 */
internal class VoiceAudioEngine(
    private val context: Context,
    private val onMicChunk: (streamId: Long, pcm: ByteArray, offset: Int, length: Int) -> Unit,
    private val onMicLevel: (Float) -> Unit,
    private val onSpeakerLevel: (Float) -> Unit,
    private val onMicError: (String) -> Unit = {},
    private val onBluetoothAvailableChanged: (Boolean) -> Unit = {},
    private val onRouteChanged: (AudioRoute) -> Unit = {},
) : VoiceAudioBackend {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionLock = Any()
    private val ownerToken = Any()
    private val sessionActive = AtomicBoolean(false)
    private val hungUp = AtomicBoolean(false)
    @Volatile private var focusSuspended: Boolean = false
    @Volatile override var muted: Boolean = false
        set(value) {
            field = value
            if (value) {
                smoothedMic = 0f
                onMicLevel(0f)
            }
        }

    // Audio focus, device listener, and Bluetooth route-recovery state (guarded by sessionLock)
    private var focusRequest: AudioFocusRequest? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var commDeviceListener: Any? = null
    private var scoReceiver: BroadcastReceiver? = null
    private var scoWasActive: Boolean = false
    private var btRouteObserved: Boolean = false
    private var btUnexpectedDropRetries: Int = 0
    private var btRecoveryAttemptedStreamId: Long? = null
    private var bouncingBluetooth: Boolean = false
    private var pendingRouteCheck: Runnable? = null
    private var pendingRouteRebind: Runnable? = null
    @Volatile private var currentRoute: AudioRoute = AudioRoute.SPEAKER

    // Capture state
    private val micLock = Any()
    private val micRecordLock = Any()
    @Volatile private var micStreamId: Long? = null
    @Volatile private var activeRecord: AudioRecord? = null
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

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "audio focus lost ($focusChange); suspending voice audio")
                focusSuspended = true
                smoothedMic = 0f
                onMicLevel(0f)
                synchronized(spkTrackLock) {
                    spkTrack?.let { runCatching { it.pause(); it.flush() } }
                }
                smoothedSpk = 0f
                onSpeakerLevel(0f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (hungUp.get()) return@OnAudioFocusChangeListener
                Log.i(TAG, "audio focus regained; resuming voice audio")
                focusSuspended = false
                var fallbackRoute: AudioRoute? = null
                synchronized(audioSystemLock) {
                    synchronized(sessionLock) {
                        if (sessionActive.get() && !hungUp.get() && activeAudioOwner === ownerToken) {
                            runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
                            fallbackRoute = applyRouteLocked(currentRoute)
                        }
                    }
                }
                fallbackRoute?.let(onRouteChanged)
                if (!hungUp.get()) {
                    synchronized(spkTrackLock) {
                        spkTrack?.let { runCatching { it.play() } }
                    }
                }
            }
        }
    }

    /** Immediately silences playback & mic without blocking the caller (Main) thread while shutdown finishes. */
    override fun silenceForHangup() {
        hungUp.set(true)
        muted = true
        focusSuspended = true
        synchronized(sessionLock) {
            cancelPendingRouteActionsLocked()
        }
        spkGeneration.incrementAndGet()
        spkQueue.clear()
        synchronized(spkTrackLock) {
            spkTrack?.let { runCatching { it.pause(); it.flush() } }
        }
        smoothedMic = 0f
        smoothedSpk = 0f
        onMicLevel(0f)
        onSpeakerLevel(0f)
    }

    /** Enters `MODE_IN_COMMUNICATION`, requests communication audio focus, monitors BT devices, and applies [route]. */
    override fun beginSession(route: AudioRoute) {
        if (hungUp.get()) return
        // If a superseded engine has not finished tearing down yet, release it cleanly first
        // outside audioSystemLock so stopping its audio threads never deadlocks or overlaps ours.
        val previous = synchronized(audioSystemLock) {
            activeEngine?.takeIf { it !== this }
        }
        previous?.release()
        if (hungUp.get()) return

        // Give Android's Bluetooth SCO/BLE HAL a brief settling window after tearing down a prior call
        // so setCommunicationDevice(bt) does not race an in-flight clearCommunicationDevice().
        if (route == AudioRoute.BLUETOOTH && Looper.myLooper() != Looper.getMainLooper()) {
            val waitMs = synchronized(audioSystemLock) {
                val elapsed = SystemClock.elapsedRealtime() - lastTeardownElapsedMs
                if (lastTeardownElapsedMs > 0L && elapsed in 0L until BT_REBIND_SETTLE_MS) {
                    BT_REBIND_SETTLE_MS - elapsed
                } else 0L
            }
            if (waitMs > 0L) {
                Log.i(TAG, "waiting ${waitMs}ms for Bluetooth audio teardown to settle before rebinding")
                runCatching { Thread.sleep(waitMs) }
                if (hungUp.get()) return
            }
        }

        var fallbackRoute: AudioRoute? = null
        synchronized(audioSystemLock) {
            if (hungUp.get()) return
            synchronized(sessionLock) {
                val newlyStarted = sessionActive.compareAndSet(false, true)
                currentRoute = route
                focusSuspended = false
                cancelPendingRouteActionsLocked()
                if (activeAudioOwner !== ownerToken) {
                    if (activeAudioOwner == null) {
                        savedAudioMode = audioManager.mode
                        @Suppress("DEPRECATION")
                        savedSpeakerphone = audioManager.isSpeakerphoneOn
                    }
                    activeAudioOwner = ownerToken
                    activeEngine = this
                }
                if (newlyStarted) {
                    btRouteObserved = false
                    btUnexpectedDropRetries = 0
                    btRecoveryAttemptedStreamId = null
                    runCatching { requestAudioFocus() }
                    runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
                    registerDeviceListenersLocked()
                } else {
                    runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
                }
                fallbackRoute = applyRouteLocked(route)
            }
        }
        fallbackRoute?.let(onRouteChanged)
    }

    /** Returns true if a Bluetooth communication device is currently available. */
    override fun isBluetoothAvailable(): Boolean = isBluetoothAvailable(audioManager)

    /** Switches audio output between Speaker, Earpiece, and Bluetooth. */
    override fun applyRoute(route: AudioRoute) {
        var fallbackRoute: AudioRoute? = null
        synchronized(audioSystemLock) {
            synchronized(sessionLock) {
                currentRoute = route
                cancelPendingRouteActionsLocked()
                btUnexpectedDropRetries = 0
                if (!sessionActive.get() || hungUp.get() || activeAudioOwner !== ownerToken) return
                fallbackRoute = applyRouteLocked(route)
            }
        }
        fallbackRoute?.let(onRouteChanged)
    }

    /** Ensures `MODE_IN_COMMUNICATION` and [currentRoute] are active before creating an `AudioRecord` or `AudioTrack`. */
    private fun ensureRouteForStream() {
        var fallbackRoute: AudioRoute? = null
        synchronized(audioSystemLock) {
            synchronized(sessionLock) {
                if (!sessionActive.get() || hungUp.get() || activeAudioOwner !== ownerToken) return
                if (bouncingBluetooth) return
                runCatching {
                    if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    }
                }
                val needsApply = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val active = runCatching { audioManager.communicationDevice }.getOrNull()
                    when (currentRoute) {
                        AudioRoute.SPEAKER -> active?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        AudioRoute.EARPIECE -> active?.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                        AudioRoute.BLUETOOTH -> active == null ||
                            (active.type !in BLUETOOTH_TYPES && active.type !in WIRED_HEADSET_TYPES)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    when (currentRoute) {
                        AudioRoute.SPEAKER -> !audioManager.isSpeakerphoneOn || audioManager.isBluetoothScoOn
                        AudioRoute.EARPIECE -> audioManager.isSpeakerphoneOn || audioManager.isBluetoothScoOn
                        AudioRoute.BLUETOOTH -> !audioManager.isBluetoothScoOn
                    }
                }
                if (needsApply) {
                    fallbackRoute = applyRouteLocked(currentRoute)
                }
            }
        }
        fallbackRoute?.let(onRouteChanged)
    }

    /** Must be called under [audioSystemLock] + [sessionLock]. Returns a fallback route if [route] had to be overridden. */
    private fun applyRouteLocked(route: AudioRoute): AudioRoute? {
        var fallback: AudioRoute? = null
        runCatching {
            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val target = when (route) {
                    AudioRoute.SPEAKER -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    AudioRoute.EARPIECE -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    AudioRoute.BLUETOOTH -> devices.firstOrNull { it.type in BLUETOOTH_TYPES }
                        ?: devices.firstOrNull { it.type in WIRED_HEADSET_TYPES }
                }
                if (target != null) {
                    val ok = audioManager.setCommunicationDevice(target)
                    if (!ok) {
                        Log.w(TAG, "setCommunicationDevice(${target.type}) returned false for route=$route")
                        if (route == AudioRoute.BLUETOOTH) {
                            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let {
                                audioManager.setCommunicationDevice(it)
                            }
                            currentRoute = AudioRoute.SPEAKER
                            fallback = AudioRoute.SPEAKER
                        }
                    } else {
                        Log.i(TAG, "applied communication device type=${target.type} for route=$route")
                    }
                } else if (route == AudioRoute.BLUETOOTH) {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let {
                        audioManager.setCommunicationDevice(it)
                    }
                    currentRoute = AudioRoute.SPEAKER
                    fallback = AudioRoute.SPEAKER
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
                        if (!isBluetoothAvailable(audioManager)) {
                            audioManager.stopBluetoothSco()
                            audioManager.isBluetoothScoOn = false
                            audioManager.isSpeakerphoneOn = true
                            currentRoute = AudioRoute.SPEAKER
                            fallback = AudioRoute.SPEAKER
                        } else {
                            audioManager.isSpeakerphoneOn = false
                            audioManager.startBluetoothSco()
                            audioManager.isBluetoothScoOn = true
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "applyRoute($route) failed", it) }
        return fallback
    }

    /** Must be called under [sessionLock]. */
    private fun cancelPendingRouteActionsLocked() {
        pendingRouteCheck?.let(mainHandler::removeCallbacks)
        pendingRouteCheck = null
        pendingRouteRebind?.let(mainHandler::removeCallbacks)
        pendingRouteRebind = null
        bouncingBluetooth = false
    }

    private fun scheduleBluetoothRouteCheck(streamId: Long, track: AudioTrack) {
        val runnable = Runnable { verifyBluetoothPlaybackRoute(streamId, track) }
        synchronized(sessionLock) {
            if (!sessionActive.get() || hungUp.get() || currentRoute != AudioRoute.BLUETOOTH) return
            if (btRecoveryAttemptedStreamId == streamId) return
            pendingRouteCheck?.let(mainHandler::removeCallbacks)
            pendingRouteCheck = runnable
            mainHandler.postDelayed(runnable, BT_ROUTE_VERIFY_DELAY_MS)
        }
    }

    /**
     * Defense-in-depth verification once an `AudioTrack` is playing on Bluetooth: if the OS dropped
     * the communication device or routed the track away from Bluetooth despite a connected headset,
     * performs a single bounded silent re-negotiation so audio reaches Bluetooth without manual toggling.
     */
    private fun verifyBluetoothPlaybackRoute(streamId: Long, track: AudioTrack) {
        // Query routedDevice before acquiring audioSystemLock to keep spkTrackLock and audioSystemLock un-nested.
        val routed = synchronized(spkTrackLock) {
            if (spkTrack !== track) return
            runCatching { track.routedDevice }.getOrNull()
        }
        var directFallback: AudioRoute? = null
        val shouldBounce = synchronized(audioSystemLock) {
            synchronized(sessionLock) {
                pendingRouteCheck = null
                if (!sessionActive.get() || hungUp.get() || focusSuspended) return
                if (activeAudioOwner !== ownerToken || currentRoute != AudioRoute.BLUETOOTH) return
                if (spkStreamId != streamId || btRecoveryAttemptedStreamId == streamId) return
                if (!isBluetoothAvailable(audioManager)) return

                val modeOk = audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
                val routedIsBt = routed == null ||
                    routed.type in BLUETOOTH_ROUTED_TYPES ||
                    routed.type in WIRED_HEADSET_TYPES
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val comm = runCatching { audioManager.communicationDevice }.getOrNull()
                    val commIsBt = comm != null &&
                        (comm.type in BLUETOOTH_TYPES || comm.type in WIRED_HEADSET_TYPES)
                    if (modeOk && commIsBt && routedIsBt) return
                    btRecoveryAttemptedStreamId = streamId
                    Log.w(TAG, "BT route mismatch stream=$streamId mode=${audioManager.mode} comm=${comm?.type} routed=${routed?.type}; recovering")
                    if (!modeOk || !commIsBt) {
                        directFallback = applyRouteLocked(AudioRoute.BLUETOOTH)
                        false
                    } else {
                        // AudioManager reports BT selected, yet AudioTrack is routed elsewhere:
                        // force a real communication-device transition (non-BT -> BT) to rebind HAL.
                        bouncingBluetooth = true
                        bounceAwayFromBluetoothApi31Locked()
                        true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val scoOn = audioManager.isBluetoothScoOn
                    if (modeOk && scoOn && routedIsBt) return
                    btRecoveryAttemptedStreamId = streamId
                    Log.w(TAG, "legacy BT route mismatch stream=$streamId mode=${audioManager.mode} scoOn=$scoOn routed=${routed?.type}; recovering")
                    bouncingBluetooth = true
                    @Suppress("DEPRECATION")
                    runCatching {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                    }
                    true
                }
            }
        }
        directFallback?.let(onRouteChanged)
        if (shouldBounce) {
            val rebind = Runnable {
                var fallbackRoute: AudioRoute? = null
                synchronized(audioSystemLock) {
                    synchronized(sessionLock) {
                        pendingRouteRebind = null
                        bouncingBluetooth = false
                        if (!sessionActive.get() || hungUp.get() || focusSuspended) return@synchronized
                        if (activeAudioOwner !== ownerToken || currentRoute != AudioRoute.BLUETOOTH) return@synchronized
                        fallbackRoute = applyRouteLocked(AudioRoute.BLUETOOTH)
                    }
                }
                fallbackRoute?.let(onRouteChanged)
            }
            synchronized(sessionLock) {
                if (!sessionActive.get() || hungUp.get() || currentRoute != AudioRoute.BLUETOOTH) {
                    bouncingBluetooth = false
                    return
                }
                pendingRouteRebind?.let(mainHandler::removeCallbacks)
                pendingRouteRebind = rebind
                mainHandler.postDelayed(rebind, BT_ROUTE_BOUNCE_MS)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun bounceAwayFromBluetoothApi31Locked() {
        runCatching {
            audioManager.clearCommunicationDevice()
            val active = audioManager.communicationDevice
            if (active != null && (active.type in BLUETOOTH_TYPES || active.type in WIRED_HEADSET_TYPES)) {
                val devices = audioManager.availableCommunicationDevices
                val nonBt = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (nonBt != null) {
                    audioManager.setCommunicationDevice(nonBt)
                }
            }
        }.onFailure { Log.w(TAG, "bounceAwayFromBluetooth failed", it) }
    }

    private fun handleBluetoothTopologyChanged(forceFallback: Boolean = false) {
        val btNow = isBluetoothAvailable(audioManager)
        onBluetoothAvailableChanged(btNow)
        var fallbackRoute: AudioRoute? = null
        synchronized(audioSystemLock) {
            synchronized(sessionLock) {
                if (!sessionActive.get() || hungUp.get() || activeAudioOwner !== ownerToken) return
                if ((!btNow || forceFallback) && currentRoute == AudioRoute.BLUETOOTH) {
                    Log.i(TAG, "Bluetooth audio disconnected; falling back to speaker")
                    currentRoute = AudioRoute.SPEAKER
                    applyRouteLocked(AudioRoute.SPEAKER)
                    fallbackRoute = AudioRoute.SPEAKER
                }
            }
        }
        fallbackRoute?.let(onRouteChanged)
    }

    private fun registerDeviceListenersLocked() {
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = handleBluetoothTopologyChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = handleBluetoothTopologyChanged()
        }
        deviceCallback = cb
        runCatching { audioManager.registerAudioDeviceCallback(cb, mainHandler) }
            .onFailure { Log.w(TAG, "registerAudioDeviceCallback failed", it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerCommunicationDeviceListenerApi31()
        } else {
            scoWasActive = false
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                    val prev = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
                    when (state) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED,
                        AudioManager.SCO_AUDIO_STATE_CONNECTING -> scoWasActive = true
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                            val dropped = scoWasActive ||
                                prev == AudioManager.SCO_AUDIO_STATE_CONNECTED ||
                                prev == AudioManager.SCO_AUDIO_STATE_CONNECTING
                            scoWasActive = false
                            if (dropped) handleBluetoothTopologyChanged(forceFallback = !isBluetoothAvailable(audioManager))
                        }
                    }
                }
            }
            scoReceiver = receiver
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                    ContextCompat.RECEIVER_EXPORTED,
                )
            }.onFailure { Log.w(TAG, "registerReceiver(ACTION_SCO_AUDIO_STATE_UPDATED) failed", it) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerCommunicationDeviceListenerApi31() {
        val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
            val btNow = isBluetoothAvailable(audioManager)
            onBluetoothAvailableChanged(btNow)
            val isBtOrHeadset = device != null && (device.type in BLUETOOTH_TYPES || device.type in WIRED_HEADSET_TYPES)
            var fallbackRoute: AudioRoute? = null
            var scheduleReapply = false
            synchronized(audioSystemLock) {
                synchronized(sessionLock) {
                    if (!sessionActive.get() || hungUp.get() || activeAudioOwner !== ownerToken) return@synchronized
                    if (isBtOrHeadset) {
                        btRouteObserved = true
                        btUnexpectedDropRetries = 0
                    } else if (!btNow && currentRoute == AudioRoute.BLUETOOTH) {
                        currentRoute = AudioRoute.SPEAKER
                        applyRouteLocked(AudioRoute.SPEAKER)
                        fallbackRoute = AudioRoute.SPEAKER
                    } else if (
                        btNow &&
                        currentRoute == AudioRoute.BLUETOOTH &&
                        btRouteObserved &&
                        !focusSuspended &&
                        !bouncingBluetooth &&
                        btUnexpectedDropRetries < MAX_BT_DROP_RETRIES
                    ) {
                        btUnexpectedDropRetries++
                        scheduleReapply = true
                    }
                }
            }
            fallbackRoute?.let(onRouteChanged)
            if (scheduleReapply) {
                Log.w(TAG, "Bluetooth communication device changed to ${device?.type} while BT still available; re-applying")
                val reapply = Runnable {
                    var fb: AudioRoute? = null
                    synchronized(audioSystemLock) {
                        synchronized(sessionLock) {
                            pendingRouteRebind = null
                            if (!sessionActive.get() || hungUp.get() || focusSuspended) return@synchronized
                            if (activeAudioOwner !== ownerToken || currentRoute != AudioRoute.BLUETOOTH) return@synchronized
                            if (isBluetoothAvailable(audioManager)) {
                                fb = applyRouteLocked(AudioRoute.BLUETOOTH)
                            }
                        }
                    }
                    fb?.let(onRouteChanged)
                }
                synchronized(sessionLock) {
                    if (sessionActive.get() && !hungUp.get() && currentRoute == AudioRoute.BLUETOOTH) {
                        pendingRouteRebind?.let(mainHandler::removeCallbacks)
                        pendingRouteRebind = reapply
                        mainHandler.postDelayed(reapply, BT_ROUTE_BOUNCE_MS)
                    }
                }
            }
        }
        commDeviceListener = listener
        runCatching { audioManager.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener) }
            .onFailure { Log.w(TAG, "addOnCommunicationDeviceChangedListener failed", it) }
    }

    private fun unregisterDeviceListenersLocked() {
        deviceCallback?.let { cb ->
            runCatching { audioManager.unregisterAudioDeviceCallback(cb) }
        }
        deviceCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            unregisterCommunicationDeviceListenerApi31()
        }
        scoReceiver?.let { receiver ->
            runCatching { context.unregisterReceiver(receiver) }
        }
        scoReceiver = null
        scoWasActive = false
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun unregisterCommunicationDeviceListenerApi31() {
        val listener = commDeviceListener as? AudioManager.OnCommunicationDeviceChangedListener ?: return
        commDeviceListener = null
        runCatching { audioManager.removeOnCommunicationDeviceChangedListener(listener) }
    }

    /** Starts capturing 16 kHz mono s16le PCM (`1280 B` / 40 ms reads) for [streamId]. */
    @SuppressLint("MissingPermission")
    override fun startMic(streamId: Long, sampleRate: Int, channels: Int): Boolean {
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

    override fun stopMic(streamId: Long?) {
        val threadToStop = synchronized(micLock) {
            if (streamId != null && micStreamId != streamId) return
            micStreamId = null
            micThread.also { micThread = null }
        }
        // Unblock AudioRecord.read() immediately without releasing the native record from this thread.
        synchronized(micRecordLock) {
            activeRecord?.let { runCatching { it.stop() } }
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
        ensureRouteForStream()
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.w(TAG, "AudioRecord invalid minBufferSize=$minBuf for rate=$sampleRate")
            if (micStreamId == streamId) onMicError("invalid minBufferSize=$minBuf")
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
            if (micStreamId == streamId) onMicError("AudioRecord creation failed: ${e.message}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord failed to initialize")
            runCatching { record.release() }
            if (micStreamId == streamId) onMicError("AudioRecord not initialized")
            return
        }
        val stillActive = synchronized(micRecordLock) {
            if (micStreamId != streamId) false else {
                activeRecord = record
                true
            }
        }
        if (!stillActive) {
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
        var fatalError: String? = null
        var consecutiveZeroReads = 0
        try {
            record.startRecording()
            while (!Thread.currentThread().isInterrupted && micStreamId == streamId) {
                var filled = 0
                while (filled < chunkBytes && !Thread.currentThread().isInterrupted && micStreamId == streamId) {
                    val read = record.read(buf, filled, chunkBytes - filled)
                    when {
                        read > 0 -> {
                            consecutiveZeroReads = 0
                            filled += read
                        }
                        read == 0 -> {
                            consecutiveZeroReads++
                            if (consecutiveZeroReads >= MAX_CONSECUTIVE_ZERO_READS) {
                                fatalError = "AudioRecord stalled after $consecutiveZeroReads zero reads"
                                Log.w(TAG, fatalError)
                                break
                            }
                            Thread.sleep(ZERO_READ_SLEEP_MS)
                        }
                        else -> {
                            if (micStreamId == streamId && !Thread.currentThread().isInterrupted) {
                                fatalError = "AudioRecord read error=$read"
                                Log.w(TAG, "$fatalError, exiting capture loop")
                            }
                            break
                        }
                    }
                }
                if (fatalError != null) break
                if (filled <= 0 || micStreamId != streamId) continue
                val aligned = filled and 1.inv()
                if (aligned <= 0) continue
                if (muted || focusSuspended) {
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
            if (micStreamId == streamId) fatalError = "AudioRecord failed: ${e.message}"
        } finally {
            synchronized(micRecordLock) {
                if (activeRecord === record) activeRecord = null
            }
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
        val err = fatalError
        if (err != null && micStreamId == streamId) {
            onMicError(err)
        }
    }

    /**
     * Opens a low-latency `USAGE_VOICE_COMMUNICATION` [AudioTrack] at [sampleRate] Hz mono s16le.
     * Returns null on success, or an error description for `SPK_ACK`.
     */
    override fun startSpeaker(streamId: Long, sampleRate: Int, channels: Int): String? {
        if (sampleRate !in VoiceProtocol.SPK_RATE_MIN..VoiceProtocol.SPK_RATE_MAX) {
            return "unsupported sample rate $sampleRate"
        }
        if (channels != 1) {
            return "unsupported channels $channels"
        }
        stopSpeaker()
        ensureRouteForStream()
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
        try {
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
        } catch (t: Throwable) {
            synchronized(spkLock) {
                if (spkStreamId == streamId) spkStreamId = null
                synchronized(spkTrackLock) { if (spkTrack === track) spkTrack = null }
            }
            runCatching { track.release() }
            return "audio track thread start failed: ${t.message ?: "unknown"}"
        }
        Log.i(TAG, "speaker started stream=$streamId rate=$sampleRate")
        return null
    }

    /** Enqueues s16le PCM bytes for [streamId]; drops oldest if the queue overflows. */
    override fun writeSpeaker(streamId: Long, pcm: ByteArray, offset: Int, length: Int) {
        if (spkStreamId != streamId || length <= 0) return
        val copy = pcm.copyOfRange(offset, offset + length)
        val chunk = PlayChunk(spkGeneration.get(), copy, 0, copy.size)
        while (!spkQueue.offer(chunk)) {
            spkQueue.poll()
        }
    }

    /** Barge-in (`SPK_CLEAR`): drops queued audio and immediately runs `pause() / flush() / play()` without blocking on `write()`. */
    override fun clearSpeaker(streamId: Long) {
        if (spkStreamId != streamId) return
        spkGeneration.incrementAndGet()
        spkQueue.clear()
        synchronized(spkTrackLock) {
            spkTrack?.let { track ->
                runCatching {
                    track.pause()
                    track.flush()
                    if (!focusSuspended) track.play()
                }.onFailure { Log.w(TAG, "speaker barge-in flush failed", it) }
            }
        }
        smoothedSpk = 0f
        onSpeakerLevel(0f)
    }

    /** Signals playback to stop and unblocks `track.write()`; `playbackLoop()`'s `finally` exclusively owns `track.release()`. */
    override fun stopSpeaker(streamId: Long?) {
        val threadToStop: Thread?
        synchronized(spkLock) {
            if (streamId != null && spkStreamId != streamId) return
            spkStreamId = null
            spkGeneration.incrementAndGet()
            spkQueue.clear()
            threadToStop = spkThread.also { spkThread = null }
        }
        synchronized(sessionLock) {
            pendingRouteCheck?.let(mainHandler::removeCallbacks)
            pendingRouteCheck = null
        }
        synchronized(spkTrackLock) {
            spkTrack?.let { track ->
                runCatching { track.pause(); track.flush(); track.stop() }
            }
            spkTrack = null
        }
        threadToStop?.interrupt()
        if (threadToStop != null && threadToStop !== Thread.currentThread()) {
            runCatching { threadToStop.join(350) }
        }
        smoothedSpk = 0f
        onSpeakerLevel(0f)
    }

    private fun playbackLoop(streamId: Long, track: AudioTrack, initialGen: Int) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        var lastAudioAt = 0L
        try {
            var startedPlaying = false
            synchronized(spkTrackLock) {
                if (spkTrack === track && !focusSuspended) {
                    track.play()
                    startedPlaying = true
                }
            }
            if (startedPlaying) {
                scheduleBluetoothRouteCheck(streamId, track)
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
                if (focusSuspended) continue
                val level = VoiceAudioMath.rmsLevel(chunk.pcm, chunk.offset, chunk.length)
                smoothedSpk = VoiceAudioMath.smoothLevel(smoothedSpk, level)
                lastAudioAt = System.nanoTime()
                onSpeakerLevel(smoothedSpk)
                // Never hold spkTrackLock across blocking write so clearSpeaker() / stopSpeaker() can interrupt immediately.
                val written = track.write(chunk.pcm, chunk.offset, chunk.length, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    if (spkStreamId == streamId && !Thread.currentThread().isInterrupted) {
                        Log.w(TAG, "AudioTrack write error=$written (stream=$streamId)")
                    }
                    if (written == AudioTrack.ERROR_DEAD_OBJECT || written == AudioTrack.ERROR_INVALID_OPERATION) break
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "speaker playback loop ended (gen=$initialGen)", e)
        } finally {
            // Clear spkTrack under spkTrackLock before releasing so no concurrent control call can touch a released AudioTrack.
            synchronized(spkTrackLock) {
                if (spkTrack === track) spkTrack = null
            }
            runCatching { track.pause(); track.flush(); track.release() }
            if (spkStreamId == streamId) {
                smoothedSpk = 0f
                onSpeakerLevel(0f)
            }
            Log.i(TAG, "speaker stopped stream=$streamId")
        }
    }

    /** Stops capture & playback, unregisters listeners, and restores global audio mode/focus if still the active owner. */
    override fun release() {
        hungUp.set(true)
        stopMic()
        stopSpeaker()
        synchronized(audioSystemLock) {
            synchronized(sessionLock) {
                cancelPendingRouteActionsLocked()
                if (!sessionActive.compareAndSet(true, false)) {
                    runCatching { abandonAudioFocus() }
                    return
                }
                focusSuspended = false
                btRouteObserved = false
                btUnexpectedDropRetries = 0
                btRecoveryAttemptedStreamId = null
                unregisterDeviceListenersLocked()
                if (activeAudioOwner === ownerToken) {
                    activeAudioOwner = null
                    if (activeEngine === this) activeEngine = null
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            audioManager.clearCommunicationDevice()
                        } else {
                            @Suppress("DEPRECATION")
                            audioManager.stopBluetoothSco()
                            @Suppress("DEPRECATION")
                            audioManager.isBluetoothScoOn = false
                            @Suppress("DEPRECATION")
                            audioManager.isSpeakerphoneOn = savedSpeakerphone
                        }
                    }
                    runCatching { audioManager.mode = savedAudioMode }
                    lastTeardownElapsedMs = SystemClock.elapsedRealtime()
                } else if (activeEngine === this) {
                    activeEngine = null
                }
                // Always abandon this engine's AudioFocusRequest so a superseded engine never leaks focus.
                runCatching { abandonAudioFocus() }
            }
        }
    }

    private fun requestAudioFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .setWillPauseWhenDucked(false)
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    companion object {
        private const val TAG = "RoamttyVoice"
        private const val MAX_SPK_QUEUE = 128
        private const val SILENCE_DECAY_NS = 350_000_000L
        private const val ZERO_READ_SLEEP_MS = 20L
        private const val MAX_CONSECUTIVE_ZERO_READS = 50
        private const val BT_REBIND_SETTLE_MS = 220L
        private const val BT_ROUTE_VERIFY_DELAY_MS = 450L
        private const val BT_ROUTE_BOUNCE_MS = 150L
        private const val MAX_BT_DROP_RETRIES = 2

        /** Serializes global [AudioManager] mode/focus/routing across rapid reconnects so an old call never clobbers a new one. */
        private val audioSystemLock = Any()
        private var activeAudioOwner: Any? = null
        private var activeEngine: VoiceAudioEngine? = null
        private var savedAudioMode: Int = AudioManager.MODE_NORMAL
        private var savedSpeakerphone: Boolean = false
        private var lastTeardownElapsedMs: Long = 0L

        private val BLUETOOTH_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
        private val BLUETOOTH_ROUTED_TYPES = buildSet {
            addAll(BLUETOOTH_TYPES)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(AudioDeviceInfo.TYPE_HEARING_AID)
            }
        }
        private val WIRED_HEADSET_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
        }

        /** Single source of truth for Bluetooth communication audio availability across the app. */
        fun isBluetoothAvailable(context: Context): Boolean = runCatching {
            isBluetoothAvailable(context.getSystemService(AudioManager::class.java))
        }.getOrDefault(false)

        private fun isBluetoothAvailable(audioManager: AudioManager): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return audioManager.availableCommunicationDevices.any { it.type in BLUETOOTH_TYPES }
            }
            return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in BLUETOOTH_TYPES }
        }
    }
}
