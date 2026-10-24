package io.itermux.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Center on the upper golden section of the current safe viewport, then clamp to its edges.
 * The default [endInset] keeps floating controls close to the screen edge, just off the rounded corner.
 * While [held], the centre stays where it was, so a keyboard coming or going does not move it from under a finger.
 */
@Composable
internal fun TerminalDockPlacement(modifier: Modifier = Modifier, endInset: Dp = 8.dp, held: Boolean = false,
    content: @Composable (Dp) -> Unit) {
    val heldCenter = remember { floatArrayOf(Float.NaN) }
    val holding by rememberUpdatedState(held)
    BoxWithConstraints(modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.statusBars)
        .only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End))) {
        val availableHeight = (maxHeight - 8.dp).coerceAtLeast(0.dp)
        Layout(content = { content(availableHeight) }, modifier = Modifier.fillMaxSize().testTag("terminal-dock-viewport")) { measurables, constraints ->
            val margin = 4.dp.roundToPx().coerceAtMost(constraints.maxHeight / 2)
            val inset = endInset.roundToPx().coerceAtMost(constraints.maxWidth)
            val child = measurables.single().measure(Constraints(
                maxWidth = constraints.maxWidth - inset, maxHeight = constraints.maxHeight - 2 * margin))
            val center = heldCenter[0].takeIf { holding && !it.isNaN() } ?: (constraints.maxHeight * 0.382f)
            heldCenter[0] = center
            val top = (center - child.height / 2f).roundToInt()
                .coerceIn(margin, (constraints.maxHeight - margin - child.height).coerceAtLeast(margin))
            layout(constraints.maxWidth, constraints.maxHeight) {
                child.placeRelative(constraints.maxWidth - inset - child.width, top)
            }
        }
    }
}

/** Landscape with room to spare lays the dock out as a two-column grid. */
internal fun terminalDockTwoColumns(landscape: Boolean, imeVisible: Boolean, availableHeight: Dp) =
    landscape && !imeVisible && availableHeight >= 320.dp

/** A pill for the single column; the grid gets corners that follow its round corner buttons rather than one big oval. */
internal fun terminalDockShape(twoColumns: Boolean): Shape =
    if (twoColumns) RoundedCornerShape(24.dp) else RoundedCornerShape(percent = 50)

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TerminalDock(
    availableHeight: Dp,
    landscape: Boolean,
    imeVisible: Boolean,
    modifier: Modifier = Modifier,
    collapseControl: @Composable () -> Unit,
    sessionControl: @Composable (Modifier) -> Unit,
    primaryControls: @Composable () -> Unit
) {
    val twoColumns = terminalDockTwoColumns(landscape, imeVisible, availableHeight)
    val dockModifier = modifier.width(if (twoColumns) 96.dp else 48.dp)
        .heightIn(max = availableHeight.coerceAtLeast(0.dp)).verticalScroll(rememberScrollState())
        .testTag("terminal-dock")
    if (twoColumns) {
        Column(dockModifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Row {
                collapseControl()
                sessionControl(Modifier.size(48.dp))
            }
            FlowRow(maxItemsInEachRow = 2) {
                primaryControls()
            }
        }
    } else {
        // Measure every control naturally. New actions must not consume a fixed shortcut budget.
        Column(dockModifier,
            horizontalAlignment = Alignment.CenterHorizontally) {
            collapseControl()
            sessionControl(Modifier.size(48.dp))
            primaryControls()
        }
    }
}
