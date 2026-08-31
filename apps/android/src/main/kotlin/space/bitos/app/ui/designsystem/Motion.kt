package space.bitos.app.ui.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import space.bitos.core.design.DesignTokens

/**
 * Motion tokens (DESIGN_SYSTEM §2.4) as Compose animation specs, so the
 * duration/curve ladder lives in one place. Components use these instead
 * of hand-picked numbers.
 */
object BitOSMotion {
    val instant = DesignTokens.Duration.INSTANT
    val fast = DesignTokens.Duration.FAST
    val normal = DesignTokens.Duration.NORMAL
    val slow = DesignTokens.Duration.SLOW
    val emphasis = DesignTokens.Duration.EMPHASIS

    /** easeInOutCubic — default for most transitions. */
    val standardEasing: Easing = CubicBezierEasing(0.65f, 0.35f, 0.35f, 0.65f)

    /** easeOutCubic — elements entering. */
    val enterEasing: Easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

    /** easeInCubic — elements exiting. */
    val exitEasing: Easing = CubicBezierEasing(0.68f, 0f, 0.66f, 0.17f)

    fun <T> standard(duration: Int = normal): TweenSpec<T> =
        TweenSpec(durationMillis = duration, easing = standardEasing)

    fun <T> enter(duration: Int = normal): TweenSpec<T> =
        TweenSpec(durationMillis = duration, easing = enterEasing)

    fun <T> exit(duration: Int = normal): TweenSpec<T> =
        TweenSpec(durationMillis = duration, easing = exitEasing)

    /** elasticOut — the like scale-bounce (§2.4). */
    fun <T> bounce(duration: Int = normal): SpringSpec<T> =
        SpringSpec(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 350f)

    /** Like scale keyframes: 1 → [DesignTokens.LIKE_SCALE_PEAK] → 1. */
    const val likeScalePeak = DesignTokens.LIKE_SCALE_PEAK.toFloat()
}

/**
 * Shimmer driver shared by every skeleton: a 0→1 phase looping with the
 * contract duration. Returns the animated fraction for brush math.
 */
@Composable
fun rememberShimmerPhase(durationMillis: Int = 1400): Float {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = TweenSpec(durationMillis = durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    return phase
}
