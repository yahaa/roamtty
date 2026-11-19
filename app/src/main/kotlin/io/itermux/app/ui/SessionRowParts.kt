package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.session.AgentStatus
import io.itermux.app.session.CodingAgent
import io.itermux.app.tr
import io.itermux.app.trf
import kotlinx.coroutines.delay

/** The last part of [path], or the path itself when it is a root ("/", "C:\\"). */
internal fun pathLeaf(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

/** The agent's name on a small wash of its colour, before the session's name. */
@Composable
internal fun AgentBadge(agent: CodingAgent, modifier: Modifier = Modifier) {
    val brand = agent.brand()
    Text(agent.shortLabel, modifier.clip(RoundedCornerShape(5.dp)).background(brand.copy(alpha = 0.14f))
        .padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = brand,
        fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
}

/** The host a session runs on: its name on a wash of [hostColor], grey around a hollow dot while the host is offline. */
@Composable
internal fun HostChip(name: String, id: String, offline: Boolean, tag: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val tint = if (offline) colors.onSurfaceVariant.copy(alpha = 0.7f) else hostColor(id)
    Row(modifier.widthIn(max = 140.dp).clip(CircleShape)
        .background(if (offline) colors.onSurface.copy(alpha = 0.06f) else tint.copy(alpha = 0.14f))
        .padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape)
            .then(if (offline) Modifier.border(1.5.dp, tint, CircleShape) else Modifier.background(tint)))
        Spacer(Modifier.width(5.dp))
        Text(name, Modifier.testTag(tag), style = MaterialTheme.typography.labelMedium, color = tint,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A dot and [label] in the colour of [state]. */
@Composable
internal fun AgentStateLabel(state: AgentStatus.State, label: String, modifier: Modifier = Modifier) {
    val color = state.color()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false)
    }
}

/** A small pill button on a row's status line: filled with [fill], text only when [plain], else on a neutral wash. */
/** The right of a row: its actions always fit, and the tags before them give way one at a time when they would not. */
@Composable
internal fun SessionRowTrailing(tags: List<String>, tagPrefix: String, sessionId: String, modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        // A tag is whole or gone: squeezed, it would read "tm…".
        Layout(content = { tags.forEach { MetaTag(it, Modifier.testTag("$tagPrefix-tag-$it-$sessionId")) } },
            modifier = Modifier.weight(1f, fill = false).testTag("$tagPrefix-tags-$sessionId")) { measurables, constraints ->
            val gap = 6.dp.roundToPx()
            val placeables = measurables.map { it.measure(Constraints()) }
            var width = 0
            val shown = placeables.takeWhile { tag ->
                val next = width + (if (width > 0) gap else 0) + tag.width
                (next <= constraints.maxWidth).also { fits -> if (fits) width = next }
            }
            val height = shown.maxOfOrNull { it.height } ?: 0
            layout(width, height) {
                var x = 0
                shown.forEach { tag ->
                    tag.placeRelative(x, (height - tag.height) / 2)
                    x += tag.width + gap
                }
            }
        }
        actions()
    }
}

@Composable
internal fun RowAction(label: String, tag: String, icon: Int? = null, fill: Color? = null, plain: Boolean = false,
    iconOnly: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = fill ?: if (plain) Color.Transparent else colors.onSurface.copy(alpha = 0.07f)
    val content = if (fill != null) Color(0xFF1D1A12) else if (plain) colors.onSurfaceVariant else colors.onSurface
    // Only the icon: a tag among the row's tags; the label is what TalkBack reads.
    if (icon != null && iconOnly) {
        IconTag(icon, label, tag, onClick)
        return
    }
    Row(Modifier.height(28.dp).clip(CircleShape).background(background).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = if (plain) 6.dp else 10.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, Modifier.size(13.dp), tint = content)
            Spacer(Modifier.width(4.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = content, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false)
    }
}

/** The follow-up typed on the row: a line and a send button; the keyboard's send works too. */
@Composable
internal fun FollowUpField(text: String, onText: (String) -> Unit, agent: CodingAgent?, sending: Boolean, tagPrefix: String,
    id: String, onSend: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(text, onText, Modifier.weight(1f).heightIn(min = 38.dp).clip(RoundedCornerShape(10.dp))
            .background(colors.background).border(1.dp, colors.onSurface.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            .focusRequester(focus).testTag("$tagPrefix-follow-up-input-$id"),
            singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onSend() }),
            decorationBox = { field ->
                Box(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text(trf("继续问 %s…", agent?.shortLabel ?: "Agent"), style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1)
                    field()
                }
            })
        Spacer(Modifier.width(Spacing.Tight))
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
            .background(if (text.isBlank() || sending) colors.onSurface.copy(alpha = 0.3f) else colors.onSurface)
            .clickable(enabled = text.isNotBlank() && !sending, role = Role.Button, onClickLabel = tr("发送"), onClick = onSend)
            .testTag("$tagPrefix-follow-up-send-$id"), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = tr("发送"), Modifier.size(18.dp), tint = colors.surface)
        }
    }
}

/**
 * Seconds the agent has been in its state, moved on between polls by a clock of its own that starts over with each
 * status; 0 without one.
 */
@Composable
internal fun rememberElapsedSeconds(status: AgentStatus?): Long {
    var ticks by remember(status) { mutableLongStateOf(0L) }
    LaunchedEffect(status) { if (status != null) while (true) { delay(10_000); ticks += 1 } }
    return ((status?.elapsedMillis ?: 0L) + ticks * 10_000) / 1000
}

/** A running state's length, short as Claude Code's own: "12s", "4m20s", "1h05m". */
internal fun shortDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m${(seconds % 60).toString().padStart(2, '0')}s"
    else -> "${seconds / 3600}h${(seconds % 3600 / 60).toString().padStart(2, '0')}m"
}

/** How long ago an agent finished, without spaces so it never breaks across lines: "刚刚", "3分钟前". */
internal fun ago(seconds: Long): String = when {
    seconds < 60 -> tr("刚刚")
    seconds < 3600 -> trf("%d分钟前", seconds / 60)
    seconds < 86_400 -> trf("%d小时前", seconds / 3600)
    else -> trf("%d天前", seconds / 86_400)
}

/**
 * A card title on one line that, when too long for it, keeps scrolling like a subtitle (rests 1.2 s, then 30 dp/s with a
 * third of the line between copies) and fades out at the right edge; a title that fits stays still and unfaded.
 */
@Composable
internal fun MarqueeTitle(text: String, modifier: Modifier = Modifier) {
    // The marquee lays the text out at its full width; more than the line gets means it scrolls.
    var full by remember { mutableIntStateOf(0) }
    var line by remember { mutableIntStateOf(0) }
    val fade = if (full > line) Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val edge = 18.dp.toPx()
        drawRect(Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - edge,
            endX = size.width), blendMode = BlendMode.DstIn)
    } else Modifier
    Text(text, modifier.onSizeChanged { line = it.width }.then(fade).basicMarquee(iterations = Int.MAX_VALUE),
        style = MaterialTheme.typography.titleMedium, maxLines = 1, onTextLayout = { full = it.size.width })
}

/** How tall a card's status line stands, with or without the icon buttons (36dp to touch) at its end. */
internal val STATUS_ROW_HEIGHT = 36.dp
/** How strongly an offline host's card shows its agent's marks. */
internal const val OFFLINE_ALPHA = 0.55f
