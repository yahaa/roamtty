package io.itermux.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import io.itermux.app.R
import io.itermux.app.data.Host
import io.itermux.app.session.StartDirectory
import io.itermux.app.tr
import io.itermux.app.trf

/**
 * Asked when a host is tapped: which directory the new terminal starts in. The field starts at `~` (home), so
 * the default stays one tap; folders opened before on this host are listed for a quick pick.
 */
@Composable
internal fun StartDirectorySheet(host: Host, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences("app-settings", Context.MODE_PRIVATE) }
    var recent by remember(host.id) { mutableStateOf(preferences.recentStartDirectories(host.id)) }
    var field by remember { mutableStateOf(StartDirectory.HOME) }
    val colors = MaterialTheme.colorScheme
    fun open(directory: String) {
        val dir = StartDirectory.normalize(directory)
        preferences.saveRecentStartDirectories(host.id, StartDirectory.remember(recent, dir))
        onOpen(dir)
    }
    AppSheet(onDismiss, fillHeight = false) {
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("start-directory-sheet")) {
            SheetHeader(trf("打开 %s", host.name), "${host.username}@${host.address}", onDismiss)
            AppSearchField(field, { field = it }, tr("留空进入主目录"),
                Modifier.fillMaxWidth().padding(horizontal = Spacing.Page).testTag("start-directory-input"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { open(field) }), leadingIcon = R.drawable.ic_folder)
            Text(tr("输入远端目录的完整路径，或以 ~/ 开头的主目录下路径。"),
                Modifier.padding(horizontal = Spacing.Section, vertical = Spacing.Tight),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)

            Spacer(Modifier.height(Spacing.Tight))
            GroupedSection(Modifier.padding(horizontal = Spacing.Page), header = tr("快速选择")) {
                DirectoryRow(tr("主目录"), StartDirectory.HOME, R.drawable.ic_hosts, "start-directory-home") { open(StartDirectory.HOME) }
                recent.forEachIndexed { index, dir ->
                    GroupedDivider(start = 52.dp)
                    DirectoryRow(dir.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }, dir, R.drawable.ic_history,
                        "start-directory-recent-$index", onForget = {
                            recent = recent - dir
                            preferences.saveRecentStartDirectories(host.id, recent)
                        }) { open(dir) }
                }
            }

            Button(onClick = { open(field) },
                modifier = Modifier.fillMaxWidth().padding(Spacing.Page).heightIn(min = 52.dp).testTag("start-directory-open")) {
                Text(if (StartDirectory.isHome(field)) tr("在主目录打开终端") else tr("在此目录打开终端"),
                    style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun DirectoryRow(title: String, path: String, icon: Int, tag: String, onForget: (() -> Unit)? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        GroupedRow(title, Modifier.weight(1f), subtitle = path,
            leading = { Icon(painterResource(icon), contentDescription = null, Modifier.size(20.dp), tint = colors.primary) },
            trailing = if (onForget == null) ({ Chevron() }) else null, onClick = onClick)
        onForget?.let {
            IconButton(onClick = it, modifier = Modifier.padding(end = Spacing.Hairline)) {
                Icon(painterResource(R.drawable.ic_clear), contentDescription = trf("移除 %s", path),
                    tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun SharedPreferences.recentStartDirectories(hostId: String): List<String> =
    getString("$RECENT_KEY:$hostId", null).orEmpty().split('\n').filter { it.isNotEmpty() }

private fun SharedPreferences.saveRecentStartDirectories(hostId: String, directories: List<String>) =
    edit { putString("$RECENT_KEY:$hostId", directories.joinToString("\n")) }

private const val RECENT_KEY = "startDirectories"
