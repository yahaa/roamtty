package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.desktop.DesktopConnection
import io.itermux.desktop.*
import kotlin.math.roundToInt

/** A floating mouse: drag its pad to move the cursor, buttons for left and right, a strip to scroll. */
@Composable
internal fun BoxScope.VirtualMouse(connection: DesktopConnection) {
    var offset by remember { mutableStateOf(IntOffset(0, 0)) }
    val density = LocalDensity.current.density
    var rest by remember { mutableStateOf(Offset.Zero) }
    Column(Modifier.align(Alignment.BottomEnd).padding(16.dp).offset { offset }.width(132.dp).clip(RoundedCornerShape(20.dp))
        .background(DesktopPanelColor).testTag("desktop-mouse")) {
        Box(Modifier.fillMaxWidth().height(18.dp).pointerInput(Unit) {
            detectDragGestures { change, drag -> change.consume(); offset += IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
        }, contentAlignment = Alignment.Center) { Box(Modifier.size(28.dp, 4.dp).clip(CircleShape).background(DesktopInkDim)) }
        Row(Modifier.fillMaxWidth().height(44.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Color(0x22FFFFFF)).pointerInput(Unit) {
                detectTapGestures(onPress = {
                    connection.input(DesktopProtocol.button(1, true)); tryAwaitRelease(); connection.input(DesktopProtocol.button(1, false))
                })
            }.testTag("desktop-mouse-left"))
            Box(Modifier.width(18.dp).fillMaxHeight().pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    connection.input(DesktopProtocol.wheel(0, (-drag.y * 4 / density).roundToInt()))
                }
            }.testTag("desktop-mouse-wheel"), contentAlignment = Alignment.Center) { Box(Modifier.size(4.dp, 20.dp).clip(CircleShape).background(DesktopInkDim)) }
            Box(Modifier.weight(1f).fillMaxHeight().background(Color(0x22FFFFFF)).pointerInput(Unit) {
                detectTapGestures(onPress = {
                    connection.input(DesktopProtocol.button(2, true)); tryAwaitRelease(); connection.input(DesktopProtocol.button(2, false))
                })
            }.testTag("desktop-mouse-right"))
        }
        Box(Modifier.fillMaxWidth().height(70.dp).pointerInput(Unit) {
            detectDragGestures { change, drag ->
                change.consume()
                rest += drag * 1.5f
                val dx = rest.x.toInt(); val dy = rest.y.toInt()
                rest -= Offset(dx.toFloat(), dy.toFloat())
                if (dx != 0 || dy != 0) connection.input(DesktopProtocol.rel(dx, dy))
            }
        }.testTag("desktop-mouse-pad"), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_mouse), null, Modifier.size(22.dp), tint = DesktopInkDim)
        }
    }
}
