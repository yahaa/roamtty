package io.itermux.app.ui

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.R
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.desktop.DesktopInputProbe
import io.itermux.app.desktop.DesktopSettings
import io.itermux.app.tr
import io.itermux.desktop.*
import kotlinx.coroutines.delay
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner

internal val DesktopPanelColor = Color(0xF21C1C1E)
internal val DesktopRailColor = Color(0xFF141416)
internal val DesktopInk = Color(0xFFF2F2F7)
internal val DesktopInkDim = Color(0xFF9A9AA1)
internal val DesktopAccent = Color(0xFFFF9F0A)

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
    // The page shows the picture; leaving pauses the host unless the small window over the terminal takes it.
    DisposableEffect(connection) {
        connection.shown("page", small = false)
        onDispose { connection.hidden("page") }
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
            Icon(painterResource(icon), contentDescription = null, Modifier.size(if (landscape) 24.dp else 22.dp), tint = if (selected[i]) DesktopAccent else DesktopInk)
            Spacer(Modifier.height(3.dp))
            Text(label, fontSize = if (landscape) 12.sp else 11.sp, color = if (selected[i]) DesktopAccent else DesktopInkDim, maxLines = 2,
                textAlign = TextAlign.Center) // The landscape rail is narrower than a two-word English label.
        }
    }
    // A cutout on the rail's side widens the rail to the screen edge instead of squeezing its buttons.
    // 操作 stays in the bottom corner: leftmost in portrait, lowest in landscape.
    if (landscape) Column(Modifier.fillMaxHeight().background(DesktopRailColor).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End)).width(72.dp),
        verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) { items.indices.reversed().forEach { content(it) } }
    else Row(Modifier.fillMaxWidth().background(DesktopRailColor).padding(top = 4.dp, bottom = portraitRailBottom()), horizontalArrangement = Arrangement.SpaceEvenly) {
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
