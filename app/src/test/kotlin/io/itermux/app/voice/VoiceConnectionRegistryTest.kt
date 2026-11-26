package io.itermux.app.voice

import io.itermux.app.data.Host
import io.itermux.app.login.PendingPrompt
import io.itermux.desktop.*
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VoiceConnectionRegistryTest {

    private fun testHost(id: String = "host-1") = Host(
        id = id,
        name = "test-mac",
        address = "127.0.0.1",
        username = "tester",
    )

    private class FakeAudioBackend(
        private val startMicOk: Boolean = true,
        private val releaseDelayMs: Long = 0L,
        private val events: MutableList<String>? = null,
    ) : VoiceAudioBackend {
        override var muted: Boolean = false
        val released = AtomicBoolean(false)
        override fun isBluetoothAvailable(): Boolean = false
        override fun beginSession(route: AudioRoute) { events?.add("engine.beginSession") }
        override fun applyRoute(route: AudioRoute) {}
        override fun startMic(streamId: Long, sampleRate: Int, channels: Int): Boolean {
            events?.add("engine.startMic:$streamId")
            return startMicOk
        }
        override fun stopMic(streamId: Long?) { events?.add("engine.stopMic") }
        override fun startSpeaker(streamId: Long, sampleRate: Int, channels: Int): String? = null
        override fun writeSpeaker(streamId: Long, pcm: ByteArray, offset: Int, length: Int) {}
        override fun clearSpeaker(streamId: Long) {}
        override fun stopSpeaker(streamId: Long?) {}
        override fun release() {
            if (releaseDelayMs > 0) Thread.sleep(releaseDelayMs)
            events?.add("engine.release")
            released.set(true)
        }
    }

    /** Bidirectional piped RemoteStream that records incoming client frames and detects close ordering. */
    private class PipedRemoteStream(
        private val events: MutableList<String>? = null,
    ) : RemoteStream {
        val serverToClient = PipedOutputStream()
        override val input: InputStream = PipedInputStream(serverToClient, 64 * 1024)

        val clientToServer = PipedInputStream(64 * 1024)
        private val rawOut = PipedOutputStream(clientToServer)
        val receivedFrames = CopyOnWriteArrayList<Frame>()
        val shutdownReceived = CountDownLatch(1)

        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) { rawOut.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { rawOut.write(b, off, len) }
            override fun flush() { rawOut.flush() }
            override fun close() { rawOut.close() }
        }
        override val errors: InputStream = ByteArrayInputStream(ByteArray(0))
        @Volatile override var closed: Boolean = false
        @Volatile override var exitStatus: Int? = null

        private val reader = Thread({
            try {
                while (true) {
                    val f = DesktopProtocol.read(clientToServer) ?: break
                    receivedFrames.add(f)
                    if (f.type == DesktopProtocol.CONFIG) {
                        val json = runCatching { JSONObject(String(f.payload, Charsets.UTF_8)) }.getOrNull()
                        if (json?.optBoolean("shutdown") == true) {
                            events?.add("server.receivedShutdown")
                            shutdownReceived.countDown()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "test-voice-server-rx").apply { isDaemon = true; start() }

        fun sendLine(line: String) {
            serverToClient.write("$line\n".toByteArray(Charsets.UTF_8))
            serverToClient.flush()
        }

        fun sendFrame(type: Int, payload: ByteArray) {
            serverToClient.write(DesktopProtocol.frame(type, payload))
            serverToClient.flush()
        }

        fun closeServerStdout(status: Int = 0) {
            exitStatus = status
            runCatching { serverToClient.close() }
        }

        override fun close() {
            if (!closed) {
                closed = true
                events?.add("stream.close")
            }
            runCatching { serverToClient.close() }
            runCatching { rawOut.close() }
            runCatching { input.close() }
            runCatching { clientToServer.close() }
        }
    }

    private fun helloJson(port: Int = 24713, session: String = "sess-1"): String =
        """{"proto":1,"ver":"0.9.9","os":"linux","mode":"voice","mic_port":$port,"session":"$session","resume_token":"tok123","codecs":["pcm16"]}"""

    private fun le(size: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

    @Test fun hangupSendsShutdownBeforeStreamCloseAndEngineRelease() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val stream = PipedRemoteStream(events)
        val readyLatch = CountDownLatch(1)
        val endedLatch = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val audio = FakeAudioBackend(releaseDelayMs = 80L, events = events)

        val remote = object : RemoteHost {
            override suspend fun probePlugins(): Set<HostPlugin>? = null
            override suspend fun probeDesktop(): HostDesktop = HostDesktop("yes", "Linux x86_64", "roamtty-desktop 0.9.9 proto 1")
            override suspend fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int = 0
            override suspend fun runCommand(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int = 0
            override suspend fun openStream(command: String): RemoteStream {
                stream.sendLine(helloJson())
                return stream
            }
        }
        val transport = fakeTransport(remote)
        val conn = VoiceConnection(
            host = testHost(),
            sessionId = "s-1",
            scope = scope,
            password = "secret",
            passphrase = "",
            tmuxSessions = { emptyList() },
            onReady = { _, _ -> readyLatch.countDown(); VoiceReadyOutcome.INJECTED },
            onEnded = { endedLatch.countDown() },
            transport = transport,
            engineFactory = { _, _, _, _, _, _ -> audio },
            shutdownGraceMs = 300L,
            mainDispatcher = Dispatchers.Unconfined,
        )

        // Close server stdout as soon as server receives shutdown: true (tests early EOF exit too!)
        val eofResponder = Thread {
            if (stream.shutdownReceived.await(3, TimeUnit.SECONDS)) {
                stream.closeServerStdout(0)
            }
        }.apply { isDaemon = true; start() }

        conn.start(VoiceMode.LIVE, AudioRoute.SPEAKER)
        assertTrue("onReady must fire", readyLatch.await(3, TimeUnit.SECONDS))
        assertTrue(conn.startKeySent)

        conn.hangup()
        assertTrue("server must receive CONFIG {shutdown:true}", stream.shutdownReceived.await(2, TimeUnit.SECONDS))
        assertTrue("connection must end", endedLatch.await(3, TimeUnit.SECONDS))
        eofResponder.join(1000)
        scope.cancel()

        val shutdownIdx = events.indexOf("server.receivedShutdown")
        val closeIdx = events.indexOf("stream.close")
        val releaseIdx = events.indexOf("engine.release")
        assertTrue("events=$events", shutdownIdx >= 0 && closeIdx > shutdownIdx)
        assertTrue("engine.release must not block before shutdown arrives: $events", releaseIdx > shutdownIdx)
        assertEquals(0, conn.currentSshQueued)
    }

    @Test fun missedSshPingsFlagNetworkPoor() = runBlocking {
        val stream = PipedRemoteStream()
        val readyLatch = CountDownLatch(1)
        val endedLatch = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val remote = object : RemoteHost {
            override suspend fun probePlugins(): Set<HostPlugin>? = null
            override suspend fun probeDesktop(): HostDesktop = HostDesktop("yes", "Linux x86_64", "roamtty-desktop 0.9.9 proto 1")
            override suspend fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int = 0
            override suspend fun runCommand(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int = 0
            override suspend fun openStream(command: String): RemoteStream {
                stream.sendLine(helloJson())
                return stream
            }
        }
        val conn = VoiceConnection(
            host = testHost(),
            sessionId = "s-poor",
            scope = scope,
            password = "",
            passphrase = "",
            tmuxSessions = { emptyList() },
            onReady = { _, _ -> readyLatch.countDown(); VoiceReadyOutcome.INJECTED },
            onEnded = { endedLatch.countDown() },
            transport = fakeTransport(remote),
            engineFactory = { _, _, _, _, _, _ -> FakeAudioBackend() },
            shutdownGraceMs = 80L,
            mainDispatcher = Dispatchers.Unconfined,
        )

        conn.start(VoiceMode.LIVE, AudioRoute.SPEAKER)
        assertTrue(readyLatch.await(3, TimeUnit.SECONDS))
        assertFalse(conn.ui.value.networkPoor)

        // Without server PONG replies, two missed pings mark the link unstable.
        val deadline = System.currentTimeMillis() + 3_000L
        while (!conn.ui.value.networkPoor && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        assertTrue("unanswered pings must flag networkPoor", conn.ui.value.networkPoor)

        conn.hangup()
        stream.closeServerStdout(0)
        assertTrue(endedLatch.await(3, TimeUnit.SECONDS))
        scope.cancel()
        assertFalse(conn.ui.value.networkPoor)
    }

    @Test fun onDestroyScopeCancelStillDeliversShutdownFrame() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val stream = PipedRemoteStream(events)
        val readyLatch = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val remote = object : RemoteHost {
            override suspend fun probePlugins(): Set<HostPlugin>? = null
            override suspend fun probeDesktop(): HostDesktop = HostDesktop("yes", "Linux x86_64", "roamtty-desktop 0.9.9 proto 1")
            override suspend fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int = 0
            override suspend fun runCommand(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int = 0
            override suspend fun openStream(command: String): RemoteStream {
                stream.sendLine(helloJson())
                return stream
            }
        }
        val registry = VoiceRegistry(
            scope = scope,
            tmuxSessionsForHost = { emptyList() },
            onChange = {},
            connectionFactory = { host, sid, pw, pp, tmux, onReady, onEnded, isOwner ->
                VoiceConnection(
                    host = host, sessionId = sid, scope = scope, password = pw, passphrase = pp,
                    tmuxSessions = tmux, onReady = onReady, onEnded = onEnded, isCurrentOwner = isOwner,
                    transport = fakeTransport(remote),
                    engineFactory = { _, _, _, _, _, _ -> FakeAudioBackend(events = events) },
                    shutdownGraceMs = 200L, mainDispatcher = Dispatchers.Unconfined,
                )
            },
        )
        registry.start(testHost(), "s-destroy", "", "", VoiceMode.LIVE, AudioRoute.SPEAKER) { _, _ ->
            readyLatch.countDown()
            VoiceReadyOutcome.INJECTED
        }
        assertTrue(readyLatch.await(3, TimeUnit.SECONDS))

        // Simulate SessionService.onDestroy(): closeAll() immediately followed by scope.cancel().
        registry.closeAll()
        scope.cancel()

        assertTrue("shutdown frame must still reach host after scope.cancel()", stream.shutdownReceived.await(2, TimeUnit.SECONDS))
        stream.closeServerStdout(0)
    }

    @Test fun registryEmitsFailureNotificationEvenWhenConnectionRemovedImmediately() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val receivedFailures = CopyOnWriteArrayList<VoiceFailure>()
        val failureLatch = CountDownLatch(1)

        val remote = object : RemoteHost {
            override suspend fun probePlugins(): Set<HostPlugin>? = null
            override suspend fun probeDesktop(): HostDesktop? = null // triggers deterministic failure
            override suspend fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int = 0
            override suspend fun runCommand(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int = 0
        }
        val registry = VoiceRegistry(
            scope = scope,
            tmuxSessionsForHost = { emptyList() },
            onChange = {},
            connectionFactory = { host, sid, pw, pp, tmux, onReady, onEnded, isOwner ->
                VoiceConnection(
                    host = host, sessionId = sid, scope = scope, password = pw, passphrase = pp,
                    tmuxSessions = tmux, onReady = onReady, onEnded = onEnded, isCurrentOwner = isOwner,
                    transport = fakeTransport(remote),
                    engineFactory = { _, _, _, _, _, _ -> FakeAudioBackend() },
                    mainDispatcher = Dispatchers.Unconfined,
                )
            },
        )
        val collector = launch(Dispatchers.Unconfined) {
            registry.failures.collect {
                receivedFailures += it
                failureLatch.countDown()
            }
        }
        registry.start(testHost("h-fail"), "s-fail", "pw", "", VoiceMode.LIVE, AudioRoute.SPEAKER) { _, _ ->
            VoiceReadyOutcome.INJECTED
        }
        assertTrue("failure SharedFlow must deliver error message", failureLatch.await(3, TimeUnit.SECONDS))
        val f = receivedFailures.single()
        assertEquals("h-fail", f.hostId)
        assertEquals("s-fail", f.sessionId)
        assertTrue(f.message.isNotBlank())
        // Registry still cleaned up the failed entry
        assertNull(registry["h-fail"])
        collector.cancel()
        scope.cancel()
    }

    @Test fun protoUnsupportedInstallsOnlyOnceAndPersistsDesktopPlugin() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val installCount = AtomicInteger(0)
        val persistedCount = AtomicInteger(0)
        val failureLatch = CountDownLatch(1)

        val remote = object : RemoteHost {
            override suspend fun probePlugins(): Set<HostPlugin>? = null
            override suspend fun probeDesktop(): HostDesktop = HostDesktop("yes", "Linux x86_64", "roamtty-desktop 0.9.9 proto 1")
            override suspend fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int = 0
            override suspend fun runCommand(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int = 0
            override suspend fun openStream(command: String): RemoteStream = PipedRemoteStream().apply {
                sendLine("""{"error":"proto_unsupported","detail":"need proto 2"}""")
                closeServerStdout(1)
            }
        }
        val transport = fakeTransport(
            remote = remote,
            onInstall = { installCount.incrementAndGet(); null },
            onMarkInstalled = { persistedCount.incrementAndGet() },
        )
        val conn = VoiceConnection(
            host = testHost(),
            sessionId = "s-proto",
            scope = scope,
            password = "",
            passphrase = "",
            tmuxSessions = { emptyList() },
            onReady = { _, _ -> VoiceReadyOutcome.INJECTED },
            onEnded = { failureLatch.countDown() },
            transport = transport,
            engineFactory = { _, _, _, _, _, _ -> FakeAudioBackend() },
            mainDispatcher = Dispatchers.Unconfined,
        )
        conn.start(VoiceMode.LIVE, AudioRoute.SPEAKER)
        assertTrue(failureLatch.await(3, TimeUnit.SECONDS))
        assertEquals("proto_unsupported must only reinstall once", 1, installCount.get())
        assertEquals("successful headless install must persist HostPlugin.DESKTOP", 1, persistedCount.get())
        assertTrue(conn.ui.value.phase is VoiceCallPhase.Failed)
        scope.cancel()
    }

    @Test fun closingDuringEnvInjectSkipsOnReadyAndRapidReconnectPreventsStaleClear() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val injectEntered = CountDownLatch(1)
        val releaseInject = CountDownLatch(1)
        val onReadyCalls = AtomicInteger(0)
        val clearScripts = CopyOnWriteArrayList<String>()
        val endedA = CountDownLatch(1)

        val remoteA = object : RemoteHost {
            override suspend fun probePlugins(): Set<HostPlugin>? = null
            override suspend fun probeDesktop(): HostDesktop = HostDesktop("yes", "Linux x86_64", "roamtty-desktop 0.9.9 proto 1")
            override suspend fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int {
                if ("rm -f" in script) {
                    clearScripts += script
                } else {
                    injectEntered.countDown()
                    releaseInject.await(3, TimeUnit.SECONDS)
                }
                return 0
            }
            override suspend fun runCommand(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int = 0
            override suspend fun openStream(command: String): RemoteStream = PipedRemoteStream().apply {
                sendLine(helloJson())
            }
        }
        val streamB = PipedRemoteStream()
        val remoteB = object : RemoteHost {
            override suspend fun probePlugins(): Set<HostPlugin>? = null
            override suspend fun probeDesktop(): HostDesktop = HostDesktop("yes", "Linux x86_64", "roamtty-desktop 0.9.9 proto 1")
            override suspend fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int = 0
            override suspend fun runCommand(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int = 0
            override suspend fun openStream(command: String): RemoteStream {
                streamB.sendLine(helloJson(port = 24714))
                return streamB
            }
        }
        val remotes = ArrayDeque(listOf(remoteA, remoteB))
        val readyB = CountDownLatch(1)
        val registry = VoiceRegistry(
            scope = scope,
            tmuxSessionsForHost = { emptyList() },
            onChange = {},
            connectionFactory = { host, sid, pw, pp, tmux, onReady, onEnded, isOwner ->
                val r = remotes.removeFirst()
                val isA = (r === remoteA)
                VoiceConnection(
                    host = host, sessionId = sid, scope = scope, password = pw, passphrase = pp,
                    tmuxSessions = tmux, onReady = onReady,
                    onEnded = { c -> onEnded(c); if (isA) endedA.countDown() },
                    isCurrentOwner = isOwner,
                    transport = fakeTransport(r),
                    engineFactory = { _, _, _, _, _, _ -> FakeAudioBackend() },
                    shutdownGraceMs = 80L, mainDispatcher = Dispatchers.Unconfined,
                )
            },
        )
        val host = testHost("h-rapid")
        val connA = registry.start(host, "s-a", "", "", VoiceMode.LIVE, AudioRoute.SPEAKER) { _, _ ->
            onReadyCalls.incrementAndGet()
            VoiceReadyOutcome.INJECTED
        }
        assertTrue(injectEntered.await(3, TimeUnit.SECONDS))

        // Start a replacement connection B on the same host while A is mid-inject.
        registry.start(host, "s-b", "", "", VoiceMode.LIVE, AudioRoute.SPEAKER) { _, _ ->
            readyB.countDown()
            VoiceReadyOutcome.INJECTED
        }
        releaseInject.countDown()

        assertTrue(endedA.await(3, TimeUnit.SECONDS))
        assertTrue(readyB.await(3, TimeUnit.SECONDS))
        assertEquals("A must not call onReady after closing during inject", 0, onReadyCalls.get())
        assertFalse(connA.startKeySent)
        assertTrue("superseded A must not run VoiceEnvironment.clear() over B: $clearScripts", clearScripts.isEmpty())

        // closeBySession only closes owning session
        registry.closeBySession("other-session")
        assertNotNull(registry[host.id])
        registry.closeBySession("s-b")
        assertNull(registry[host.id])
        streamB.closeServerStdout(0)
        scope.cancel()
    }

    @Test fun startMicFailureFailsCleanly() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stream = PipedRemoteStream()
        val readyLatch = CountDownLatch(1)
        val endedLatch = CountDownLatch(1)
        val remote = object : RemoteHost {
            override suspend fun probePlugins(): Set<HostPlugin>? = null
            override suspend fun probeDesktop(): HostDesktop = HostDesktop("yes", "Linux x86_64", "roamtty-desktop 0.9.9 proto 1")
            override suspend fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int = 0
            override suspend fun runCommand(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int = 0
            override suspend fun openStream(command: String): RemoteStream {
                stream.sendLine(helloJson())
                return stream
            }
        }
        val conn = VoiceConnection(
            host = testHost(),
            sessionId = "s-micfail",
            scope = scope,
            password = "",
            passphrase = "",
            tmuxSessions = { emptyList() },
            onReady = { _, _ -> readyLatch.countDown(); VoiceReadyOutcome.INJECTED },
            onEnded = { endedLatch.countDown() },
            transport = fakeTransport(remote),
            engineFactory = { _, _, _, _, _, _ -> FakeAudioBackend(startMicOk = false) },
            shutdownGraceMs = 80L,
            mainDispatcher = Dispatchers.Unconfined,
        )
        conn.start(VoiceMode.LIVE, AudioRoute.SPEAKER)
        assertTrue(readyLatch.await(3, TimeUnit.SECONDS))

        stream.sendFrame(VoiceProtocol.MIC_START, le(9) { putInt(1); putInt(16_000); put(1) })
        assertTrue(endedLatch.await(3, TimeUnit.SECONDS))
        assertTrue("startMic returning false must fail cleanly", conn.ui.value.phase is VoiceCallPhase.Failed)
        scope.cancel()
    }

    @Test fun sshMicAudioQueueCapsAtTwentyDroppingOldestWhileKeepingReliableFrames() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writeBlocked = CountDownLatch(1)
        val releaseWrites = CountDownLatch(1)
        val firstWriteBlocked = AtomicBoolean(false)
        val rawIn = PipedInputStream(64 * 1024)
        val rawOut = PipedOutputStream(rawIn)
        val serverToClient = PipedOutputStream()
        val clientIn = PipedInputStream(serverToClient, 64 * 1024)
        val receivedMicSeqs = CopyOnWriteArrayList<Int>()
        val receivedSpkAck = CountDownLatch(1)

        val stream = object : RemoteStream {
            override val input: InputStream = clientIn
            override val output: OutputStream = object : OutputStream() {
                override fun write(b: Int) { rawOut.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (firstWriteBlocked.compareAndSet(false, true)) {
                        writeBlocked.countDown()
                        releaseWrites.await(3, TimeUnit.SECONDS)
                    }
                    rawOut.write(b, off, len)
                }
                override fun flush() { rawOut.flush() }
                override fun close() { rawOut.close() }
            }
            override val errors: InputStream = ByteArrayInputStream(ByteArray(0))
            @Volatile override var closed: Boolean = false
            @Volatile override var exitStatus: Int? = null
            override fun close() {
                closed = true
                runCatching { serverToClient.close() }
                runCatching { rawOut.close() }
                runCatching { clientIn.close() }
                runCatching { rawIn.close() }
            }
        }
        val serverRx = Thread {
            try {
                while (true) {
                    val f = DesktopProtocol.read(rawIn) ?: break
                    when (f.type) {
                        VoiceProtocol.MIC_AUDIO -> {
                            val seq = ByteBuffer.wrap(f.payload, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                            receivedMicSeqs += seq
                        }
                        VoiceProtocol.SPK_ACK -> receivedSpkAck.countDown()
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        var capturedOnMicChunk: ((Long, ByteArray, Int, Int) -> Unit)? = null
        val readyLatch = CountDownLatch(1)
        val remote = object : RemoteHost {
            override suspend fun probePlugins(): Set<HostPlugin>? = null
            override suspend fun probeDesktop(): HostDesktop = HostDesktop("yes", "Linux x86_64", "roamtty-desktop 0.9.9 proto 1")
            override suspend fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int = 0
            override suspend fun runCommand(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int = 0
            override suspend fun openStream(command: String): RemoteStream {
                serverToClient.write("${helloJson()}\n".toByteArray(Charsets.UTF_8))
                serverToClient.flush()
                return stream
            }
        }
        val conn = VoiceConnection(
            host = testHost(),
            sessionId = "s-cap",
            scope = scope,
            password = "",
            passphrase = "",
            tmuxSessions = { emptyList() },
            onReady = { _, _ -> readyLatch.countDown(); VoiceReadyOutcome.INJECTED },
            onEnded = {},
            transport = fakeTransport(remote),
            engineFactory = { onMicChunk, _, _, _, _, _ ->
                capturedOnMicChunk = onMicChunk
                FakeAudioBackend()
            },
            shutdownGraceMs = 80L,
            mainDispatcher = Dispatchers.Unconfined,
        )
        conn.start(VoiceMode.LIVE, AudioRoute.SPEAKER)
        assertTrue(readyLatch.await(3, TimeUnit.SECONDS))

        // First chunk blocks the writer so subsequent chunks queue up.
        fun pcmWithSeq(seq: Int): ByteArray = ByteArray(VoiceProtocol.MIC_FRAME_CHUNK_BYTES).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(0, seq.toShort())
        }
        capturedOnMicChunk!!.invoke(1L, pcmWithSeq(0), 0, VoiceProtocol.MIC_FRAME_CHUNK_BYTES)
        assertTrue(writeBlocked.await(3, TimeUnit.SECONDS))

        // Enqueue 35 mic chunks + 1 reliable SPK_START->SPK_ACK while writer is blocked.
        for (i in 1..35) {
            capturedOnMicChunk!!.invoke(1L, pcmWithSeq(i), 0, VoiceProtocol.MIC_FRAME_CHUNK_BYTES)
        }
        assertTrue("queued mic frames must stay capped at ${VoiceConnection.MIC_SSH_CAP}, got ${conn.currentSshQueued}",
            conn.currentSshQueued <= VoiceConnection.MIC_SSH_CAP)

        // Trigger a reliable SPK_ACK while mic queue is full.
        serverToClient.write(DesktopProtocol.frame(VoiceProtocol.SPK_START, le(9) { putInt(9); putInt(24_000); put(1) }))
        serverToClient.flush()
        delay(30)
        releaseWrites.countDown()

        assertTrue("reliable SPK_ACK must never be dropped", receivedSpkAck.await(3, TimeUnit.SECONDS))
        delay(50)
        // Oldest mic chunks (1..15) were dropped; newest chunk (35) survived.
        assertTrue("newest mic frame 35 must arrive: $receivedMicSeqs", 35 in receivedMicSeqs)
        assertFalse("oldest overflowed mic frame 1 must be dropped: $receivedMicSeqs", 1 in receivedMicSeqs)

        conn.hangup()
        delay(120)
        stream.close()
        serverRx.join(1000)
        scope.cancel()
    }

    private fun fakeTransport(
        remote: RemoteHost,
        onInstall: suspend () -> String? = { null },
        onMarkInstalled: suspend () -> Unit = {},
    ): VoiceSessionTransport = object : VoiceSessionTransport {
        override val manifestVersion: String = "0.9.9"
        override val udpHostOverride: String = ""
        override val testLoss: Double = 0.0
        override fun targetFor(uname: String): Pair<String, PluginManifest.Target> =
            "linux-x86_64" to PluginManifest.Target("roamtty-desktop.gz", "0".repeat(64), 1024L)
        override suspend fun install(remote: RemoteHost, target: String, build: PluginManifest.Target, onLine: (String) -> Unit): String? =
            onInstall()
        override suspend fun administer(
            host: Host,
            password: String,
            passphrase: String,
            pending: MutableStateFlow<PendingPrompt?>,
            onHandle: (disconnect: () -> Unit) -> Unit,
            block: suspend (RemoteHost) -> Unit,
        ): VoiceSessionTransport.AdministerResult {
            onHandle {}
            block(remote)
            return VoiceSessionTransport.AdministerResult(phase = Phase.CONNECTED)
        }
        override suspend fun markDesktopPluginInstalled(host: Host) { onMarkInstalled() }
    }
}
