package io.itermux.app.ui

import io.itermux.app.appSettings
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
import io.itermux.ssh.RemoteEntry

internal enum class RemoteFileSort(val label: String) {
    NAME("名称"), MODIFIED("修改时间"), SIZE("大小"), TYPE("类型");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: NAME
    }
}

internal class RemoteFileOptions(private val preferences: SharedPreferences) {
    var showHiddenFiles by mutableStateOf(false)
        private set
    var sort by mutableStateOf(RemoteFileSort.NAME)
        private set
    var sortDescending by mutableStateOf(false)
        private set
    var codeWrap by mutableStateOf(false)
        private set
    var codeFontSize by mutableFloatStateOf(DEFAULT_CODE_FONT_SIZE)
        private set

    init { refresh() }

    fun refresh() {
        showHiddenFiles = preferences.getBoolean("showHiddenFiles", false)
        sort = RemoteFileSort.from(preferences.getString("fileSort", null))
        sortDescending = preferences.getBoolean("fileSortDescending", false)
        codeWrap = preferences.getBoolean("codeWrap", false)
        codeFontSize = preferences.getFloat("codeFontSize", DEFAULT_CODE_FONT_SIZE).coerceIn(MIN_CODE_FONT_SIZE, MAX_CODE_FONT_SIZE)
    }

    fun setShowHidden(value: Boolean) {
        showHiddenFiles = value
        preferences.edit { putBoolean("showHiddenFiles", value) }
    }

    /** Choosing the active order again flips its direction. */
    fun chooseSort(value: RemoteFileSort) {
        val descending = if (value == sort) !sortDescending else value == RemoteFileSort.MODIFIED || value == RemoteFileSort.SIZE
        sort = value
        sortDescending = descending
        preferences.edit { putString("fileSort", value.name); putBoolean("fileSortDescending", descending) }
    }

    fun changeCodeWrap(value: Boolean) {
        codeWrap = value
        preferences.edit { putBoolean("codeWrap", value) }
    }

    fun changeCodeFontSize(delta: Float) {
        codeFontSize = (codeFontSize + delta).coerceIn(MIN_CODE_FONT_SIZE, MAX_CODE_FONT_SIZE)
        preferences.edit { putFloat("codeFontSize", codeFontSize) }
    }

    companion object {
        const val DEFAULT_CODE_FONT_SIZE = 13f
        const val MIN_CODE_FONT_SIZE = 9f
        const val MAX_CODE_FONT_SIZE = 22f
        private val keys = setOf("showHiddenFiles", "fileSort", "fileSortDescending", "codeWrap", "codeFontSize")
        fun observes(key: String?) = key == null || key in keys
    }
}

/** Keep the browser and any open upload/download picker in sync. */
@Composable
internal fun rememberRemoteFileOptions(): RemoteFileOptions {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.appSettings() }
    val options = remember(preferences) { RemoteFileOptions(preferences) }
    DisposableEffect(preferences, options) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (RemoteFileOptions.observes(key)) options.refresh()
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

/** Folders always come first, as in editor file trees; ties fall back to A→Z names in either direction. */
internal fun sortRemoteEntries(entries: List<RemoteEntry>, sort: RemoteFileSort, descending: Boolean): List<RemoteEntry> {
    val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { entry: RemoteEntry -> entry.name }
    val primary: Comparator<RemoteEntry> = when (sort) {
        RemoteFileSort.NAME -> byName
        RemoteFileSort.MODIFIED -> compareBy { it.mtimeSeconds }
        RemoteFileSort.SIZE -> compareBy { if (it.isDirectory) 0L else it.size }
        RemoteFileSort.TYPE -> compareBy(String.CASE_INSENSITIVE_ORDER) { if (it.isDirectory) "" else it.name.substringAfterLast('.', "") }
    }
    return entries.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }
        .then(if (descending) primary.reversed() else primary).then(byName))
}
