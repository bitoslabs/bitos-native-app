package space.bitos.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import space.bitos.app.ui.theme.BitOSColors

/**
 * Branded account-switch overlay (legacy `AccountSwitcherOverlay` parity):
 * opaque scrim + breathing hex mark with a rotating amber orbit ring; the
 * FROM identity holds ~450 ms then morphs into the target (fade + scale +
 * slight rotation, 480 ms easeOutBack); staged captions with a determinate
 * progress fill; green check-badge completion pop; a readable done beat and
 * a 1250 ms brand floor before the 240 ms fade-out reveals the rebound app.
 */
@Composable
fun AccountSwitchOverlay(
    fromPubkey: String?,
    toPubkey: String,
    toName: String?,
    hapticsEnabled: () -> Boolean = { false },
    switchAction: suspend () -> Unit,
    onFinished: () -> Unit,
) {
    var phase by remember { mutableStateOf(Phase.RUNNING) }
    var shownPubkey by remember { mutableStateOf(fromPubkey ?: toPubkey) }
    var handoffDone by remember { mutableStateOf(fromPubkey == null) }
    var progress by remember { mutableStateOf(0.1f) }
    var stage by remember { mutableStateOf(0) }
    var fade by remember { mutableStateOf(1f) }
    val start = remember { System.currentTimeMillis() }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        // Staged captions (legacy: keys → network → profile → shell) — the
        // local switch is fast, so stages run as a readable cadence.
        listOf("Reading the sealed key", "Connecting relays", "Loading profile", "Rebuilding your feed")
            .forEachIndexed { index, _ ->
                stage = index
                progress = (index + 1) / 4f * 0.75f
                delay(180)
            }
        // Handoff beat: the old identity holds ~450 ms, then morphs.
        delay(200)
        handoffDone = true
        shownPubkey = toPubkey
        switchAction()
        progress = 1f
        phase = Phase.DONE
        // Completion tick (gated by the persisted haptics preference).
        if (hapticsEnabled()) {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        }
        delay(700) // completion beat — "Now using …" must be readable
        val elapsed = System.currentTimeMillis() - start
        if (elapsed < 1250) delay(1250 - elapsed) // brand moment floor
        fade = 0f
        delay(240)
        onFinished()
    }

    val fadeAlpha by animateFloatAsState(fade, animationSpec = tween(240), label = "switch-fade")
    val breathe = rememberInfiniteTransition(label = "switch-breathe")
    val breatheScale by breathe.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val orbit = rememberInfiniteTransition(label = "switch-orbit")
    val orbitAngle by orbit.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "orbit",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F).copy(alpha = fadeAlpha)),
    ) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(128.dp)
                    .scale(breatheScale),
                contentAlignment = Alignment.Center,
            ) {
                OrbitHexRing(angle = orbitAngle, failed = phase == Phase.FAILED)
                // Identity handoff: fade + scale 0.55→1 + slight rotation.
                AnimatedContent(
                    targetState = shownPubkey,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(300)) +
                            scaleIn(animationSpec = spring(dampingRatio = 0.55f), initialScale = 0.55f))
                            .togetherWith(fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.8f))
                    },
                    label = "handoff",
                ) { pubkey ->
                    Box(
                        Modifier
                            .size(92.dp)
                            .rotate(if (handoffDone) 0f else -3.4f),
                    ) {
                        if (pubkey != null) {
                            space.bitos.app.ui.components.PubkeyAvatar(pubkey = pubkey, size = 92)
                        }
                    }
                }
                // Completion badge pop.
                androidx.compose.animation.AnimatedVisibility(
                    visible = phase == Phase.DONE || phase == Phase.FAILED,
                    enter = scaleIn(animationSpec = spring(dampingRatio = 0.5f)) + fadeIn(tween(200)),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (phase == Phase.FAILED) BitOSColors.error else BitOSColors.success),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                when (phase) {
                    Phase.DONE -> "Now using ${toName ?: "your account"}"
                    else -> "Switching accounts"
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.W800,
                color = BitOSColors.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            val stages = listOf("Reading the sealed key", "Connecting relays", "Loading profile", "Rebuilding your feed")
            Text(
                if (phase == Phase.DONE) "Done" else stages[stage],
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = BitOSColors.textTertiary,
            )
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { progress },
                color = if (phase == Phase.FAILED) BitOSColors.error else BitOSColors.primary,
                trackColor = BitOSColors.surfaceOverlay,
                modifier = Modifier.width(180.dp).height(3.dp),
            )
        }
    }
}

private enum class Phase { RUNNING, DONE, FAILED }

/**
 * Rotating amber orbit along the hex ring (legacy `_OrbitHexPainter`
 * parity): the hex OUTLINE stays static; the bright arc orbits inside it.
 * The sweep-gradient stops are rebuilt each frame shifted by the animation
 * angle, so the arc is always traveling — nothing can interrupt the spin.
 */
@Composable
private fun OrbitHexRing(angle: Float, failed: Boolean) {
    // Palette reads stay outside the draw lambda (DrawScope is not a
    // composable context) so the ring follows the active theme.
    val amberBase = if (failed) BitOSColors.error else BitOSColors.primary
    Canvas(Modifier.size(128.dp)) {
        val stroke = 1.8.dp.toPx()
        val hex = hexPath(size.width - stroke, size.height - stroke, stroke / 2)
        val amber = amberBase
        val bright = if (failed) Color(0xFFFF6B61) else Color(0xFFFFD83D)
        // Static faint outline (the shape never rotates).
        drawPath(path = hex, color = amber.copy(alpha = 0.12f), style = Stroke(stroke))
        // Orbiting bright arc: sweep stops shifted by the animated angle.
        val t = ((angle % 360f) + 360f) % 360f / 360f
        val arc = 0.16f
        val dim = amber.copy(alpha = 0.12f)
        val stops = ArrayList<Pair<Float, Color>>(6)
        stops.add(0f to dim)
        val start = t
        val peak = t + arc / 2
        val end = t + arc
        if (end <= 1f) {
            if (start > 0f) stops.add(start to dim)
            stops.add(peak to bright.copy(alpha = 0.95f))
            stops.add(end to dim)
        } else {
            // Wrap: tail to 1.0, then the remainder from 0.0.
            val overflow = end - 1f
            stops.add((t + (1f - t) / 2).coerceAtMost(1f) to bright.copy(alpha = 0.95f))
            stops.add(1f to dim)
        }
        stops.add(1f to dim)
        drawPath(
            path = hex,
            brush = Brush.sweepGradient(colorStops = stops.toTypedArray(), center = center),
            style = Stroke(stroke * 1.4f),
        )
    }
}

/** Flat-top regular hexagon path (HexShape geometry). */
private fun hexPath(w: Float, h: Float, inset: Float): Path = Path().apply {
    val cx = w / 2 + inset
    val cy = h / 2 + inset
    val r = minOf(w, h) / 2
    for (i in 0 until 6) {
        val a = Math.PI / 6.0 + i * Math.PI / 3.0
        val x = cx + (r * kotlin.math.cos(a)).toFloat()
        val y = cy + (r * kotlin.math.sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
