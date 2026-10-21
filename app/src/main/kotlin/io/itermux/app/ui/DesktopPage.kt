package io.itermux.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.desktop.DesktopSettings
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.desktop.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

private val PanelColor = Color(0xF21C1C1E)
private val RailColor = Color(0xFF141416)
private val Ink = Color(0xFFF2F2F7)
private val InkDim = Color(0xFF9A9AA1)
private val Accent = Color(0xFFFF9F0A)

private enum class DesktopPanel { NONE, OPERATIONS, KEYBOARD }

/**
 * A host's desktop (plan §5): the picture fills the page, a rail on the right (bottom in portrait)
 * holds 操作 · 键盘 · 显示桌面 · 所有窗口, everything else lives in the 操作 panel. Leaving keeps the
 * link in the background ([DesktopConnection.pause]).
 */
@Composable
internal fun DesktopPage(connection: DesktopConnection, onLeave: () -> Unit, onDisconnect: () -> Unit, onFiles: () -> Unit,
    modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { DesktopSettings(context) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val phase by connection.phase.collectAsStateWithLifecycle()
    val video by connection.videoSize.collectAsStateWithLifecycle()
    val pending by connection.pending.collectAsStateWithLifecycle()
    var panel by rememberSaveable { mutableStateOf(DesktopPanel.NONE) }
    var mode by remember { mutableStateOf(settings.pointerMode(connection.host.id)) }
    var virtualMouse by rememberSaveable { mutableStateOf(false) }
    val hasPicture = video.first > 0
    BackHandler(panel != DesktopPanel.NONE) { panel = DesktopPanel.NONE }
    BackHandler(panel == DesktopPanel.NONE) { onLeave() }
    DisposableEffect(connection) {
        connection.resume()
        onDispose { if (connection.active) connection.pause() }
    }
    val hello by connection.hello.collectAsStateWithLifecycle()
    val os = hello?.os ?: connection.host.os.lowercase()
    fun action(id: Int) = connection.input(DesktopProtocol.action(id))

    Box(modifier.fillMaxSize().background(Color.Black).testTag("desktop-page")) {
        val rail: @Composable () -> Unit = {
            DesktopRail(landscape, panel, onPanel = { panel = if (panel == it) DesktopPanel.NONE else it },
                onShowDesktop = { action(DesktopProtocol.ACTION_SHOW_DESKTOP) }, onAllWindows = { action(DesktopProtocol.ACTION_ALL_WINDOWS) })
        }
        val screen: @Composable (Modifier) -> Unit = { m ->
            Box(m.background(Color.Black)) {
                if (hasPicture) DesktopScreen(connection, mode)
                if (virtualMouse && hasPicture) VirtualMouse(connection)
                DesktopStatusOverlay(connection, phase, hasPicture, onRetry = { connection.start() }, onClose = onDisconnect)
            }
        }
        if (landscape) Row(Modifier.fillMaxSize()) {
            screen(Modifier.weight(1f).fillMaxHeight())
            rail()
        } else Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            screen(Modifier.weight(1f).fillMaxWidth())
            rail()
        }
        when (panel) {
            DesktopPanel.OPERATIONS -> OperationsPanel(connection, landscape, mode, virtualMouse,
                onMode = { mode = it; settings.setPointerMode(connection.host.id, it) },
                onVirtualMouse = { virtualMouse = it },
                onLeave = { panel = DesktopPanel.NONE; onLeave() },
                onDisconnect = { panel = DesktopPanel.NONE; onDisconnect() },
                onFiles = { panel = DesktopPanel.NONE; onFiles() },
                onDismiss = { panel = DesktopPanel.NONE })
            DesktopPanel.KEYBOARD -> KeyboardPanel(connection, os, landscape) { panel = DesktopPanel.NONE }
            DesktopPanel.NONE -> {}
        }
    }
    pending?.let { PromptDialog(it) }
}

