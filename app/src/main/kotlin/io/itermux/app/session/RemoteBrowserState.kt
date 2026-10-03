package io.itermux.app.session

import androidx.compose.runtime.*
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.ssh.RemoteEntry
import io.itermux.ssh.isSafeRemoteEntryName
import io.itermux.ssh.joinRemotePath
import kotlinx.coroutines.*

/** Session-owned navigation, drafts and mutations survive Activity recreation. No disk storage. */
class RemoteBrowserState(private val scope: CoroutineScope) {
    var directory by mutableStateOf("")
        private set
    private var backStack by mutableStateOf(emptyList<String>())
    val canGoBack: Boolean get() = backStack.isNotEmpty()
    var menuPath by mutableStateOf<String?>(null)
    var menuEntry by mutableStateOf<RemoteEntry?>(null)
    var renamePath by mutableStateOf<String?>(null)
    var renameDraft by mutableStateOf("")
    var previewPath by mutableStateOf<String?>(null)
    var newFolderDraft by mutableStateOf<String?>(null)
    var filtering by mutableStateOf(false)
    var filter by mutableStateOf("")
    var deletePath by mutableStateOf<String?>(null)
    var deleteIsDirectory by mutableStateOf(false)
    var operation by mutableStateOf<String?>(null)
        private set
    var actionError by mutableStateOf<String?>(null)
    var revision by mutableIntStateOf(0)
        private set
    private var actionJob: Job? = null

    fun navigate(path: String) {
        if (path == directory) return
        backStack = backStack + directory
        directory = path
        clearFilter()
        dismissMenu()
    }
    fun goBack(): Boolean {
        if (!canGoBack) return false
        directory = backStack.last()
        backStack = backStack.dropLast(1)
        clearFilter()
        dismissMenu()
        return true
    }
    fun showMenu(directory: String, entry: RemoteEntry) {
        menuPath = joinRemotePath(directory, entry.name)
        menuEntry = entry
    }
    fun dismissMenu() { menuPath = null; menuEntry = null }
    fun clearFilter() { filtering = false; filter = "" }
    fun refresh() { revision++ }
    fun cancel() { actionJob?.cancel() }

    fun rename(action: suspend (String, String) -> Unit) {
        val old = renamePath ?: return
        val name = renameDraft.trim()
        if (!validRemoteRename(name)) return
        val target = joinRemotePath(old.substringBeforeLast('/'), name)
        renamePath = null
        runAction(tr("正在重命名…")) { action(old, target) }
    }

    fun delete(action: suspend (String) -> Unit) {
        val path = deletePath ?: return
        deletePath = null
        runAction(tr("正在删除…")) { action(path) }
    }

    fun createFolder(directory: String, action: suspend (String) -> Unit) {
        val name = newFolderDraft?.trim() ?: return
        if (!validRemoteRename(name)) return
        newFolderDraft = null
        runAction(tr("正在创建文件夹…")) { action(joinRemotePath(directory, name)) }
    }

    /** SFTP listings describe the link itself; resolve it before choosing folder or preview. */
    fun openLink(path: String, isDirectory: suspend (String) -> Boolean) {
        scope.launch {
            try { if (isDirectory(path)) navigate(path) else previewPath = path }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { actionError = trf("无法打开链接：%s", tr(e.message ?: "链接目标不存在")) }
        }
    }

    private fun runAction(label: String, action: suspend () -> Unit) {
        if (actionJob != null) return
        operation = label
        actionError = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { action() }
            catch (_: CancellationException) { actionError = tr("操作已取消，部分更改可能已完成。") }
            catch (e: Exception) { actionError = trf("操作未完成：%s", tr(e.message ?: "请检查路径和 SFTP 服务")) }
            finally {
                operation = null
                revision++ // Re-read even after partial deletion or cancellation.
                actionJob = null
            }
        }
        actionJob = job
        job.start()
    }
}

/** Clickable path segments from the root to [path], each with its absolute target. */
internal fun breadcrumbSegments(path: String): List<Pair<String, String>> {
    if (!path.startsWith('/')) return if (path.isEmpty()) emptyList() else listOf(path to path)
    val segments = mutableListOf("/" to "/")
    var current = ""
    path.split('/').filter { it.isNotEmpty() }.forEach { name ->
        current += "/$name"
        segments += name to current
    }
    return segments
}

internal fun validRemoteRename(name: String): Boolean = name.isNotBlank() && isSafeRemoteEntryName(name)
