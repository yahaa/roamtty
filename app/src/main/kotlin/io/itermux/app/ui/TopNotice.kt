package io.itermux.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/**
 * A short confirmation styled like the system toast (app icon, plain text, rounded pill) but pinned near the top,
 * clear of the keyboard. It never takes focus, so the IME stays up and taps elsewhere still reach the terminal.
 */
@Composable
internal fun TopNotice(message: String, durationMillis: Long = 2_000, onDismiss: () -> Unit) {
    val visible = remember { MutableTransitionState(false) }
    LaunchedEffect(message) {
        visible.targetState = true
        delay(durationMillis)
        visible.targetState = false
    }
    LaunchedEffect(visible.isIdle, visible.currentState) { if (visible.isIdle && !visible.currentState && !visible.targetState) onDismiss() }
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.union(WindowInsets.displayCutout).getTop(density) + with(density) { 64.dp.roundToPx() }
    val context = LocalContext.current
    val appIcon = remember { context.packageManager.getApplicationIcon(context.packageName).toBitmap().asImageBitmap() }
    // The system toast follows the device theme, not the app's.
    val dark = isSystemInDarkTheme()
    val position = remember(top) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
                popupContentSize: IntSize) = IntOffset((windowSize.width - popupContentSize.width) / 2, top)
        }
    }
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false)) {
        AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 }) {
            Surface(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("top-notice"), shape = RoundedCornerShape(28.dp),
                color = if (dark) Color(0xFF303030) else Color(0xFFF2F2F2), contentColor = if (dark) Color(0xFFE8E8E8) else Color(0xFF1F1F1F),
                shadowElevation = 6.dp) {
                Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(appIcon, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(message, fontSize = 14.sp)
                }
            }
        }
    }
}
