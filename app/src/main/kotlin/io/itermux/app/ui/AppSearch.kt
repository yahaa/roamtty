package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.tr

/** One filled search bar for hosts, sessions, commands and folders. */
@Composable
internal fun AppSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier, keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    keyboardActions: KeyboardActions = KeyboardActions.Default, leadingIcon: Int = R.drawable.ic_search,
    trailing: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val fill = if (colors.background.luminance() > 0.5f) colors.surfaceContainerHighest else colors.surfaceContainerHigh
    TextField(value, onValueChange, placeholder = { Text(placeholder, maxLines = 1) },
        leadingIcon = { Icon(painterResource(leadingIcon), contentDescription = null, modifier = Modifier.size(20.dp)) },
        trailingIcon = when {
            trailing != null -> trailing
            value.isNotEmpty() -> { {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(painterResource(R.drawable.ic_clear), contentDescription = tr("清除"),
                        tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            } }
            else -> null
        },
        singleLine = true, modifier = modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.extraSmall,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = fill, unfocusedContainerColor = fill, disabledContainerColor = fill,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedLeadingIconColor = colors.onSurfaceVariant, unfocusedLeadingIconColor = colors.onSurfaceVariant),
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions)
}

@Composable
internal fun AppSearchToggle(expanded: Boolean, onToggle: () -> Unit, searchDescription: String,
    modifier: Modifier = Modifier) {
    ToolbarIcon(if (expanded) R.drawable.ic_close else R.drawable.ic_search,
        if (expanded) tr("收起搜索") else searchDescription, modifier, onClick = onToggle)
}

/** The landscape bar's search: a 36dp capsule, so it fits in the 48dp bar beside the title. */
@Composable
internal fun CompactSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.height(36.dp).clip(CircleShape).background(colors.onSurface.copy(alpha = 0.08f))
        .padding(start = Spacing.Row, end = if (value.isEmpty()) Spacing.Row else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_search), contentDescription = null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        Box(Modifier.weight(1f).padding(start = 6.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
            BasicTextField(value, onValueChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(36.dp)) {
            Icon(painterResource(R.drawable.ic_clear), contentDescription = tr("清除"), Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        }
    }
}
