package io.itermux.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.view.TerminalView
import kotlin.math.roundToInt

/** Keys in a row at the right end of the cursor row, [height] tall and centred on it; hidden while the cursor is scrolled away. */
@Composable
internal fun CursorRowKeys(view: TerminalView, outputRevision: State<Long>, viewportHeight: Int, height: Dp,
    avoid: Rect? = null, keys: @Composable () -> Unit) {
    // Output moves the cursor: follow it on every screen revision. Only whether the row is on screen recomposes;
    // the position is read while placing, so a stream of output moves the keys without recomposing them.
    val onScreen by remember(view, outputRevision) {
        derivedStateOf { checkNotNull(outputRevision.value); view.getCursorRowTop() >= 0 }
    }
    if (!onScreen) return
    // Where the keys' box sits before its offset, in root coordinates.
    var containerTop by remember { mutableFloatStateOf(0f) }
    Row(Modifier.onGloballyPositioned { containerTop = it.positionInRoot().y }.offset {
        checkNotNull(outputRevision.value)
        // The terminal view sits 6dp below the viewport top; centre the keys on the cursor row.
        val keyHeight = height.roundToPx()
        val keyTop = (6.dp.roundToPx() + view.getCursorRowTop() + view.lineHeight / 2 - keyHeight / 2)
            .coerceIn(0, (viewportHeight - keyHeight).coerceAtLeast(0))
        // The pet's Up/Down/Left keys sit at the same right edge: when the row falls within their span, slide left of them.
        val keyShift = if (avoid != null && containerTop + keyTop < avoid.bottom && containerTop + keyTop + keyHeight > avoid.top)
            (avoid.width + 8.dp.toPx() + 40.dp.toPx()).roundToInt() else 0
        IntOffset(-keyShift, keyTop)
    }) { keys() }
}

/** One of the Up, Down and Left keys around the pet, fading in and out with the others. */
@Composable
internal fun PetArrowKey(visible: Boolean, icon: Int, description: String, tag: String, onClick: () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(visible, enter = fadeIn(tween(120)), exit = fadeOut(tween(200))) {
        CursorKey(icon, description, tag, onClick = onClick)
    }
}

internal val CursorKeyHeight = 32.dp

/** A slim key drawn inside a larger touch area, tinted on press; shows [icon], or [label] when there is none. */
@Composable
internal fun CursorKey(icon: Int?, description: String, tag: String, label: String = "", onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(Modifier.size(40.dp, CursorKeyHeight)
        .clickable(interaction, indication = null, onClick = onClick)
        .semantics { contentDescription = description }.testTag(tag),
        contentAlignment = Alignment.Center) {
        Surface(Modifier.size(28.dp, 20.dp), shape = RoundedCornerShape(6.dp),
            color = if (pressed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.primary,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
            Box(contentAlignment = Alignment.Center) {
                if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(14.dp))
                else Text(label, fontSize = 10.sp, lineHeight = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
            }
        }
    }
}
