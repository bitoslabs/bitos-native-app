package space.bitos.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import space.bitos.app.R

/**
 * Branded boot/splash screen — port of the legacy Flutter
 * `BootSplashScreen` (web BootSplash parity): the official lightning-bolt
 * mark inside the shared flat-top hex avatar, breathing inside an
 * **animated gradient border** while core services hydrate. The border's
 * gradient stays anchored to the hexagon and its stop colors ripple
 * Bitcoin-orange ↔ brand yellow in phase-shifted waves — deliberately NOT
 * the legacy rotating `SweepGradient` orbit, which read as a spinner. PoW
 * segments sweep (web `pow-boot-seg`), official wordmark, status pill.
 * Holds [BootSplashTiming.MIN_DISPLAY] so the brand moment always lands,
 * then fades [BootSplashTiming.FADE_OUT] before the app shell mounts. The
 * native system splash (windowSplashScreen* / launch_background) hands off
 * into this screen — same colors, same mark — so launch reads as one
 * continuous brand moment.
 *
 * NOTE: disabled at app entry (fast-access decision 2026-08-28) — the
 * native system splash hands off straight to the shell. Component is
 * retained in the library (APP-022) for future branded-loading moments.
 */

/** Pure timing/color contract (JVM-testable, mirrors the Swift/Flutter/web constants). */
object BootSplashTiming {
    const val LOOP_MS = 2_000          // master animation loop
    const val ENTER_MS = 450           // entrance fade+scale (easeOutCubic)
    const val MIN_DISPLAY_MS = 900     // brand hold before fade-out
    const val FADE_OUT_MS = 300        // bs-out parity
    const val SEGMENT_COUNT = 9
    const val SEGMENT_SWEEP_DELAY = 0.12  // web pow-boot-seg: animation-delay i*0.12s
    const val SEGMENT_ON_FRACTION = 0.45  // lit for 45% of the loop
    const val SEGMENT_COLOR_CROSS_MS = 240

    /** Web `pow-boot-seg`: segment [i] is lit when its staggered local time
     * (loop-normalized [t] − i*0.12, wrapped) is within the on-fraction. */
    fun powSegmentOn(t: Float, i: Int): Boolean {
        val delay = (i * SEGMENT_SWEEP_DELAY) % 1.0
        val local = (((t - delay) % 1.0f) + 1.0f) % 1.0f
        return local < SEGMENT_ON_FRACTION
    }

    /** Breathing scale: 1 ± 0.02 over the 2s loop. */
    fun breathe(t: Float): Float = 1f + 0.02f * sin(t * 2f * PI.toFloat()).toFloat()

    // ── Animated gradient border (no spin) ──

    /** Number of color stops anchored around the border gradient. */
    const val BORDER_STOP_COUNT = 3

    /** Smooth 0→1→0 cosine wave over the loop-normalized [t] (continuous at
     *  the wrap — no hard cut, unlike a rotation). */
    fun borderWave(t: Float): Float = 0.5f - 0.5f * cos(t * 2f * PI.toFloat()).toFloat()

    /** Wave for border stop [k], phase-shifted by ⅓ loop per stop so hues
     *  ripple around the *anchored* gradient — stops never move, nothing
     *  spins; only the colors evolve. */
    fun borderStopWave(t: Float, k: Int): Float {
        val phase = t + k / BORDER_STOP_COUNT.toFloat()
        return borderWave(((phase % 1f) + 1f) % 1f)
    }

    /** Border stop color at loop time [t]: Bitcoin-orange ↔ brand-yellow
     *  ripple. [k] wraps modulo [BORDER_STOP_COUNT] so the closing stop
     *  repeats the opening one (seamless angular join). */
    fun borderColor(t: Float, k: Int): Color =
        lerp(ORANGE, YELLOW, borderStopWave(t, k % BORDER_STOP_COUNT))

    // Brand palette (web/Flutter parity)
    val ORANGE = Color(0xFFF7931A)
    val YELLOW = Color(0xFFFFD83D)
    val BG_LIGHT = Color(0xFFF4F7FB)
    val BG_DARK = Color(0xFF0A0A0F)
    val SURFACE_LIGHT = Color(0xFFFFFFFF)
    val SURFACE_DARK = Color(0xFF171720)
}

/** Entrance easing — Flutter `Curves.easeOutCubic` (cubic-bezier .33,1,.68,1). */
private val EaseOutCubic = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

