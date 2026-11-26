package io.itermux.app.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.itermux.desktop.VoiceCallPhase
import io.itermux.desktop.VoiceMode
import io.itermux.desktop.VoiceUiState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Semantic voice call visual state for the `26×26` ghost pet.
 * In any state other than [Idle], the ghost swaps its left-hand mini keyboard for a retro curved
 * telephone handset lifted to its ear and reduces right-hand wrench motion to ~20%.
 */
internal sealed interface PetVoiceState {
    data object Idle : PetVoiceState
    data object Connecting : PetVoiceState
    data class Listening(val level: Float = 0f, val dictating: Boolean = false) : PetVoiceState
    data class Speaking(val level: Float = 0f) : PetVoiceState
    data object Muted : PetVoiceState

    val inCall: Boolean get() = this !is Idle

    /** Stable test-tag suffix identifying the visual state. */
    val tagName: String
        get() = when (this) {
            Idle -> "idle"
            Connecting -> "connecting"
            is Listening -> if (dictating) "dictating" else "listening"
            is Speaking -> "speaking"
            Muted -> "muted"
        }

    companion object {
        fun from(ui: VoiceUiState): PetVoiceState = when (ui.phase) {
            VoiceCallPhase.Idle, is VoiceCallPhase.Failed -> Idle
            is VoiceCallPhase.Connecting -> Connecting
            VoiceCallPhase.Listening -> Listening(ui.micLevel, dictating = ui.mode == VoiceMode.DICTATE)
            VoiceCallPhase.Speaking -> Speaking(ui.speakerLevel)
            VoiceCallPhase.Muted -> Muted
        }
    }
}

/**
 * The collapsed toolbox: a little ghost floating at the edge with a wrench and a little keyboard
 * (or a curved retro telephone handset lifted to its ear during an active `agy /voice` call).
 *
 * A tap calls [onClick]. A long press calls [onHoldStart] with the pet centre, then the ghost follows
 * the finger and [onHoldMove] gets the finger's offset from that centre; [onHoldEnd] reports a release
 * (true) or a cancel (false) and the ghost springs home. Positions are in root coordinates.
 */
@Composable
internal fun ToolboxPet(
    description: String,
    voiceState: PetVoiceState = PetVoiceState.Idle,
    onHoldStart: (Offset) -> Unit = {},
    onHoldMove: (Offset) -> Unit = {},
    onHoldEnd: (Boolean) -> Unit = {},
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val follow = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val click by rememberUpdatedState(onClick)
    val holdStart by rememberUpdatedState(onHoldStart)
    val holdMove by rememberUpdatedState(onHoldMove)
    val holdEnd by rememberUpdatedState(onHoldEnd)
    val press by animateFloatAsState(if (pressed) 0.86f else 1f, spring(dampingRatio = 0.4f, stiffness = 900f), label = "press")
    val hop = remember { Animatable(0f) }
    // Hop playfully when first appearing and when swapping between keyboard and telephone handset.
    LaunchedEffect(voiceState.inCall, voiceState is PetVoiceState.Connecting) {
        hop.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
        hop.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 450f))
    }
    val reducedMotion = rememberReducedMotion()
    val motion = rememberGhostMotion(voiceState, reducedMotion)
    val wrench = MaterialTheme.colorScheme.primary
    val canvasSize = if (voiceState.inCall) 54.dp else 44.dp
    val boxSize = if (voiceState.inCall) 54.dp else 48.dp

    Box(Modifier.size(boxSize)
        .onGloballyPositioned { coordinates = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                pressed = true
                // Lifting before the long-press timeout is a tap; sliding off first cancels.
                var tapped = false
                val quick = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    tapped = waitForUpOrCancellation()?.also { it.consume() } != null
                }
                if (quick != null) {
                    pressed = false
                    if (tapped) click()
                    return@awaitEachGesture
                }
                pressed = false
                val center = coordinates?.localToRoot(Offset(size.width / 2f, size.height / 2f)) ?: return@awaitEachGesture
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                holdStart(center)
                val released = drag(down.id) { change ->
                    change.consume()
                    val local = change.position - Offset(size.width / 2f, size.height / 2f)
                    scope.launch { follow.snapTo(local) }
                    // From where the hold began on screen, so the pet moving under the finger does not point the ring
                    // at another item.
                    val offset = coordinates?.localToRoot(change.position)?.minus(center) ?: local
                    holdMove(offset)
                }
                holdEnd(released)
                scope.launch { follow.animateTo(Offset.Zero, spring(dampingRatio = 0.45f, stiffness = 380f)) }
            }
        }
        .semantics {
            contentDescription = description
            onClick { click(); true }
        }
        .testTag("restore-dock"),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(canvasSize).graphicsLayer {
            translationX = follow.value.x
            translationY = follow.value.y
        }.testTag("pet-voice-${voiceState.tagName}")) {
            drawGhostStage(
                wrench = wrench,
                voiceState = voiceState,
                motion = motion,
                press = press,
                hop = hop.value,
                reducedMotion = reducedMotion,
            )
        }
    }
}

