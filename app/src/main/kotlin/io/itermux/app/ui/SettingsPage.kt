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
import io.itermux.app.TerminalFontId
import io.itermux.app.TerminalFonts
import io.itermux.app.tr

@Composable
internal fun SettingsPage(theme: AppTheme, onThemeChange: (AppTheme) -> Unit,
    language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit) {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    var fontImportError by remember { mutableStateOf<String?>(null) }
    val fontSelection = TerminalFonts.selection
    val fontRevision = TerminalFonts.revision
    val hasCustom = remember(fontRevision) { TerminalFonts.hasCustomFont(context) }
    val customName = remember(fontRevision) { TerminalFonts.customFontDisplayName(context) }
    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        val lower = (name ?: uri.lastPathSegment.orEmpty()).lowercase()
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) {
            fontImportError = tr("请选择 .ttf 或 .otf 字体文件")
            return@rememberLauncherForActivityResult
        }
        fontImportError = if (TerminalFonts.importCustomFont(context, uri, name)) null
        else tr("无法加载该字体文件")
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
            Text(tr("终端字体"), style = MaterialTheme.typography.titleMedium)
            Text(tr("选择等宽字体可改善清晰度。系统等宽在部分手机上会发虚或被横向拉伸。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.selectableGroup()) {
                    TerminalFontId.entries.filter { it != TerminalFontId.CUSTOM }.forEachIndexed { index, option ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                        ListItem(
                            headlineContent = { Text(tr(option.label)) },
                            supportingContent = if (option == TerminalFontId.SYSTEM) {
                                { Text(tr("跟随系统 Typeface.MONOSPACE"), style = MaterialTheme.typography.bodySmall) }
                            } else null,
                            trailingContent = { RadioButton(selected = fontSelection == option, onClick = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.selectable(selected = fontSelection == option,
                                onClick = { TerminalFonts.setSelection(context, option); fontImportError = null },
                                role = Role.RadioButton)
                                .testTag("settings-font-${option.id}"))
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                    ListItem(
                        headlineContent = {
                            Text(
                                if (hasCustom && customName != null) tr("自定义字体") + " · $customName"
                                else tr("自定义字体")
                            )
                        },
                        supportingContent = {
                            Text(
                                if (hasCustom) tr("已从本机导入；点选应用，或重新选择文件")
                                else tr("从本机选择 .ttf / .otf 文件"),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        trailingContent = {
                            if (hasCustom) RadioButton(selected = fontSelection == TerminalFontId.CUSTOM, onClick = null)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.selectable(
                            selected = fontSelection == TerminalFontId.CUSTOM && hasCustom,
                            onClick = {
                                if (hasCustom) TerminalFonts.setSelection(context, TerminalFontId.CUSTOM)
                                else pickFont.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream", "*/*"))
                            },
                            role = Role.RadioButton
                        ).testTag("settings-font-custom")
                    )
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { pickFont.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream", "*/*")) },
                    modifier = Modifier.testTag("settings-font-pick")
                ) { Text(tr("选择字体文件…")) }
                if (hasCustom) {
                    TextButton(
                        onClick = { TerminalFonts.clearCustomFont(context); fontImportError = null },
                        modifier = Modifier.testTag("settings-font-clear-custom")
                    ) { Text(tr("清除自定义字体")) }
                }
            }
            fontImportError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
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
