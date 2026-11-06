package io.itermux.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.desktop.DesktopInputProbe
import io.itermux.app.desktop.DesktopThumbnails
import io.itermux.app.desktop.DesktopSettings
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.desktop.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Constraints

private val PanelColor = Color(0xF21C1C1E)
private val RailColor = Color(0xFF141416)
private val Ink = Color(0xFFF2F2F7)
private val InkDim = Color(0xFF9A9AA1)
private val Accent = Color(0xFFFF9F0A)

internal enum class DesktopPanel { NONE, OPERATIONS, KEYBOARD }

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
    val landscape = isLandscape()
    val phase by connection.phase.collectAsStateWithLifecycle()
    val video by connection.videoSize.collectAsStateWithLifecycle()
    val pending by connection.pending.collectAsStateWithLifecycle()
    var panel by rememberSaveable { mutableStateOf(DesktopPanel.NONE) }
    var mode by remember { mutableStateOf(settings.pointerMode(connection.host.id)) }
    var virtualMouse by rememberSaveable { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(settings.showStats) }
    val statsBox = remember { StatsBox(settings.statsPosition) }
    // Closing the figures with their ✕ says once where to bring them back.
    var statsHint by remember { mutableStateOf(false) }
    LaunchedEffect(statsHint) { if (statsHint) { delay(2500); statsHint = false } }
    val hasPicture = video.first > 0
    BackHandler(panel != DesktopPanel.NONE) { panel = DesktopPanel.NONE }
    BackHandler(panel == DesktopPanel.NONE) { onLeave() }
    DisposableEffect(connection) {
        connection.resume()
        onDispose { if (connection.active) connection.pause() }
    }
    val hello by connection.hello.collectAsStateWithLifecycle()
    val os = hello?.os ?: connection.host.os.lowercase()
    val pageView = LocalView.current
    // 显示桌面 and 所有窗口 answer with a tap at once: the host may take a moment to show it.
    fun action(id: Int) {
        pageView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        DesktopInputProbe.token("a$id")
        connection.input(DesktopProtocol.action(id))
    }

    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Box(modifier.fillMaxSize().background(Color.Black).testTag("desktop-page").onPreviewKeyEvent { event ->
        // Back (the key, and the system's back gesture, which arrives as it) goes straight to the back handlers. Left
        // to Compose, the first one only clears the focus the picture holds for a hardware keyboard, and a second
        // back was needed to leave.
        if (event.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyUp && !event.nativeKeyEvent.isCanceled) backDispatcher?.onBackPressed()
        true
    }) {
        val rail: @Composable () -> Unit = {
            DesktopRail(landscape, panel, onPanel = { panel = if (panel == it) DesktopPanel.NONE else it },
                onShowDesktop = { action(DesktopProtocol.ACTION_SHOW_DESKTOP) }, onAllWindows = { action(DesktopProtocol.ACTION_ALL_WINDOWS) })
        }
        val screen: @Composable (Modifier) -> Unit = { m ->
            Box(m.background(Color.Black)) {
                if (hasPicture) DesktopScreen(connection, mode, keys = panel == DesktopPanel.NONE, statsBox,
                    onStatsMoved = { settings.statsPosition = it })
                if (virtualMouse && hasPicture) VirtualMouse(connection)
                DesktopStatusOverlay(connection, phase, hasPicture, showStats, statsBox, statsHint,
                    onCloseStats = { showStats = false; settings.showStats = false; statsHint = true },
                    onRetry = { connection.start() }, onClose = onDisconnect)
            }
        }
        if (landscape) Row(Modifier.fillMaxSize()) {
            // The cutout's side only: on the other the picture runs to the screen edge.
            screen(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)))
            rail()
        // The rail takes most of a gesture bar's inset itself; the keyboard panel keeps it all.
        } else Column(Modifier.fillMaxSize().windowInsetsPadding(if (panel == DesktopPanel.KEYBOARD) WindowInsets.safeDrawing
            else WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            screen(Modifier.weight(1f).fillMaxWidth())
            // While typing in portrait the input method takes the rail's place, as in the terminal.
            if (panel != DesktopPanel.KEYBOARD) rail()
        }
        when (panel) {
            DesktopPanel.OPERATIONS -> OperationsPanel(connection, landscape, mode, virtualMouse, showStats,
                onMode = {
                    if (it != mode) pageView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    mode = it; settings.setPointerMode(connection.host.id, it)
                },
                onVirtualMouse = { virtualMouse = it },
                onShowStats = { showStats = it; settings.showStats = it },
                statsMoved = statsBox.moved,
                onResetStats = { statsBox.reset(); settings.statsPosition = 0f to 0f; showStats = true; settings.showStats = true },
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
internal fun DesktopRail(landscape: Boolean, panel: DesktopPanel, onPanel: (DesktopPanel) -> Unit, onShowDesktop: () -> Unit,
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
            Icon(painterResource(icon), contentDescription = null, Modifier.size(if (landscape) 24.dp else 22.dp), tint = if (selected[i]) Accent else Ink)
            Spacer(Modifier.height(3.dp))
            Text(label, fontSize = if (landscape) 12.sp else 11.sp, color = if (selected[i]) Accent else InkDim, maxLines = 2,
                textAlign = TextAlign.Center) // The landscape rail is narrower than a two-word English label.
        }
    }
    // A cutout on the rail's side widens the rail to the screen edge instead of squeezing its buttons.
    // 操作 stays in the bottom corner: leftmost in portrait, lowest in landscape.
    if (landscape) Column(Modifier.fillMaxHeight().background(RailColor).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End)).width(72.dp),
        verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) { items.indices.reversed().forEach { content(it) } }
    else Row(Modifier.fillMaxWidth().background(RailColor).padding(top = 4.dp, bottom = portraitRailBottom()), horizontalArrangement = Arrangement.SpaceEvenly) {
        items.indices.forEach { content(it) }
    }
}

