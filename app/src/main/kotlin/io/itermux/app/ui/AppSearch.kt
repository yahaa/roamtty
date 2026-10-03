package io.itermux.app.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