/** Enlarged ghost preview holding the telephone handset for the confirmation bottom sheet. */
@Composable
internal fun VoicePetPreview(
    voiceState: PetVoiceState = PetVoiceState.Listening(0.35f),
    size: Dp = 54.dp,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReducedMotion()
    val motion = rememberGhostMotion(voiceState, reducedMotion)
    val wrench = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(size).testTag("voice-sheet-pet")) {
        drawGhostStage(
            wrench = wrench,
            voiceState = voiceState,
            motion = motion,
            press = 1f,
            hop = 0f,
            reducedMotion = reducedMotion,
        )
    }
}

@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

private class GhostMotion(
    val float: Float,
    val sway: Float,
    val hem: Float,
    val eyes: Float,
    val wave: Float,
    val pulsePhase: Float,
    val ringPhase: Float,
    val soundPhase: Float,
)

@Composable
private fun rememberGhostMotion(voiceState: PetVoiceState, reducedMotion: Boolean): GhostMotion {
    if (reducedMotion) {
        return GhostMotion(float = 0.35f, sway = 0f, hem = 0f, eyes = 1f, wave = 0f, pulsePhase = 0f, ringPhase = 0f, soundPhase = 0f)
    }
    val idle = rememberInfiniteTransition(label = "pet")
    val floatPeriod = when (voiceState) {
        PetVoiceState.Muted -> 2200
        is PetVoiceState.Speaking -> 1050
        else -> 1400
    }
    val swayAmp = when (voiceState) {
        is PetVoiceState.Speaking -> 6.5f
        PetVoiceState.Muted -> 2.5f
        else -> 5f
    }
    val swayPeriod = if (voiceState is PetVoiceState.Speaking) 1500 else 2300
    val pulsePeriod = if (voiceState is PetVoiceState.Connecting) 1400 else 1800

    val floatVal by idle.animateFloat(0f, 1f, infiniteRepeatable(tween(floatPeriod, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float")
    val sway by idle.animateFloat(-swayAmp, swayAmp, infiniteRepeatable(tween(swayPeriod, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sway")
    val hem by idle.animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "hem")
    val eyes by idle.animateFloat(1f, 1f, GhostBlink, label = "blink")
    val rawWave by idle.animateFloat(0f, 0f, GhostWave, label = "wave")
    val pulse by idle.animateFloat(0f, 1f, infiniteRepeatable(tween(pulsePeriod, easing = LinearEasing)), label = "pulse")
    val ring by idle.animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(840, easing = LinearEasing)), label = "ring")
    val sound by idle.animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(1190, easing = LinearEasing)), label = "sound")

    return GhostMotion(
        float = floatVal,
        sway = sway,
        hem = hem,
        eyes = eyes,
        wave = if (voiceState.inCall) rawWave * 0.18f else rawWave,
        pulsePhase = pulse,
        ringPhase = ring,
        soundPhase = sound,
    )
}

private fun DrawScope.drawGhostStage(
    wrench: Color,
    voiceState: PetVoiceState,
    motion: GhostMotion,
    press: Float,
    hop: Float,
    reducedMotion: Boolean,
) {
    // Use 30x30 virtual space with 2-unit padding during calls so aura rings & sound arcs never clip.
    val pad = if (voiceState.inCall) 2f else 0f
    val grid = if (voiceState.inCall) 30f else 26f
    val unit = size.minDimension / grid
    scale(unit, unit, pivot = Offset.Zero) {
        translate(left = pad, top = pad) {
            val lift = motion.float * 1.6f + hop * (if (voiceState.inCall) 3.4f else 4f)
            val level = when (voiceState) {
                is PetVoiceState.Listening -> voiceState.level.coerceIn(0f, 1f)
                is PetVoiceState.Speaking -> voiceState.level.coerceIn(0f, 1f)
                else -> 0f
            }

            // 1) Breathing voice aura behind the ghost during active call states
            if (voiceState is PetVoiceState.Listening || voiceState is PetVoiceState.Speaking || voiceState is PetVoiceState.Connecting) {
                val auraColor = when (voiceState) {
                    is PetVoiceState.Speaking -> VoicePeach
                    is PetVoiceState.Listening -> VoiceGreen
                    else -> VoiceConnectingAura
                }
                val cy = 12.6f - lift * 0.6f
                val baseR = 9.2f + level * 2.4f
                val auraR = baseR + 2.6f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(auraColor.copy(alpha = 0.22f + level * 0.14f), Color.Transparent),
                        center = Offset(12f, cy),
                        radius = auraR,
                    ),
                    radius = auraR,
                    center = Offset(12f, cy),
                )
                if (!reducedMotion) {
                    val p = motion.pulsePhase
                    val ringR = 8.4f + p * (4.4f + level * 1.8f)
                    val ringAlpha = (1f - p) * (if (voiceState is PetVoiceState.Connecting) 0.5f else 0.45f)
                    drawCircle(
                        color = auraColor.copy(alpha = ringAlpha.coerceIn(0f, 1f)),
                        radius = ringR,
                        center = Offset(12f, cy),
                        style = Stroke(width = 0.85f),
                    )
                }
            }

            // 2) Ground shadow
            val shadow = (1f - lift / 9f).coerceAtLeast(0.2f)
            drawOval(Color.Black.copy(alpha = 0.24f * shadow), Offset(12f - 5f * shadow, 23.6f), Size(10f * shadow, 1.6f))

            // 3) Ghost transform (lift + press + sway)
            translate(top = 0.4f - lift) {
                scale(press, press, pivot = Offset(12f, 14f)) {
                    rotate(motion.sway, pivot = Offset(12f, 21f)) {
                        drawGhost(
                            wrench = wrench,
                            hem = motion.hem,
                            eyes = motion.eyes,
                            wave = motion.wave,
                            voiceState = voiceState,
                            ringPhase = motion.ringPhase,
                            soundPhase = motion.soundPhase,
                            reducedMotion = reducedMotion,
                        )
                    }
                }
            }

            // 4) Amber muted badge pinned cleanly at bottom-right
            if (voiceState is PetVoiceState.Muted) {
                val bx = 21.5f
                val by = 20.5f
                drawCircle(VoiceWarn, radius = 3.6f, center = Offset(bx, by))
                drawCircle(VoiceMutedBadgeBorder, radius = 3.6f, center = Offset(bx, by), style = Stroke(width = 1.1f))
                drawLine(VoiceMutedBadgeSlash, Offset(bx, by - 1.5f), Offset(bx, by + 0.5f), strokeWidth = 1.05f, cap = StrokeCap.Round)
                drawLine(VoiceMutedBadgeSlash, Offset(bx - 1.5f, by - 1.5f), Offset(bx + 1.5f, by + 1.5f), strokeWidth = 1.05f, cap = StrokeCap.Round)
            }
        }
    }
}