@Composable
private fun DesktopRail(landscape: Boolean, panel: DesktopPanel, onPanel: (DesktopPanel) -> Unit, onShowDesktop: () -> Unit,
    onAllWindows: () -> Unit) {
    val items: List<Triple<String, Int, () -> Unit>> = listOf(
        Triple(tr("操作"), R.drawable.ic_operations) { onPanel(DesktopPanel.OPERATIONS) },
        Triple(tr("键盘"), R.drawable.ic_keyboard) { onPanel(DesktopPanel.KEYBOARD) },
        Triple(tr("显示桌面"), R.drawable.ic_show_desktop, onShowDesktop),
        Triple(tr("所有窗口"), R.drawable.ic_windows_all, onAllWindows),
    )
    val tags = listOf("desktop-rail-operations", "desktop-rail-keyboard", "desktop-rail-show-desktop", "desktop-rail-all-windows")
    val selected = listOf(panel == DesktopPanel.OPERATIONS, panel == DesktopPanel.KEYBOARD, false, false)
    val content: @Composable (Int) -> Unit = { i ->
        val (label, icon, click) = items[i]
        Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = click).padding(horizontal = 6.dp, vertical = 8.dp)
            .widthIn(min = 56.dp).testTag(tags[i]), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), contentDescription = null, Modifier.size(22.dp), tint = if (selected[i]) Accent else Ink)
            Spacer(Modifier.height(3.dp))
            Text(label, fontSize = 11.sp, color = if (selected[i]) Accent else InkDim, maxLines = 1)
        }
    }
    if (landscape) Column(Modifier.fillMaxHeight().width(72.dp).background(RailColor).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End)),
        verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) { items.indices.forEach { content(it) } }
    else Row(Modifier.fillMaxWidth().background(RailColor).padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        items.indices.forEach { content(it) }
    }
}

