package io.itermux.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class FilePreviewIntegrationTest {
    @Test fun readsBoundedPrefixLiteralPathsAndEmptyFilesOnIndependentChannels() = runBlocking {
        assumeTrue(System.getenv("ITERMUX_SFTP_FIXTURE") == "1")
        val ssh = JschSession(Target("127.0.0.1", 22228, "itermux_test", AuthMethod.PASSWORD, SessionMode.SHELL),
            Credentials("test-only".toByteArray()), MemoryPins(),
            { if (it is Prompt.VerifyHost) listOf("trust") else listOf("test-only") })
        val connection = launch { ssh.connect {} }
        val prefix = "preview-${UUID.randomUUID()}"
        val files = listOf("$prefix-中文 ?*.txt", "$prefix-empty.txt", "$prefix-exact.txt")
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { ssh.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            val content = "start\n" + "中文\n".repeat(60_000) + "tail"
            ssh.upload(files[0], content.byteInputStream())
            val preview = ssh.readFileContent(files[0], TEXT_PREVIEW_BYTES)
            assertEquals(TEXT_PREVIEW_BYTES, preview.bytes.size)
            assertEquals(content.toByteArray().size.toLong(), preview.size)
            assertTrue(preview.truncated)
            assertTrue(preview.decodeText()!!.startsWith("start\n"))
            assertFalse(preview.decodeText()!!.endsWith("tail"))
            ssh.upload(files[1], byteArrayOf().inputStream())
            assertEquals("", ssh.readFileContent(files[1], 32).decodeText())
            ssh.upload(files[2], "1234".byteInputStream())
            assertFalse(ssh.readFileContent(files[2], 4).truncated)
            assertTrue(ssh.readFileContent(files[2], 3).truncated)
            try { ssh.readFileContent("/phone-a", 32); fail("Directories must not be read") }
            catch (_: IllegalArgumentException) { }
            try { ssh.readFileContent("$prefix-missing", 32); fail("Missing files must fail") }
            catch (_: com.jcraft.jsch.SftpException) { }
            assertEquals(Phase.CONNECTED, ssh.state.value.phase)
            assertTrue(ssh.listRemoteDirectory().entries.any { it.name == files[0] })
        } finally {
            files.forEach { runCatching { ssh.deleteFile(it) } }
            ssh.disconnect()
            withTimeout(5000) { connection.join() }
        }
    }
}
