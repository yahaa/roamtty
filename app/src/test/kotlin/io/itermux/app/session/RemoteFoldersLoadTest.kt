package io.itermux.app.session

import io.itermux.ssh.HostPlugin
import io.itermux.ssh.RemoteHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteFoldersLoadTest {
    private class Host(val posixStatus: Int? = 127, val posixLines: List<String> = listOf("sh is not recognized"),
        val nativeStatus: Int = 0) : RemoteHost {
        var commands = 0
        override suspend fun probePlugins(): Set<HostPlugin>? = null
        override suspend fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): Int? {
            posixLines.forEach(onLine)
            return posixStatus
        }
        override suspend fun runCommand(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int {
            commands++
            assertTrue(command.startsWith("powershell.exe "))
            listOf("C:\\Users\\测试", "C:\\Users\\测试", "My projects").forEach(onLine)
            return nativeStatus
        }
    }

    @Test fun missingPosixShellFallsBackAndDiscardsItsDiagnostics() = runBlocking {
        val host = Host()
        assertEquals(listOf("My projects"), RemoteFolders.load(host, "~")!!.folders)
        assertEquals(1, host.commands)
    }
    @Test fun malformedPosixOutputAlsoTriesNativeWindows() = runBlocking {
        assertEquals("C:/Users/测试", RemoteFolders.load(Host(posixStatus = 0), "~")!!.path)
    }
    @Test fun successfulPosixDoesNotTryWindows() = runBlocking {
        val host = Host(0, listOf("/home/dev", "/home/dev", "src"))
        assertEquals(listOf("src"), RemoteFolders.load(host, "~")!!.folders)
        assertEquals(0, host.commands)
    }
    @Test fun timeoutDoesNotStartAnotherRequestOnTheLostConnection() = runBlocking {
        val host = Host(posixStatus = null)
        assertNull(RemoteFolders.load(host, "~"))
        assertEquals(0, host.commands)
    }
    @Test fun nativeFailureDoesNotAcceptPartialOutput() = runBlocking {
        assertNull(RemoteFolders.load(Host(nativeStatus = 3), "~"))
    }
}