/** The picture, the cursor drawn locally, and every touch turned into input. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DesktopScreen(connection: DesktopConnection, mode: PointerMode) {
    val density = LocalDensity.current.density
    val hello by connection.hello.collectAsStateWithLifecycle()
    val displayIndex by connection.display.collectAsStateWithLifecycle()
    val video by connection.videoSize.collectAsStateWithLifecycle()
    val cursor by connection.cursor.collectAsStateWithLifecycle()
    val display = hello?.displays?.getOrNull(displayIndex)
    // Coordinates are native pixels of the display; before the hello, the video size stands in.
    val remote = (display?.width ?: video.first) to (display?.height ?: video.second)
    var area by remember { mutableStateOf(IntSize.Zero) }
    val gestures = remember { DesktopGestures(mode, density) }
    gestures.mode = mode
    var viewport by remember { mutableStateOf(Viewport(1f, 1f, 1, 1)) }
    LaunchedEffect(area, remote) {
        if (area.width == 0) return@LaunchedEffect
        viewport = Viewport(area.width.toFloat(), area.height.toFloat(), remote.first, remote.second).let {
            if (it.remoteWidth == viewport.remoteWidth && it.remoteHeight == viewport.remoteHeight)
                it.copy(zoom = viewport.zoom, panX = viewport.panX, panY = viewport.panY).clamped() else it
        }
        gestures.viewport = viewport
    }
    val scope = rememberCoroutineScope()
    var longPress by remember { mutableStateOf<Job?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun apply(actions: List<GestureAction>) {
        val d = displayIndex
        actions.forEach { a ->
            when (a) {
                is GestureAction.MoveTo -> connection.input(DesktopProtocol.abs(d, a.x, a.y))
                is GestureAction.MoveBy -> connection.input(DesktopProtocol.rel(a.dx, a.dy))
                is GestureAction.Button -> connection.input(DesktopProtocol.button(a.button, a.down))
                is GestureAction.Wheel -> connection.input(DesktopProtocol.wheel(a.dx, a.dy))
                is GestureAction.View -> viewport = a.viewport
            }
        }
    }
    fun scheduleLongPress() {
        longPress?.cancel()
        val at = gestures.longPressAt ?: return
        longPress = scope.launch {
            delay((at - SystemClock.uptimeMillis()).coerceAtLeast(0))
            apply(gestures.tick(SystemClock.uptimeMillis()))
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { area = it }
        .focusRequester(focus).onKeyEvent { event ->
            // A hardware keyboard: physical keys straight through as HID usages.
            val hid = HidUsage.fromAndroid(event.nativeKeyEvent.keyCode) ?: return@onKeyEvent false
            when (event.type) {
                KeyEventType.KeyDown -> if (event.nativeKeyEvent.repeatCount == 0) connection.input(DesktopProtocol.key(hid, true))
                    else { connection.input(DesktopProtocol.key(hid, false)); connection.input(DesktopProtocol.key(hid, true)) }
                KeyEventType.KeyUp -> connection.input(DesktopProtocol.key(hid, false))
            }
            true
        }) {
        val v = viewport
        // A fixed buffer of the video's size: zooming only moves and scales the surface, never reallocates it.
        AndroidView(factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { connection.decoder.attach(holder.surface) }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { connection.decoder.attach(holder.surface) }
                    override fun surfaceDestroyed(holder: SurfaceHolder) { connection.decoder.detach() }
                })
            }
        }, update = { view -> if (video.first > 0) view.holder.setFixedSize(video.first, video.second) },
            modifier = Modifier.layout { measurable, _ ->
                val w = v.contentWidth.roundToInt().coerceAtLeast(1)
                val h = v.contentHeight.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                layout(area.width, area.height) { placeable.place(v.left.roundToInt(), v.top.roundToInt()) }
            }.testTag("desktop-surface"))
        // The returning-home gesture works on the black bars only.
        Box(Modifier.layout { measurable, _ ->
            val w = v.contentWidth.roundToInt().coerceIn(1, area.width.coerceAtLeast(1))
            val h = v.contentHeight.roundToInt().coerceIn(1, area.height.coerceAtLeast(1))
            val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
            layout(area.width, area.height) { p.place(v.left.roundToInt().coerceAtLeast(0), v.top.roundToInt().coerceAtLeast(0)) }
        }.systemGestureExclusion())
        Canvas(Modifier.fillMaxSize()) {
            val c = cursor ?: return@Canvas
            if (!c.visible) return@Canvas
            val (x, y) = v.toView(c.x.toFloat(), c.y.toFloat())
            // Drawn at the picture's scale, but never smaller than legible.
            val s = maxOf(v.scale, 0.9f * density / 2f)
            val shape = c.shape
            if (shape != null) translate(x - shape.hotX * s, y - shape.hotY * s) {
                scale(s, s, pivot = Offset.Zero) { drawImage(shape.bitmap.asImageBitmap()) }
            } else {
                val arrow = Path().apply {
                    moveTo(x, y); lineTo(x, y + 18 * density); lineTo(x + 5 * density, y + 13 * density); lineTo(x + 12 * density, y + 12 * density); close()
                }
                drawPath(arrow, Color.White)
            }
        }
        Box(Modifier.fillMaxSize().testTag("desktop-touch").pointerInteropFilter { e ->
            val t = e.eventTime
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { apply(gestures.down(e.x, e.y, t)); scheduleLongPress() }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                    longPress?.cancel()
                    apply(gestures.twoDown(e.getX(0), e.getY(0), e.getX(1), e.getY(1)))
                }
                MotionEvent.ACTION_MOVE -> if (e.pointerCount >= 2) apply(gestures.twoMove(e.getX(0), e.getY(0), e.getX(1), e.getY(1)))
                    else { apply(gestures.move(e.x, e.y, t)); if (gestures.longPressAt == null) longPress?.cancel() }
                MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount == 2) gestures.twoUp()
                MotionEvent.ACTION_UP -> { longPress?.cancel(); apply(gestures.up(e.x, e.y, t)) }
                MotionEvent.ACTION_CANCEL -> { longPress?.cancel(); apply(gestures.cancel()) }
            }
            true
        })
    }
}

/** Connecting steps, needs, failures, the network badge and the recovering card, over the picture. */
@Composable
private fun BoxScope.DesktopStatusOverlay(connection: DesktopConnection, phase: DesktopPhase, hasPicture: Boolean,
    onRetry: () -> Unit, onClose: () -> Unit) {
    val log by connection.log.collectAsStateWithLifecycle()
    val notice by connection.notice.collectAsStateWithLifecycle()
    val showCard = !hasPicture || phase is DesktopPhase.Failed || phase is DesktopPhase.NeedsAction
    if (showCard) Card(Modifier.align(Alignment.Center).widthIn(max = 420.dp).padding(16.dp).testTag("desktop-status"),
        colors = CardDefaults.cardColors(containerColor = PanelColor), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(connection.host.name, color = InkDim, fontSize = 13.sp)
            when (phase) {
                is DesktopPhase.Failed -> {
                    Text(phase.message, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("desktop-error"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRetry, Modifier.testTag("desktop-retry")) { Text(tr("重试")) }
                        TextButton(onClick = onClose) { Text(tr("关闭"), color = InkDim) }
                    }
                }
                is DesktopPhase.NeedsAction -> {
                    Text(needsText(phase.needs), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("desktop-needs"))
                    Text(tr("满足后会自动连接"), color = InkDim, fontSize = 13.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                }
                else -> {
                    val step = (phase as? DesktopPhase.Connecting)?.step
                    Text(tr("正在打开桌面"), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    StepLine(tr("登录主机"), step == DesktopPhase.Step.LOGIN, step != null && step != DesktopPhase.Step.LOGIN)
                    StepLine(if (step == DesktopPhase.Step.INSTALL) tr("安装桌面插件") else tr("启动桌面插件"),
                        step == DesktopPhase.Step.INSTALL || step == DesktopPhase.Step.START, step == DesktopPhase.Step.FIRST_FRAME)
                    StepLine(tr("等待画面"), step == DesktopPhase.Step.FIRST_FRAME, false)
                }
            }
            if (log.isNotEmpty() && !hasPicture) Text(log.takeLast(5).joinToString("\n"), color = InkDim, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, maxLines = 5, modifier = Modifier.testTag("desktop-log"))
        }
    }
    if (hasPicture) {
        NetworkBadge(connection, phase, Modifier.align(Alignment.BottomStart).padding(10.dp))
        val recovering = phase as? DesktopPhase.Recovering
        var longEnough by remember(recovering?.sinceMs) { mutableStateOf(false) }
        LaunchedEffect(recovering?.sinceMs) {
            longEnough = false
            if (recovering != null) { delay((2000 - (System.currentTimeMillis() - recovering.sinceMs)).coerceAtLeast(0)); longEnough = true }
        }
        if (recovering != null && longEnough) Card(Modifier.align(Alignment.Center).testTag("desktop-recovering"),
            colors = CardDefaults.cardColors(containerColor = PanelColor)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(tr("连接中断，正在恢复…"), color = Ink)
            }
        }
    }
    notice?.let { Text(it, Modifier.align(Alignment.TopCenter).padding(10.dp).clip(CircleShape).background(PanelColor)
        .padding(horizontal = 14.dp, vertical = 6.dp).testTag("desktop-notice"), color = Ink, fontSize = 13.sp) }
}

