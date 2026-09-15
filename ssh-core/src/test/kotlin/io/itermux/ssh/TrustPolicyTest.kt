package io.itermux.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class MemoryPins : PinStore {
    private val pins = mutableMapOf<String, HostPin>()
    private val mutex = Mutex()
    override suspend fun get(endpoint: String) = mutex.withLock { pins[endpoint] }
    override suspend fun putIfAbsent(pin: HostPin) = mutex.withLock { pins.getOrPut(pin.endpoint) { pin } }
}
class TrustPolicyTest {
    private fun pin(key: String) = HostPin("[host]:22", "ssh-ed25519", Base64.getEncoder().encodeToString(key.toByteArray()))
    @Test fun firstUseRequiresApprovalAndRemembersIt() = runBlocking {
        val store = MemoryPins(); val policy = TrustPolicy(store); val pin = pin("a")
        var prompts = 0
        policy.verify(pin) { prompts++; true }
        policy.verify(pin) { prompts++; true }
        assertEquals(1, prompts); assertEquals(pin, store.get(pin.endpoint))
    }
    @Test fun changedKeyNeverPromptsToSilentlyReplace() = runBlocking {
        val store = MemoryPins(); store.putIfAbsent(pin("old"))
        try { TrustPolicy(store).verify(pin("new")) { error("Must not prompt on changed key") }; fail("Expected block") }
        catch (expected: HostKeyChanged) { assertEquals(pin("old"), expected.old) }
        assertEquals(pin("old"), store.get("[host]:22"))
    }
    @Test fun denialDoesNotPersist() = runBlocking {
        val store = MemoryPins()
        try { TrustPolicy(store).verify(pin("a")) { false }; fail() } catch (_: HostKeyRejected) { }
        assertNull(store.get("[host]:22"))
    }
    @Test fun simultaneousFirstUseCannotOverwriteAnotherApproval() = runBlocking {
        val store = MemoryPins(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val loser = async {
            runCatching { TrustPolicy(store).verify(pin("loser")) { entered.complete(Unit); release.await(); true } }.exceptionOrNull()
        }
        entered.await()
        TrustPolicy(store).verify(pin("winner")) { true }
        release.complete(Unit)
        assertTrue(loser.await() is HostKeyChanged)
        assertEquals(pin("winner"), store.get("[host]:22"))
    }
    @Test fun canonicalEndpointsIncludePortAndIpv6() {
        assertEquals("[example.com]:2222", Target("EXAMPLE.COM.", 2222, "dev", AuthMethod.KEY, SessionMode.SHELL).endpoint)
        assertEquals("[::1]:22", Target("[::1]", 22, "dev", AuthMethod.KEY, SessionMode.SHELL).endpoint)
        assertThrows(IllegalArgumentException::class.java) { Target("ssh://host", 22, "dev", AuthMethod.KEY, SessionMode.SHELL) }
        assertThrows(IllegalArgumentException::class.java) { Target("host", 0, "dev", AuthMethod.KEY, SessionMode.SHELL) }
    }
    @Test fun fingerprintUsesSha256WithoutPadding() {
        assertEquals("SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU", fingerprint(byteArrayOf()))
    }
}
