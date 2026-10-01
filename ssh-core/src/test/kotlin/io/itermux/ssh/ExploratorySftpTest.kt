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

/** Regression probes against scripts/ssh-key-echo.py, enabled explicitly. */
class ExploratorySftpTest {
    private fun probe(block: suspend (JschSession, File) -> Unit) = runBlocking {
        assumeTrue(System.getenv("ITERMUX_SFTP_FIXTURE") == "1")
        val root = File(System.getProperty("itermux.root"), ".local/sftp-fixture")
        val folder = File(root, "qa-${UUID.randomUUID()}").apply { mkdirs() }
        val port = System.getenv("ITERMUX_QA_SFTP_PORT")?.toInt() ?: 22228
        val ssh = JschSession(Target("127.0.0.1", port, "itermux_test", AuthMethod.PASSWORD, SessionMode.SHELL),
            Credentials("test-only".toByteArray()), MemoryPins(), { listOf("trust") })
        val connection = launch { ssh.connect {} }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) {
                ssh.state.first { it.phase == Phase.CONNECTED || !it.active }
            }.phase)
            block(ssh, folder)
        } finally {
            ssh.disconnect()
            withTimeout(5000) { connection.join() }
            folder.deleteRecursively()
        }
    }

    @Test fun downloadTreatsQuestionMarkAsLiteralFilename() = probe { ssh, folder ->
        File(folder, "report?.txt").writeText("literal file")
        File(folder, "report1.txt").writeText("other file")
        val output = ByteArrayOutputStream()
        ssh.download("${folder.name}/report?.txt", output)
        assertEquals("literal file", output.toString("UTF-8"))
    }

    @Test fun uploadTreatsQuestionMarkAsLiteralFilename() = probe { ssh, folder ->
        val name = "notes?.txt"
        ssh.upload("${folder.name}/$name", ByteArrayInputStream("literal upload".toByteArray()))
        assertEquals("literal upload", File(folder, name).readText())
    }

    @Test fun downloadDoesNotSilentlyReadADifferentBackslashFilename() = probe { ssh, folder ->
        File(folder, "report\\1.txt").writeText("chosen file")
        File(folder, "report1.txt").writeText("WRONG file")
        val output = ByteArrayOutputStream()
        ssh.download("${folder.name}/report\\1.txt", output)
        assertEquals("chosen file", output.toString("UTF-8"))
    }

    @Test fun uploadAcceptsAValid230ByteFilename() = probe { ssh, folder ->
        val name = "a".repeat(226) + ".txt"
        File(folder, name).writeText("filesystem accepts this name")
        assertTrue(File(folder, name).delete())
        ssh.upload("${folder.name}/$name", ByteArrayInputStream("long filename".toByteArray()))
        assertEquals("long filename", File(folder, name).readText())
    }

    @Test fun specialDirectoryAndFilenameRoundTripWithoutOverwritingNeighbors() = probe { ssh, folder ->
        val directory = File(folder, "dir\\?*").apply { mkdir() }
        val name = "notes\\?*.txt"
        val path = "${folder.name}/${directory.name}/$name"
        val content = "literal path at every level".toByteArray()
        ssh.upload(path, ByteArrayInputStream(content))
        val listing = ssh.listRemoteDirectory("${folder.name}/${directory.name}")
        assertEquals("/${folder.name}/${directory.name}", listing.path)
        assertEquals(listOf(name), listing.entries.map { it.name })
        val output = ByteArrayOutputStream()
        ssh.download(path, output)
        assertArrayEquals(content, output.toByteArray())
        try {
            ssh.upload(path, ByteArrayInputStream("overwrite".toByteArray()))
            fail("Existing literal target must be protected")
        } catch (_: java.nio.file.FileAlreadyExistsException) { }
        assertArrayEquals(content, File(directory, name).readBytes())
        assertEquals(listOf(name), directory.list()!!.toList())
    }

    @Test fun specialRelativeDirectoryResolvesToUsablePathForAllFileOperations() = probe { ssh, folder ->
        val directory = File(folder, "dir\\?*").apply { mkdir() }
        File(directory, "history?.txt").writeText("old\nlatest\n")
        val resolved = ssh.listRemoteDirectory("${folder.name}/${directory.name}").path
        assertEquals(listOf("history?.txt"), ssh.listRemoteDirectory(resolved).entries.map { it.name })
        assertEquals("latest\n" to true, ssh.readRemoteText("$resolved/history?.txt", 7))
        ssh.rename("$resolved/history?.txt", "$resolved/renamed*.txt")
        assertEquals("old\nlatest\n" to false, ssh.readRemoteText("$resolved/renamed*.txt"))
        ssh.deleteFile("$resolved/renamed*.txt")
        assertNull(ssh.readRemoteText("$resolved/renamed*.txt"))
        assertTrue(ssh.listRemoteDirectory(resolved).entries.isEmpty())
    }

}
