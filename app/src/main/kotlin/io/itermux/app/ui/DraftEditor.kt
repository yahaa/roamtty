package io.itermux.app.ui

import io.itermux.app.tr

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The text editor under the terminal: [draft] is composed here and sent as one paste ([onSend]).
 * Landscape keeps it to one row so the terminal keeps its height.
 */
@Composable
internal fun DraftEditor(draft: TextFieldValue, onDraftChange: (TextFieldValue) -> Unit, landscape: Boolean, imeVisible: Boolean,
    connected: Boolean, onCollapse: () -> Unit, onEnter: () -> Unit, onSend: () -> Unit) {
    if (landscape) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(draft, onDraftChange,
                placeholder = { Text(tr("输入文本后，在这里确认"), maxLines = 1) },
                minLines = 1, maxLines = if (imeVisible) 2 else 3,
                modifier = Modifier.weight(1f).testTag("prompt-editor"))
            DraftInputControls(draft, onDraftChange)
            TextButton(onClick = onCollapse, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr("收起")) }
            TextButton(onClick = onEnter, enabled = connected, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr("回车 ↵")) }
            Button(onClick = onSend, enabled = draft.text.isNotEmpty() && connected, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(tr("发送文本")) }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            OutlinedTextField(draft, onDraftChange, placeholder = { Text(tr("输入文本后，在这里确认")) }, minLines = 1, maxLines = if (imeVisible) 2 else 4, modifier = Modifier.fillMaxWidth().testTag("prompt-editor"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DraftInputControls(draft, onDraftChange)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCollapse, modifier = Modifier.width(48.dp), contentPadding = PaddingValues(4.dp)) { Text(tr("收起")) }
                TextButton(onClick = onEnter, enabled = connected, modifier = Modifier.width(48.dp).semantics { contentDescription = tr("回车") }, contentPadding = PaddingValues(4.dp)) { Text("↵", fontSize = 22.sp) }
                Button(onClick = onSend, enabled = draft.text.isNotEmpty() && connected, modifier = Modifier.width(88.dp), contentPadding = PaddingValues(8.dp)) { Text(tr("发送文本")) }
            }
        }
    }
}
