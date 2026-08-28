package space.bitos.core.feed

/**
 * Empty-feed auto-retry policy (unified feature spec §3.4, APP-004): when
 * the feed window is still empty, the client re-issues its subscription REQ
 * on an exponential backoff starting at 2 s and capped at 30 s, gated on
 * relay connectivity (a REQ is pointless while no relay is connected —
 * reconnection is the relay pool's own responsibility).
 *
 * Pure and deterministic so both native stores schedule identical behavior;
 * the attempt counter saturates where the delay reaches the cap.
 */
object EmptyFeedRetry {

    const val INITIAL_DELAY_MS: Long = 2_000
    const val MAX_DELAY_MS: Long = 30_000

    /** Attempt index at which the delay saturates at [MAX_DELAY_MS]. */
    const val MAX_ATTEMPT: Int = 4

    /**
     * Delay before the next empty-feed re-subscribe. `attempt` counts
     * fired retries (0 = first retry after the initial subscription).
     * Saturates instead of overflowing for any non-negative input.
     */
    fun delayMs(attempt: Int): Long {
        val safe = attempt.coerceAtLeast(0).coerceAtMost(20)
        val exponential = INITIAL_DELAY_MS shl safe
        return if (exponential > MAX_DELAY_MS) MAX_DELAY_MS else exponential
    }

    /**
     * Next attempt index; saturates at [MAX_ATTEMPT] so the counter stays
     * bounded while the feed remains empty.
     */
    fun advance(attempt: Int): Int = if (attempt >= MAX_ATTEMPT) MAX_ATTEMPT else attempt + 1

    /**
     * Connectivity gate: a retry REQ only fires when the window is empty
     * AND at least one relay is connected (an unconnected pool would drop
     * it; its own reconnect loop is the recovery path there).
     */
    fun shouldResubscribe(windowEmpty: Boolean, connectedRelays: Int): Boolean =
        windowEmpty && connectedRelays > 0
}
