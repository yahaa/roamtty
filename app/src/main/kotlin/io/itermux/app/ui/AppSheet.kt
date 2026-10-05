package io.itermux.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.tr

/** Shared, content-first presentation for short selection tasks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSheet(onDismiss: () -> Unit, fillHeight: Boolean = true, fullScreen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 560.dp,
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0), bottomEnd = CornerSize(0)),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().then(when {
            fullScreen -> Modifier.fillMaxHeight()
            fillHeight -> Modifier.fillMaxHeight(0.88f)
            else -> Modifier
        }).imePadding(),
            content = content)
    }
}

/** Material 3 1.3 uses the system theme for its dialog window, while our app can override it. */
@Composable
internal fun SheetSystemBars(background: Color = MaterialTheme.colorScheme.surfaceContainerLow, immersive: Boolean = false) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val light = background.luminance() > 0.5f
    SideEffect {
        window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
                // Immersive sheets give the whole screen to content; an edge swipe brings the bars back for a moment.
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (immersive) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@Composable
internal fun SheetHeader(title: String, subtitle: String? = null, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = tr("关闭"))
        }
    }
}