/** Under the portrait rail: just clear of a gesture bar's handle, all of a 3-button bar (it can be tapped). */
@Composable
private fun portraitRailBottom(): Dp {
    val density = LocalDensity.current
    val nav = WindowInsets.navigationBars.getBottom(density)
    val tappable = WindowInsets.tappableElement.getBottom(density)
    return with(density) { maxOf(tappable.toDp(), nav.toDp() - 12.dp, 4.dp) }
}

/** The picture, the cursor drawn locally, and every touch turned into input. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DesktopScreen(connection: DesktopConnection, mode: PointerMode, keys: Boolean, stats: StatsBox,
    onStatsMoved: (Pair<Float, Float>) -> Unit) {
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
    val focus = remember { FocusRequester() }
    // A hardware keyboard types into the picture whenever no panel holds the focus.
    LaunchedEffect(keys) { if (keys) runCatching { focus.requestFocus() } }

    val view = LocalView.current
    // Bumped by every gesture call: the cursor and the ring redraw from the engine's state.
    var drawn by remember { mutableIntStateOf(0) }
    val os = hello?.os ?: connection.host.os
    fun apply(actions: List<GestureAction>) {
        val d = displayIndex
        actions.forEach { a ->
            when (a) {
                is GestureAction.MoveTo -> connection.input(DesktopProtocol.abs(d, a.x, a.y))
                is GestureAction.Button -> connection.input(DesktopProtocol.button(a.button, a.down))
                is GestureAction.Wheel -> connection.input(DesktopProtocol.wheel(a.dx, a.dy))
                is GestureAction.View -> viewport = a.viewport
                is GestureAction.Swipe -> DesktopSwipeShortcut.frames(a.direction, os).forEach(connection::input)
                is GestureAction.Feedback -> view.performHapticFeedback(when (a.kind) {
                    GestureFeedback.HOLD, GestureFeedback.PICKUP -> HapticFeedbackConstants.LONG_PRESS
                    GestureFeedback.CLICK, GestureFeedback.SHORTCUT -> HapticFeedbackConstants.KEYBOARD_TAP
                })
            }
            DesktopInputProbe.record(a)
        }
        drawn++
    }
    // One timer on the main looper for whenever the engine next needs a tick (a hold, a waiting right click,
    // inertia), on the uptime clock touches carry: not a coroutine delay, whose clock a test may hold still.
    val ticker = remember { Handler(Looper.getMainLooper()) }
    val tickToken = remember { Any() }
    var pendingAt by remember { mutableStateOf<Long?>(null) }
    fun scheduleTick() {
        val at = gestures.tickAt
        if (at == pendingAt) return
        ticker.removeCallbacksAndMessages(tickToken)
        pendingAt = at ?: return
        ticker.postAtTime({
            pendingAt = null
            apply(gestures.tick(SystemClock.uptimeMillis()))
            scheduleTick()
        }, tickToken, at)
    }
    // The host's cursor: taken unless the phone moved its own a moment ago.
    LaunchedEffect(cursor) { cursor?.let { gestures.hostCursor(it.x, it.y, SystemClock.uptimeMillis()); drawn++ } }
    // The hosts list shows each desktop's last picture: one soon after it appears, then every few seconds, and on leaving.
    val context = LocalContext.current
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    LaunchedEffect(surfaceView, video) {
        val view = surfaceView ?: return@LaunchedEffect
        if (video.first <= 0) return@LaunchedEffect
        delay(1500)
        while (true) {
            DesktopThumbnails.captureAndSave(context, connection.host.id, view, video.first, video.second)
            delay(10_000)
        }
    }
    DisposableEffect(surfaceView) {
        onDispose {
            surfaceView?.let { DesktopThumbnails.captureAndSave(context, connection.host.id, it, video.first, video.second) }
        }
    }
    // Leaving the page mid-drag or right after a tap must not leave the host's button down.
    DisposableEffect(Unit) { onDispose { ticker.removeCallbacksAndMessages(tickToken); apply(gestures.cancel()) } }

    // Each pointer keeps its id while it touches: the engine follows fingers, not indices.
    fun feed(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                gestures.mode = mode
                gestures.viewport = viewport
                if (e.actionMasked == MotionEvent.ACTION_DOWN) DesktopInputProbe.gestureStarted(e.eventTime)
                val i = e.actionIndex
                apply(gestures.began(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime))
            }
            MotionEvent.ACTION_MOVE -> apply(gestures.moved((0 until e.pointerCount).map { Triple(e.getPointerId(it), e.getX(it), e.getY(it)) }, e.eventTime))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                apply(gestures.ended(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime))
                if (e.actionMasked == MotionEvent.ACTION_UP) DesktopInputProbe.gestureEnded(e.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> apply(gestures.cancel())
        }
        // The next tick follows whatever the touch changed (a hold, a waiting right click, inertia).
        scheduleTick()
    }
    // A touch that starts on the network figures waits: a still hold moves them, anything else is replayed
    // to the picture as it came ([OverlayTouch]), so the figures never take a gesture from the desktop.
    val overlay = remember { OverlayTouch(ViewConfiguration.get(context).scaledTouchSlop.toFloat()) }
    val held = remember { ArrayList<MotionEvent>() }
    val holdToken = remember { Any() }
    // Where the touch area sits in the window, the first finger and where it is: plain fields, read per event.
    val track = remember { object { var origin = Offset.Zero; var firstId = 0; var last = Offset.Zero } }
    fun release() {
        ticker.removeCallbacksAndMessages(holdToken)
        held.forEach { it.recycle() }
        held.clear()
    }
    fun pickUp() {
        release()
        stats.startDrag(track.last.x, track.last.y)
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
    fun drop() {
        if (stats.dragging) { stats.dragging = false; onStatsMoved(stats.fx to stats.fy) }
    }
    fun route(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            release(); drop()
            track.firstId = e.getPointerId(0)
            track.last = Offset(e.x, e.y)
            val onBox = stats.bounds?.contains(Offset(e.x + track.origin.x, e.y + track.origin.y)) == true
            if (overlay.down(onBox, e.x, e.y, e.eventTime) != OverlayTouch.Owner.PENDING) return feed(e)
            held += MotionEvent.obtain(e)
            ticker.postAtTime({ if (overlay.tick(SystemClock.uptimeMillis()) == OverlayTouch.Owner.BOX) pickUp() },
                holdToken, overlay.holdAt ?: return)
            return
        }
        val before = overlay.owner
        val first = e.findPointerIndex(track.firstId).coerceAtLeast(0)
        val x = e.getX(first)
        val y = e.getY(first)
        val after = when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> overlay.moved(x, y, e.eventTime)
            MotionEvent.ACTION_POINTER_DOWN -> overlay.otherFinger()
            MotionEvent.ACTION_UP -> overlay.up(e.eventTime)
            MotionEvent.ACTION_CANCEL -> before.also { overlay.reset() }
            else -> before
        }
        if (before == OverlayTouch.Owner.PENDING) track.last = Offset(x, y)
        when (after) {
            OverlayTouch.Owner.PENDING -> held += MotionEvent.obtain(e)
            OverlayTouch.Owner.PICTURE -> {
                if (before == OverlayTouch.Owner.PENDING) { held.forEach(::feed); release() }
                feed(e)
            }
            OverlayTouch.Owner.BOX -> {
                if (before == OverlayTouch.Owner.PENDING) pickUp()
                if (e.actionMasked == MotionEvent.ACTION_MOVE) stats.dragTo(x, y)
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) drop()
            }
            OverlayTouch.Owner.NONE -> {}
        }
        if (e.actionMasked == MotionEvent.ACTION_CANCEL && before == OverlayTouch.Owner.PENDING) release()
    }
    DisposableEffect(Unit) { onDispose { release(); stats.dragging = false } }

    Box(Modifier.fillMaxSize().onSizeChanged { area = it }
        .focusRequester(focus).focusable().onKeyEvent { event ->
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
            SurfaceView(ctx).also { surfaceView = it }.apply {
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
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(area.width, area.height) { placeable.place(v.left.roundToInt(), v.top.roundToInt()) }
            }.testTag("desktop-surface"))
        // The system's back gesture works on the black bars only: the picture keeps its edges for edge swipes.
        // (The rect, not a layout's bounds: a layout modifier reports the whole view as its size.)
        Box(Modifier.fillMaxSize().systemGestureExclusion {
            Rect(maxOf(0f, v.left), maxOf(0f, v.top),
                minOf(it.size.width.toFloat(), v.left + v.contentWidth), minOf(it.size.height.toFloat(), v.top + v.contentHeight))
        })
        Canvas(Modifier.fillMaxSize()) {
            drawn.let { }
            val c = cursor
            if (!gestures.hasCursor || c?.visible == false) return@Canvas
            // The phone's cursor: where its last move put it, ahead of the host's echo.
            val (x, y) = v.toView(gestures.cursorX, gestures.cursorY)
            // The host's image at the picture's scale, kept between CURSOR_MIN_DP and CURSOR_MAX_DP: the
            // picture is often a fifth of the host's size, and zoomed in five times.
            val shape = c?.shape
            if (shape != null) {
                val size = maxOf(shape.bitmap.width, shape.bitmap.height, 1)
                val s = v.scale.coerceIn(CURSOR_MIN_DP * density / size, CURSOR_MAX_DP * density / size)
                translate(x - shape.hotX * s, y - shape.hotY * s) {
                    scale(s, s, pivot = Offset.Zero) { drawImage(shape.bitmap.asImageBitmap()) }
                }
            } else {
                // No image from the host: the standard arrow, white with a black edge, 15 dp tall.
                val u = 15 * density / 21f
                val arrow = Path().apply {
                    moveTo(x, y); lineTo(x, y + 15.5f * u); lineTo(x + 4 * u, y + 11.7f * u); lineTo(x + 6.8f * u, y + 18 * u)
                    lineTo(x + 9.3f * u, y + 16.9f * u); lineTo(x + 6.6f * u, y + 10.8f * u); lineTo(x + 11.5f * u, y + 10.8f * u); close()
                }
                drawPath(arrow, Color.White)
                drawPath(arrow, Color.Black, style = Stroke(width = 1.2f * u))
            }
        }
        // Read inside the ring alone, so a touch recomposes only it.
        HoldRing({ drawn.let { gestures.indicator } }, density)
        Box(Modifier.fillMaxSize().testTag("desktop-touch").onGloballyPositioned { track.origin = it.positionInWindow() }
            .pointerInteropFilter { e -> route(e); true })
    }
}

/**
 * The hold ring under the fingers: nothing for a tap (it starts after [RING_DELAY_MS]), then it fills
 * as the hold comes and turns solid when it fires, blue for the left button, red for the right.
 */
