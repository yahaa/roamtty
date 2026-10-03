package io.itermux.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** File operations own their short-lived channels; the transport supplies the active connection. */
internal class SftpOperations(
    private val connectedSession: () -> Session,
    private val isStopped: () -> Boolean,
    private val connectTimeoutMs: Int
) {
    /** A separate SFTP channel shares the authenticated SSH connection without touching the PTY. */
    suspend fun upload(remotePath: String, source: InputStream, onProgress: (Long) -> Unit = {}) = withSftp { sftp ->
        val path = literalSftpPath(validTransferPath(remotePath))
        if (sftp.exists(path)) {
            require(!sftp.lstat(path).isDir) { "目标是目录，请选择目录并指定文件名" }
            throw FileAlreadyExistsException(remotePath)
        }
        val directory = remotePath.substringBeforeLast('/', "")
        val temporary = literalSftpPath((if ('/' in remotePath) "$directory/" else "") + ".itermux-${UUID.randomUUID()}.part")
        try {
            sftp.put(source, temporary, transferMonitor(coroutineContext[Job], onProgress))
            coroutineContext.ensureActive()
            if (sftp.exists(path)) throw FileAlreadyExistsException(remotePath)
            sftp.rename(temporary, path)
        } catch (e: Exception) {
            runCatching { sftp.rm(temporary) }
            throw e
        }
    }

    suspend fun download(remotePath: String, destination: OutputStream, onProgress: (Long) -> Unit = {}, onSize: (Long) -> Unit = {}) = withSftp { sftp ->
        val path = literalSftpPath(validTransferPath(remotePath))
        val attributes = sftp.stat(path)
        require(!attributes.isDir) { "不能下载文件夹" }
        onSize(attributes.size)
        sftp.get(path, destination, transferMonitor(coroutineContext[Job], onProgress))
        coroutineContext.ensureActive()
    }

    suspend fun readFileContent(remotePath: String, maxBytes: Int): RemoteFileContent = withSftp { sftp ->
        require(maxBytes in 1..IMAGE_PREVIEW_BYTES)
        val path = literalSftpPath(validTransferPath(remotePath))
        val attributes = sftp.stat(path)
        require(attributes.isReg) { "只能预览普通文件" }
        // Closing the preview cancels a blocked read without touching the terminal channel.
        coroutineScope {
            val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { sftp.disconnect() }
            }
            try {
                val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 8192))
                sftp.get(path, transferMonitor(coroutineContext[Job]) {}).use { input ->
                    val buffer = ByteArray(8192)
                    var remaining = maxBytes + 1 // Detect growth after stat and exact-boundary files.
                    while (remaining > 0) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        remaining -= count
                    }
                }
                coroutineContext.ensureActive()
                val bytes = out.toByteArray()
                RemoteFileContent(bytes.copyOf(minOf(bytes.size, maxBytes)), attributes.size, bytes.size > maxBytes)
            } finally {
                cancellation.cancel()
            }
        }
    }

    /**
     * Read a remote text file (home-relative or absolute) without upload-path overwrite rules.
     * Returns null if missing. If larger than [maxBytes], keeps only the trailing bytes
     * (caller should skip the first partial line).
     */
    suspend fun readRemoteText(remotePath: String, maxBytes: Int = 512 * 1024): Pair<String, Boolean>? = withSftp { sftp ->
        require(maxBytes in 1..512 * 1024)
        val path = literalSftpPath(validTransferPath(remotePath))
        val attributes = try {
            sftp.stat(path)
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return@withSftp null else throw e
        }
        if (attributes.isDir) return@withSftp null
        val offset = (attributes.size - maxBytes).coerceAtLeast(0)
        val out = java.io.ByteArrayOutputStream(maxBytes)
        // Bound both network reads and allocation even if the file grows after stat.
        sftp.get(path, transferMonitor(coroutineContext[Job]) {}, offset).use { input ->
            var remaining = maxBytes
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                coroutineContext.ensureActive()
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                out.write(buffer, 0, count)
                remaining -= count
            }
        }
        coroutineContext.ensureActive()
        val bytes = out.toByteArray()
        String(bytes, Charsets.UTF_8) to (offset > 0)
    }

    /** List a remote SFTP directory for the file picker. An empty path opens the SFTP home. */
    suspend fun listRemoteDirectory(remotePath: String = ""): RemoteDirectory = withSftp { sftp ->
        val path = if (remotePath.isBlank()) sftp.pwd() else validTransferPath(remotePath)
        // Collect via selector to avoid an intermediate Vector copy and to honor cancellation
        // mid-listing (BREAK). Attrs come from LsEntry — no per-entry stat.
        val entries = ArrayList<RemoteEntry>(256)
        val job = coroutineContext[Job]
        sftp.ls(literalSftpPath(path), ChannelSftp.LsEntrySelector { entry ->
            if (job?.isActive == false) return@LsEntrySelector ChannelSftp.LsEntrySelector.BREAK
            val name = entry.filename
            if (name.isNullOrBlank() || name == "." || name == "..") {
                return@LsEntrySelector ChannelSftp.LsEntrySelector.CONTINUE
            }
            val attrs = entry.attrs
            entries.add(
                RemoteEntry(
                    name = name,
                    isDirectory = attrs.isDir,
                    size = attrs.size,
                    mtimeSeconds = attrs.mTime.toLong() and 0xFFFFFFFFL,
                    permissions = attrs.permissionsString
                )
            )
            ChannelSftp.LsEntrySelector.CONTINUE
        })
        coroutineContext.ensureActive()
        entries.sortWith(remoteEntryListComparator())
        // Skip realpath RTT when we already have an absolute path (pwd / navigated).
        // Relative paths still need realpath for a stable breadcrumb.
        val resolved = if (path.startsWith("/")) path else sftp.realpath(path)
        RemoteDirectory(resolved, entries)
    }

    /** Rename a remote file or directory. Paths follow the same validation as transfers. */
    suspend fun rename(oldPath: String, newPath: String) = withSftp { sftp ->
        val from = literalSftpPath(validTransferPath(oldPath))
        val to = literalSftpPath(validTransferPath(newPath))
        sftp.rename(from, to)
    }

    /**
     * Delete a remote file or directory. Directories are removed recursively
     * (contents first, then rmdir). Empty directories are removed directly.
     * Refuses `.`/`..`/root/unsafe paths. On partial failure, aborts with a clear error.
     */
    suspend fun deleteFile(path: String) = withSftp { sftp ->
        require(isSafeRemoteDeletePath(path)) { "无效的删除路径" }
        deleteRemoteRecursive(sftp, validTransferPath(path))
    }

    private suspend fun deleteRemoteRecursive(sftp: ChannelSftp, path: String) {
        coroutineContext.ensureActive()
        val remote = literalSftpPath(path)
        val attrs = try {
            sftp.lstat(remote)
        } catch (e: SftpException) {
            throw IllegalStateException("无法删除：$path（${e.message}）", e)
        }
        if (attrs.isDir) {
            @Suppress("UNCHECKED_CAST")
            val listing = try {
                sftp.ls(remote) as java.util.Vector<ChannelSftp.LsEntry>
            } catch (e: SftpException) {
                throw IllegalStateException("无法列出目录：$path（${e.message}）", e)
            }
            for (item in listing) {
                val name = item.filename ?: continue
                if (name.isEmpty() || name == "." || name == "..") continue
                if (!isSafeRemoteEntryName(name)) {
                    throw IllegalArgumentException("目录含有不安全的名称，已中止删除：$name")
                }
                val child = joinRemotePath(path, name)
                deleteRemoteRecursive(sftp, child)
            }
            try {
                coroutineContext.ensureActive()
                sftp.rmdir(remote)
            } catch (e: SftpException) {
                throw IllegalStateException("无法删除目录：$path（${e.message}）", e)
            }
        } else {
            try {
                coroutineContext.ensureActive()
                sftp.rm(remote)
            } catch (e: SftpException) {
                throw IllegalStateException("无法删除文件：$path（${e.message}）", e)
            }
        }
    }

    private suspend fun <T> withSftp(block: suspend (ChannelSftp) -> T): T = withContext(Dispatchers.IO) {
        val ssh = connectedSession()
        val sftp = ssh.openChannel("sftp") as ChannelSftp
        try {
            sftp.connect(connectTimeoutMs)
            coroutineContext.ensureActive()
            block(sftp)
        } finally {
            sftp.disconnect()
        }
    }

    private fun transferMonitor(job: Job?, onProgress: (Long) -> Unit): SftpProgressMonitor = object : SftpProgressMonitor {
        private var transferred = 0L
        override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit
        override fun count(count: Long): Boolean {
            transferred += count
            onProgress(transferred)
            return !isStopped() && job?.isActive != false
        }
        override fun end() = Unit
    }

    private fun validTransferPath(path: String): String {
        require(path.isNotBlank() && path.length <= 4096 && path.none { it == '\u0000' || it == '\n' || it == '\r' }) { "请输入有效的远端文件路径" }
        return path
    }

    // JSch's high-level operations interpret glob patterns; realpath alone takes a raw path.
    private fun literalSftpPath(path: String): String = buildString {
        path.forEach { char ->
            if (char == '\\' || char == '*' || char == '?') append('\\')
            append(char)
        }
    }

    private fun ChannelSftp.exists(path: String): Boolean = try {
        lstat(path)
        true
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw e
    }

}
