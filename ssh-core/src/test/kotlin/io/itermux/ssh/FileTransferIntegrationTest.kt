package io.itermux.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Real SFTP round trip against scripts/ssh-key-echo.py, enabled explicitly for local validation. */
class FileTransferIntegrationTest {
    @Test fun uploadDownloadAndExistingTargetProtection() = runBlocking {
        assumeTrue("Start the isolated SSH/SFTP fixture first", System.getenv("ITERMUX_SFTP_FIXTURE") == "1")
        val target = Target("127.0.0.1", 22228, "itermux_test", AuthMethod.PASSWORD, SessionMode.SHELL)
        val ssh = JschSession(target, Credentials("test-only".toByteArray()), MemoryPins(),
            { prompt -> if (prompt is Prompt.VerifyHost) listOf("trust") else listOf("test-only") })
        val connection = launch { ssh.connect {} }
        val remote = "roundtrip-${UUID.randomUUID()}.bin"
        val content = ByteArray(768 * 1024) { (it * 37).toByte() }
        try {
            assertEquals(Phase.CONNECTED,
                withTimeout(20_000) { ssh.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            var uploaded = 0L
            ssh.upload(remote, ByteArrayInputStream(content)) { uploaded = it }
            assertEquals(content.size.toLong(), uploaded)
            val directory = ssh.listRemoteDirectory()
            assertTrue(directory.path.startsWith("/"))
            assertTrue(directory.entries.none { it.name.isBlank() })
            assertTrue(directory.entries.any { it.name == remote && !it.isDirectory })
            val folder = File(System.getProperty("itermux.root"), ".local/sftp-fixture/browse-${UUID.randomUUID()}")
            assertTrue(folder.mkdir())
            val otherFolder = File(System.getProperty("itermux.root"), ".local/sftp-fixture/browse-${UUID.randomUUID()}")
            assertTrue(otherFolder.mkdir())
            try {
                assertTrue(ssh.listRemoteDirectory().entries.any { it.name == folder.name && it.isDirectory })
                val nested = ssh.listRemoteDirectory("${directory.path.trimEnd('/')}/${folder.name}")
                assertTrue(nested.path.endsWith("/${folder.name}"))
                assertTrue(nested.entries.isEmpty())
                val firstPath = "${nested.path}/same-name.bin"
                val secondPath = "${directory.path.trimEnd('/')}/${otherFolder.name}/same-name.bin"
                ssh.upload(firstPath, ByteArrayInputStream(content))
                ssh.upload(secondPath, ByteArrayInputStream("second directory".toByteArray()))
                val secondCopy = ByteArrayOutputStream()
                ssh.download(secondPath, secondCopy)
                assertEquals("second directory", secondCopy.toString(Charsets.UTF_8.name()))
                try {
                    ssh.upload(firstPath, ByteArrayInputStream("replacement".toByteArray()))
                    fail("The same target file must still reject overwrite")
                } catch (_: java.nio.file.FileAlreadyExistsException) { }
                var downloadSize = -1L
                ssh.download(firstPath, ByteArrayOutputStream(), onSize = { downloadSize = it })
                assertEquals(content.size.toLong(), downloadSize)
                assertTrue(File(folder, "same-name.bin").delete())
                assertTrue(File(otherFolder, "same-name.bin").delete())
            } finally {
                assertTrue(folder.delete())
                assertTrue(otherFolder.delete())
            }
            val downloaded = ByteArrayOutputStream()
            var received = 0L
            ssh.download(remote, downloaded) { received = it }
            assertEquals(content.size.toLong(), received)
            assertArrayEquals(content, downloaded.toByteArray())
            try {
                ssh.upload(remote, ByteArrayInputStream("replacement".toByteArray()))
                fail("Upload must refuse an existing file")
            } catch (_: java.nio.file.FileAlreadyExistsException) { }
            val afterRejectedUpload = ByteArrayOutputStream()
            ssh.download(remote, afterRejectedUpload)
            assertArrayEquals(content, afterRejectedUpload.toByteArray())
            val cancelledName = "cancel-${UUID.randomUUID()}.bin"
            val cancelled = launch {
                val owner = coroutineContext[Job]
                runCatching {
                    ssh.upload(cancelledName, ByteArrayInputStream(ByteArray(2 * 1024 * 1024))) {
                        if (it >= 64 * 1024) owner?.cancel()
                    }
                }
            }
            cancelled.join()
            val files = File(System.getProperty("itermux.root"), ".local/sftp-fixture").listFiles().orEmpty()
            assertFalse("Cancelled upload must remove its partial remote file", files.any { it.name.startsWith(cancelledName) })
        } finally {
            ssh.disconnect()
            withTimeout(5000) { connection.join() }
        }
    }
}
