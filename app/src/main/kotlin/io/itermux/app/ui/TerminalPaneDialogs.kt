package io.itermux.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val session = pane.session
    val connected = pane.connected
    val pending by session.pending.collectAsStateWithLifecycle()
    val transferStatus by session.transfers.status.collectAsStateWithLifecycle()
    pending?.let { request -> PromptDialog(request) }

    var pendingVoiceMode by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingVoiceRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var micPermissionPermanentlyDenied by rememberSaveable { mutableStateOf(false) }
    var resumeTick by remember { mutableIntStateOf(0) }

    // Re-check RECORD_AUDIO when returning from system App Settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTick++
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    micPermissionPermanentlyDenied = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun startVoiceConfirmed(mode: VoiceMode, route: AudioRoute) {
        pane.voiceSheetOpen = false
        pane.dockExpanded = false
        pane.voiceSuppressesPinnedDock = true
        pane.voicePopoverOpen = false
        pane.terminal?.scrollToBottom()
        voice.onStart(mode, route)
    }

    fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val mode = pendingVoiceMode?.let { runCatching { VoiceMode.valueOf(it) }.getOrNull() }
        val route = pendingVoiceRoute?.let { runCatching { AudioRoute.valueOf(it) }.getOrNull() }
        pendingVoiceMode = null
        pendingVoiceRoute = null
        if (granted) {
            micPermissionPermanentlyDenied = false
            if (mode != null && route != null) startVoiceConfirmed(mode, route)
        } else {
            val activity = context.findActivity()
            val permanentlyDenied = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            micPermissionPermanentlyDenied = permanentlyDenied
            if (!permanentlyDenied) {
                Toast.makeText(context, tr("需要麦克风权限才能进行语音对话"), Toast.LENGTH_LONG).show()
            }
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
        // Query Bluetooth availability once when opening the sheet (not on every TerminalRoute recomposition).
        val sheetBluetoothAvailable = remember(pane.voiceSheetOpen, resumeTick) {
            voice.checkBluetoothAvailable()
        }
        val hasMicPermission = remember(pane.voiceSheetOpen, resumeTick, micPermissionPermanentlyDenied) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
        VoiceStartSheet(
            hostName = voice.hostName.ifBlank { session.host.name },
            hasMicPermission = hasMicPermission,
            micPermissionPermanentlyDenied = !hasMicPermission && micPermissionPermanentlyDenied,
            desktopPipAudioActive = voice.desktopPipAudioActive,
            pluginInstalled = voice.pluginInstalled,
            bluetoothAvailable = sheetBluetoothAvailable,
            onOpenAppSettings = ::openAppSettings,
            onDismiss = { pane.voiceSheetOpen = false },
            onConfirm = { mode, route ->
                val effectiveRoute = if (route == AudioRoute.BLUETOOTH && !sheetBluetoothAvailable) AudioRoute.SPEAKER else route
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    micPermissionPermanentlyDenied = false
                    startVoiceConfirmed(mode, effectiveRoute)
                } else if (micPermissionPermanentlyDenied) {
                    openAppSettings()
                } else {
                    pendingVoiceMode = mode.name
                    pendingVoiceRoute = effectiveRoute.name
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
