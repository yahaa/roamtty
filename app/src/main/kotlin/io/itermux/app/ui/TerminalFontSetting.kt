package io.itermux.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.itermux.app.TerminalFontId
import io.itermux.app.TerminalFonts
import io.itermux.app.tr

@Composable
internal fun TerminalFontSetting(selection: TerminalFontId, revision: Int, hasCustom: Boolean,
    customName: String?, error: String?, onImport: () -> Unit, onClear: () -> Unit, onSelect: (TerminalFontId) -> Unit) {
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val previewFont = remember(selection, revision) { FontFamily(TerminalFonts.resolveTypeface(context)) }
    GroupedSection(header = tr("终端字体")) {
        GroupedRow(tr(selection.label), Modifier.testTag("settings-font-open"), subtitle = tr("选择适合阅读的等宽字体"),
            trailing = { Chevron() }, onClick = { pickerOpen = true })
        GroupedDivider()
        Text("Aa Bb  0O 1l  {} []\n$ git status", fontFamily = previewFont,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().padding(Spacing.Page).testTag("settings-font-preview"))
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = Spacing.Page)) }
    if (pickerOpen) AppSheet(onDismiss = { pickerOpen = false }) {
        LazyColumn(Modifier.fillMaxSize().selectableGroup().testTag("font-options"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") { SheetHeader(tr("终端字体"), tr("选择后立即预览"), onDismiss = { pickerOpen = false }) }
            item(key = "preview") {
                Surface(Modifier.fillMaxWidth().padding(horizontal = Spacing.Section, vertical = Spacing.Tight),
                    shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.groupedContainer) {
                    Text("Aa Bb  0O 1l  {} []\n$ git status", fontFamily = previewFont,
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(Spacing.Page))
                }
            }
            TerminalFontId.entries.filter { it != TerminalFontId.CUSTOM || hasCustom }.forEach { option ->
                item(key = option.id) {
                    CheckRow(if (option == TerminalFontId.CUSTOM) customName ?: tr(option.label) else tr(option.label),
                        selection == option, Modifier.padding(horizontal = Spacing.Tight).clip(MaterialTheme.shapes.small)
                            .testTag("settings-font-${option.id}")) { onSelect(option) }
                }
            }
            item { HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
            item {
                TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("settings-font-pick")) {
                    Text(tr("选择字体文件…"), Modifier.fillMaxWidth().padding(8.dp))
                }
            }
            if (hasCustom) item {
                TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("settings-font-clear-custom")) {
                    Text(tr("清除自定义字体"), Modifier.fillMaxWidth().padding(8.dp))
                }
            }
            error?.let { item { Text(it, Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.error) } }
        }
    }
}
