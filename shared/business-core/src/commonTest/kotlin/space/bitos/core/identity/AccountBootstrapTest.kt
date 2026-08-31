package space.bitos.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cold-start account bootstrap: the one-shot account heads (own kind-0,
 * kind-3 contacts, bookmarks, blocks) re-issue while unresolved, bounded
 * per connectivity episode and gated on relay connectivity.
 */
class AccountBootstrapTest {

    @Test
    fun reissuesUnresolvedHeadWhileConnectedAndBudgetRemains() {
        assertTrue(AccountBootstrap.shouldReissue(resolved = false, attempts = 0, connectedRelays = 1))
        assertTrue(
            AccountBootstrap.shouldReissue(
                resolved = false,
                attempts = AccountBootstrap.MAX_ATTEMPTS - 1,
                connectedRelays = 3,
            ),
        )
    }

    @Test
    fun stopsOnceResolved() {
        assertFalse(AccountBootstrap.shouldReissue(resolved = true, attempts = 0, connectedRelays = 3))
    }

    @Test
    fun burnsNoAttemptsBeyondTheBudget() {
        assertFalse(
            AccountBootstrap.shouldReissue(
                resolved = false,
                attempts = AccountBootstrap.MAX_ATTEMPTS,
                connectedRelays = 2,
            ),
        )
        assertFalse(
            AccountBootstrap.shouldReissue(resolved = false, attempts = Int.MAX_VALUE, connectedRelays = 2),
        )
    }

    @Test
    fun neverFiresWithoutConnectivity() {
        // A REQ composed with zero connected relays is dropped again; the
        // pool's reconnect loop owns recovery (EmptyFeedRetry parity).
        assertFalse(AccountBootstrap.shouldReissue(resolved = false, attempts = 0, connectedRelays = 0))
    }

    @Test
    fun growingConnectivityOpensANewEpisode() {
        assertTrue(AccountBootstrap.shouldOpenEpisode(previousConnected = 0, currentConnected = 1))
        assertTrue(AccountBootstrap.shouldOpenEpisode(previousConnected = 1, currentConnected = 3))
        // Losing a relay does not grant a fresh budget.
        assertFalse(AccountBootstrap.shouldOpenEpisode(previousConnected = 3, currentConnected = 1))
        assertFalse(AccountBootstrap.shouldOpenEpisode(previousConnected = 1, currentConnected = 1))
    }

    @Test
    fun budgetIsPerEpisodeSoALaterReconnectCanStillFetch() {
        // Episode 1 burns out with the head unresolved…
        var attempts = 0
        var connected = 1
        while (AccountBootstrap.shouldReissue(resolved = false, attempts, connected)) attempts++
        assertEquals(AccountBootstrap.MAX_ATTEMPTS, attempts)
        // …a NEW relay connecting resets the budget for the next episode.
        connected += 1
        assertTrue(AccountBootstrap.shouldOpenEpisode(connected - 1, connected))
        assertTrue(AccountBootstrap.shouldReissue(resolved = false, attempts = 0, connectedRelays = connected))
    }
}
