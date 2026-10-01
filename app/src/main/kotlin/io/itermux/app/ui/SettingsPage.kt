package io.itermux.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import android.content.Intent
import android.content.ActivityNotFoundException
import android.provider.OpenableColumns
import androidx.core.net.toUri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.itermux.app.AppLanguage
import io.itermux.app.TerminalFonts
import io.itermux.app.tr
import kotlinx.coroutines.*

@Composable
internal fun SettingsPage(theme: AppTheme, onThemeChange: (AppTheme) -> Unit,
    language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit) {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    val scope = rememberCoroutineScope()
    var fontImportError by remember { mutableStateOf<String?>(null) }
    val fontSelection = TerminalFonts.selection
    val fontRevision = TerminalFonts.revision
    val hasCustom = remember(fontRevision) { TerminalFonts.hasCustomFont(context) }
    val customName = remember(fontRevision) { TerminalFonts.customFontDisplayName(context) }
    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() }
            val lower = (name ?: uri.lastPathSegment.orEmpty()).lowercase()
            if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) {
                fontImportError = tr("请选择 .ttf 或 .otf 字体文件")
                return@launch
            }
            fontImportError = if (TerminalFonts.importCustomFont(context.applicationContext, uri, name)) null
            else tr("无法加载该字体文件")
        }
    }

    val backgroundSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        exempt = power.isIgnoringBatteryOptimizations(context.packageName)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exempt = power.isIgnoringBatteryOptimizations(context.packageName)
    }
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
            TerminalFontSetting(fontSelection, fontRevision, hasCustom, customName, fontImportError,
                onImport = { pickFont.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream", "*/*")) },
                onClear = { TerminalFonts.clearCustomFont(context); fontImportError = null },
                onSelect = { TerminalFonts.setSelection(context, it); fontImportError = null })
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
            Spacer(Modifier.height(24.dp))
            Text(tr("后台连接"), style = MaterialTheme.typography.titleMedium)
            Text(tr("锁屏后保持 SSH 需要允许后台运行，可能增加耗电。部分手机还需在系统设置中允许自启动。"),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            if (exempt) Text(tr("已允许不受电池优化限制"), modifier = Modifier.testTag("battery-exempt"))
            else OutlinedButton(onClick = {
                try {
                    backgroundSettings.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    backgroundSettings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:${context.packageName}".toUri()))
                }
            }, modifier = Modifier.testTag("allow-background")) { Text(tr("允许后台运行")) }
        }
    }
}
