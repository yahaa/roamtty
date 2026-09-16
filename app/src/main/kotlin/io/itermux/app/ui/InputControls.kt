package io.itermux.app.ui

import android.icu.text.BreakIterator
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InputControlButton(
    symbol: String, description: String, tag: String, enabled: Boolean = true,
    size: Dp = 48.dp, onLongClick: (() -> Unit)? = null, onClick: () -> Unit
) {
    Box(
        Modifier.size(size).clip(CircleShape).testTag(tag)
            .semantics { contentDescription = description }
            .combinedClickable(enabled = enabled, role = Role.Button,
                onLongClickLabel = if (onLongClick != null) "清空当前输入" else null,
                onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
    }
}

@Composable
internal fun DraftInputControls(value: TextFieldValue, onChange: (TextFieldValue) -> Unit) {
    Row {
        InputControlButton("←", "光标左移", "draft-left", size = 40.dp,
            onClick = { onChange(moveDraftCursor(value, true)) })
        InputControlButton("→", "光标右移", "draft-right", size = 40.dp,
            onClick = { onChange(moveDraftCursor(value, false)) })
        InputControlButton("⌫", "删除字符，长按清空当前输入", "draft-delete", size = 40.dp,
            onLongClick = { onChange(TextFieldValue()) }, onClick = { onChange(deleteDraftCharacter(value)) })
    }
}