private val GhostBody = Color(0xFFF8F7FF)
private val GhostInk = Color(0xFF3B3550)
private val GhostBlush = Color(0xFFFF9EB5)
private val WrenchMetal = Color(0xFFD9DEE8)
private val KeyboardBody = Color(0xFFEDEBF5)

internal val VoiceGreen = Color(0xFF7FD19B)
internal val VoiceGreenInk = Color(0xFF162E1F)
internal val VoicePeach = Color(0xFFEFA783)
internal val VoiceWarn = Color(0xFFF2C46D)
internal val VoiceDanger = Color(0xFFFF7A70)
internal val VoiceDangerText = Color(0xFFFF8F86)
private val VoiceMutedPhone = Color(0xFF9A9890)
private val VoiceConnectingAura = Color(0xFFC7C4BD)
private val VoiceMutedBadgeBorder = Color(0xFF252728)
private val VoiceMutedBadgeSlash = Color(0xFF2A1D03)

/** The ghost's idle blink and wrench wave, shared by the toolbox pet and the animated logo. */
internal val GhostBlink = infiniteRepeatable<Float>(keyframes {
    durationMillis = 3600
    1f at 3250
    0.1f at 3330
    1f at 3420
})
internal val GhostWave = infiniteRepeatable<Float>(keyframes {
    durationMillis = 5200
    0f at 3000
    -30f at 3180
    14f at 3380
    -30f at 3580
    14f at 3780
    0f at 3980
})

