package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Animated GIF layer timing (web `gifLayerPainter` parity): looping frame
 * selection at media time — never clamps past the end (the frozen-layer
 * bug), never divides by zero, identical on both platforms.
 */
class GifLayerRulesTest {

    private val delays = listOf(100, 200, 300) // total 600 ms

    @Test
    fun selectsFramesWithinTheFirstPass() {
        assertEquals(0, GifLayerRules.frameIndexAt(delays, 0))
        assertEquals(0, GifLayerRules.frameIndexAt(delays, 99))
        assertEquals(1, GifLayerRules.frameIndexAt(delays, 100))
        assertEquals(1, GifLayerRules.frameIndexAt(delays, 299))
        assertEquals(2, GifLayerRules.frameIndexAt(delays, 300))
        assertEquals(2, GifLayerRules.frameIndexAt(delays, 599))
    }

    @Test
    fun loopsPastTheEndInsteadOfFreezing() {
        // Exactly one full pass → back to frame 0.
        assertEquals(0, GifLayerRules.frameIndexAt(delays, 600))
        // Deep into a long export: 5 s in = 8.33 passes → 5_000 % 600 = 200 → frame 1.
        assertEquals(1, GifLayerRules.frameIndexAt(delays, 5_000))
        // Negative times (scrub guards) wrap, never crash.
        assertEquals(2, GifLayerRules.frameIndexAt(delays, -100))
    }

    @Test
    fun degenerateTimingsNeverBreak() {
        assertEquals(600L, GifLayerRules.totalDurationMs(delays))
        // Zero/empty delay lists: total ≥ 1, selection stays in range.
        assertEquals(1L, GifLayerRules.totalDurationMs(emptyList()))
        assertEquals(1L, GifLayerRules.totalDurationMs(listOf(0, 0)))
        assertEquals(0, GifLayerRules.frameIndexAt(emptyList(), 12_345))
        assertEquals(0, GifLayerRules.frameIndexAt(listOf(0, 0), 12_345))
    }

    @Test
    fun twoFramesMeanAnimated() {
        assertTrue(GifLayerRules.isAnimated(2))
        assertTrue(GifLayerRules.isAnimated(48))
        assertFalse(GifLayerRules.isAnimated(1))
        assertFalse(GifLayerRules.isAnimated(0))
    }
}