@Composable
fun BootSplashScreen(
    status: String = "Booting BitOS…",
    onDismissed: () -> Unit,
) {
    val isDark = true
    // APP-023: the product shell is dark-only (Theme.kt `darkColorScheme`)
    // until light tokens ship, so the splash must be dark in BOTH system
    // modes — a system-light splash handing off into the near-black shell
    // reads as a jarring "black screen". Drive from the theme setting when
    // APP-023 lands.
    val bg = if (isDark) BootSplashTiming.BG_DARK else BootSplashTiming.BG_LIGHT
    val dim = if (isDark) Color.White.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.45f)

    // Master loop drives breathing + orbit + PoW sweep (2s, linear).
    val master = rememberInfiniteTransition(label = "bootMaster")
    val progress by master.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BootSplashTiming.LOOP_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "bootProgress",
    )

    // Gentle entrance: fade + scale 0.94 → 1.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(BootSplashTiming.ENTER_MS, easing = EaseOutCubic)) }

    // Hold, fade out, release the app shell.
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        delay(BootSplashTiming.MIN_DISPLAY_MS.toLong())
        fade.animateTo(0f, tween(BootSplashTiming.FADE_OUT_MS))
        onDismissed()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .graphicsLayer { alpha = fade.value }
            .semantics { contentDescription = "Loading BitOS" },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = enter.value
                scaleX = 0.94f + 0.06f * enter.value
                scaleY = 0.94f + 0.06f * enter.value
            },
        ) {
            SplashHexAvatar(progress = progress, isDark = isDark)
            Spacer(Modifier.height(20.dp))
            // Official wordmark (light/dark assets, ~26dp like the web/Flutter splash)
            Image(
                painter = painterResource(R.drawable.bitos_branding),
                contentDescription = null,
                modifier = Modifier.height(26.dp),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.height(18.dp))
            PowBootSegments(progress = progress, isDark = isDark)
            Spacer(Modifier.height(12.dp))
            StatusPill(status = status, isDark = isDark, dim = dim)
        }
    }
}

/** The bolt mark in the shared hex avatar: surface + orange glow + animated
 *  gradient border (anchored hue ripple, no rotation) + breathing scale
 *  (legacy `_SplashHexAvatar`, reworked off the rotating orbit). */
@Composable
private fun SplashHexAvatar(progress: Float, isDark: Boolean, size: Dp = 92.dp) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { scaleX = BootSplashTiming.breathe(progress); scaleY = BootSplashTiming.breathe(progress) }
            .drawBehind {
                // Glow (BoxShadow 0x33F7931A blur 22 spread 1 parity)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(BootSplashTiming.ORANGE.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(size.toPx() / 2f, size.toPx() / 2f),
                        radius = size.toPx() * 0.72f,
                    ),
                    radius = size.toPx() * 0.72f,
                )
                // Border geometry (legacy hexPath(size−4).shift(2,2) parity).
                val px = with(density) { 1.6.dp.toPx() }
                val hex = hexOutlinePx(Size(this.size.width - 2f * px, this.size.height - 2f * px))
                hex.translate(Offset(px, px))
                // Animated gradient border: sweep gradient **anchored** to the
                // hexagon (no rotate()) whose three stops crossfade orange↔yellow
                // in phase-shifted waves — the gradient flows along the outline
                // without ever spinning. Stop 3 repeats stop 0 for a seamless wrap.
                // (The legacy GradientRotation comet read as a spinner.)
                drawPath(
                    path = hex,
                    brush = Brush.sweepGradient(
                        colors = List(BootSplashTiming.BORDER_STOP_COUNT + 1) { k ->
                            BootSplashTiming.borderColor(progress, k % BootSplashTiming.BORDER_STOP_COUNT)
                        },
                        center = Offset(this.size.width / 2f, this.size.height / 2f),
                    ),
                    style = Stroke(width = px, cap = StrokeCap.Round),
                )
            },
    ) {
        // Hex surface with the bolt, padded like the legacy splash (13/92 h,
        // 20/92 v). The 5/92 inset is what exposes the animated border ring:
        // without it the full-size clip(HexShape()) surface covers the stroke.
        val markSize = size
        Box(
            modifier = Modifier
                .size(markSize)
                .padding(markSize * (5f / 92f))
                .clip(HexShape())
                .background(if (isDark) BootSplashTiming.SURFACE_DARK else BootSplashTiming.SURFACE_LIGHT)
                .padding(
                    horizontal = markSize * (13f / 92f),
                    vertical = markSize * (20f / 92f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.bitos_bolt),
                contentDescription = "BitOS lightning bolt",
                // Fill the padded hex interior (56×42 at 92dp) — legacy
                // SvgPicture fills its ClipPath padding exactly the same way.
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** Hexagon path in raw px (web `.hex-clip` geometry, shared with [HexShape]). */
internal fun hexOutlinePx(size: Size): Path {
    val v = space.bitos.app.ui.components.HexIdentity.vertices(size.width, size.height)
    return Path().apply {
        moveTo(v[0].x, v[0].y)
        for (i in 1 until v.size) lineTo(v[i].x, v[i].y)
        close()
    }
}

/** 9 PoW segments in a staggered sweep (web `pow-boot-seg`). */
@Composable
private fun PowBootSegments(progress: Float, isDark: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(BootSplashTiming.SEGMENT_COUNT) { i ->
            val on = BootSplashTiming.powSegmentOn(progress, i)
            val color by animateColorAsState(
                targetValue = when {
                    on -> BootSplashTiming.ORANGE
                    isDark -> Color.White.copy(alpha = 0.10f)
                    else -> Color.Black.copy(alpha = 0.08f)
                },
                animationSpec = tween(BootSplashTiming.SEGMENT_COLOR_CROSS_MS),
                label = "seg$i",
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

/** Mono status pill ("Booting BitOS…"). */
@Composable
private fun StatusPill(status: String, isDark: Boolean, dim: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(if (isDark) Color.White.copy(alpha = 0.055f) else Color.Black.copy(alpha = 0.045f))
            .border(
                width = 1.dp,
                color = if (isDark) Color.White.copy(alpha = 0.09f) else Color.Black.copy(alpha = 0.07f),
                shape = RoundedCornerShape(99.dp),
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        androidx.compose.material3.Text(
            text = status,
            color = dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

