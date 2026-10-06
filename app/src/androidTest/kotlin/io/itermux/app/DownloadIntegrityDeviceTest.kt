package io.itermux.app

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.Host
import io.itermux.app.session.DownloadPhase
import io.itermux.app.session.TerminalConnection
import io.itermux.app.session.TransferStatus
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Start sftp-download-server.py on an adb-reversed port and pass sftpIntegrityPort to the runner. */
@RunWith(AndroidJUnit4::class)
class DownloadIntegrityDeviceTest {
    private fun transfer(path: String, verify: (TransferStatus, Int) -> Unit) = runBlocking {
        val port = InstrumentationRegistry.getArguments().getString("sftpIntegrityPort")?.toIntOrNull()
        assumeTrue("Requires sftpIntegrityPort and the isolated SFTP fixture", port != null)
        assumeTrue("MediaStore Downloads requires API 29", Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val destination = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "download-integrity-${UUID.randomUUID()}.bin")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            }))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val pins = object : PinStore {
            private var accepted: HostPin? = null
            override suspend fun get(endpoint: String) = accepted
            override suspend fun putIfAbsent(pin: HostPin) = accepted ?: pin.also { accepted = it }
        }
        val session = TerminalConnection(Host(name = "Download integrity", address = "127.0.0.1", port = port!!,
            username = "test", mode = "SHELL"), SessionMode.SHELL, Credentials("fixture-only".toByteArray()), pins, scope)
        val prompts = scope.launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(15_000) {
                session.state.first { it.phase == Phase.CONNECTED || !it.active }
            }.phase)
            withContext(Dispatchers.Main) { session.transfers.download(context, session, destination, path) }
            val result = withTimeout(15_000) { session.transfers.status.first { !it.running && it.result != null } }
            val remaining = resolver.query(destination, null, null, null, null)!!.use { it.count }
            verify(result, remaining)
            if (remaining != 0) assertEquals(result.bytes, resolver.openInputStream(destination)!!.use { it.readBytes().size.toLong() })
        } finally {
            prompts.cancel()
            withContext(Dispatchers.Main) { session.disconnect() }
            scope.cancel()
            runCatching { resolver.delete(destination, null, null) }
        }
    }

    @Test fun truncatedDownloadFailsKeepsActualProgressAndDeletesThePartialDocument() = transfer("/short") { status, remaining ->
        assertEquals(false, status.succeeded)
        assertEquals(DownloadPhase.FAILED, status.downloads.single().phase)
        assertEquals(32L, status.bytes)
        assertEquals(100L, status.total)
        assertNull(status.downloads.single().localPath)
        assertEquals(0, remaining)
    }

    @Test fun dynamicZeroStatStreamCompletesWithItsActualBytesAndUnknownTotal() = transfer("/dynamic") { status, remaining ->
        assertEquals(true, status.succeeded)
        assertEquals(DownloadPhase.COMPLETE, status.downloads.single().phase)
        assertEquals(32L, status.bytes)
        assertEquals(-1L, status.total)
        assertNotNull(status.downloads.single().localPath)
        assertEquals(1, remaining)
    }
}
