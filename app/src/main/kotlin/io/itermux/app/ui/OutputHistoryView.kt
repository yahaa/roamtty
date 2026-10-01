package io.itermux.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.itermux.app.tr
import kotlinx.coroutines.launch

@Composable
internal fun OutputHistoryView(lines: List<String>, fontSize: Int, onExit: () -> Unit) {
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = (lines.size - 1).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize().testTag("output-history")) {
        Column {
            Text(tr("本次连接已显示的输出"), Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
            Row {
                TextButton(onClick = { scope.launch { scroll.scrollToItem(0) } }, enabled = lines.isNotEmpty(),
                    modifier = Modifier.testTag("output-history-top")) { Text(tr("最早")) }
                TextButton(onClick = { scope.launch { scroll.scrollToItem((lines.size - 1).coerceAtLeast(0)) } },
                    enabled = lines.isNotEmpty()) { Text(tr("最新")) }
                TextButton(onClick = onExit, modifier = Modifier.testTag("output-history-live")) { Text(tr("返回实时画面")) }
            }
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("output-history-lines"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    items(lines) { line -> Text(line, fontFamily = FontFamily.Monospace, fontSize = fontSize.sp) }
                }
            }
        }
    }
}
