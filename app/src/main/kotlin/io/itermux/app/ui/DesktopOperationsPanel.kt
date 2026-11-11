package io.itermux.app.ui

import android.net.ConnectivityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.desktop.DesktopSettings
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.desktop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun BoxScope.OperationsPanel(connection: DesktopConnection, landscape: Boolean, mode: PointerMode, virtualMouse: Boolean,
    showStats: Boolean, onMode: (PointerMode) -> Unit, onVirtualMouse: (Boolean) -> Unit, onShowStats: (Boolean) -> Unit,
    statsMoved: Boolean, onResetStats: () -> Unit, onLeave: () -> Unit, onDisconnect: () -> Unit, onFiles: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewOnly by connection.viewOnly.collectAsStateWithLifecycle()
    val lock by connection.lockOnClose.collectAsStateWithLifecycle()
    val audio by connection.audio.collectAsStateWithLifecycle()
    val audioAvailable by connection.audioAvailable.collectAsStateWithLifecycle()
    val quality by connection.quality.collectAsStateWithLifecycle()
    val fps by connection.fps.collectAsStateWithLifecycle()
    val hello by connection.hello.collectAsStateWithLifecycle()
    val display by connection.display.collectAsStateWithLifecycle()
    val stats by connection.stats.collectAsStateWithLifecycle()
    var clipNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(connection) {
        connection.remoteClipboard.collect { text ->
            context.copyText(text)
            clipNote = if (text.isEmpty()) tr("远程剪贴板为空") else trf("已复制远程剪贴板（%d 字）", text.length)
        }
    }
    val refresh = (LocalView.current.display?.refreshRate ?: 60f).roundToInt()
    val metered = remember { context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = onDismiss))
    Card(Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
        .then(if (landscape) Modifier.padding(end = 80.dp).width(340.dp).fillMaxHeight(0.92f) else Modifier.fillMaxWidth().fillMaxHeight(0.7f))
        .testTag("desktop-operations"), colors = CardDefaults.cardColors(containerColor = DesktopPanelColor.copy(alpha = 1f)), shape = RoundedCornerShape(18.dp)) {
        // Closing the panel is the ✕ in its corner, where every sheet keeps it; ending the connection is the
        // red button at the very bottom, away from it (the old top row put 断开 under the thumb that closes).
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("操作"), color = DesktopInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(connection.host.name, color = DesktopInkDim, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = onDismiss, Modifier.testTag("desktop-op-close")) {
                Icon(painterResource(R.drawable.ic_close), tr("关闭"), Modifier.size(22.dp), tint = DesktopInk)
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp)
            .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelButton(tr("返回"), R.drawable.ic_exit, "desktop-op-exit", Modifier.weight(1f), tr("连接保持"), onLeave)
                PanelButton(tr("文件"), R.drawable.ic_folder, "desktop-op-files", Modifier.weight(1f), tr("上传和下载"), onFiles)
            }
            Section(tr("剪贴板")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelButton(tr("发送手机剪贴板"), R.drawable.ic_upload, "desktop-op-clip-send", Modifier.weight(1f)) {
                        val text = context.clipboardText()
                        if (text.isEmpty()) clipNote = tr("手机剪贴板为空")
                        else {
                            clipNote = tr("已发送到远程剪贴板")
                            // Encode and fragment off the UI thread (large Unicode clipboards can be multi-MiB).
                            scope.launch(Dispatchers.Default) {
                                connection.send(DesktopProtocol.clipSet(text.take(1 shl 20)))
                            }
                        }
                    }
                    PanelButton(tr("获取远程剪贴板"), R.drawable.ic_download, "desktop-op-clip-get", Modifier.weight(1f)) {
                        connection.send(DesktopProtocol.req(DesktopProtocol.REQ_CLIP_GET))
                    }
                }
                clipNote?.let { Text(it, color = DesktopInkDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).testTag("desktop-clip-note")) }
            }
            Section(tr("操作方式")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(tr("屏幕触控"), mode == PointerMode.TOUCH, "desktop-mode-touch", Modifier.weight(1f)) { onMode(PointerMode.TOUCH) }
                    Choice(tr("鼠标指针"), mode == PointerMode.MOUSE, "desktop-mode-mouse", Modifier.weight(1f)) { onMode(PointerMode.MOUSE) }
                }
            }
            ToggleRow(tr("声音"), if (audioAvailable == false) tr("这台电脑暂时无法传回声音") else tr("在手机上播放电脑的声音"), audio,
                "desktop-audio") { connection.setAudio(it) }
            ToggleRow(tr("仅观看"), tr("不向电脑发送任何操作"), viewOnly, "desktop-view-only") { connection.setViewOnly(it) }
            ToggleRow(tr("虚拟鼠标"), tr("悬浮的左键、右键和滚轮"), virtualMouse, "desktop-virtual-mouse") { onVirtualMouse(it) }
            Column {
                ToggleRow(tr("显示网络状态"), tr("画面上显示延迟、帧率和码率，长按可拖动"), showStats, "desktop-show-stats") { onShowStats(it) }
                if (statsMoved) Text(tr("恢复默认位置（左上角）"), color = DesktopAccent, fontSize = 13.sp, modifier = Modifier
                    .padding(top = 2.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onResetStats)
                    .padding(vertical = 6.dp).testTag("desktop-stats-reset"))
            }
            Section(tr("帧率")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesktopSettings.FPS.filter { it <= maxOf(60, refresh) }.forEach { f ->
                        Choice("$f", fps == f, "desktop-fps-$f", Modifier.weight(1f)) { connection.setFps(f) }
                    }
                }
            }
            Section(tr("画质")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to tr("自动"), "smooth" to tr("流畅"), "clear" to tr("清晰"), "original" to tr("原画")).forEach { (q, label) ->
                        Choice(label, quality == q, "desktop-quality-$q", Modifier.weight(1f)) { connection.setQuality(q) }
                    }
                }
                // About 30 MB a minute at 4 Mbps; shown on mobile data only.
                if (metered && stats.kbps > 0) Text(trf("预估流量 %d MB/分钟", (stats.kbps * 60 / 8 / 1000).coerceAtLeast(1)),
                    color = DesktopInkDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            hello?.displays?.takeIf { it.size > 1 }?.let { displays ->
                Section(tr("显示器")) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        displays.forEachIndexed { i, d -> Choice(d.name.ifEmpty { trf("显示器 %d", i + 1) }, display == i, "desktop-display-$i") { connection.setDisplay(i) } }
                    }
                }
            }
            ToggleRow(tr("断开后锁定电脑"), tr("断开连接时锁屏，返回不算"), lock, "desktop-lock-on-close") { connection.setLockOnClose(it) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, StatusColors.danger.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .clickable(onClick = onDisconnect).padding(vertical = 12.dp).testTag("desktop-op-disconnect"),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_power), null, Modifier.size(18.dp), tint = StatusColors.danger)
                Spacer(Modifier.width(8.dp))
                Text(tr("断开连接"), color = StatusColors.danger, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, color = DesktopInkDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
private fun PanelButton(label: String, icon: Int, tag: String, modifier: Modifier = Modifier, caption: String? = null, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x14FFFFFF)).clickable(onClick = onClick)
        .padding(vertical = 10.dp, horizontal = 6.dp).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = DesktopInk)
        Spacer(Modifier.height(4.dp))
        Text(label, color = DesktopInk, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
        caption?.let { Text(it, color = DesktopInkDim, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1) }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(if (selected) DesktopAccent.copy(alpha = 0.22f) else Color(0x14FFFFFF))
        .border(1.dp, if (selected) DesktopAccent else Color.Transparent, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) DesktopAccent else DesktopInk, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = DesktopInk, fontSize = 15.sp)
            Text(subtitle, color = DesktopInkDim, fontSize = 12.sp)
        }
        Switch(checked, onChange, Modifier.testTag(tag))
    }
}
