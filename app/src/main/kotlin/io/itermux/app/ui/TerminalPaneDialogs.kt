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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

    var pendingVoiceRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var micPermissionPermanentlyDenied by rememberSaveable { mutableStateOf(false) }

    // Re-check RECORD_AUDIO when returning from system App Settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    micPermissionPermanentlyDenied = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun startVoiceConfirmed(route: AudioRoute) {
        pane.dockExpanded = false
        pane.voiceSuppressesPinnedDock = true
        pane.voicePopoverOpen = false
        pane.terminal?.scrollToBottom()
        voice.onStart(VoiceMode.LIVE, route)
    }

    fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val route = pendingVoiceRoute?.let { runCatching { AudioRoute.valueOf(it) }.getOrNull() }
        pendingVoiceRoute = null
        if (granted) {
            micPermissionPermanentlyDenied = false
            if (route != null) startVoiceConfirmed(route)
        } else {
            val activity = context.findActivity()
            val permanentlyDenied = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            micPermissionPermanentlyDenied = permanentlyDenied
            val msg = if (permanentlyDenied) {
                tr("麦克风权限已被拒绝，请在系统设置中开启")
            } else {
                tr("需要麦克风权限才能进行语音对话")
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    // One-tap start into full-duplex live voice: Bluetooth if connected, else speakerphone.
    LaunchedEffect(pane.voiceStartRequested) {
        if (!pane.voiceStartRequested) return@LaunchedEffect
        pane.voiceStartRequested = false
        val route = if (voice.checkBluetoothAvailable()) AudioRoute.BLUETOOTH else AudioRoute.SPEAKER
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            micPermissionPermanentlyDenied = false
            startVoiceConfirmed(route)
        } else if (micPermissionPermanentlyDenied) {
            Toast.makeText(context, tr("麦克风权限已被拒绝，请在系统设置中开启"), Toast.LENGTH_LONG).show()
            openAppSettings()
        } else {
            pendingVoiceRoute = route.name
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