@Composable
private fun HoldRing(indicator: () -> HoldIndicator?, density: Float) {
    val ring = indicator() ?: return
    var progress by remember(ring) { mutableFloatStateOf(if (ring.armed) 1f else 0f) }
    if (!ring.armed) LaunchedEffect(ring) {
        val start = ring.firesAt - (if (ring.fingers > 1) DesktopGestures.HOLD2_MS else DesktopGestures.HOLD_MS) + RING_DELAY_MS
        while (true) {
            val now = withFrameMillis { SystemClock.uptimeMillis() }
            progress = ((now - start).toFloat() / (ring.firesAt - start).coerceAtLeast(1)).coerceIn(0f, 1f)
            if (now >= ring.firesAt) break
        }
    }
    if (progress <= 0f) return
    val color = if (ring.fingers > 1) Color(0xFFFF3B30) else Color(0xFF0A84FF)
    Canvas(Modifier.fillMaxSize().testTag(if (ring.armed) "desktop-hold-ring-armed" else "desktop-hold-ring")) {
        val r = (if (ring.fingers > 1) 34f else 26f) * density
        val topLeft = Offset(ring.x - r, ring.y - r)
        val size = Size(2 * r, 2 * r)
        if (ring.armed) {
            drawCircle(color.copy(alpha = 0.3f), r, Offset(ring.x, ring.y))
            drawCircle(color, r, Offset(ring.x, ring.y), style = Stroke(3 * density))
        } else drawArc(Color.White.copy(alpha = 0.85f), -90f, 360f * progress, false, topLeft, size, style = Stroke(3 * density))
    }
}

