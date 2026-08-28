package space.bitos.core.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * APP-004 empty-feed retry policy: 2 s exponential backoff capped at 30 s,
 * saturating attempt counter, connectivity-gated re-subscribe decision.
 */
class EmptyFeedRetryTest {

    @Test
    fun backoffStartsAtTwoSecondsAndDoubles() {
        assertEquals(2_000, EmptyFeedRetry.delayMs(0))
        assertEquals(4_000, EmptyFeedRetry.delayMs(1))
        assertEquals(8_000, EmptyFeedRetry.delayMs(2))
        assertEquals(16_000, EmptyFeedRetry.delayMs(3))
    }

    @Test
    fun backoffCapsAtThirtySeconds() {
        assertEquals(30_000, EmptyFeedRetry.delayMs(EmptyFeedRetry.MAX_ATTEMPT))
        assertEquals(30_000, EmptyFeedRetry.delayMs(EmptyFeedRetry.MAX_ATTEMPT + 1))
        // Saturates for arbitrarily large inputs instead of overflowing.
        assertEquals(30_000, EmptyFeedRetry.delayMs(60))
        assertEquals(30_000, EmptyFeedRetry.delayMs(Int.MAX_VALUE))
    }

    @Test
    fun negativeAttemptsClampToTheFirstRetry() {
        assertEquals(2_000, EmptyFeedRetry.delayMs(-1))
    }

    @Test
    fun attemptCounterSaturatesAtTheCap() {
        var attempt = 0
        repeat(10) { attempt = EmptyFeedRetry.advance(attempt) }
        assertEquals(EmptyFeedRetry.MAX_ATTEMPT, attempt)
        // Delay and counter agree: at the cap the delay no longer grows.
        assertEquals(EmptyFeedRetry.delayMs(attempt), EmptyFeedRetry.delayMs(EmptyFeedRetry.MAX_ATTEMPT))
    }

    @Test
    fun resubscribeOnlyWhenEmptyAndConnected() {
        assertTrue(EmptyFeedRetry.shouldResubscribe(windowEmpty = true, connectedRelays = 1))
        assertTrue(EmptyFeedRetry.shouldResubscribe(windowEmpty = true, connectedRelays = 3))
        // Non-empty window: never burn a retry.
        assertFalse(EmptyFeedRetry.shouldResubscribe(windowEmpty = false, connectedRelays = 3))
        // No connectivity: the relay pool's reconnect loop owns recovery.
        assertFalse(EmptyFeedRetry.shouldResubscribe(windowEmpty = true, connectedRelays = 0))
    }
}
