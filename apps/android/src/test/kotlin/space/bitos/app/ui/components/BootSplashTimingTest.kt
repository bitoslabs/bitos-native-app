package space.bitos.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Boot splash timing contract (legacy Flutter `BootSplashScreen` / web
 * `pow-boot-seg` parity): staggered sweep delay i*0.12s, lit for 45% of
 * the 2s loop, breathing ±2%.
 */
class BootSplashTimingTest {

    @Test
    fun segmentsSweepWithStaggeredDelay() {
        // Segment 0 lights immediately at t=0 (no delay, on-fraction 0.45).
        assertTrue(BootSplashTiming.powSegmentOn(0f, 0))
        // Segment 1 (delay 0.12) is still off just before its delay elapses…
        assertFalse(BootSplashTiming.powSegmentOn(0.11f, 1))
        // …and lights right after.
        assertTrue(BootSplashTiming.powSegmentOn(0.13f, 1))
        // Segment 0 turns off after 45% of the loop.
        assertTrue(BootSplashTiming.powSegmentOn(0.44f, 0))
        assertFalse(BootSplashTiming.powSegmentOn(0.46f, 0))
    }

    @Test
    fun sweepWrapsAroundTheLoop() {
        // Near the loop end, later segments light first (wrap-around):
        // t=0.99 → segment 8 (delay 0.96) has local 0.03 (on),
        // segment 0 has local 0.99 (off).
        val t = 0.99f
        assertTrue(BootSplashTiming.powSegmentOn(t, 8))
        assertFalse(BootSplashTiming.powSegmentOn(t, 0))
    }

    @Test
    fun segmentCountAndConstantsMatchWeb() {
        assertEquals(9, BootSplashTiming.SEGMENT_COUNT)
        assertEquals(0.12, BootSplashTiming.SEGMENT_SWEEP_DELAY)
        assertEquals(0.45, BootSplashTiming.SEGMENT_ON_FRACTION)
        assertEquals(2_000, BootSplashTiming.LOOP_MS)
        assertEquals(900, BootSplashTiming.MIN_DISPLAY_MS)
        assertEquals(300, BootSplashTiming.FADE_OUT_MS)
    }

    @Test
    fun breathingOscillatesWithinTwoPercent() {
        assertEquals(1f, BootSplashTiming.breathe(0f))
        assertEquals(1.02f, BootSplashTiming.breathe(0.25f), 1e-4f) // sin(π/2)
        assertEquals(0.98f, BootSplashTiming.breathe(0.75f), 1e-4f) // sin(3π/2)
    }

    // ── Animated gradient border (no spin) ──

    @Test
    fun borderWavePingPongsSmoothly() {
        // Cosine wave: 0 → ½ (quarter) → 1 (half) → ½ (¾) → seamless wrap.
        assertEquals(0f, BootSplashTiming.borderWave(0f), 1e-4f)
        assertEquals(0.5f, BootSplashTiming.borderWave(0.25f), 1e-4f)
        assertEquals(1f, BootSplashTiming.borderWave(0.5f), 1e-4f)
        assertEquals(0.5f, BootSplashTiming.borderWave(0.75f), 1e-4f)
        // Continuous across the loop wrap — no rotation-style hard cut.
        assertEquals(
            BootSplashTiming.borderWave(0.999f),
            BootSplashTiming.borderWave(0.001f),
            0.02f,
        )
    }

    @Test
    fun borderStopsRipplePhaseShiftedNotRotated() {
        // Stop 0 peaks (full yellow) at t=0.5; neighbours peak a third of a
        // loop apart — stops stay anchored, only colors evolve (no spin).
        assertTrue(BootSplashTiming.borderStopWave(0.5f, 0) > 0.99f)
        assertTrue(BootSplashTiming.borderStopWave(0.5f, 1) < 0.26f)
        assertTrue(BootSplashTiming.borderStopWave(0.5f, 2) < 0.26f)
        // The wrap stop (k=3) repeats stop 0's color → seamless gradient join.
        assertEquals(
            BootSplashTiming.borderStopWave(0.5f, 3),
            BootSplashTiming.borderStopWave(0.5f, 0),
            1e-6f,
        )
        // Waves never leave the 0…1 lerp domain at any loop time.
        for (i in 0..19) {
            val t = i / 19f
            for (k in 0 until 3) {
                val w = BootSplashTiming.borderStopWave(t, k)
                assertTrue(w in 0f..1f)
            }
        }
        assertEquals(3, BootSplashTiming.BORDER_STOP_COUNT)
    }

    @Test
    fun borderColorRipplesBetweenBrandColors() {
        // t=0 → stop color is pure Bitcoin orange; half a loop later → yellow
        // (component-wise, tolerant of float lerp rounding).
        val c0 = BootSplashTiming.borderColor(0f, 0)
        assertEquals(BootSplashTiming.ORANGE.red, c0.red, 1e-3f)
        assertEquals(BootSplashTiming.ORANGE.green, c0.green, 1e-3f)
        assertEquals(BootSplashTiming.ORANGE.blue, c0.blue, 1e-3f)
        val c1 = BootSplashTiming.borderColor(0.5f, 0)
        assertEquals(BootSplashTiming.YELLOW.red, c1.red, 1e-3f)
        assertEquals(BootSplashTiming.YELLOW.green, c1.green, 1e-3f)
        assertEquals(BootSplashTiming.YELLOW.blue, c1.blue, 1e-3f)
        // Quarter loop = even mix (continuous flow, not a jump).
        val mid = BootSplashTiming.borderColor(0.25f, 0)
        assertTrue(mid.red > BootSplashTiming.ORANGE.red)
        assertTrue(mid.blue < BootSplashTiming.YELLOW.blue)
    }
}
