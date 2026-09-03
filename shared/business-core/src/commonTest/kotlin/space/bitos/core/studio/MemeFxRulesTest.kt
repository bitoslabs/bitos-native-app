package space.bitos.core.studio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MST-044: fx transforms (web fx.ts math) + visibility windows. */
class MemeFxRulesTest {

    private fun overlay(fx: MemeOverlayFx?, startMs: Long? = null, endMs: Long? = null) =
        MemeOverlay(
            id = "o", kind = MemeOverlayKind.TEXT, text = "x", font = MemeFontSlot.IMPACT,
            size = 64, colorIndex = 0, outline = 0, shadow = false,
            x = 0.5f, y = 0.5f, scale = 1f, rotationDeg = 0f,
            caps = null, bar = null, startMs = startMs, endMs = endMs, fx = fx,
        )

    @Test
    fun identityCasesMatchTheWebEngine() {
        // no fx / static render (posters) / settled entrances → identity.
        assertEquals(MemeFxRules.IDENTITY, MemeFxRules.transformAt(overlay(null), 500))
        assertEquals(MemeFxRules.IDENTITY, MemeFxRules.transformAt(overlay(MemeOverlayFx.POP), null))
        assertEquals(MemeFxRules.IDENTITY, MemeFxRules.transformAt(overlay(MemeOverlayFx.POP), MemeFxRules.ENTRY_MS))
        assertEquals(MemeFxRules.IDENTITY, MemeFxRules.transformAt(overlay(MemeOverlayFx.FADE), MemeFxRules.ENTRY_MS + 1))
    }

    @Test
    fun entranceMathMatchesEaseOutBackAndLinearFade() {
        // pop at t=0 → easeOutBack(0) = 1 + c3·(−1)³ + c1·1 = 1 − 2.70158 + 1.70158 = 0.
        assertEquals(0f, MemeFxRules.transformAt(overlay(MemeOverlayFx.POP), 0).scale)
        // Mid-entry overshoots past 1 (easeOutBack signature).
        val mid = MemeFxRules.transformAt(overlay(MemeOverlayFx.POP), MemeFxRules.ENTRY_MS / 2).scale
        assertTrue(mid > 1f, "overshoot at half entry: $mid")
        // fade is a clean linear ramp from 0 → 1.
        assertEquals(0f, MemeFxRules.transformAt(overlay(MemeOverlayFx.FADE), 0).alpha)
        assertEquals(0.5f, MemeFxRules.transformAt(overlay(MemeOverlayFx.FADE), MemeFxRules.ENTRY_MS / 2).alpha, 1e-6f)
        // Entry time is relative to the overlay's own start window.
        assertEquals(0f, MemeFxRules.transformAt(overlay(MemeOverlayFx.FADE, startMs = 1_000), 1_000).alpha)
    }

    @Test
    fun loopMathIsPeriodicAndBounded() {
        val spin = overlay(MemeOverlayFx.SPIN)
        // Phase 0 → rotation 0; a full loop later → 2π; half loop → π.
        assertEquals(0f, MemeFxRules.transformAt(spin, 0).rotateRad, 1e-6f)
        val tau = (2 * kotlin.math.PI).toFloat()
        // A full loop wraps to 0 (≡ 2π modulo — web `(t % LOOP)/LOOP` math).
        assertEquals(0f, MemeFxRules.transformAt(spin, MemeFxRules.LOOP_MS).rotateRad, 1e-6f)
        assertEquals(tau / 2, MemeFxRules.transformAt(spin, MemeFxRules.LOOP_MS / 2).rotateRad, 1e-6f)
        // Shake: dx bounded by 0.008, dy ≤ 0 (upward-only, web formula).
        repeat(45) { step ->
            val t = step * 20L
            val tr = MemeFxRules.transformAt(overlay(MemeOverlayFx.SHAKE), t)
            assertTrue(abs(tr.dx) <= 0.0080001f)
            assertTrue(tr.dy <= 0.000001f)
        }
    }

    @Test
    fun windowsAreHalfOpenAndNonsenseEditsDegrade() {
        val windowed = overlay(MemeOverlayFx.POP, startMs = 1_000, endMs = 2_000)
        assertTrue(!MemeFxRules.visibleAt(windowed, 999))
        assertTrue(MemeFxRules.visibleAt(windowed, 1_000))
        assertTrue(MemeFxRules.visibleAt(windowed, 1_999))
        assertTrue(!MemeFxRules.visibleAt(windowed, 2_000))
        // Commands: fx + windows ride the stack; end ≤ start degrades to open.
        val base = MemeProject(mode = MemeMode.VIDEO, overlays = listOf(overlay(null)))
        val popped = MemeRules.apply(base, MemeCommand.UpdateOverlay(id = "o", fx = MemeOverlayFx.POP, startMs = 100, endMs = 200))
        assertEquals(MemeOverlayFx.POP, popped.overlays[0].fx)
        assertEquals(100, popped.overlays[0].startMs)
        val cleared = MemeRules.apply(popped, MemeCommand.UpdateOverlay(id = "o", clearFx = true))
        assertEquals(null, cleared.overlays[0].fx)
        val nonsense = MemeRules.apply(cleared, MemeCommand.UpdateOverlay(id = "o", startMs = 500, endMs = 400))
        assertEquals(null, nonsense.overlays[0].endMs)
        // Codec round-trips the new fields.
        val encoded = MemeCommandCodec.encode(MemeCommand.UpdateOverlay(id = "o", fx = MemeOverlayFx.SHAKE, startMs = 10, endMs = 900))
        val decoded = MemeCommandCodec.decode(encoded) as MemeCommand.UpdateOverlay
        assertEquals(MemeOverlayFx.SHAKE, decoded.fx)
        assertEquals(10, decoded.startMs)
        assertEquals(900, decoded.endMs)
    }
}