/** Connecting steps, needs, failures, the network figures and the recovering card, over the picture. */
@Composable
private fun BoxScope.DesktopStatusOverlay(connection: DesktopConnection, phase: DesktopPhase, hasPicture: Boolean,
    showStats: Boolean, statsBox: StatsBox, statsHint: Boolean, onCloseStats: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit) {
    val log by connection.log.collectAsStateWithLifecycle()
    val notice by connection.notice.collectAsStateWithLifecycle()
    val showCard = !hasPicture || phase is DesktopPhase.Failed || phase is DesktopPhase.NeedsAction
    if (showCard) when (phase) {
        is DesktopPhase.Failed -> StatusCard(connection.host.name, tr("无法打开桌面"), footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onRetry, Modifier.testTag("desktop-retry")) { Text(tr("重试")) }
                TextButton(onClick = onClose) { Text(tr("关闭"), color = InkDim) }
            }
        }) {
            Text(phase.message, color = Ink, fontSize = 14.sp, modifier = Modifier.testTag("desktop-error"))
        }
        is DesktopPhase.NeedsAction -> StatusCard(connection.host.name, tr("需要在电脑上操作"),
            footer = { CardLine(tr("满足后会自动连接"), progress = true) }) {
            Text(needsText(phase.needs), color = Ink, fontSize = 14.sp, modifier = Modifier.testTag("desktop-needs"))
            if (connection.windowsHost && phase.needs.firstOrNull() == "not-logged-in") WindowsServiceHint()
        }
        else -> {
            val step = (phase as? DesktopPhase.Connecting)?.step
            StatusCard(connection.host.name, tr("正在打开桌面"), footer = {
                // One line, the latest: the card keeps its size however much the install says.
                Text(log.lastOrNull().orEmpty(), color = InkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().testTag("desktop-log"))
            }) {
                StepLine(tr("登录主机"), step == DesktopPhase.Step.LOGIN, step != null && step != DesktopPhase.Step.LOGIN)
                StepLine(if (step == DesktopPhase.Step.INSTALL) tr("安装桌面插件") else tr("启动桌面插件"),
                    step == DesktopPhase.Step.INSTALL || step == DesktopPhase.Step.START, step == DesktopPhase.Step.FIRST_FRAME)
                StepLine(tr("等待画面"), step == DesktopPhase.Step.FIRST_FRAME, false)
            }
        }
    }
    if (hasPicture) {
        NetworkStats(connection, phase, showStats, statsBox, onCloseStats)
        val recovering = phase as? DesktopPhase.Recovering
        var longEnough by remember(recovering?.sinceMs) { mutableStateOf(false) }
        LaunchedEffect(recovering?.sinceMs) {
            longEnough = false
            if (recovering != null) { delay((2000 - (System.currentTimeMillis() - recovering.sinceMs)).coerceAtLeast(0)); longEnough = true }
        }
        if (recovering != null && longEnough) StatusCard(connection.host.name, tr("连接中断"), tag = "desktop-recovering",
            footer = { CardLine(tr("网络恢复后画面会自动回来"), progress = true) }) {
            Text(tr("正在恢复…"), color = Ink, fontSize = 14.sp)
        }
    }
    (notice ?: tr("网络状态已隐藏，可在「操作」中重新打开").takeIf { statsHint })?.let { Text(it, Modifier.align(Alignment.TopCenter).padding(10.dp).clip(CircleShape).background(PanelColor)
        .padding(horizontal = 14.dp, vertical = 6.dp).testTag("desktop-notice"), color = Ink, fontSize = 13.sp) }
}

/**
 * The one shape of every card over the picture: the host, a title, a body that scrolls when long and a
 * footer line. Fixed in size, so the card never jumps as steps and log lines come and go.
 */
@Composable
private fun BoxScope.StatusCard(host: String, title: String, tag: String = "desktop-status",
    footer: @Composable () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.align(Alignment.Center).padding(16.dp).size(STATUS_CARD_WIDTH, STATUS_CARD_HEIGHT).testTag(tag),
        colors = CardDefaults.cardColors(containerColor = PanelColor.copy(alpha = 1f)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp)) {
            Text(host, color = InkDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = 14.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = body)
            Box(Modifier.fillMaxWidth().heightIn(min = 24.dp).padding(top = 8.dp), contentAlignment = Alignment.CenterStart) { footer() }
        }
    }
}