private fun needsText(needs: List<String>): String = when (needs.firstOrNull()) {
    "not-logged-in" -> tr("请先在这台电脑上登录桌面")
    "screen-recording" -> tr("请在 Mac 上允许“屏幕录制”：系统设置 › 隐私与安全性 › 屏幕与系统音频录制，打开 Roamtty Desktop")
    "accessibility" -> tr("请在 Mac 上允许“辅助功能”：系统设置 › 隐私与安全性 › 辅助功能，打开 Roamtty Desktop")
    "locked" -> tr("请在本机解锁")
    "portal-approval" -> tr("请在电脑上点“允许”共享屏幕")
    else -> tr("需要在电脑上操作")
}

@Composable
private fun StepLine(label: String, current: Boolean, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = StatusColors.success)
                current -> CircularProgressIndicator(Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
                else -> Box(Modifier.size(8.dp).clip(CircleShape).background(InkDim))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (current || done) Ink else InkDim, fontSize = 14.sp)
    }
}

@Composable
private fun NetworkBadge(connection: DesktopConnection, phase: DesktopPhase, modifier: Modifier) {
    val stats by connection.stats.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val poor = (phase as? DesktopPhase.Connected)?.poor == true
    val dot = when {
        phase is DesktopPhase.Recovering -> InkDim
        poor -> StatusColors.warning
        else -> StatusColors.success
    }
    Column(modifier) {
        if (open) Card(colors = CardDefaults.cardColors(containerColor = PanelColor), modifier = Modifier.padding(bottom = 6.dp).testTag("desktop-stats")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(
                    tr("延迟") to (stats.rttMs?.let { "$it ms" } ?: "—"),
                    tr("码率") to "${stats.kbps} kbps",
                    tr("帧率") to "${stats.fps} fps",
                    tr("分辨率") to if (stats.width > 0) "${stats.width}×${stats.height}" else "—",
                    tr("链路") to stats.link,
                    tr("丢包") to (stats.lossPercent?.let { "%.1f %%".format(it) } ?: "—"),
                    tr("FEC 恢复") to "${stats.fecRecovered}",
                    tr("解码器") to stats.decoder.ifEmpty { "—" },
                    tr("编码器") to stats.encoder.ifEmpty { "—" },
                    tr("已解码") to "${stats.frames}",
                ).forEach { (k, v) ->
                    Row { Text(k, color = InkDim, fontSize = 12.sp, modifier = Modifier.width(64.dp)); Text(v, color = Ink, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                }
            }
        }
        Row(Modifier.clip(CircleShape).background(PanelColor).clickable { open = !open }.padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("desktop-network"), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(when {
                phase is DesktopPhase.Recovering -> tr("恢复中")
                poor -> tr("网络较差")
                else -> listOfNotNull(stats.link.takeIf { it == "UDP" }, stats.rttMs?.let { "$it ms" }).joinToString(" · ").ifEmpty { "SSH" }
            }, color = Ink, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BoxScope.OperationsPanel(connection: DesktopConnection, landscape: Boolean, mode: PointerMode, virtualMouse: Boolean,
    onMode: (PointerMode) -> Unit, onVirtualMouse: (Boolean) -> Unit, onLeave: () -> Unit, onDisconnect: () -> Unit,
    onFiles: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewOnly by connection.viewOnly.collectAsStateWithLifecycle()
    val lock by connection.lockOnClose.collectAsStateWithLifecycle()
    val quality by connection.quality.collectAsStateWithLifecycle()
    val fps by connection.fps.collectAsStateWithLifecycle()
    val hello by connection.hello.collectAsStateWithLifecycle()
    val display by connection.display.collectAsStateWithLifecycle()
    val stats by connection.stats.collectAsStateWithLifecycle()
    var clipNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(connection) {
        connection.remoteClipboard.collect { text ->
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Roamtty", text))
            clipNote = if (text.isEmpty()) tr("远程剪贴板为空") else trf("已复制远程剪贴板（%d 字）", text.length)
        }
    }
    val refresh = (context.display?.refreshRate ?: 60f).roundToInt()
    val metered = remember { context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = onDismiss))
    Card(Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
        .then(if (landscape) Modifier.padding(end = 80.dp).width(340.dp).fillMaxHeight(0.92f) else Modifier.fillMaxWidth().fillMaxHeight(0.7f))
        .testTag("desktop-operations"), colors = CardDefaults.cardColors(containerColor = PanelColor), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelButton(tr("退出"), R.drawable.ic_exit, "desktop-op-exit", Modifier.weight(1f), onLeave)
                PanelButton(tr("文件"), R.drawable.ic_folder, "desktop-op-files", Modifier.weight(1f), onFiles)
                PanelButton(tr("断开"), R.drawable.ic_close, "desktop-op-disconnect", Modifier.weight(1f), onDisconnect)
            }
            Section(tr("剪贴板")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelButton(tr("发送手机剪贴板"), R.drawable.ic_upload, "desktop-op-clip-send", Modifier.weight(1f)) {
                        val text = context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (text.isEmpty()) clipNote = tr("手机剪贴板为空")
                        else { connection.send(DesktopProtocol.clipSet(text.take(1 shl 20))); clipNote = tr("已发送到远程剪贴板") }
                    }
                    PanelButton(tr("获取远程剪贴板"), R.drawable.ic_download, "desktop-op-clip-get", Modifier.weight(1f)) {
                        connection.send(DesktopProtocol.req(DesktopProtocol.REQ_CLIP_GET))
                    }
                }
                clipNote?.let { Text(it, color = InkDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).testTag("desktop-clip-note")) }
            }
            Section(tr("操作方式")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(tr("屏幕触控"), mode == PointerMode.TOUCH, "desktop-mode-touch", Modifier.weight(1f)) { onMode(PointerMode.TOUCH) }
                    Choice(tr("鼠标指针"), mode == PointerMode.MOUSE, "desktop-mode-mouse", Modifier.weight(1f)) { onMode(PointerMode.MOUSE) }
                }
            }
            ToggleRow(tr("仅观看"), tr("不向电脑发送任何操作"), viewOnly, "desktop-view-only") { connection.setViewOnly(it) }
            ToggleRow(tr("虚拟鼠标"), tr("悬浮的左键、右键和滚轮"), virtualMouse, "desktop-virtual-mouse") { onVirtualMouse(it) }
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
                    color = InkDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            hello?.displays?.takeIf { it.size > 1 }?.let { displays ->
                Section(tr("显示器")) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        displays.forEachIndexed { i, d -> Choice(d.name.ifEmpty { trf("显示器 %d", i + 1) }, display == i, "desktop-display-$i") { connection.setDisplay(i) } }
                    }
                }
            }
            ToggleRow(tr("断开后锁定电脑"), tr("连接真正断开时锁屏，退出到后台不算"), lock, "desktop-lock-on-close") { connection.setLockOnClose(it) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, color = InkDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
private fun PanelButton(label: String, icon: Int, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x14FFFFFF)).clickable(onClick = onClick)
        .padding(vertical = 10.dp, horizontal = 6.dp).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = Ink)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Ink, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(if (selected) Accent.copy(alpha = 0.22f) else Color(0x14FFFFFF))
        .border(1.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) Accent else Ink, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp)
            Text(subtitle, color = InkDim, fontSize = 12.sp)
        }
        Switch(checked, onChange, Modifier.testTag(tag))
    }
}

