package io.itermux.ssh

import org.junit.Assert.*
import org.junit.Test

class RemoteFileContentTest {
    @Test fun typesRespectDirectoriesNamesCaseAndExecutablePermissions() {
        assertEquals(RemoteFileKind.DIRECTORY, remoteFileKind("notes.md", true))
        assertEquals(RemoteFileKind.IMAGE, remoteFileKind("photo.JPEG"))
        assertEquals(RemoteFileKind.ARCHIVE, remoteFileKind("backup.tar.gz"))
        assertEquals(RemoteFileKind.CODE, remoteFileKind("Dockerfile"))
        assertEquals(RemoteFileKind.CONFIG, remoteFileKind(".env.local"))
        assertEquals(RemoteFileKind.CONFIG, remoteFileKind("pod.yaml"))
        assertEquals(RemoteFileKind.TEXT, remoteFileKind("README"))
        assertEquals(RemoteFileKind.EXECUTABLE, remoteFileKind("server", permissions = "-rwxr-xr-x"))
        assertEquals(RemoteFileKind.FILE, remoteFileKind("unknown"))
    }

    @Test fun readsUtf8AndBomMarkedUtf16WithoutLosingWhitespace() {
        val text = "  中文\t😀\nnext\r\n"
        listOf(text.toByteArray(), byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + text.toByteArray(),
            byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xfe.toByte(), 0xff.toByte()) + text.toByteArray(Charsets.UTF_16BE)).forEach {
            assertEquals(text, RemoteFileContent(it, it.size.toLong(), false).decodeText())
        }
        assertEquals("", RemoteFileContent(byteArrayOf(), 0, false).decodeText())
    }

    @Test fun rejectsBinaryAndInvalidTextEvenWithTextExtension() {
        listOf(byteArrayOf(1, 2, 3), "PNG\u0000binary".toByteArray(), byteArrayOf(0xff.toByte(), 0x12)).forEach {
            assertNull(RemoteFileContent(it, it.size.toLong(), false).decodeText())
        }
    }

    @Test fun truncationInsideUtf8OrUtf16CharactersDoesNotShowCorruption() {
        val bytes = "hello中".toByteArray()
        val prefix = bytes.copyOf(bytes.size - 1)
        assertEquals("hello", RemoteFileContent(prefix, bytes.size.toLong(), true).decodeText())
        assertNull(RemoteFileContent(prefix, prefix.size.toLong(), false).decodeText())
        val utf16 = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "hello😀".toByteArray(Charsets.UTF_16LE)
        assertEquals("hello", RemoteFileContent(utf16.copyOf(utf16.size - 2), utf16.size.toLong(), true).decodeText())
    }
}
