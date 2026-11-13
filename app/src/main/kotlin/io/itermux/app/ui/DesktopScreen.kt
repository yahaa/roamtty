package io.itermux.app.ui

import android.content.Context
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.desktop.RemoteCursor
import io.itermux.app.desktop.DesktopThumbnails
import io.itermux.desktop.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Constraints

/**
 * The picture, the cursor drawn locally, and every touch turned into input ([DesktopTouchInput]). The same on the
 * desktop page and, [small], in the small window over the terminal: there the picture is on a TextureView (it clips
 * to the window's corners and moves with it) and no thumbnail is taken for the hosts list.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopScreen(connection: DesktopConnection, mode: PointerMode, keys: Boolean, stats: StatsBox,
    onStatsMoved: (Pair<Float, Float>) -> Unit, small: Boolean = false) {
    val density = LocalDensity.current.density
    val context = LocalContext.current
    val hello by connection.hello.collectAsStateWithLifecycle()
    val displayIndex by connection.display.collectAsStateWithLifecycle()
    val video by connection.videoSize.collectAsStateWithLifecycle()
    val cursor by connection.cursor.collectAsStateWithLifecycle()
    val display = hello?.displays?.getOrNull(displayIndex)
    // Coordinates are native pixels of the display; before the hello, the video size stands in.
    val remote = (display?.width ?: video.first) to (display?.height ?: video.second)
    var area by remember { mutableStateOf(IntSize.Zero) }
    val input = remember { DesktopTouchInput(connection, mode, density, ViewConfiguration.get(context).scaledTouchSlop.toFloat()) }
    input.update(mode, displayIndex, hello?.os ?: connection.host.os, LocalView.current, stats, onStatsMoved)
    LaunchedEffect(area, remote) {
        if (area.width == 0) return@LaunchedEffect
        val viewport = input.viewport
        input.viewport = Viewport(area.width.toFloat(), area.height.toFloat(), remote.first, remote.second).let {
            if (it.remoteWidth == viewport.remoteWidth && it.remoteHeight == viewport.remoteHeight)
                it.copy(zoom = viewport.zoom, panX = viewport.panX, panY = viewport.panY).clamped() else it
        }
        input.gestures.viewport = input.viewport
    }
    val focus = remember { FocusRequester() }
    // A hardware keyboard types into the picture whenever no panel holds the focus.
    LaunchedEffect(keys) { if (keys) runCatching { focus.requestFocus() } }
    LaunchedEffect(cursor) { cursor?.let { input.hostCursor(it.x, it.y) } }
    DisposableEffect(Unit) { onDispose { input.dispose() } }

    Box(Modifier.fillMaxSize().onSizeChanged { area = it }.focusRequester(focus).focusable().hardwareKeys(connection)) {
        val v = input.viewport
        // A fixed buffer of the video's size: zooming only moves and scales the surface, never reallocates it.
        val placed = Modifier.layout { measurable, _ ->
            val w = v.contentWidth.roundToInt().coerceAtLeast(1)
            val h = v.contentHeight.roundToInt().coerceAtLeast(1)
            val placeable = measurable.measure(Constraints.fixed(w, h))
            layout(area.width, area.height) { placeable.place(v.left.roundToInt(), v.top.roundToInt()) }
        }
        DesktopPicture(connection, video, small, placed)
        // The system's back gesture works on the black bars only: the picture keeps its edges for edge swipes.
        // (The rect, not a layout's bounds: a layout modifier reports the whole view as its size.)
        Box(Modifier.fillMaxSize().systemGestureExclusion {
            Rect(maxOf(0f, v.left), maxOf(0f, v.top),
                minOf(it.size.width.toFloat(), v.left + v.contentWidth), minOf(it.size.height.toFloat(), v.top + v.contentHeight))
        })
        DesktopCursor(input, cursor, v, density)
        // Read inside the ring alone, so a touch recomposes only it.
        HoldRing({ input.drawn.let { input.gestures.indicator } }, density)
        Box(Modifier.fillMaxSize().testTag(if (small) "pip-desktop-touch" else "desktop-touch")
            .onGloballyPositioned { input.origin = it.positionInWindow() }
            .pointerInteropFilter { e -> input.route(e); true })
    }
}

/** A hardware keyboard: physical keys straight through as HID usages. */
private fun Modifier.hardwareKeys(connection: DesktopConnection) = onKeyEvent { event ->
    val hid = HidUsage.fromAndroid(event.nativeKeyEvent.keyCode) ?: return@onKeyEvent false
    when (event.type) {
        KeyEventType.KeyDown -> if (event.nativeKeyEvent.repeatCount == 0) connection.input(DesktopProtocol.key(hid, true))
            else { connection.input(DesktopProtocol.key(hid, false)); connection.input(DesktopProtocol.key(hid, true)) }
        KeyEventType.KeyUp -> connection.input(DesktopProtocol.key(hid, false))
    }
    true
}

/**
 * The surface the decoder draws on. The page's is a SurfaceView, whose picture the hosts list shows as each
 * desktop's last: one soon after it appears, then every few seconds, and on leaving.
 */
@Composable
private fun DesktopPicture(connection: DesktopConnection, video: Pair<Int, Int>, small: Boolean, modifier: Modifier) {
    if (small) {
        AndroidView(factory = { ctx -> pictureTexture(ctx, connection) },
            update = { view -> if (video.first > 0) view.surfaceTexture?.setDefaultBufferSize(video.first, video.second) },
            modifier = modifier.testTag("pip-desktop-surface"))
        return
    }
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
    AndroidView(factory = { ctx ->
        SurfaceView(ctx).also { surfaceView = it }.apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { connection.decoder.attach(holder.surface) }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { connection.decoder.attach(holder.surface) }
                override fun surfaceDestroyed(holder: SurfaceHolder) { connection.decoder.detach(holder.surface) }
            })
        }
    }, update = { view -> if (video.first > 0) view.holder.setFixedSize(video.first, video.second) },
        modifier = modifier.testTag("desktop-surface"))
}

/** The phone's cursor: where its last move put it, ahead of the host's echo. */
@Composable
private fun DesktopCursor(input: DesktopTouchInput, cursor: RemoteCursor?, v: Viewport, density: Float) {
    val gestures = input.gestures
    Canvas(Modifier.fillMaxSize()) {
        input.drawn.let { }
        if (!gestures.hasCursor || cursor?.visible == false) return@Canvas
        val (x, y) = v.toView(gestures.cursorX, gestures.cursorY)
        // The host's image at the picture's scale, kept between CURSOR_MIN_DP and CURSOR_MAX_DP: the
        // picture is often a fifth of the host's size, and zoomed in five times.
        val shape = cursor?.shape
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

/** The smallest the remote cursor is drawn, in dp: about a phone's own text cursor height. */
private const val CURSOR_MIN_DP = 15f
private const val CURSOR_MAX_DP = 24f
private const val RING_DELAY_MS = 200L

/** The picture on a TextureView, handed to the decoder while it exists (see [DesktopScreen]'s small form). */
private fun pictureTexture(context: Context, connection: DesktopConnection) = android.view.TextureView(context).apply {
    surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
        var surface: android.view.Surface? = null
        override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
            val (w, h) = connection.videoSize.value
            if (w > 0) texture.setDefaultBufferSize(w, h)
            surface = android.view.Surface(texture).also(connection.decoder::attach)
        }
        override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
            // Parks the decoder before the surface goes, so the next one (the page's) takes it over.
            surface?.let { connection.decoder.detach(it); it.release() }
            surface = null
            return true
        }
        override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) {}
    }
}
