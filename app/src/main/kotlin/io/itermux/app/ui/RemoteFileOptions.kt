package io.itermux.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import io.itermux.app.R
import io.itermux.app.tr

internal class RemoteFileOptions(private val preferences: SharedPreferences) {
    var showHiddenFiles by mutableStateOf(preferences.getBoolean("showHiddenFiles", false))
        private set

    fun refresh() {
        showHiddenFiles = preferences.getBoolean("showHiddenFiles", false)
    }

    fun setShowHidden(value: Boolean) {
        showHiddenFiles = value
        preferences.edit { putBoolean("showHiddenFiles", value) }
    }
}

/** Keep the browser and any open upload/download picker in sync. */
@Composable
internal fun rememberRemoteFileOptions(): RemoteFileOptions {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences("app-settings", Context.MODE_PRIVATE) }
    val options = remember(preferences) { RemoteFileOptions(preferences) }
    DisposableEffect(preferences, options) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "showHiddenFiles") options.refresh()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        options.refresh()
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return options
}

@Composable
internal fun RemoteFileOptionsMenu(options: RemoteFileOptions, modifier: Modifier = Modifier, tagPrefix: String = "files",
    menuItems: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit = {}) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("$tagPrefix-options")) {
            Icon(painterResource(R.drawable.ic_more), contentDescription = tr("文件浏览选项"))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 220.dp, max = 320.dp)) {
            menuItems { expanded = false }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = options.showHiddenFiles, role = Role.Checkbox, onValueChange = options::setShowHidden)
                .padding(horizontal = 12.dp).testTag("$tagPrefix-show-hidden"),
                verticalAlignment = Alignment.CenterVertically) {
                Text(tr("显示隐藏文件"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                Checkbox(checked = options.showHiddenFiles, onCheckedChange = null)
            }
        }
    }
}
