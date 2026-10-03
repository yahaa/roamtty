package io.itermux.ssh

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

enum class RemoteFileKind {
    DIRECTORY, TEXT, CODE, CONFIG, IMAGE, AUDIO, VIDEO, ARCHIVE, PDF, DOCUMENT, SPREADSHEET, PRESENTATION, EXECUTABLE, FILE
}

fun remoteFileKind(name: String, isDirectory: Boolean = false, permissions: String = ""): RemoteFileKind {
    if (isDirectory) return RemoteFileKind.DIRECTORY
    val lower = name.lowercase(Locale.ROOT)
    val extension = lower.substringAfterLast('.', "")
    return when {
        lower in setOf("dockerfile", "makefile", "gemfile", "rakefile", "justfile") -> RemoteFileKind.CODE
        lower.startsWith(".env") || lower in setOf(".gitignore", ".gitattributes", ".editorconfig", ".bashrc", ".zshrc") -> RemoteFileKind.CONFIG
        lower in setOf("readme", "license", "licence", "notice", "changelog", "authors") -> RemoteFileKind.TEXT
        extension in setOf("txt", "md", "markdown", "rst", "log", "csv", "tsv") -> RemoteFileKind.TEXT
        extension in setOf("json", "yaml", "yml", "toml", "xml", "ini", "conf", "cfg", "properties", "env", "lock") -> RemoteFileKind.CONFIG
        extension in setOf("sh", "bash", "zsh", "fish", "py", "js", "jsx", "ts", "tsx", "java", "kt", "kts", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "swift", "dart", "lua", "sql", "html", "css", "scss", "vue", "svelte", "gradle", "ps1", "bat") -> RemoteFileKind.CODE
        extension in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "ico", "tif", "tiff") -> RemoteFileKind.IMAGE
        extension in setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus") -> RemoteFileKind.AUDIO
        extension in setOf("mp4", "mkv", "mov", "avi", "webm", "m4v") -> RemoteFileKind.VIDEO
        extension in setOf("zip", "tar", "gz", "bz2", "xz", "7z", "rar", "tgz", "zst", "jar") -> RemoteFileKind.ARCHIVE
        extension == "pdf" -> RemoteFileKind.PDF
        extension in setOf("doc", "docx", "odt", "rtf", "epub") -> RemoteFileKind.DOCUMENT
        extension in setOf("xls", "xlsx", "ods") -> RemoteFileKind.SPREADSHEET
        extension in setOf("ppt", "pptx", "odp") -> RemoteFileKind.PRESENTATION
        extension in setOf("apk", "exe", "msi", "deb", "rpm", "appimage") || 'x' in permissions -> RemoteFileKind.EXECUTABLE
        else -> RemoteFileKind.FILE
    }
}

const val TEXT_PREVIEW_BYTES = 256 * 1024
const val IMAGE_PREVIEW_BYTES = 8 * 1024 * 1024

/** A bounded prefix, never the tail used by shell-history readers. */
data class RemoteFileContent(val bytes: ByteArray, val size: Long, val truncated: Boolean)

/** Strict decoding avoids rendering binary data as a wall of replacement characters.
 * A truncated prefix may end inside a character; the decoder leaves that suffix unread.
 */
fun RemoteFileContent.decodeText(): String? {
    val utf16Le = bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()
    val utf16Be = bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()
    val charset = when {
        utf16Le -> Charsets.UTF_16LE
        utf16Be -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }
    val offset = when {
        utf16Le || utf16Be -> 2
        bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> 3
        else -> 0
    }
    val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    val output = java.nio.CharBuffer.allocate(bytes.size)
    val result = decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset), output, !truncated)
    if (result.isError) return null
    output.flip()
    val text = output.toString()
    if (text.any { it < ' ' && it !in "\n\r\t\u000c" || it == '\u007f' }) return null
    return text
}