internal fun DrawScope.drawGhost(
    wrench: Color,
    hem: Float,
    eyes: Float,
    wave: Float,
    voiceState: PetVoiceState = PetVoiceState.Idle,
    ringPhase: Float = 0f,
    soundPhase: Float = 0f,
    reducedMotion: Boolean = false,
) {
    val line = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val grip = Offset(19.6f, 14.2f)

    // Wrench behind the right paw, swinging calmly during voice calls.
    rotate(wave, pivot = grip) {
        drawLine(GhostInk, grip, Offset(22.4f, 7.6f), strokeWidth = 2.8f, cap = StrokeCap.Round)
        drawLine(wrench, grip, Offset(22.4f, 7.6f), strokeWidth = 1.4f, cap = StrokeCap.Round)
        // Open-ended head, its jaw facing away from the grip.
        val head = Rect(Offset(23.2f, 5.4f), 2.1f)
        drawArc(GhostInk, -22f, 270f, false, head.topLeft, head.size, style = Stroke(3f, cap = StrokeCap.Round))
        drawArc(WrenchMetal, -22f, 270f, false, head.topLeft, head.size, style = Stroke(1.5f, cap = StrokeCap.Round))
    }

    // Dome head, straight sides and a hem that ripples as it floats.
    val body = Path().apply {
        moveTo(4f, 20.4f)
        lineTo(4f, 11f)
        cubicTo(4f, 6.2f, 7.6f, 3f, 12f, 3f)
        cubicTo(16.4f, 3f, 20f, 6.2f, 20f, 11f)
        lineTo(20f, 20.4f)
        val steps = 24
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            lineTo(20f - 16f * t, 20.4f + 1.3f * sin(t * 3f * 2f * PI.toFloat() + hem))
        }
        close()
    }
    drawPath(body, GhostBody)
    drawPath(body, GhostInk, style = line)

    // Eyes, blushes, and expressive mouth.
    listOf(9.2f, 14.8f).forEach { x ->
        scale(1f, eyes, pivot = Offset(x, 11.2f)) {
            drawOval(GhostInk, Offset(x - 1.1f, 9.6f), Size(2.2f, 3.2f))
            drawCircle(Color.White, 0.45f, Offset(x + 0.35f, 10.4f))
        }
    }
    drawOval(GhostBlush.copy(alpha = 0.6f), Offset(6.4f, 13f), Size(2.4f, 1.3f))
    drawOval(GhostBlush.copy(alpha = 0.6f), Offset(15.2f, 13f), Size(2.4f, 1.3f))

    when (voiceState) {
        is PetVoiceState.Speaking -> {
            val level = voiceState.level.coerceIn(0f, 1f)
            val mw = 0.85f + level * 0.3f
            val mh = 0.85f + level * 1.15f
            drawOval(GhostInk, Offset(12f - mw, 14.6f - mh), Size(mw * 2f, mh * 2f))
        }
        PetVoiceState.Muted -> {
            drawLine(GhostInk, Offset(11.1f, 14.6f), Offset(12.9f, 14.6f), strokeWidth = 1.05f, cap = StrokeCap.Round)
        }
        else -> {
            drawOval(GhostInk, Offset(11.2f, 13.6f), Size(1.6f, 1.8f))
        }
    }

    if (!voiceState.inCall) {
        // IDLE: Left paw holds a mini keyboard in front of the body, tipping slightly with the wrench wave.
        val paw = Offset(3.9f, 14.6f - wave / 40f)
        rotate(-10f + wave / 8f, pivot = paw) {
            val board = Rect(Offset(paw.x - 2.2f, paw.y + 0.2f), Size(7.2f, 4.4f))
            drawRoundRect(KeyboardBody, board.topLeft, board.size, CornerRadius(0.9f))
            drawRoundRect(GhostInk, board.topLeft, board.size, CornerRadius(0.9f), style = Stroke(1.1f))
            for (row in 0..1) for (col in 0..3) {
                drawRoundRect(wrench, Offset(board.left + 0.8f + col * 1.45f, board.top + 0.75f + row * 1.25f), Size(0.95f, 0.8f), CornerRadius(0.25f))
            }
            drawRoundRect(wrench, Offset(board.left + 1.9f, board.top + 3.25f), Size(3.2f, 0.6f), CornerRadius(0.3f))
        }
        drawCircle(GhostBody, 1.7f, paw)
        drawCircle(GhostInk, 1.7f, paw, style = line)
        drawCircle(GhostBody, 1.8f, grip)
        drawCircle(GhostInk, 1.8f, grip, style = line)
    } else {
        // CALL MODE: Left paw lifts a curved retro telephone handset + coiled cord to the ghost's ear!
        val phoneColor = when (voiceState) {
            is PetVoiceState.Speaking -> VoicePeach
            PetVoiceState.Muted -> VoiceMutedPhone
            else -> VoiceGreen
        }
        val ringJiggle = when {
            reducedMotion -> 0f
            voiceState is PetVoiceState.Connecting -> {
                val burst = if (sin(ringPhase) > 0f) 1f else 0.15f
                sin(ringPhase * 9f) * 7.5f * burst
            }
            voiceState is PetVoiceState.Speaking -> sin(soundPhase * 2f) * 3.2f
            else -> 0f
        }
        val paw = Offset(3.3f, 12.3f)
        rotate(ringJiggle, pivot = paw) {
            // Subtle coiled cord curling down from mouthpiece
            val cord = Path().apply {
                moveTo(5.6f, 16.0f)
                cubicTo(4.2f, 17.5f, 6.2f, 18.4f, 4.4f, 19.6f)
            }
            drawPath(cord, GhostInk, style = Stroke(width = 1.0f, cap = StrokeCap.Round))

            // Curved receiver spine from earpiece (top-left) around cheek to mouthpiece (bottom-right)
            val spine = Path().apply {
                moveTo(4.8f, 8.2f)
                quadraticTo(1.1f, 12.1f, 5.7f, 15.6f)
            }
            drawPath(spine, GhostInk, style = Stroke(width = 3.6f, cap = StrokeCap.Round))
            drawPath(spine, phoneColor, style = Stroke(width = 1.9f, cap = StrokeCap.Round))

            // Earpiece & mouthpiece caps
            drawHandsetCap(Offset(5.1f, 8.2f), -32f, phoneColor)
            drawHandsetCap(Offset(5.8f, 15.5f), 30f, phoneColor)

            // Sound-wave arcs radiating near the top-left earpiece when speaking or connecting
            if (voiceState is PetVoiceState.Speaking || voiceState is PetVoiceState.Connecting) {
                val waveCol = if (voiceState is PetVoiceState.Speaking) VoicePeach else VoiceGreen
                val alpha1 = if (reducedMotion) 0.85f else 0.35f + 0.65f * max(0f, sin(soundPhase * 3f))
                val alpha2 = if (reducedMotion) 0.55f else 0.25f + 0.75f * max(0f, sin(soundPhase * 3f - 1.0f))
                val arcCenter = Offset(3.5f, 7.4f)
                val r1 = Rect(arcCenter, 2.6f)
                drawArc(waveCol.copy(alpha = alpha1), 165f, 75f, false, r1.topLeft, r1.size, style = Stroke(1.05f, cap = StrokeCap.Round))
                val r2 = Rect(arcCenter, 4.3f)
                drawArc(waveCol.copy(alpha = alpha2), 168f, 70f, false, r2.topLeft, r2.size, style = Stroke(1.05f, cap = StrokeCap.Round))
            }

            // Left paw gripping the middle of the handset
            val hand = Offset(2.7f, 12.2f)
            drawCircle(GhostBody, 1.65f, hand)
            drawCircle(GhostInk, 1.65f, hand, style = Stroke(width = 1.25f))
        }

        // Right paw holding the wrench calmly
        drawCircle(GhostBody, 1.8f, grip)
        drawCircle(GhostInk, 1.8f, grip, style = line)
    }
}

private fun DrawScope.drawHandsetCap(center: Offset, angleDeg: Float, fillColor: Color) {
    translate(center.x, center.y) {
        rotate(angleDeg, pivot = Offset.Zero) {
            drawRoundRect(fillColor, Offset(-1.7f, -1.25f), Size(3.4f, 2.5f), CornerRadius(1.15f))
            drawRoundRect(GhostInk, Offset(-1.7f, -1.25f), Size(3.4f, 2.5f), CornerRadius(1.15f), style = Stroke(1.1f))
        }
    }
}
