package io.itermux.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.TerminalFontId
import io.itermux.app.TerminalFonts
import io.itermux.app.tr

@Composable
internal fun TerminalFontSetting(selection: TerminalFontId, revision: Int, hasCustom: Boolean,
    customName: String?, error: String?, onImport: () -> Unit, onClear: () -> Unit, onSelect: (TerminalFontId) -> Unit) {
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val previewFont = remember(selection, revision) { FontFamily(TerminalFonts.resolveTypeface(context)) }
    Text(tr("终端字体"), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            ListItem(headlineContent = { Text(tr(selection.label)) },
                supportingContent = { Text(tr("选择适合阅读的等宽字体")) },
                trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { pickerOpen = true }.testTag("settings-font-open"))
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Text("Aa Bb  0O 1l  {} []\n$ git status", fontFamily = previewFont,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("settings-font-preview"))
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
    if (pickerOpen) AppSheet(onDismiss = { pickerOpen = false }) {
        LazyColumn(Modifier.fillMaxSize().selectableGroup().testTag("font-options"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") { SheetHeader(tr("终端字体"), tr("选择后立即预览"), onDismiss = { pickerOpen = false }) }
            item(key = "preview") {
                Text("Aa Bb  0O 1l  {} []\n$ git status", fontFamily = previewFont,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp))
            }
            TerminalFontId.entries.filter { it != TerminalFontId.CUSTOM || hasCustom }.forEach { option ->
                item(key = option.id) {
                    ListItem(headlineContent = { Text(if (option == TerminalFontId.CUSTOM) customName ?: tr(option.label) else tr(option.label)) },
                        trailingContent = { RadioButton(selected = selection == option, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.selectable(selected = selection == option,
                            onClick = { onSelect(option) }, role = Role.RadioButton)
                            .padding(horizontal = 8.dp).testTag("settings-font-${option.id}"))
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
