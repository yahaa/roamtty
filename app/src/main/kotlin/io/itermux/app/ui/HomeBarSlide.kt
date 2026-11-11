package io.itermux.app.ui

import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt

/**
 * The landscape home page's one short bar, which slides away as a list scrolls on and comes back as soon as it
 * scrolls back: [connection] goes on the lists, [modifier] on the bar.
 */
@Stable
internal class HomeBarSlide(val connection: NestedScrollConnection, private val offset: FloatState) {
    /** Slid up by the offset, and only as tall as what still shows, so the list takes the rest. */
    val modifier: Modifier = Modifier.clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val shift = offset.floatValue.roundToInt()
        layout(placeable.width, (placeable.height + shift).coerceAtLeast(0)) { placeable.place(0, shift) }
    }
}

/** [slides] while the bar may move at all (not with the search open); [listAtTop] while the list in front rests at its first row. */
@Composable
internal fun rememberHomeBarSlide(slides: Boolean, listAtTop: Boolean): HomeBarSlide {
    val barHeight = with(LocalDensity.current) { HOME_COMPACT_BAR_HEIGHT.toPx() }
    val offset = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(slides) { if (!slides) offset.floatValue = 0f }
    // A scroll that skips nested scrolling (scrolling to an item, a reset to the top) can bring the list back to its
    // first row with the bar still away: the bar follows it back.
    LaunchedEffect(listAtTop, slides) {
        if (listAtTop && slides && offset.floatValue < 0f) animate(offset.floatValue, 0f) { value, _ -> offset.floatValue = value }
    }
    val connection = remember(slides, barHeight) {
        object : NestedScrollConnection {
            // The bar moves first; the list scrolls by what is left.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!slides) return Offset.Zero
                val before = offset.floatValue
                offset.floatValue = (before + available.y).coerceIn(-barHeight, 0f)
                return Offset(0f, offset.floatValue - before)
            }

            // Never left half way: to whichever end is nearer.
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val current = offset.floatValue
                if (slides && current > -barHeight && current < 0f)
                    animate(current, if (current < -barHeight / 2) -barHeight else 0f) { value, _ -> offset.floatValue = value }
                return Velocity.Zero
            }
        }
    }
    return remember(connection) { HomeBarSlide(connection, offset) }
}
