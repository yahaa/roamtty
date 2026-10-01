package io.itermux.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.itermux.app.R
import io.itermux.app.tr

/** Shared with the home screen so search keeps the same appearance across pages. */
@Composable
internal fun AppSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default) {
    OutlinedTextField(value, onValueChange, placeholder = { Text(placeholder) },
        leadingIcon = { Text("⌕", fontSize = 23.sp) },
        trailingIcon = { if (value.isNotEmpty()) TextButton(onClick = { onValueChange("") }) { Text(tr("清除")) } },
        singleLine = true, modifier = modifier, shape = RoundedCornerShape(14.dp),
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions)
}

@Composable
internal fun AppSearchToggle(expanded: Boolean, onToggle: () -> Unit, searchDescription: String,
    modifier: Modifier = Modifier) {
    IconButton(onClick = onToggle, modifier = modifier) {
        if (expanded) Text("×", fontSize = 26.sp,
            modifier = Modifier.semantics { contentDescription = tr("收起搜索") })
        else Icon(painterResource(R.drawable.ic_search), contentDescription = searchDescription)
    }
}
