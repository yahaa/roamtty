package io.itermux.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.itermux.app.tr
import io.itermux.app.session.TerminalConnection
import io.itermux.app.session.validRemoteRename
import io.itermux.ssh.*
import io.itermux.app.session.RemoteBrowserState

/** The browser's questions: the new name, the new folder's name, and whether to delete. */
@Composable
internal fun FileBrowserDialogs(session: TerminalConnection, connected: Boolean, listing: RemoteDirectory?, currentPath: String) {
    val browser = session.fileBrowser
    val idle = connected && browser.operation == null
    browser.renamePath?.let { path -> RenameDialog(browser, path, idle) { browser.rename(session::renameRemoteFile) } }
    browser.newFolderDraft?.let { draft ->
        NewFolderDialog(browser, draft, exists = listing?.entries?.any { it.name == draft.trim() } == true, idle) {
            browser.createFolder(currentPath, session::makeRemoteDirectory)
        }
    }
    browser.deletePath?.let { path -> DeleteDialog(browser, path, idle) { browser.delete(session::deleteRemoteFile) } }
}

@Composable
private fun RenameDialog(browser: RemoteBrowserState, path: String, idle: Boolean, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = { browser.renamePath = null }, title = { Text(tr("重命名")) },
        text = { OutlinedTextField(browser.renameDraft, { browser.renameDraft = it }, singleLine = true,
            label = { Text(tr("新文件名")) }, modifier = Modifier.fillMaxWidth().testTag("files-rename-input")) },
        confirmButton = { TextButton(onClick = onConfirm,
            enabled = idle && validRemoteRename(browser.renameDraft.trim()) && browser.renameDraft.trim() != path.substringAfterLast('/'),
            modifier = Modifier.testTag("files-rename-confirm")) { Text(tr("保存")) } },
        dismissButton = { TextButton(onClick = { browser.renamePath = null }) { Text(tr("取消")) } })
}

@Composable
private fun NewFolderDialog(browser: RemoteBrowserState, draft: String, exists: Boolean, idle: Boolean, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = { browser.newFolderDraft = null }, title = { Text(tr("新建文件夹")) },
        text = { OutlinedTextField(draft, { browser.newFolderDraft = it }, singleLine = true,
            label = { Text(tr("文件夹名称")) }, isError = exists,
            supportingText = if (exists) { { Text(tr("已存在同名文件或文件夹")) } } else null,
            modifier = Modifier.fillMaxWidth().testTag("files-new-folder-input")) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = idle && validRemoteRename(draft.trim()) && !exists,
            modifier = Modifier.testTag("files-new-folder-confirm")) { Text(tr("创建")) } },
        dismissButton = { TextButton(onClick = { browser.newFolderDraft = null }) { Text(tr("取消")) } })
}

@Composable
private fun DeleteDialog(browser: RemoteBrowserState, path: String, idle: Boolean, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = { browser.deletePath = null }, title = { Text(tr("删除文件夹？").takeIf { browser.deleteIsDirectory } ?: tr("删除文件？")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectionContainer { Text(path, style = MaterialTheme.typography.bodyMedium) }
            Text(tr(if (browser.deleteIsDirectory) "将永久删除远端目录及其全部内容，此操作不可撤销。" else "将永久删除远端文件，此操作不可撤销。"))
        } },
        confirmButton = { TextButton(onClick = onConfirm, enabled = idle,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.testTag("files-delete-confirm")) { Text(tr("删除")) } },
        dismissButton = { TextButton(onClick = { browser.deletePath = null }) { Text(tr("取消")) } })
}
