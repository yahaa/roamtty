package io.itermux.app.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.itermux.app.TerminalFonts
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.session.TerminalConnection
import io.itermux.app.tr
import io.itermux.desktop.DesktopPhase
import com.termux.terminal.TextStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val MiniInk = Color(0xFFF2F2F7)
private val MiniInkDim = Color(0xFF9A9AA1)
private val MiniAccent = Color(0xFFFF9F0A)

/** The owner name the small window registers with the desktop connection ([DesktopConnection.shown]). */
private const val PIP_OWNER = "pip"

/**
 * A host's desktop in the small window: the same connection and decoder as the desktop page, drawn on a TextureView
 * (which clips to the window's corners and moves with it). While only this shows the picture, the host sends it at a
 * lower rate. Before the first picture (or after a failure) a line says where the desktop is.
 */
@Composable
internal fun DesktopMiniContent(connection: DesktopConnection) {
    val video by connection.videoSize.collectAsStateWithLifecycle()
    val phase by connection.phase.collectAsStateWithLifecycle()
    DisposableEffect(connection) {
        connection.shown(PIP_OWNER, small = true)
        onDispose { connection.hidden(PIP_OWNER) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    var surface: Surface? = null
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        val (w, h) = connection.videoSize.value
                        if (w > 0) texture.setDefaultBufferSize(w, h)
                        surface = Surface(texture).also(connection.decoder::attach)
                    }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        // Parks the decoder before the surface goes, so the next page's surface takes it over.
                        surface?.let { connection.decoder.detach(it); it.release() }
                        surface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                }
            }
        }, update = { view -> if (video.first > 0) view.surfaceTexture?.setDefaultBufferSize(video.first, video.second) },
            modifier = Modifier.fillMaxSize())
        val status = when (phase) {
            is DesktopPhase.Failed -> tr("桌面已断开")
            is DesktopPhase.NeedsAction -> tr("需要在电脑上操作")
            is DesktopPhase.Recovering -> tr("连接中断")
            else -> if (video.first <= 0) tr("正在打开桌面") else null
        }
        if (status != null) Column(Modifier.fillMaxSize().background(Color(0xFF1F1F21)).padding(10.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(connection.host.name, color = MiniInkDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val busy = phase !is DesktopPhase.Failed
            if (busy) CircularProgressIndicator(Modifier.size(14.dp), color = MiniAccent, strokeWidth = 1.5.dp,
                trackColor = MiniAccent.copy(alpha = 0.25f))
            Text(status, color = MiniInk, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * A session in the small window: its host and title in one quiet line, then the rows up to its cursor
 * ([TerminalMiniView]), redrawn at most 15 times a second while output comes.
 */
@Composable
internal fun TerminalMiniContent(session: TerminalConnection) {
    val context = LocalContext.current
    val title by session.title.collectAsStateWithLifecycle()
    var view by remember { mutableStateOf<TerminalMiniView?>(null) }
    LaunchedEffect(session, view) {
        val v = view ?: return@LaunchedEffect
        session.revision.collect { v.invalidate(); delay(66) }
    }
    val emulator = session.getEmulator()
    val background = Color(emulator.mColors.mCurrentColors[if (emulator.isReverseVideo) TextStyle.COLOR_INDEX_FOREGROUND
        else TextStyle.COLOR_INDEX_BACKGROUND])
    Column(Modifier.fillMaxSize().background(background)) {
        Text(session.titleForDisplay(title), color = MiniInkDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp))
        AndroidView(factory = { TerminalMiniView(it).also { v -> view = v } }, update = { v ->
            v.session = session
            val metrics = v.resources
            v.setFont((MINI_FONT_SP * metrics.displayMetrics.density * metrics.configuration.fontScale).roundToInt(),
                TerminalFonts.resolveTypeface(context))
        }, modifier = Modifier.fillMaxSize())
    }
}

private const val MINI_FONT_SP = 10f
