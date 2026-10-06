package io.itermux.ssh

import com.jcraft.jsch.JSch
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real JSch transfers: the server gives normal protocol EOF before or after its advertised size. */
class SftpDownloadIntegrityTest {
    private fun fixture(block: suspend (SftpOperations, AtomicBoolean) -> Unit) = runBlocking {
        val script = File(System.getProperty("itermux.root"), "ssh-core/src/test/resources/sftp-download-server.py")
        val process = ProcessBuilder("python3", "-u", script.path)
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        var ssh: com.jcraft.jsch.Session? = null
        try {
            val line = withContext(Dispatchers.IO) { process.inputStream.bufferedReader().readLine() }
            assumeTrue(line.orEmpty(), line?.startsWith("SKIP:") != true)
            val port = requireNotNull(line?.toIntOrNull()) { "SFTP fixture failed: $line" }
            val connected = JSch().getSession("test", "127.0.0.1", port).also {
                ssh = it
                // Trust only this disposable loopback fixture; production trust is untouched.
                it.setConfig("StrictHostKeyChecking", "no")
                it.setPassword("fixture-only")
                it.connect(3000)
            }
            val stopped = AtomicBoolean(false)
            withTimeout(10_000) { block(SftpOperations({ connected }, stopped::get, 1000), stopped) }
        } finally {
            ssh?.disconnect()
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    @Test fun normalFileReportsOnlySuccessfullyWrittenBytes() = fixture { sftp, _ ->
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        var total = -1L
        sftp.download("/normal", output, { progress += it }) { total = it }
        assertEquals(32L, total)
        assertEquals(32L, progress.last())
        assertArrayEquals(ByteArray(32) { 'x'.code.toByte() }, output.toByteArray())
    }

    @Test fun normalProtocolEofCannotCompleteATruncatedFile() = fixture { sftp, _ ->
        val output = ByteArrayOutputStream()
        var received = 0L
        var total = -1L
        try {
            sftp.download("/short", output, { received = it }) { total = it }
            fail("Early protocol EOF must fail a known-size ordinary file")
        } catch (e: IOException) {
            assertEquals("远端文件大小已变化或下载不完整，请重试。", e.message)
        }
        assertEquals(100L, total)
        assertEquals(32L, received)
        assertEquals(32, output.size())
    }

    @Test fun growingOrdinaryFileAlsoFailsItsSizeContract() = fixture { sftp, _ ->
        val output = ByteArrayOutputStream()
        var received = 0L
        try {
            sftp.download("/grown", output, { received = it })
            fail("A file that grew after stat must not be reported as complete")
        } catch (_: IOException) { }
        assertEquals(32L, received)
        assertEquals(32, output.size())
    }

    @Test fun emptyDynamicSpecialAndMissingSizeStreamsUseUnknownTotals() = fixture { sftp, _ ->
        for (path in listOf("/empty", "/dynamic", "/special", "/unknown")) {
            val output = ByteArrayOutputStream()
            val progress = mutableListOf<Long>()
            var total = Long.MIN_VALUE
            sftp.download(path, output, { progress += it }) { total = it }
            val actual = if (path == "/empty") 0L else 32L
            assertEquals(path, -1L, total)
            assertEquals(path, actual, progress.last())
            assertEquals(path, actual, output.size().toLong())
        }
    }

    @Test fun stoppedMonitorCannotReturnACompletedDownload() = fixture { sftp, stopped ->
        try {
            sftp.download("/normal", ByteArrayOutputStream(), { stopped.set(true) })
            fail("A stopped transfer must throw, even if JSch returns normally")
        } catch (_: CancellationException) { }
    }
}