/** A key of the computer keyboard layout (assets/desktop/keyboard-*.json). */
private data class LayoutKey(val label: String, val hid: Int, val width: Float)

private fun loadLayout(context: android.content.Context, os: String): List<List<List<LayoutKey>>> {
    val name = when (os.lowercase()) { "macos" -> "mac"; "windows" -> "win"; else -> "linux" }
    val json = JSONObject(context.assets.open("desktop/keyboard-$name.json").bufferedReader().use { it.readText() })
    val pages = json.getJSONArray("pages")
    return (0 until pages.length()).map { p ->
        val rows = pages.getJSONObject(p).getJSONArray("rows")
        (0 until rows.length()).map { r ->
            val keys = rows.getJSONArray(r)
            (0 until keys.length()).map { k -> keys.getJSONObject(k).let { LayoutKey(it.getString("l"), it.getInt("k"), it.optDouble("w", 1.0).toFloat()) } }
        }
    }
}

/**
 * Two pages: the phone's input method (composed text goes out as text, with a row of shortcut keys)
 * and a computer keyboard whose keys go by position; modifiers latch for one key, or stay on a long press.
 */
@Composable
private fun BoxScope.KeyboardPanel(connection: DesktopConnection, os: String, landscape: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(0) }
    val latch = remember { ModifierLatch() }
    var latchRevision by remember { mutableStateOf(0) }
    val layout = remember(os) { loadLayout(context, os) }
    var layoutPage by remember { mutableStateOf(0) }
    val mac = os.lowercase() == "macos"
    fun send(events: List<Pair<Int, Boolean>>) { events.forEach { (k, d) -> connection.input(DesktopProtocol.key(k, d)) }; latchRevision++ }
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(if (landscape) Modifier.padding(end = 72.dp) else Modifier)
        .background(PanelColor).imePadding().testTag("desktop-keyboard")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Choice(tr("输入法"), page == 0, "desktop-kb-ime") { page = 0 }
            Spacer(Modifier.width(6.dp))
            Choice(tr("电脑键盘"), page == 1, "desktop-kb-pc") { page = 1 }
            Spacer(Modifier.weight(1f))
            if (page == 1) {
                Choice(if (latch.combo) tr("组合键：开") else tr("组合键"), latch.combo, "desktop-kb-combo") {
                    latch.setCombo(!latch.combo); latchRevision++
                }
                if (latch.combo) {
                    Spacer(Modifier.width(6.dp))
                    Choice(tr("发送"), false, "desktop-kb-combo-send") { send(latch.sendCombo()) }
                }
                Spacer(Modifier.width(6.dp))
                Choice(if (layoutPage == 0) "Fn" else "ABC", false, "desktop-kb-page") { layoutPage = 1 - layoutPage }
            }
            Spacer(Modifier.width(6.dp))
            Icon(painterResource(R.drawable.ic_close), tr("关闭键盘"), Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onDismiss).padding(6.dp), tint = Ink)
        }
        if (page == 0) ImeInput(connection, mac) else {
            key(latchRevision) {
                Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    layout.getOrNull(layoutPage)?.forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { k ->
                                val isMod = k.hid in HidUsage.MODIFIERS
                                val state = if (isMod) latch.state(k.hid) else ModifierLatch.State.OFF
                                val pendingCombo = k.hid in latch.pending
                                Box(Modifier.weight(k.width).height(if (landscape) 34.dp else 42.dp).clip(RoundedCornerShape(6.dp))
                                    .background(when {
                                        state == ModifierLatch.State.LOCKED -> Accent
                                        state == ModifierLatch.State.ONCE || pendingCombo -> Accent.copy(alpha = 0.4f)
                                        isMod -> Color(0x22FFFFFF)
                                        else -> Color(0x33FFFFFF)
                                    })
                                    .pointerInput(k.hid, latch.combo) {
                                        detectTapGestures(onLongPress = { if (isMod && !latch.combo) { latch.hold(k.hid); latchRevision++ } }) {
                                            if (isMod && !latch.combo) { latch.tap(k.hid); latchRevision++ } else send(latch.press(k.hid))
                                        }
                                    }.testTag("desktop-key-${k.hid}"), contentAlignment = Alignment.Center) {
                                    Text(k.label, color = Ink, fontSize = if (k.label.length > 3) 11.sp else 14.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The phone's input method: finished text is typed on the host as text (any language), Backspace
 * and Enter as keys, while a composition (pinyin) stays here until committed.
 */
@Composable
private fun ImeInput(connection: DesktopConnection, mac: Boolean) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun key(hid: Int, mods: List<Int> = emptyList()) {
        mods.forEach { connection.input(DesktopProtocol.key(it, true)) }
        connection.input(DesktopProtocol.key(hid, true)); connection.input(DesktopProtocol.key(hid, false))
        mods.reversed().forEach { connection.input(DesktopProtocol.key(it, false)) }
    }
    val cmd = if (mac) HidUsage.LEFT_GUI else HidUsage.LEFT_CTRL
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            "Esc" to { key(HidUsage.ESCAPE) }, "Tab" to { key(HidUsage.TAB) },
            "←" to { key(HidUsage.LEFT) }, "↑" to { key(HidUsage.UP) }, "↓" to { key(HidUsage.DOWN) }, "→" to { key(HidUsage.RIGHT) },
            (if (mac) "⌘C" else "Ctrl+C") to { key(HidUsage.C, listOf(cmd)) }, (if (mac) "⌘V" else "Ctrl+V") to { key(HidUsage.V, listOf(cmd)) },
            (if (mac) "⌘Z" else "Ctrl+Z") to { key(HidUsage.Z, listOf(cmd)) }, (if (mac) "⌘A" else "Ctrl+A") to { key(HidUsage.A, listOf(cmd)) },
            "Del" to { key(HidUsage.DELETE) }, "Home" to { key(HidUsage.HOME) }, "End" to { key(HidUsage.END) },
        ).forEach { (label, click) -> Choice(label, false, "desktop-quick-$label") { click() } }
    }
    BasicTextField(value, onValueChange = { next ->
        // Composition still running: keep it here. Committed: send what is new, then start over.
        if (next.composition != null) { value = next; return@BasicTextField }
        val old = value.text
        val text = next.text
        if (text.length > old.length && text.startsWith(old)) connection.input(DesktopProtocol.text(text.substring(old.length)))
        else if (text.length < old.length && old.startsWith(text)) repeat(old.length - text.length) { key(HidUsage.BACKSPACE) }
        else if (text != old && text.isNotEmpty()) connection.input(DesktopProtocol.text(text))
        value = TextFieldValue("", TextRange.Zero)
    }, modifier = Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF))
        .padding(horizontal = 12.dp, vertical = 10.dp).focusRequester(focus)
        .onPreviewKeyEvent { e ->
            // On an empty field the IME sends Backspace and Enter as key events.
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.Backspace -> if (value.text.isEmpty()) { key(HidUsage.BACKSPACE); true } else false
                Key.Enter, Key.NumPadEnter -> { key(HidUsage.ENTER); true }
                else -> false
            }
        }.testTag("desktop-ime-field"),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink), cursorBrush = SolidColor(Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSend = { key(HidUsage.ENTER) }),
        decorationBox = { inner -> Box { if (value.text.isEmpty()) Text(tr("在这里输入，文字直接发到电脑"), color = InkDim); inner() } })
}

