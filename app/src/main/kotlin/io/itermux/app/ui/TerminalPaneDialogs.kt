package io.itermux.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.session.CodexImageAttach
import io.itermux.app.session.DownloadItem
import io.itermux.app.tr
import io.itermux.desktop.AudioRoute
import io.itermux.desktop.VoiceMode

/** The terminal page's dialogs and sheets: login questions, the tapped file, transfers, common commands, confirmations. */
@Composable
internal fun TerminalPaneDialogs(pane: TerminalPaneState, onRestart: () -> Unit, voice: TerminalVoice = TerminalVoice()) {
    val context = LocalContext.current
    val session = pane.session
    val connected = pane.connected
    val pending by session.pending.collectAsStateWithLifecycle()
    val transferStatus by session.transfers.status.collectAsStateWithLifecycle()
    pending?.let { request -> PromptDialog(request) }
    var pendingVoiceConfirm by remember { mutableStateOf<Pair<VoiceMode, AudioRoute>?>(null) }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val start = pendingVoiceConfirm
        pendingVoiceConfirm = null
        if (granted && start != null) {
            pane.voiceSheetOpen = false
            pane.dockExpanded = false
            pane.voiceSuppressesPinnedDock = true
            pane.voicePopoverOpen = false
            voice.onStart(start.first, start.second)
        } else if (!granted) {
            Toast.makeText(context, tr("需要麦克风权限才能进行语音对话"), Toast.LENGTH_LONG).show()
        }
    }
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
    if (pane.usageSheetOpen) AgentUsageSheet(
        session = session,
        onCompacted = {
            pane.usageSheetOpen = false
            pane.terminal?.scrollToBottom()
        },
        onDismiss = { pane.usageSheetOpen = false },
    )
    if (pane.voiceSheetOpen) {
        val hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        VoiceStartSheet(
            hostName = voice.hostName.ifBlank { session.host.name },
            hasMicPermission = hasMicPermission,
            desktopPipAudioActive = voice.desktopPipAudioActive,
            pluginInstalled = voice.pluginInstalled,
            bluetoothAvailable = voice.bluetoothAvailable,
            onDismiss = { pane.voiceSheetOpen = false },
            onConfirm = { mode, route ->
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    pane.voiceSheetOpen = false
                    pane.dockExpanded = false
                    pane.voiceSuppressesPinnedDock = true
                    pane.voicePopoverOpen = false
                    voice.onStart(mode, route)
                } else {
                    pendingVoiceConfirm = mode to route
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        )
    }
    pane.confirmPaste?.let { text ->
        PasteConfirmDialog(enabled = connected, onSend = { pane.sendPaste(text); pane.confirmPaste = null }) { pane.confirmPaste = null }
    }
    if (pane.restartConfirm) RestartSessionDialog(session.mode, onRestart = { pane.restartConfirm = false; onRestart() }) {
        pane.restartConfirm = false
    }
}
