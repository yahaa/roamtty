package io.itermux.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.itermux.app.AppLanguage
import io.itermux.app.tr

@Composable
internal fun SettingsPage(theme: AppTheme, onThemeChange: (AppTheme) -> Unit,
    language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(tr("设置"), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))
            Text(tr("外观"), style = MaterialTheme.typography.titleMedium)
            Text(tr("选择应用的显示主题"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.selectableGroup()) {
                    AppTheme.entries.forEachIndexed { index, option ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                        ListItem(headlineContent = { Text(tr(option.label)) },
                            trailingContent = { RadioButton(selected = theme == option, onClick = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.selectable(selected = theme == option,
                                onClick = { onThemeChange(option) }, role = Role.RadioButton)
                                .testTag("settings-theme-${option.name.lowercase()}"))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(tr("语言"), style = MaterialTheme.typography.titleMedium)
            Text(tr("选择应用的显示语言"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.selectableGroup()) {
                    AppLanguage.entries.forEachIndexed { index, option ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                        ListItem(headlineContent = { Text(option.label) },
                            trailingContent = { RadioButton(selected = language == option, onClick = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.selectable(selected = language == option,
                                onClick = { onLanguageChange(option) }, role = Role.RadioButton)
                                .testTag("settings-language-${option.name.lowercase()}"))
                    }
                }
            }
        }
    }
}
