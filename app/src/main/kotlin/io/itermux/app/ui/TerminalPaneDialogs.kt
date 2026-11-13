package io.itermux.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.session.CodexImageAttach
import io.itermux.app.session.DownloadItem

/** The terminal page's dialogs and sheets: login questions, the tapped file, transfers, common commands, confirmations. */
@Composable
internal fun TerminalPaneDialogs(pane: TerminalPaneState, onRestart: () -> Unit) {
    val session = pane.session
    val connected = pane.connected
    val pending by session.pending.collectAsStateWithLifecycle()
    val transferStatus by session.transfers.status.collectAsStateWithLifecycle()
    pending?.let { request -> PromptDialog(request) }
    // Outlives the preview: Download closes it, and these actions then carry the download through.
    val downloads = rememberDownloadActions(session)
    pane.tappedFile?.let { file ->
        RemoteFilePreview(session, file.path, connected,
            canDownload = connected && !transferStatus.running && !downloads.busy,
            onDownload = {
                pane.closeTappedFile()
                downloads.start(listOf(DownloadItem(file.path)), false)
            }, onDismiss = pane::closeTappedFile, line = file.line)
    }
    FileTransferUi(session, pane.fileAction, downloads = downloads,
        onInsertPath = { path -> pane.stageInEditor(CodexImageAttach.pathForPaste(path)) }) { pane.fileAction = null }
    if (pane.commonCommandsOpen) CommonCommandsSheet(session, onSelect = { command ->
        pane.commonCommandsOpen = false
        pane.closeEditor()
        pane.terminal?.scrollToBottom()
        session.executeHistoryCommand(command)
    }, onDismiss = { pane.commonCommandsOpen = false })
    pane.confirmPaste?.let { text ->
        PasteConfirmDialog(enabled = connected, onSend = { pane.sendPaste(text); pane.confirmPaste = null }) { pane.confirmPaste = null }
    }
    if (pane.restartConfirm) RestartSessionDialog(session.mode, onRestart = { pane.restartConfirm = false; onRestart() }) {
        pane.restartConfirm = false
    }
}