/** A floating mouse: drag its pad to move the cursor, buttons for left and right, a strip to scroll. */
@Composable
private fun BoxScope.VirtualMouse(connection: DesktopConnection) {
    var offset by remember { mutableStateOf(IntOffset(0, 0)) }
    val density = LocalDensity.current.density
    var rest by remember { mutableStateOf(Offset.Zero) }
    Column(Modifier.align(Alignment.BottomEnd).padding(16.dp).offset { offset }.width(132.dp).clip(RoundedCornerShape(20.dp))
        .background(PanelColor).testTag("desktop-mouse")) {
        Box(Modifier.fillMaxWidth().height(18.dp).pointerInput(Unit) {
            detectDragGestures { change, drag -> change.consume(); offset += IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
        }, contentAlignment = Alignment.Center) { Box(Modifier.size(28.dp, 4.dp).clip(CircleShape).background(InkDim)) }
        Row(Modifier.fillMaxWidth().height(44.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Color(0x22FFFFFF)).pointerInput(Unit) {
                detectTapGestures(onPress = {
                    connection.input(DesktopProtocol.button(1, true)); tryAwaitRelease(); connection.input(DesktopProtocol.button(1, false))
                })
            }.testTag("desktop-mouse-left"))
            Box(Modifier.width(18.dp).fillMaxHeight().pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    connection.input(DesktopProtocol.wheel(0, (-drag.y * 4 / density).roundToInt()))
                }
            }.testTag("desktop-mouse-wheel"), contentAlignment = Alignment.Center) { Box(Modifier.size(4.dp, 20.dp).clip(CircleShape).background(InkDim)) }
            Box(Modifier.weight(1f).fillMaxHeight().background(Color(0x22FFFFFF)).pointerInput(Unit) {
                detectTapGestures(onPress = {
                    connection.input(DesktopProtocol.button(2, true)); tryAwaitRelease(); connection.input(DesktopProtocol.button(2, false))
                })
            }.testTag("desktop-mouse-right"))
        }
        Box(Modifier.fillMaxWidth().height(70.dp).pointerInput(Unit) {
            detectDragGestures { change, drag ->
                change.consume()
                rest += drag * 1.5f
                val dx = rest.x.toInt(); val dy = rest.y.toInt()
                rest -= Offset(dx.toFloat(), dy.toFloat())
                if (dx != 0 || dy != 0) connection.input(DesktopProtocol.rel(dx, dy))
            }
        }.testTag("desktop-mouse-pad"), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_mouse), null, Modifier.size(22.dp), tint = InkDim)
        }
    }
}

