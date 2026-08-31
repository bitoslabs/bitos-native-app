package space.bitos.core.identity

/**
 * Cold-start account bootstrap policy (unified with `EmptyFeedRetry`).
 *
 * The account-scoped head requests — own kind-0 profile, kind-3 contact
 * list, NIP-51 bookmark and block lists — are one-shot REQs fired the moment
 * an account becomes active. On a cold start (app relaunch, account switch
 * while offline) those REQs are typically composed before any relay socket
 * finished connecting, and an unconnected transport silently drops them:
 * the You surface stays anonymous with an empty follow set until something
 * else re-asks. Unlike the feed window there is no empty-state retry loop
 * for these heads, so the recovery is a bounded re-issue driven by relay
 * connectivity: each head re-fires while it is still UNRESOLVED, at most
 * [MAX_ATTEMPTS] times per connectivity episode, and never again once its
 * head landed (any verified answer, even an empty list, counts).
 */
object AccountBootstrap {

    /** Re-issues one head may burn per connectivity episode. */
    const val MAX_ATTEMPTS: Int = 5

    /**
     * Whether one account head should be re-issued now. `resolved` means the
     * newest head for this surface landed; `attempts` counts re-issues
     * already burned for the head since the current episode began.
     * Connectivity-gated like [space.bitos.core.feed.EmptyFeedRetry]: a REQ
     * composed with zero connected relays would be dropped again.
     */
    fun shouldReissue(resolved: Boolean, attempts: Int, connectedRelays: Int): Boolean =
        connectedRelays > 0 && !resolved && attempts < MAX_ATTEMPTS

    /**
     * Fresh relay connectivity opens a new episode: when the connected count
     * GROWS (a relay finished connecting, or a pool edit added one), the
     * per-head attempt budgets reset so a later reconnect can still fetch a
     * head that early episodes missed. Shrinking connectivity never resets.
     */
    fun shouldOpenEpisode(previousConnected: Int, currentConnected: Int): Boolean =
        currentConnected > previousConnected
}
