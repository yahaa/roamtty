package io.itermux.app.ui

import io.itermux.app.tr
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.itermux.ssh.Phase

/*
 * Shared building blocks. Apple's layout principles, expressed with Compose:
 * one large title per page, toolbar actions as icons, segmented controls for peer views,
 * inset grouped panels for settings and forms, and quiet empty states with one clear action.
 */

/** Spacing follows a 4 dp grid; pages use [Page] as their outer gutter. */
internal object Spacing {
    val Hairline = 4.dp
    val Tight = 8.dp
    val Row = 12.dp
    val Page = 16.dp
    val Section = 24.dp
}

/** Panels sit on the page background: white on warm grey in light, one step brighter in dark. */
internal val ColorScheme.groupedContainer: Color
    @Composable @ReadOnlyComposable
    get() = if (background.luminance() > 0.5f) surfaceContainerLowest else surfaceContainer

/** Panels inside a dialog sit one step brighter than its surfaceContainerHigh sheet, never darker. */
internal val ColorScheme.dialogPanel: Color
    @Composable @ReadOnlyComposable
    get() = if (background.luminance() > 0.5f) surfaceContainerLowest else surfaceContainerHighest

/**
 * A toolbar row: navigation on the leading edge, then [actions]. Without a [title], actions start right
 * after navigation, so callers decide where flexible space goes (a centered segment or a spacer).
 */
@Composable
internal fun AppToolbar(
    modifier: Modifier = Modifier,
    title: String? = null,
    navigation: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        navigation()
        if (title != null) Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).weight(1f).semantics { heading() })
        actions()
    }
}

/** Large page title (or custom [titleContent]) with an optional one-line summary and a trailing page action, aligned to the page gutter. */
@Composable
internal fun LargeTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null,
    titleContent: (@Composable () -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = Spacing.Page, end = if (action == null) Spacing.Page else Spacing.Tight,
        bottom = Spacing.Row), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (titleContent != null) titleContent()
            else Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() })
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        action?.invoke()
    }
}

@Composable
internal fun ToolbarIcon(icon: Int, description: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(24.dp),
            tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}

internal class Segment(val label: String, val selected: Boolean, val tag: String? = null, val onClick: () -> Unit)

/**
 * A segmented control: equal segments, a sliding thumb and tab semantics.
 * The visible track is 36 dp; the touch target stays 48 dp tall.
 */
@Composable
internal fun SegmentedControl(segments: List<Segment>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val light = colors.background.luminance() > 0.5f
    val selectedIndex = segments.indexOfFirst { it.selected }.coerceAtLeast(0)
    BoxWithConstraints(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        val trackPadding = 3.dp
        val segmentWidth = (maxWidth - trackPadding * 2) / segments.size
        val thumbOffset by animateDpAsState(segmentWidth * selectedIndex, spring(dampingRatio = 0.85f, stiffness = 600f),
            label = "segment-thumb")
        Box(Modifier.fillMaxWidth().height(36.dp).clip(MaterialTheme.shapes.extraSmall)
            .background(if (light) colors.surfaceContainerHighest else colors.surfaceContainerHigh)
            .padding(trackPadding)) {
            Surface(Modifier.offset(x = thumbOffset).width(segmentWidth).fillMaxHeight(),
                shape = RoundedCornerShape(8.dp), color = if (light) colors.surfaceContainerLowest else colors.surfaceBright,
                shadowElevation = if (light) 1.dp else 0.dp) {}
            Row(Modifier.fillMaxSize().selectableGroup()) {
                segments.forEach { segment ->
                    Box(Modifier.weight(1f).fillMaxHeight()
                        .selectable(segment.selected, onClick = segment.onClick, role = Role.Tab)
                        .then(segment.tag?.let { Modifier.testTag(it) } ?: Modifier),
                        contentAlignment = Alignment.Center) {
                        Text(segment.label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (segment.selected) colors.onSurface else colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** An inset grouped panel with an optional caption above and explanation below. */
@Composable
internal fun GroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        header?.let {
            Text(it, style = MaterialTheme.typography.sectionHeader, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.Page, end = Spacing.Page, bottom = Spacing.Tight).semantics { heading() })
        }
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.groupedContainer) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
        footer?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.Page, end = Spacing.Page, top = Spacing.Tight))
        }
    }
}

/** Divider between rows of a grouped panel, inset to the text column. */
@Composable
internal fun GroupedDivider(start: Dp = Spacing.Page) {
    HorizontalDivider(Modifier.padding(start = start), thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * A grouped-panel row. Whole-row tap when [onClick] is set; trailing content sits on the end edge.
 * A disabled row keeps its place but dims and ignores taps.
 */
@Composable
internal fun GroupedRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
        .alpha(if (enabled) 1f else 0.38f)
        .heightIn(min = 52.dp).padding(horizontal = Spacing.Page, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        leading?.let { it(); Spacer(Modifier.width(Spacing.Row)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.let { Spacer(Modifier.width(Spacing.Tight)); it() }
    }
}

/** Disclosure chevron for rows that open another view. */
@Composable
internal fun Chevron() {
    Icon(painterResource(io.itermux.app.R.drawable.ic_chevron_right), contentDescription = null,
        tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
}

/** Centered message for an empty list: optional artwork, a title, one sentence and one action. */
@Composable
internal fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    artwork: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = Spacing.Section),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        artwork?.let { it(); Spacer(Modifier.height(Spacing.Page)) }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.Tight))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        action?.let { Spacer(Modifier.height(Spacing.Section)); it() }
    }
}

/** Connection state as a coloured dot plus text, so colour is never the only signal; [hostOffline] names the reason. */
@Composable
internal fun StatusLabel(phase: Phase, modifier: Modifier = Modifier, hostOffline: Boolean = false) {
    val color = if (hostOffline) StatusColors.danger else StatusColors.of(phase)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(if (hostOffline) tr("主机离线") else phaseLabel(phase), style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false,
            color = if (phase == Phase.CONNECTED) color else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small neutral pill for secondary attributes such as session mode or authentication. */
@Composable
internal fun MetaTag(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(horizontal = 6.dp, vertical = 2.dp))
}

/**
 * A button drawn as a [MetaTag]: its height, corners and wash, holding only an icon, so it sits in a row of tags as one
 * of them. [label] is what TalkBack reads; [badge] puts a status dot in its top corner. The touch target reaches past
 * the tag's left side only, so the tag lines up with the row's right edge.
 */
@Composable
internal fun IconTag(icon: Int, label: String, tag: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    wash: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), badge: Color? = null, badgeTag: String? = null) {
    Box(Modifier.size(40.dp, 36.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.CenterEnd) {
        Box(Modifier.size(28.dp, 20.dp).clip(RoundedCornerShape(6.dp)).background(wash), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = label, Modifier.size(13.dp), tint = tint)
        }
        if (badge != null) Box(Modifier.align(Alignment.CenterEnd).offset(3.dp, (-10).dp).size(7.dp).clip(CircleShape)
            .background(badge).border(1.5.dp, MaterialTheme.colorScheme.groupedContainer, CircleShape)
            .then(if (badgeTag != null) Modifier.testTag(badgeTag) else Modifier))
    }
}
