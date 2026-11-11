package io.itermux.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.tr
import io.itermux.desktop.*
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Rect

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
internal fun NetworkStats(connection: DesktopConnection, phase: DesktopPhase, show: Boolean, box: StatsBox, onClose: () -> Unit) {
    val modifier = Modifier.padding(8.dp)
    val context = LocalContext.current
    val stats by connection.stats.collectAsStateWithLifecycle()
    val poor = (phase as? DesktopPhase.Connected)?.poor == true
    val recovering = phase is DesktopPhase.Recovering
    if (!show) {
        if (poor || recovering) Row(modifier.clip(CircleShape).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("desktop-network-warning"), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (recovering) DesktopInkDim else StatusColors.warning))
            Spacer(Modifier.width(5.dp))
            Text(if (recovering) tr("恢复中") else tr("网络较差"), color = DesktopInk, fontSize = 11.sp)
        }
        return
    }
    val transport = remember(stats.frames / 120) { transportName(context) }
    val l = stats.latency
    val warn = StatusColors.warning
    val bad = StatusColors.danger
    fun ms(v: Int?, slow: Int, verySlow: Int) = when {
        v == null -> DesktopInk
        v >= verySlow -> bad
        v >= slow -> warn
        else -> DesktopInk
    }
    val total = l?.totalMs?.roundToInt()
    val lines = listOfNotNull(
        "${if (recovering) tr("恢复中") else stats.link} ${stats.rttMs?.let { "$it ms" } ?: "—"}" to
            if (poor) warn else ms(stats.rttMs, 80, 150),
        "${stats.fps} fps  ${"%.1f".format(stats.kbps / 1000.0)} Mbps" to DesktopInk,
        "${tr("端到端")} ${total?.let { "$it ms" } ?: "—"}" to ms(total, 100, 200),
        l?.let { "${tr("主机端")} ${msSum(it.captureMs, it.convertMs, it.encodeMs) ?: "—"} · ${tr("网络")} ${it.networkMs?.roundToInt() ?: "—"} · " +
            "${tr("手机端")} ${msSum(it.decodeMs, it.renderMs) ?: "—"}" to DesktopInkDim },
        stats.lossPercent?.let { "${tr("丢包")} ${"%.1f".format(it)}%" + (if (stats.fecRecovered > 0) "  FEC ${stats.fecRecovered}" else "") to
            if (it >= 5) warn else DesktopInk },
        (if (stats.width > 0) "${stats.width}×${stats.height}" else "—") + (stats.encoder.takeIf { it.isNotEmpty() }?.let { " $it" } ?: "") to DesktopInkDim,
        stats.audioMs?.let { "${tr("声音")} ${tr("缓冲")} $it ms" to DesktopInkDim },
        transport to DesktopInkDim,
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
                Icon(painterResource(R.drawable.ic_close), tr("关闭"), Modifier.size(12.dp), tint = DesktopInkDim)
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
