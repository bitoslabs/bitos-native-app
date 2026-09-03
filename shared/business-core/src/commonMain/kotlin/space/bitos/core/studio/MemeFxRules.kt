package space.bitos.core.studio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos

/**
 * Overlay FX engine (plan MST-044; web `meme/fx.ts` port): pure math over
 * (overlay, media time) — entrance effects (pop/fade) resolve over
 * [ENTRY_MS] from the overlay's window start; loop effects (shake/spin)
 * repeat every [LOOP_MS]. Visibility windows are half-open `[start, end)`
 * in media ms. The identity return keeps the no-fx path exactly as fast
 * as before; posters (null time) render untransformed.
 */
object MemeFxRules {

    const val ENTRY_MS = 380L
    const val LOOP_MS = 900L

    data class FxTransform(
        val scale: Float = 1f,
        val rotateRad: Float = 0f,
        /** x-offset as a fraction of canvas width. */
        val dx: Float = 0f,
        /** y-offset as a fraction of canvas height. */
        val dy: Float = 0f,
        val alpha: Float = 1f,
    )

    val IDENTITY = FxTransform()

    fun transformAt(overlay: MemeOverlay, atMs: Long?): FxTransform {
        val fx = overlay.fx ?: return IDENTITY
        if (atMs == null) return IDENTITY
        val t = (atMs - (overlay.startMs ?: 0)).coerceAtLeast(0)
        return when (fx) {
            MemeOverlayFx.POP -> {
                if (t >= ENTRY_MS) return IDENTITY
                FxTransform(scale = easeOutBack(t.toFloat() / ENTRY_MS))
            }

            MemeOverlayFx.FADE -> {
                if (t >= ENTRY_MS) return IDENTITY
                FxTransform(alpha = (t.toFloat() / ENTRY_MS).coerceIn(0f, 1f))
            }

            MemeOverlayFx.SHAKE -> {
                val phase = (t % LOOP_MS).toFloat() / LOOP_MS
                val wave = sin(phase * PI.toFloat() * 2) * cos(phase * PI.toFloat() * 4)
                FxTransform(dx = wave * 0.008f, dy = -abs(wave) * 0.004f)
            }

            MemeOverlayFx.SPIN -> {
                val phase = (t % LOOP_MS).toFloat() / LOOP_MS
                FxTransform(rotateRad = phase * PI.toFloat() * 2)
            }
        }
    }

    /** Half-open visibility window `[start, end)` in media ms. */
    fun visibleAt(overlay: MemeOverlay, atMs: Long): Boolean {
        if (overlay.startMs != null && atMs < overlay.startMs) return false
        if (overlay.endMs != null && atMs >= overlay.endMs) return false
        return true
    }

    /** Overshooting spring settle (standard easeOutBack constants). */
    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val u = t - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }
}