/** A footer's one quiet line, with a small spinner when something is still going on. */
@Composable
private fun CardLine(text: String, progress: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (progress) {
            CircularProgressIndicator(Modifier.size(12.dp), color = Accent, strokeWidth = 1.5.dp, trackColor = Accent.copy(alpha = 0.25f))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = InkDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val STATUS_CARD_WIDTH = 296.dp
private val STATUS_CARD_HEIGHT = 244.dp

/** Windows without the plugin's service: one administrator install reaches the sign-in and lock screens. */
@Composable
private fun WindowsServiceHint() {
    val context = LocalContext.current
    Text(tr("在这台电脑上用管理员 PowerShell 运行一次下面的命令，之后电脑重启或锁屏时也能直接连接："), color = InkDim, fontSize = 13.sp)
    Text(io.itermux.ssh.WindowsDesktop.SERVICE_INSTALL, color = Ink, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.testTag("desktop-service-command"))
    TextButton(onClick = { context.copyText(io.itermux.ssh.WindowsDesktop.SERVICE_INSTALL) },
        Modifier.testTag("desktop-service-copy")) { Text(tr("复制命令")) }
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
                current -> CircularProgressIndicator(Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp, trackColor = Accent.copy(alpha = 0.25f))
                else -> Box(Modifier.size(8.dp).clip(CircleShape).background(InkDim))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (current || done) Ink else InkDim, fontSize = 14.sp)
    }
}

/** The sum of a frame's parts that were measured, null when none was. */
private fun msSum(vararg parts: Double?): Int? = parts.filterNotNull().takeIf { it.isNotEmpty() }?.sum()?.roundToInt()

/** Wi-Fi, 移动数据 or 以太网: what the phone's link to the host starts with. */
private fun transportName(context: Context): String {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "—"
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> tr("移动数据")
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> tr("以太网")
        else -> "—"
    }
}

/**
 * Where the network figures sit over the picture, as fractions of the room it leaves them (0, 0 the top
 * left), and their drag. The long press that starts one is [DesktopScreen]'s ([OverlayTouch]).
 */
@Stable
internal class StatsBox(initial: Pair<Float, Float>) {
    var fx by mutableFloatStateOf(initial.first.coerceIn(0f, 1f))
        private set
    var fy by mutableFloatStateOf(initial.second.coerceIn(0f, 1f))
        private set
    var dragging by mutableStateOf(false)
    /** The figures in window pixels while shown, null while not. */
    var bounds: Rect? = null
    /** The room the picture leaves them, in pixels: what a fraction is of. */
    var room = IntSize.Zero
    private var from = Offset.Zero
    private var fromFraction = Offset.Zero
    val moved get() = fx != 0f || fy != 0f

    fun startDrag(x: Float, y: Float) {
        from = Offset(x, y); fromFraction = Offset(fx, fy); dragging = true
    }

    /** Follows the finger from where the drag started, kept inside the picture. */
    fun dragTo(x: Float, y: Float) {
        if (!dragging) return
        if (room.width > 0) fx = (fromFraction.x + (x - from.x) / room.width).coerceIn(0f, 1f)
        if (room.height > 0) fy = (fromFraction.y + (y - from.y) / room.height).coerceIn(0f, 1f)
    }

    fun reset() { fx = 0f; fy = 0f }
}

/**
 * The figures over the picture, one short line each, the way game streaming shows them; shown only when
 * 显示网络状态 is on in 操作, top left until a long press moves them. Only their ✕ takes a touch: anything
 * else on them reaches the picture under them ([OverlayTouch]). Switched off, only a poor or recovering
 * link still says so.
 */
