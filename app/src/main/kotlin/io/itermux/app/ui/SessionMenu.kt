package io.itermux.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.itermux.app.R
import io.itermux.app.session.sessionName
import io.itermux.app.tr

/** One thing a card's long-press menu offers; a [destructive] one is red and says in [detail] what it does. */
internal class SessionMenuAction(val label: String, val icon: Int, val tag: String, val detail: String? = null,
    val destructive: Boolean = false, val onClick: () -> Unit)

/** The popup's window starts where the app's does; what it draws is placed from screen positions. */
private object WindowOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize) = IntOffset.Zero
}

/**
 * A card's long-press menu: everything but the card, at [card] on the screen, dims, and [groups] of actions open under
 * the finger at [press] (on the screen too), below the card or, without room there, above it. A rule parts the groups.
 */
@Composable
internal fun SessionMenu(card: Rect, press: Offset, groups: List<List<SessionMenuAction>>, onDismiss: () -> Unit,
    modifier: Modifier = Modifier) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(140)) }
    // Where the popup's window sits on the screen; nothing is drawn until it is known.
    var origin by remember { mutableStateOf<Offset?>(null) }
    Popup(popupPositionProvider = WindowOrigin, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val hole = origin?.let { card.translate(-it) }
        Layout(content = {
            Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionOnScreen() }
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .drawBehind {
                    if (hole == null) return@drawBehind
                    val radius = CornerRadius(18.dp.toPx())
                    val cut = Path().apply { addRoundRect(RoundRect(hole, radius)) }
                    clipPath(cut, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.6f * appear.value)) }
                    drawRoundRect(Color.White.copy(alpha = 0.12f * appear.value), hole.topLeft, hole.size, radius,
                        style = Stroke(1.dp.toPx()))
                })
            MenuPanel(groups, onDismiss, modifier.graphicsLayer {
                alpha = if (hole == null) 0f else appear.value
                val scale = 0.94f + 0.06f * appear.value
                scaleX = scale; scaleY = scale
            })
        }) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val margin = 12.dp.roundToPx()
            val gap = 8.dp.roundToPx()
            val scrim = measurables[0].measure(constraints)
            val menu = measurables[1].measure(Constraints(maxWidth = minOf(260.dp.roundToPx(), width - 2 * margin)))
            layout(width, height) {
                scrim.place(0, 0)
                if (hole != null) {
                    val finger = press - (origin ?: Offset.Zero)
                    // Its leading edge just before the finger, inside the card, and on the screen.
                    val x = (finger.x - 24.dp.toPx()).toInt()
                        .coerceAtMost(hole.right.toInt() - menu.width).coerceAtLeast(hole.left.toInt())
                        .coerceIn(margin, (width - menu.width - margin).coerceAtLeast(margin))
                    val below = hole.bottom.toInt() + gap
                    val above = hole.top.toInt() - gap - menu.height
                    val y = when {
                        below + menu.height <= height - margin -> below
                        above >= margin -> above
                        else -> height - margin - menu.height
                    }.coerceAtLeast(margin)
                    menu.place(x, y)
                }
            }
        }
    }
}

@Composable
private fun MenuPanel(groups: List<List<SessionMenuAction>>, onDismiss: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.width(IntrinsicSize.Max).widthIn(min = 200.dp), shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainerHigh, shadowElevation = 8.dp) {
        Column(Modifier.padding(6.dp)) {
            groups.filter { it.isNotEmpty() }.forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = colors.onSurface.copy(alpha = 0.08f))
                group.forEach { action ->
                    val tint = if (action.destructive) colors.error else colors.onSurface
                    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button) { onDismiss(); action.onClick() }
                        .padding(horizontal = 12.dp, vertical = 8.dp).testTag(action.tag),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(action.icon), contentDescription = null, Modifier.size(20.dp), tint = tint)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(action.label, style = MaterialTheme.typography.bodyLarge, color = tint, maxLines = 1)
                            action.detail?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = 0.65f), maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A name the rename dialog offers to fill in: [label] says where it comes from. */
internal class NameSuggestion(val label: String, val value: String)

/**
 * Names a card. Opens on its [current] name, else the [automatic] one, all selected, so typing replaces it; left
 * empty, or set to the automatic name, the card goes back to the automatic one. [identity] says which card this is.
 * Under the field, the automatic name and [suggestion] fill it in with a tap.
 */
@Composable
internal fun RenameSessionDialog(current: String, automatic: String, suggestion: NameSuggestion?, tag: String,
    onSave: (String) -> Unit, onDismiss: () -> Unit, identity: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val start = current.ifBlank { automatic }
    var field by remember { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    val chosen = sessionName(field.text).takeUnless { it == automatic }.orEmpty()
    val changed = chosen != current
    val save = { if (changed) onSave(chosen); onDismiss() }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("$tag-dialog"),
        title = { Text(tr("重命名会话")) },
        text = {
            Column {
                identity()
                Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.dialogPanel)
                    .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 6.dp)) {
                    Text(tr("名称"), style = MaterialTheme.typography.labelMedium,
                        color = if (focused) colors.primary else colors.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(field, { field = it }, Modifier.weight(1f).padding(vertical = 4.dp).focusRequester(focus)
                            .onFocusChanged { focused = it.isFocused }.testTag("$tag-input"),
                            singleLine = true, textStyle = MaterialTheme.typography.titleMedium.copy(color = colors.onSurface),
                            cursorBrush = SolidColor(colors.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { save() }),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (field.text.isEmpty()) Text(automatic, style = MaterialTheme.typography.titleMedium,
                                        color = colors.onSurfaceVariant.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    inner()
                                }
                            })
                        if (field.text.isNotEmpty()) Box(Modifier.size(36.dp).clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = tr("清空名称")) { field = TextFieldValue("") }
                            .testTag("$tag-clear"), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(20.dp).clip(CircleShape).background(colors.onSurface.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_close), contentDescription = tr("清空名称"), Modifier.size(12.dp),
                                    tint = colors.onSurface)
                            }
                        }
                    }
                }
                // The dialog's window lays the field out after the dialog itself, so focus is asked for from in here.
                LaunchedEffect(Unit) { focus.requestFocus() }
                if (field.text.isEmpty()) Text(tr("留空则使用自动名称"), Modifier.padding(start = 4.dp, top = 6.dp),
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                    SuggestionChip(tr("自动"), automatic, chosen.isEmpty(), "$tag-auto", Modifier.weight(1f, fill = false)) {
                        field = TextFieldValue("")
                    }
                    suggestion?.takeIf { it.value.isNotBlank() && sessionName(it.value) != automatic }?.let { offered ->
                        val value = sessionName(offered.value)
                        SuggestionChip(offered.label, value, chosen == value, "$tag-suggestion", Modifier.weight(1f, fill = false)) {
                            field = TextFieldValue(value, TextRange(value.length))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = changed, modifier = Modifier.testTag("$tag-save")) { Text(tr("保存")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消")) } })
}

/** A name to fill in: where it comes from, faint, then the name; [selected] while the field holds it. */
@Composable
private fun SuggestionChip(label: String, value: String, selected: Boolean, tag: String, modifier: Modifier = Modifier,
    onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (selected) colors.primary else colors.onSurfaceVariant
    Row(modifier.clip(CircleShape).background(if (selected) colors.primary.copy(alpha = 0.14f) else colors.onSurface.copy(alpha = 0.07f))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 11.dp, vertical = 6.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint.copy(alpha = 0.75f), maxLines = 1, softWrap = false)
        Spacer(Modifier.width(5.dp))
        Text(value, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
