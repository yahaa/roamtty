package io.itermux.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import io.itermux.app.R
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.itermux.app.AppLanguage
import io.itermux.app.TerminalFonts
import io.itermux.app.session.AgentAlertSettings
import io.itermux.app.tr
import kotlinx.coroutines.*

@Composable
internal fun SettingsPage(theme: AppTheme, onThemeChange: (AppTheme) -> Unit,
    language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit, onAgentAlertsChange: () -> Unit = {}) {
    val context = LocalContext.current
    var agentAlerts by remember { mutableStateOf(AgentAlertSettings.enabled(context)) }
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

    // Also refreshes after returning from the system battery settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exempt = power.isIgnoringBatteryOptimizations(context.packageName)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.Section)) {
            LargeTitle(tr("设置"), Modifier.padding(bottom = Spacing.Tight))
            Column(Modifier.padding(horizontal = Spacing.Page), verticalArrangement = Arrangement.spacedBy(Spacing.Section)) {
                GroupedSection(header = tr("外观")) {
                    Column(Modifier.selectableGroup()) {
                        AppTheme.entries.forEachIndexed { index, option ->
                            if (index > 0) GroupedDivider()
                            CheckRow(tr(option.label), theme == option, Modifier.testTag("settings-theme-${option.name.lowercase()}")) {
                                onThemeChange(option)
                            }
                        }
                    }
                }
                TerminalFontSetting(fontSelection, fontRevision, hasCustom, customName, fontImportError,
                    onImport = { pickFont.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream", "*/*")) },
                    onClear = { TerminalFonts.clearCustomFont(context); fontImportError = null },
                    onSelect = { TerminalFonts.setSelection(context, it); fontImportError = null })
                GroupedSection(header = tr("语言")) {
                    Column(Modifier.selectableGroup()) {
                        AppLanguage.entries.forEachIndexed { index, option ->
                            if (index > 0) GroupedDivider()
                            CheckRow(option.label, language == option, Modifier.testTag("settings-language-${option.name.lowercase()}")) {
                                onLanguageChange(option)
                            }
                        }
                    }
                }
                DownloadDirectorySetting()
                GroupedSection(header = tr("通知"),
                    footer = tr("主机安装“AI 交互提醒”插件后，Claude Code、Codex、Grok 请求权限或向你提问时发送通知，处理后自动撤销。需要保持会话连接。")) {
                    GroupedRow(tr("AI 交互提醒"), Modifier.testTag("settings-agent-alerts"), trailing = {
                        Switch(agentAlerts, null)
                    }, onClick = {
                        agentAlerts = !agentAlerts
                        AgentAlertSettings.set(context, agentAlerts)
                        onAgentAlertsChange()
                    })
                }
                GroupedSection(header = tr("后台连接"),
                    footer = tr("锁屏后保持 SSH 需要允许后台运行，可能增加耗电。部分手机还需在系统设置中允许自启动。")) {
                    if (exempt) GroupedRow(tr("已允许不受电池优化限制"), Modifier.testTag("battery-exempt"),
                        trailing = { Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = StatusColors.success) })
                    else GroupedRow(tr("允许后台运行"), Modifier.testTag("allow-background"),
                        titleColor = MaterialTheme.colorScheme.primary, trailing = { Chevron() }, onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            } catch (_: ActivityNotFoundException) {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    "package:${context.packageName}".toUri()))
                            }
                        })
                }
            }
        }
    }
}

/** Single-choice row: a trailing check mark marks the selection, as in grouped settings lists. */
@Composable
internal fun CheckRow(title: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GroupedRow(title, modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        trailing = {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp)
                .alpha(if (selected) 1f else 0f), tint = MaterialTheme.colorScheme.primary)
        })
}
