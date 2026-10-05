package io.itermux.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.itermux.app.R
import kotlinx.coroutines.delay

/**
 * A short confirmation pinned under the status bar, clear of the keyboard. It never takes focus,
 * so the IME stays up and taps elsewhere still reach the terminal.
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
    val top = WindowInsets.statusBars.union(WindowInsets.displayCutout).getTop(density) + with(density) { 12.dp.roundToPx() }
    val position = remember(top) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
                popupContentSize: IntSize) = IntOffset((windowSize.width - popupContentSize.width) / 2, top)
        }
    }
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false)) {
        AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 }) {
            Surface(Modifier.padding(horizontal = 16.dp).testTag("top-notice"), shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 4.dp) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