@Composable
private fun NetworkStats(connection: DesktopConnection, phase: DesktopPhase, show: Boolean, box: StatsBox, onClose: () -> Unit) {
    val modifier = Modifier.padding(8.dp)
    val context = LocalContext.current
    val stats by connection.stats.collectAsStateWithLifecycle()
    val poor = (phase as? DesktopPhase.Connected)?.poor == true
    val recovering = phase is DesktopPhase.Recovering
    if (!show) {
        if (poor || recovering) Row(modifier.clip(CircleShape).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("desktop-network-warning"), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (recovering) InkDim else StatusColors.warning))
            Spacer(Modifier.width(5.dp))
            Text(if (recovering) tr("恢复中") else tr("网络较差"), color = Ink, fontSize = 11.sp)
        }
        return
    }
    val transport = remember(stats.frames / 120) { transportName(context) }
    val l = stats.latency
    val warn = StatusColors.warning
    val bad = StatusColors.danger
    fun ms(v: Int?, slow: Int, verySlow: Int) = when {
        v == null -> Ink
        v >= verySlow -> bad
        v >= slow -> warn
        else -> Ink
    }
    val total = l?.totalMs?.roundToInt()
    val lines = listOfNotNull(
        "${if (recovering) tr("恢复中") else stats.link} ${stats.rttMs?.let { "$it ms" } ?: "—"}" to
            if (poor) warn else ms(stats.rttMs, 80, 150),
        "${stats.fps} fps  ${"%.1f".format(stats.kbps / 1000.0)} Mbps" to Ink,
        "${tr("端到端")} ${total?.let { "$it ms" } ?: "—"}" to ms(total, 100, 200),
        l?.let { "${tr("主机端")} ${msSum(it.captureMs, it.convertMs, it.encodeMs) ?: "—"} · ${tr("网络")} ${it.networkMs?.roundToInt() ?: "—"} · " +
            "${tr("手机端")} ${msSum(it.decodeMs, it.renderMs) ?: "—"}" to InkDim },
        stats.lossPercent?.let { "${tr("丢包")} ${"%.1f".format(it)}%" + (if (stats.fecRecovered > 0) "  FEC ${stats.fecRecovered}" else "") to
            if (it >= 5) warn else Ink },
        (if (stats.width > 0) "${stats.width}×${stats.height}" else "—") + (stats.encoder.takeIf { it.isNotEmpty() }?.let { " $it" } ?: "") to InkDim,
        stats.audioMs?.let { "${tr("声音")} ${tr("缓冲")} $it ms" to InkDim },
        transport to InkDim,
    )
    DisposableEffect(box) { onDispose { box.bounds = null } }
    val shape = RoundedCornerShape(6.dp)
    // The figures at their place in the room the picture leaves them; the layout itself fills the picture
    // but takes no touch.
    Layout(content = {
        Box(Modifier.graphicsLayer { if (box.dragging) { scaleX = 1.04f; scaleY = 1.04f } }.clip(shape)
            .background(if (box.dragging) Color(0xB3000000) else Color(0x8C000000))
            .then(if (box.dragging) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.6f), shape) else Modifier)
            .onGloballyPositioned { box.bounds = it.boundsInWindow() }.testTag("desktop-stats")) {
            Column(Modifier.padding(start = 7.dp, end = 26.dp, top = 4.dp, bottom = 4.dp)) {
                lines.forEach { (text, color) -> Text(text, color = color, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 1) }
            }
            Box(Modifier.align(Alignment.TopEnd).size(26.dp).clickable(onClick = onClose).testTag("desktop-stats-close"),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_close), tr("关闭"), Modifier.size(12.dp), tint = InkDim)
            }
        }
    }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val margin = 8.dp.roundToPx()
        val p = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        box.room = IntSize((constraints.maxWidth - p.width - 2 * margin).coerceAtLeast(0),
            (constraints.maxHeight - p.height - 2 * margin).coerceAtLeast(0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            p.place(margin + (box.fx * box.room.width).roundToInt(), margin + (box.fy * box.room.height).roundToInt())
        }
    }
}