/** The desktop button at the end of a session card's bottom row (plan §4.3). */
@Composable
internal fun DesktopCardButton(state: DesktopButton, tag: String, onClick: () -> Unit) {
    if (state == DesktopButton.HIDDEN) return
    val colors = MaterialTheme.colorScheme
    val live = state == DesktopButton.CONNECTED || state == DesktopButton.POOR || state == DesktopButton.RECOVERING
    // 300 ms after a tap before the spinner shows, so a quick open never flashes it.
    var spin by remember { mutableStateOf(false) }
    val busy = state == DesktopButton.CONNECTING || state == DesktopButton.RECOVERING
    LaunchedEffect(busy) { spin = false; if (busy) { delay(300); spin = true } }
    Box(Modifier.size(44.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = tr("打开桌面"), onClick = onClick)
        .testTag(tag), contentAlignment = Alignment.Center) {
        Box(Modifier.size(34.dp, 30.dp).clip(RoundedCornerShape(9.dp))
            .background(if (live) Accent.copy(alpha = 0.22f) else colors.onSurface.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
            if (spin) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = if (live) Accent else colors.onSurface)
            else Icon(painterResource(R.drawable.ic_desktop), contentDescription = tr("桌面"), Modifier.size(16.dp),
                tint = if (live) Accent else colors.onSurface)
        }
        val badge = when (state) {
            DesktopButton.CONNECTED -> StatusColors.success
            DesktopButton.POOR, DesktopButton.NEEDS_ACTION -> StatusColors.warning
            else -> null
        }
        if (badge != null) Box(Modifier.align(Alignment.Center).offset(13.dp, (-11).dp).size(8.dp).clip(CircleShape).background(badge)
            .border(1.5.dp, colors.surface, CircleShape).testTag("$tag-badge-${state.name.lowercase()}"))
    }
}
