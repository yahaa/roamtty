package io.itermux.app.session

import android.content.Context
import android.net.Uri
import android.widget.Toast
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.itermux.app.tr
import io.itermux.app.trf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.nio.file.FileAlreadyExistsException

internal data class TransferStatus(val name: String = "", val bytes: Long = 0,
    val total: Long = -1, val running: Boolean = false, val result: String? = null,
    val remotePath: String? = null, val succeeded: Boolean? = null,
    val downloads: List<DownloadItem> = emptyList(), val bytesPerSecond: Double = 0.0)

/** The session owns both the task and its UI draft, independent of Activity/composition lifetime. */
class FileTransferController(private val scope: CoroutineScope) {
    var uploadUri by mutableStateOf<Uri?>(null)
    var uploadName by mutableStateOf("")
    var uploadPath by mutableStateOf("")
    var downloadDialog by mutableStateOf(false)
    var downloadDirectory by mutableStateOf("")
    internal var selectedDownloads by mutableStateOf(emptyList<DownloadItem>())
    internal var pendingDownloads by mutableStateOf(emptyList<DownloadItem>())
    private val mutableStatus = MutableStateFlow(TransferStatus())
    internal val status = mutableStatus.asStateFlow()
    private var job: Job? = null

    fun cancel() { job?.cancel() }
    fun dismiss() { if (!mutableStatus.value.running) mutableStatus.update { it.copy(result = null) } }

    /**
     * @param operation returns an optional success message; null keeps the default 「传输完成」.
     */
    private fun start(name: String, total: Long = -1, downloads: List<DownloadItem> = emptyList(),
        operation: suspend ((Long) -> Unit) -> String?) {
        check(job == null) { "A transfer is already running" }
        mutableStatus.value = TransferStatus(name = name, total = total, running = true, downloads = downloads)
        val task = scope.launch(start = CoroutineStart.LAZY) {
            var reported = 0L
            var lastReport = System.nanoTime()
            val started = lastReport
            try {
                val customResult = operation { bytes ->
                    val now = System.nanoTime()
                    if (bytes - reported >= 256 * 1024 || now - lastReport >= 100_000_000L) {
                        mutableStatus.update { it.copy(bytes = bytes,
                            bytesPerSecond = bytes / ((now - started) / 1e9).coerceAtLeast(0.001)) }
                        reported = bytes
                        lastReport = now
                    }
                }
                ensureActive()
                mutableStatus.update {
                    it.copy(bytes = if (it.total >= 0) it.total else it.bytes, result = customResult ?: tr("传输完成"),
                        succeeded = it.downloads.none { file -> file.phase != DownloadPhase.COMPLETE })
                }
            } catch (_: CancellationException) {
                mutableStatus.update { it.copy(result = tr("传输已取消"), succeeded = false) }
            } catch (e: Exception) {
                val message = when (e) {
                    is IncompleteDownload -> tr("下载未完成，无法删除本地半成品，请在保存位置手动删除。")
                    is FileAlreadyExistsException -> trf("远端文件已存在：%s", e.file ?: uploadPath)
                    else -> trf("传输失败：%s", tr(e.message ?: "请检查路径和 SFTP 服务"))
                }
                mutableStatus.update { it.copy(result = message, succeeded = false) }
            } finally {
                mutableStatus.update { it.copy(running = false, bytesPerSecond = 0.0) }
                job = null
            }
        }
        job = task
        task.start()
    }

    fun upload(context: Context, session: TerminalConnection, uri: Uri, path: String, name: String, size: Long) {
        val resolver = context.applicationContext.contentResolver
        start(trf("上传 %s", name), size) { progress ->
            withContext(Dispatchers.IO) {
                val input = resolver.openInputStream(uri) ?: throw IOException(tr("无法打开本地文件"))
                input.use { session.upload(path, it, progress) }
            }
            null
        }
    }

    /**
     * Independent of [upload]: convert to PNG, SFTP to a unique `/tmp/itermux-img-*.png`,
     * then insert the explicitly selected image without submitting the current prompt.
     */
    fun uploadImageForCli(context: Context, session: TerminalConnection, uri: Uri) {
        if (job != null || mutableStatus.value.running) {
            Toast.makeText(context.applicationContext, tr("已有传输进行中，请稍后再试"), Toast.LENGTH_SHORT).show()
            return
        }
        val application = context.applicationContext
        val remotePath = CodexImageAttach.newRemotePngPath()
        start(tr("上传图片…"), -1) { progress ->
            val png = withContext(Dispatchers.IO) {
                CodexImageAttach.convertToPngFile(application, uri)
            }
            try {
                withContext(Dispatchers.IO) {
                    png.inputStream().use { session.upload(remotePath, it, progress) }
                }
            } finally {
                png.delete()
            }
            withContext(Dispatchers.Main.immediate) {
                mutableStatus.update { it.copy(remotePath = remotePath) }
                session.insertImagePath(remotePath)
                trf("已上传并粘贴路径：%s", remotePath)
            }
        }
    }

    fun download(context: Context, session: TerminalConnection, uri: Uri, path: String) {
        startDownloads(context, session, listOf(DownloadItem(path))) { uri }
    }

    internal fun downloadFiles(context: Context, session: TerminalConnection, tree: Uri, files: List<DownloadItem>) {
        val resolver = context.applicationContext.contentResolver
        startDownloads(context, session, files) { file ->
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.name.substringAfterLast('.', "").lowercase()) ?: "*/*"
            DocumentsContract.createDocument(resolver, parent, mime, file.name)
                ?: throw IOException(tr("无法创建本地文件"))
        }
    }

    private fun startDownloads(context: Context, session: TerminalConnection, files: List<DownloadItem>,
        destination: (DownloadItem) -> Uri) {
        if (files.isEmpty()) return
        val application = context.applicationContext
        start(tr("下载文件"), downloads = files) {
            val result = runDownloadQueue(files, onUpdate = { items, speed ->
                val current = items.firstOrNull { it.phase == DownloadPhase.RUNNING } ?: items.last()
                mutableStatus.update { it.copy(downloads = items, bytes = current.bytes, total = current.total,
                    bytesPerSecond = speed) }
            }) { file, progress ->
                withContext(Dispatchers.IO) {
                    val uri = destination(file)
                    try {
                        var total = file.total
                        val output = application.contentResolver.openOutputStream(uri, "w") ?: throw IOException(tr("无法打开本地文件"))
                        output.use { session.download(file.path, it, { bytes -> progress(bytes, total) },
                            { size -> total = size; progress(0, total) }) }
                        ensureActive()
                    } catch (e: Exception) {
                        val deleted = withContext(NonCancellable) {
                            runCatching {
                                if (DocumentsContract.isDocumentUri(application, uri))
                                    DocumentsContract.deleteDocument(application.contentResolver, uri)
                                else application.contentResolver.delete(uri, null, null) > 0
                            }.getOrDefault(false)
                        }
                        if (!deleted) throw IncompleteDownload(e)
                        throw e
                    }
                }
            }
            val failed = result.count { it.phase == DownloadPhase.FAILED }
            if (failed > 0) trf("已完成 %d 个，失败 %d 个", result.size - failed, failed) else tr("传输完成")
        }
    }

    private class IncompleteDownload(cause: Exception) : IOException(tr("下载未完成，无法删除本地半成品，请在保存位置手动删除。"), cause)
}
