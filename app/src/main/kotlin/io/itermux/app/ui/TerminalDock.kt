package io.itermux.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TerminalDock(
    floating: Boolean,
    availableHeight: Dp,
    landscape: Boolean,
    imeVisible: Boolean,
    modifier: Modifier = Modifier,
    collapseControl: @Composable () -> Unit,
    sessionControl: @Composable (Modifier) -> Unit,
    primaryControls: @Composable () -> Unit,
    keyControls: @Composable () -> Unit
) {
    val twoColumns = landscape && !imeVisible && availableHeight >= 320.dp
    val dockModifier = modifier.width(if (twoColumns) 96.dp else 48.dp)
        .then(if (floating) Modifier else Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Vertical)))
        .testTag("terminal-dock")
    if (twoColumns) {
        Column(dockModifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Row {
                collapseControl()
                sessionControl(Modifier.size(48.dp))
            }
            FlowRow(maxItemsInEachRow = 2) {
                primaryControls()
                keyControls()
            }
        }
    } else {
        // Measure every control naturally. New actions must not consume a fixed shortcut budget.
        Column(dockModifier.heightIn(max = (availableHeight - 8.dp).coerceAtLeast(0.dp))
            .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally) {
            collapseControl()
            sessionControl(Modifier.size(48.dp))
            primaryControls()
            keyControls()
        }
    }
}
