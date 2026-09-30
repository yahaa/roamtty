package io.itermux.app.session

import android.content.Context
import android.net.Uri
import android.widget.Toast
import android.provider.DocumentsContract
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
    val total: Long = -1, val running: Boolean = false, val result: String? = null)

/** The session owns both the task and its UI draft, independent of Activity/composition lifetime. */
class FileTransferController(private val scope: CoroutineScope) {
    var uploadUri by mutableStateOf<Uri?>(null)
    var uploadName by mutableStateOf("")
    var uploadPath by mutableStateOf("")
    var downloadDialog by mutableStateOf(false)
    var downloadPath by mutableStateOf("")
    private val mutableStatus = MutableStateFlow(TransferStatus())
    internal val status = mutableStatus.asStateFlow()
    private var job: Job? = null

    fun cancel() { job?.cancel() }
    fun dismiss() { if (!mutableStatus.value.running) mutableStatus.update { it.copy(result = null) } }

    /**
     * @param operation returns an optional success message; null keeps the default 「传输完成」.
     */
    private fun start(name: String, total: Long = -1, operation: suspend ((Long) -> Unit) -> String?) {
        check(job == null) { "A transfer is already running" }
        mutableStatus.value = TransferStatus(name = name, total = total, running = true)
        job = scope.launch {
            var reported = 0L
            try {
                val customResult = operation { bytes ->
                    if (bytes - reported >= 256 * 1024) {
                        mutableStatus.update { it.copy(bytes = bytes) }
                        reported = bytes
                    }
                }
                ensureActive()
                mutableStatus.update {
                    it.copy(bytes = if (it.total >= 0) it.total else it.bytes, result = customResult ?: tr("传输完成"))
                }
            } catch (_: CancellationException) {
                mutableStatus.update { it.copy(result = tr("传输已取消")) }
            } catch (e: Exception) {
                val message = when (e) {
                    is IncompleteDownload -> tr("下载未完成，无法删除本地半成品，请在保存位置手动删除。")
                    is FileAlreadyExistsException -> trf("远端文件已存在：%s", e.file ?: uploadPath)
                    else -> trf("传输失败：%s", tr(e.message ?: "请检查路径和 SFTP 服务"))
                }
                mutableStatus.update { it.copy(result = message) }
            } finally {
                mutableStatus.update { it.copy(running = false) }
                job = null
            }
        }
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
     * then bracketed-paste the path only when Codex/Grok appears on the current PTY.
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
                if (CodexImageAttach.looksLikeCodexOrGrokCli(session.getEmulator())) {
                    session.paste(remotePath)
                    trf("已上传并粘贴路径：%s", remotePath)
                } else {
                    trf("已上传到 %s（未检测到 Codex/Grok，未自动粘贴）", remotePath)
                }
            }
        }
    }

    fun download(context: Context, session: TerminalConnection, uri: Uri, path: String) {
        val application = context.applicationContext
        start(trf("下载 %s", path)) { progress ->
            try {
                withContext(Dispatchers.IO) {
                    val output = application.contentResolver.openOutputStream(uri, "w") ?: throw IOException(tr("无法打开本地文件"))
                    output.use { session.download(path, it, progress) { size -> mutableStatus.update { it.copy(total = size) } } }
                }
            } catch (e: Exception) {
                val deleted = withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        if (DocumentsContract.isDocumentUri(application, uri))
                            DocumentsContract.deleteDocument(application.contentResolver, uri)
                        else application.contentResolver.delete(uri, null, null) > 0
                    }.getOrDefault(false)
                }
                if (!deleted) throw IncompleteDownload(e)
                throw e
            }
            null
        }
    }

    private class IncompleteDownload(cause: Exception) : IOException(cause)
}
