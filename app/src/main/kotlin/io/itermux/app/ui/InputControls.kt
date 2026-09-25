package io.itermux.app.ui

import io.itermux.app.tr

import android.icu.text.BreakIterator
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private fun characterBoundary(text: String, position: Int, left: Boolean): Int {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    val next = if (left) iterator.preceding(position) else iterator.following(position)
    return if (next == BreakIterator.DONE) position else next
}

internal fun moveDraftCursor(value: TextFieldValue, left: Boolean): TextFieldValue {
    val position = if (!value.selection.collapsed) {
        if (left) value.selection.min else value.selection.max
    } else characterBoundary(value.text, value.selection.start, left)
    return value.copy(selection = TextRange(position), composition = null)
}

internal fun deleteDraftCharacter(value: TextFieldValue): TextFieldValue {
    val end = value.selection.max
    val start = if (value.selection.collapsed) characterBoundary(value.text, end, true) else value.selection.min
    return TextFieldValue(value.text.removeRange(start, end), TextRange(start))
}

internal fun insertDraftText(value: TextFieldValue, inserted: String, maxLength: Int): TextFieldValue {
    if (inserted.isEmpty()) return value
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(start, value.text.length)
    val available = (maxLength - value.text.length + end - start).coerceAtLeast(0)
    var clipped = inserted.take(available)
    if (clipped.lastOrNull()?.isHighSurrogate() == true) clipped = clipped.dropLast(1)
    if (clipped.isEmpty()) return value
    return TextFieldValue(value.text.replaceRange(start, end, clipped), TextRange(start + clipped.length))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InputControlButton(
    symbol: String, description: String, tag: String, enabled: Boolean = true,
    size: Dp = 48.dp, selected: Boolean = false,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit
) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .testTag(tag)
            .semantics {
                contentDescription = description
                if (selected) stateDescription = tr("已启用")
            }
            .combinedClickable(enabled = enabled, role = Role.Button,
                onLongClickLabel = if (onLongClick != null) tr("清空当前输入") else null,
                onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, fontSize = if (symbol.length > 1) 14.sp else 22.sp,
            color = (if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface).copy(alpha = if (enabled) 1f else 0.38f))
    }
}

@Composable
internal fun DraftInputControls(value: TextFieldValue, onChange: (TextFieldValue) -> Unit) {
    Row {
        InputControlButton("←", tr("光标左移"), "draft-left", size = 40.dp,
            onClick = { onChange(moveDraftCursor(value, true)) })
        InputControlButton("→", tr("光标右移"), "draft-right", size = 40.dp,
            onClick = { onChange(moveDraftCursor(value, false)) })
        InputControlButton("⌫", tr("删除字符，长按清空当前输入"), "draft-delete", size = 40.dp,
            onLongClick = { onChange(TextFieldValue()) }, onClick = { onChange(deleteDraftCharacter(value)) })
    }
}
