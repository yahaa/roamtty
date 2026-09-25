package io.itermux.app

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FileTransferDeviceTest {
    @Test fun androidDocumentStreamsRoundTripOverSftp() = runBlocking {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val id = UUID.randomUUID().toString()
        fun document(name: String) = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "itermux-test-$id-$name.bin")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            }))
        val source = document("source")
        val destination = document("destination")
        val expected = ByteArray(257 * 1024) { (it * 19).toByte() }
        val ssh = JschSession(Target("127.0.0.1", 22228, "itermux_test", AuthMethod.PASSWORD, SessionMode.SHELL),
            Credentials("test-only".toByteArray()), object : PinStore {
                private var pinned: HostPin? = null
                override suspend fun get(endpoint: String) = pinned
                override suspend fun putIfAbsent(pin: HostPin) = pinned ?: pin.also { pinned = it }
            }, { if (it is Prompt.VerifyHost) listOf("trust") else listOf("test-only") })
        val connection = launch { ssh.connect {} }
        try {
            resolver.openOutputStream(source, "w")!!.use { it.write(expected) }
            assertEquals(Phase.CONNECTED,
                withTimeout(20_000) { ssh.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            val remote = "android-$id.bin"
            resolver.openInputStream(source)!!.use { ssh.upload(remote, it) }
            resolver.openOutputStream(destination, "w")!!.use { ssh.download(remote, it) }
            assertArrayEquals(expected, resolver.openInputStream(destination)!!.use { it.readBytes() })
        } finally {
            ssh.disconnect()
            withTimeout(5000) { connection.join() }
            resolver.delete(source, null, null)
            resolver.delete(destination, null, null)
        }
        Unit
    }
}
