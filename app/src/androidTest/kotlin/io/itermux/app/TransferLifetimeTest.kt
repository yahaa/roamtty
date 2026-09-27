package io.itermux.app

import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TransferLifetimeTest {
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun realDownloadContinuesAfterRecreationAndCancellationRemovesPartialFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ItermuxApp
        val resolver = context.contentResolver
        val name = "lifetime-${UUID.randomUUID()}.bin"
        val destination = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            }))
        val host = Host(name = "Transfer lifetime", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "SHELL")
        app.database.hosts().forgetPin(host.target().endpoint)
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials("test-only".toByteArray())) }
        val prompts = launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
        val size = 64L * 1024 * 1024
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            // Stream generated data without allocating a giant byte array or requiring a special fixture file.
            val source = object : InputStream() {
                var remaining = size
                override fun read(): Int = if (remaining-- > 0) 42 else -1
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (remaining == 0L) return -1
                    val count = minOf(remaining, length.toLong()).toInt()
                    buffer.fill(42, offset, offset + count); remaining -= count
                    return count
                }
            }
            session.upload(name, source)
            withContext(Dispatchers.Main) { session.transfers.download(context, session, destination, name) }
            withTimeout(15_000) { session.transfers.status.first { it.bytes > 0 } }
            assertTrue(session.transfers.status.value.running)
            activity.recreate()
            assertSame(session, service.sessions.value.single { it.id == session.id })
            val complete = withTimeout(90_000) { session.transfers.status.first { !it.running } }
            assertEquals(tr("传输完成"), complete.result)
            assertEquals(size, complete.bytes)
            withContext(Dispatchers.IO) {
                resolver.openInputStream(destination)!!.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var count = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        assertTrue((0 until read).all { buffer[it] == 42.toByte() })
                        count += read
                    }
                    assertEquals(size, count)
                }
            }
            withContext(Dispatchers.Main) { session.transfers.download(context, session, destination, name) }
            withTimeout(15_000) { session.transfers.status.first { it.bytes > 0 } }
            withContext(Dispatchers.Main) { session.transfers.cancel() }
            val cancelled = withTimeout(15_000) { session.transfers.status.first { !it.running } }
            assertEquals(tr("传输已取消"), cancelled.result)
            assertEquals("No full-name partial file", 0, resolver.query(destination, null, null, null, null)!!.use { it.count })
        } finally {
            prompts.cancel()
            withContext(Dispatchers.Main) { service.close(session.id) }
            runCatching { resolver.delete(destination, null, null) }
            activity.close()
        }
    }
}
