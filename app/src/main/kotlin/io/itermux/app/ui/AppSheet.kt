package io.itermux.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.*
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.tr

/**
 * Shared, content-first presentation for short selection tasks. With [onBack] the sheet owns Back, for one with steps:
 * it gets a `close` that slides the sheet away, to call once there is no step left to go back to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSheet(onDismiss: () -> Unit, fillHeight: Boolean = true, fullScreen: Boolean = false,
    onBack: ((close: () -> Unit) -> Unit)? = null, wide: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        // On API 34+ the sheet otherwise takes Back on its window, ahead of any BackHandler.
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = onBack == null),
        // A [wide] sheet takes the whole width with no drag handle, for a landscape layout of its own.
        sheetMaxWidth = if (wide) Dp.Unspecified else 560.dp,
        dragHandle = if (wide) null else { { BottomSheetDefaults.DragHandle() } },
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0), bottomEnd = CornerSize(0)),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        SheetSystemBars()
        // A BackHandler in the content joins the sheet dialog's dispatcher, not the activity's, so it runs first.
        val view = LocalView.current
        val backOwner = remember(view) { view.findViewTreeOnBackPressedDispatcherOwner() }
        val body = @Composable {
            Column(Modifier.fillMaxWidth().then(when {
                fullScreen -> Modifier.fillMaxHeight()
                fillHeight -> Modifier.fillMaxHeight(0.88f)
                else -> Modifier
            }).imePadding(),
                content = content)
            if (onBack != null) BackHandler { onBack { scope.launch { state.hide(); onDismiss() } } }
        }
        if (backOwner != null) CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides backOwner, body) else body()
    }
}

/** Material 3 1.3 uses the system theme for its dialog window, while our app can override it. */
@Composable
internal fun SheetSystemBars(background: Color = MaterialTheme.colorScheme.surfaceContainerLow, immersive: Boolean = false,
    hideNavigation: Boolean = false) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val light = background.luminance() > 0.5f
    // Landscape is short on rows everywhere, so sheets hide the bars just like the pages beneath them.
    val hideBars = immersive || isLandscape()
    SideEffect {
        window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
                // An edge swipe brings hidden bars back for a moment.
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (hideBars) hide(WindowInsetsCompat.Type.systemBars())
                else {
                    show(WindowInsetsCompat.Type.statusBars())
                    if (hideNavigation) hide(WindowInsetsCompat.Type.navigationBars()) else show(WindowInsetsCompat.Type.navigationBars())
                }
            }
        }
    }
}

@Composable
internal fun SheetHeader(title: String, subtitle: String? = null, onDismiss: () -> Unit, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 8.dp else 24.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("sheet-back")) {
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = tr("返回"))
        }
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