@Composable
private fun BoxScope.OperationsPanel(connection: DesktopConnection, landscape: Boolean, mode: PointerMode, virtualMouse: Boolean,
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
        .testTag("desktop-operations"), colors = CardDefaults.cardColors(containerColor = PanelColor.copy(alpha = 1f)), shape = RoundedCornerShape(18.dp)) {
        // Closing the panel is the ✕ in its corner, where every sheet keeps it; ending the connection is the
        // red button at the very bottom, away from it (the old top row put 断开 under the thumb that closes).
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("操作"), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(connection.host.name, color = InkDim, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = onDismiss, Modifier.testTag("desktop-op-close")) {
                Icon(painterResource(R.drawable.ic_close), tr("关闭"), Modifier.size(22.dp), tint = Ink)
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
                clipNote?.let { Text(it, color = InkDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).testTag("desktop-clip-note")) }
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
                if (statsMoved) Text(tr("恢复默认位置（左上角）"), color = Accent, fontSize = 13.sp, modifier = Modifier
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
                    color = InkDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
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
        Text(title, color = InkDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
private fun PanelButton(label: String, icon: Int, tag: String, modifier: Modifier = Modifier, caption: String? = null, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x14FFFFFF)).clickable(onClick = onClick)
        .padding(vertical = 10.dp, horizontal = 6.dp).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = Ink)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Ink, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
        caption?.let { Text(it, color = InkDim, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1) }
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

/** A key of the shortcut bar or the 更多 page: what it shows and the HID usage it sends. */
private data class PadKey(val label: String, val hid: Int)

/** A computer's modifier names: macOS shows ⌃ ⌥ ⌘, Windows Win, Linux Super. */
private fun modifierKeys(os: String): List<PadKey> = when (os.lowercase()) {
    "macos" -> listOf(PadKey("⌃", HidUsage.LEFT_CTRL), PadKey("⌥", HidUsage.LEFT_ALT), PadKey("⌘", HidUsage.LEFT_GUI))
    "windows" -> listOf(PadKey("Ctrl", HidUsage.LEFT_CTRL), PadKey("Alt", HidUsage.LEFT_ALT), PadKey("Win", HidUsage.LEFT_GUI))
    else -> listOf(PadKey("Ctrl", HidUsage.LEFT_CTRL), PadKey("Alt", HidUsage.LEFT_ALT), PadKey("Super", HidUsage.LEFT_GUI))
}

/** The 更多 page's shortcuts for each system: label and keys pressed together. */
private fun comboKeys(os: String): List<Pair<String, List<Int>>> = when (os.lowercase()) {
    "macos" -> listOf("⌘Tab" to listOf(HidUsage.LEFT_GUI, HidUsage.TAB), "⌘Space" to listOf(HidUsage.LEFT_GUI, HidUsage.SPACE),
        "⌘Q" to listOf(HidUsage.LEFT_GUI, HidUsage.letter('q')))
    "windows" -> listOf("Alt+Tab" to listOf(HidUsage.LEFT_ALT, HidUsage.TAB),
        "Ctrl+Shift+Esc" to listOf(HidUsage.LEFT_CTRL, HidUsage.LEFT_SHIFT, HidUsage.ESCAPE), "Win+E" to listOf(HidUsage.LEFT_GUI, HidUsage.letter('e')))
    else -> listOf("Alt+Tab" to listOf(HidUsage.LEFT_ALT, HidUsage.TAB),
        "Ctrl+Alt+T" to listOf(HidUsage.LEFT_CTRL, HidUsage.LEFT_ALT, HidUsage.letter('t')), "Alt+F4" to listOf(HidUsage.LEFT_ALT, HidUsage.f(4)))
}

/** A typed character as the key that makes it on a US layout, for shortcuts like Ctrl+C; null if none. */
private fun charKey(c: Char): Int? = when (c) {
    in 'a'..'z', in 'A'..'Z' -> HidUsage.letter(c)
    in '1'..'9' -> 0x1E + (c - '1')
    '0' -> 0x27
    ' ' -> HidUsage.SPACE
    else -> null
}

/**
 * The keyboard, as in the terminal: 键盘 brings up the phone's own input method straight away, and
 * whatever it commits is typed on the host (any language), Backspace and Enter as keys. A shortcut bar
 * (Esc, Tab, modifiers, arrows) is hidden until asked for; a modifier tapped there latches for the next
 * key or typed letter, a long press keeps it on. ⋯ swaps the input method for F-keys and navigation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.KeyboardPanel(connection: DesktopConnection, os: String, landscape: Boolean, onDismiss: () -> Unit) {
    val state = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var bar by rememberSaveable { mutableStateOf(false) }
    var more by rememberSaveable { mutableStateOf(false) }
    val latch = remember { ModifierLatch() }
    var latchRevision by remember { mutableIntStateOf(0) }
    val mods = remember(os) { modifierKeys(os) }
    fun send(events: List<Pair<Int, Boolean>>) { events.forEach { (k, d) -> connection.input(DesktopProtocol.key(k, d)) }; latchRevision++ }
    fun key(hid: Int) = send(latch.press(hid))
    fun latched() = mods.any { latch.state(it.hid) != ModifierLatch.State.OFF } || latch.state(HidUsage.LEFT_SHIFT) != ModifierLatch.State.OFF
    LaunchedEffect(more) {
        if (more) keyboard?.hide() else { runCatching { focus.requestFocus() }; keyboard?.show() }
    }
    // The input method's own hide button closes the keyboard too (not when ⋯ hid it for the 更多 page).
    val imeVisible = WindowInsets.isImeVisible
    var imeShown by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, more) {
        if (imeVisible) imeShown = true
        else if (imeShown && !more) { delay(150); onDismiss() }
    }
    // Committed text goes out and the field is cleared in the same main-thread step as the IME's edits,
    // so the next key always lands in an empty field (a cleared TextFieldValue only took effect at the
    // next recomposition, and fast input in between was doubled or lost).
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() to state.composition }.collect {
            if (state.composition != null || state.text.isEmpty()) return@collect
            val text = state.text.toString()
            state.clearText()
            // With a modifier latched, a typed letter is a shortcut (Ctrl + c), not text.
            val k = text.singleOrNull()?.let(::charKey)
            if (k != null && latched()) key(k)
            // The input method's Enter commits a newline into the multi-line field: it is the Enter key.
            else text.split('\n').forEachIndexed { i, line ->
                if (i > 0) key(HidUsage.ENTER)
                if (line.isNotEmpty()) connection.input(DesktopProtocol.text(line))
            }
        }
    }
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(if (landscape) Modifier.padding(end = 72.dp) else Modifier)
        .imePadding().testTag("desktop-keyboard")) {
        // The text being composed (pinyin) shows here until the input method commits it.
        if (state.composition != null) Text(state.text.toString(), Modifier.padding(start = 12.dp, bottom = 6.dp).clip(CircleShape)
            .background(PanelColor).border(1.dp, Accent.copy(alpha = 0.5f), CircleShape).padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("desktop-composing"), color = Accent, fontSize = 14.sp)
        if (!bar && !more) Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.End) {
            Row(Modifier.clip(CircleShape).background(PanelColor).clickable { bar = true }.padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("desktop-kb-toggle"), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_keyboard), null, Modifier.size(16.dp), tint = Ink)
                Spacer(Modifier.width(6.dp))
                Text(tr("快捷键") + " ▴", color = Ink, fontSize = 12.sp)
            }
        } else key(latchRevision) {
            Row(Modifier.fillMaxWidth().background(PanelColor.copy(alpha = 1f)).padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    BarKey("Esc", "desktop-sk-esc") { key(HidUsage.ESCAPE) }
                    BarKey("Tab", "desktop-sk-tab") { key(HidUsage.TAB) }
                    mods.forEach { m ->
                        BarKey(m.label, "desktop-sk-mod-${m.hid}", latch.state(m.hid), onLongPress = { latch.hold(m.hid); latchRevision++ }) {
                            latch.tap(m.hid); latchRevision++
                        }
                    }
                    listOf("←" to HidUsage.LEFT, "↑" to HidUsage.UP, "↓" to HidUsage.DOWN, "→" to HidUsage.RIGHT).forEach { (label, hid) ->
                        BarKey(label, "desktop-sk-$label", narrow = true) { key(hid) }
                    }
                }
                BarKey(if (more) "ABC" else "⋯", "desktop-kb-more", if (more) ModifierLatch.State.LOCKED else ModifierLatch.State.OFF) { more = !more }
                if (!more) BarKey("▾", "desktop-kb-hide-bar", narrow = true) { bar = false }
            }
        }
        if (more) key(latchRevision) { MoreKeys(os, latch, onKey = ::key, onShift = { latch.tap(HidUsage.LEFT_SHIFT); latchRevision++ },
            onCombo = { keys -> send(keys.map { it to true } + keys.reversed().map { it to false }) }) }
        // Off screen in all but name: the input method needs a focused field to type into.
        BasicTextField(state, modifier = Modifier.fillMaxWidth().height(1.dp).alpha(0f).focusRequester(focus)
            .onPreviewKeyEvent { e ->
                // Back reaches the field only once the IME is hidden: it closes the panel (the field would
                // otherwise take it to drop its focus, and a second Back was needed).
                if (e.key == Key.Back) { if (e.type == KeyEventType.KeyUp) onDismiss(); return@onPreviewKeyEvent true }
                // On an empty field the IME sends Backspace and Enter as key events.
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Backspace -> if (state.text.isEmpty()) { key(HidUsage.BACKSPACE); true } else false
                    Key.Enter, Key.NumPadEnter -> { key(HidUsage.ENTER); true }
                    else -> false
                }
            }.testTag("desktop-ime-field"),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Transparent), cursorBrush = SolidColor(Color.Transparent),
            // Multi-line with no action, as in the terminal: the input method shows a plain ↵ that always works
            // (a Send action on an empty single-line field is greyed out by some input methods and does nothing).
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default, autoCorrectEnabled = false))
    }
}

/** A key of the shortcut bar; a modifier shows whether it is latched (outlined) or held (filled). */
@Composable
private fun BarKey(label: String, tag: String, latched: ModifierLatch.State = ModifierLatch.State.OFF, narrow: Boolean = false,
    onLongPress: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.height(36.dp).widthIn(min = if (narrow) 32.dp else 42.dp).clip(shape)
        .background(when (latched) { ModifierLatch.State.LOCKED -> Accent; ModifierLatch.State.ONCE -> Accent.copy(alpha = 0.25f); else -> Color(0x1AFFFFFF) })
        .border(1.dp, if (latched == ModifierLatch.State.ONCE) Accent else Color.Transparent, shape)
        .pointerInput(onLongPress) { detectTapGestures(onLongPress = onLongPress?.let { { _ -> it() } }) { onClick() } }
        .padding(horizontal = 10.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, color = when (latched) { ModifierLatch.State.LOCKED -> Color(0xFF1C1C1E); ModifierLatch.State.ONCE -> Accent; else -> Ink },
            fontSize = 13.sp, fontWeight = if (latched == ModifierLatch.State.LOCKED) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

/** The 更多 page in place of the input method: F1–F12, navigation, Shift, arrows and a few shortcuts. */
@Composable
private fun MoreKeys(os: String, latch: ModifierLatch, onKey: (Int) -> Unit, onShift: () -> Unit, onCombo: (List<Int>) -> Unit) {
    val keys = (1..12).map { PadKey("F$it", HidUsage.f(it)) } + listOf(PadKey("Home", HidUsage.HOME), PadKey("End", HidUsage.END),
        PadKey("PgUp", HidUsage.PAGE_UP), PadKey("PgDn", HidUsage.PAGE_DOWN), PadKey("Del", HidUsage.DELETE), PadKey("Ins", HidUsage.INSERT),
        PadKey("Shift", HidUsage.LEFT_SHIFT), PadKey("PrtSc", HidUsage.PRINT_SCREEN), PadKey("←", HidUsage.LEFT), PadKey("↑", HidUsage.UP),
        PadKey("↓", HidUsage.DOWN), PadKey("→", HidUsage.RIGHT))
    Column(Modifier.fillMaxWidth().background(PanelColor.copy(alpha = 1f)).padding(horizontal = 8.dp).padding(bottom = 10.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k ->
                    val shift = k.hid == HidUsage.LEFT_SHIFT
                    val s = if (shift) latch.state(k.hid) else ModifierLatch.State.OFF
                    Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(8.dp))
                        .background(when (s) { ModifierLatch.State.OFF -> Color(0x1FFFFFFF); else -> Accent.copy(alpha = 0.4f) })
                        .clickable { if (shift) onShift() else onKey(k.hid) }.testTag("desktop-key-${k.hid}"), contentAlignment = Alignment.Center) {
                        Text(k.label, color = Ink, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            comboKeys(os).forEach { (label, combo) ->
                Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x29FFFFFF))
                    .clickable { onCombo(combo) }.testTag("desktop-combo-$label"), contentAlignment = Alignment.Center) {
                    Text(label, color = Ink, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
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

/** The desktop button at the end of a session card's bottom row (plan §4.3): an icon tag beside its other tags. */
@Composable
internal fun DesktopCardButton(state: DesktopButton, tag: String, onClick: () -> Unit) {
    if (state == DesktopButton.HIDDEN) return
    // Always the desktop's icon, never a spinner: lit while a desktop is open or on its way.
    val live = state == DesktopButton.CONNECTED || state == DesktopButton.POOR || state == DesktopButton.RECOVERING ||
        state == DesktopButton.CONNECTING
    val badge = when (state) {
        DesktopButton.CONNECTED -> StatusColors.success
        DesktopButton.POOR, DesktopButton.NEEDS_ACTION, DesktopButton.RECOVERING -> StatusColors.warning
        else -> null
    }
    if (live) IconTag(R.drawable.ic_desktop, tr("打开桌面"), tag, onClick, tint = Accent, wash = Accent.copy(alpha = 0.16f),
        badge = badge, badgeTag = "$tag-badge-${state.name.lowercase()}")
    else IconTag(R.drawable.ic_desktop, tr("打开桌面"), tag, onClick, badge = badge, badgeTag = "$tag-badge-${state.name.lowercase()}")
}

/** The smallest the remote cursor is drawn, in dp: about a phone's own text cursor height. */
private const val CURSOR_MIN_DP = 15f
private const val CURSOR_MAX_DP = 24f
private const val RING_DELAY_MS = 200L
