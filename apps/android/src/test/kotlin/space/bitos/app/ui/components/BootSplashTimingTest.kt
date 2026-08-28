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
}
