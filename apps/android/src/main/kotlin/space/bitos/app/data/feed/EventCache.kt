package space.bitos.app.data.feed

import space.bitos.core.feed.FeedNote
import space.bitos.core.model.NostrEvent

/**
 * Persistence port for the verified event cache (DAT-003). Implementations
 * store already-verified events (both trust stages passed) and hydrate the
 * feed window on cold start. Room/SQLite adapters implement this; tests use
 * an in-memory double so repository rules stay verifiable on the JVM.
 */
interface EventCache {
    /** Upsert one verified event; primary-key conflicts replace. */
    suspend fun upsertVerified(event: NostrEvent)

    /**
     * Upserts a burst of verified events in ONE transaction
     * (performance-audit-and-plan.md §Phase 3): one fsync per batch keeps
     * relay EOSE bursts off the per-event disk critical path. The default
     * loops [upsertVerified] so test doubles stay minimal; production
     * adapters override with a single transaction.
     */
    suspend fun upsertAll(events: List<NostrEvent>) {
        events.forEach { upsertVerified(it) }
    }

    /** Newest cached events (any kind), ordered by created_at desc, id. */
    suspend fun recentEvents(limit: Int): List<NostrEvent>

    /** Keep only the newest [maxRows] rows. */
    suspend fun pruneToLimit(maxRows: Int)

    /** Wipes every cached row (clear-cache, APP-018a row 5 — legacy parity). */
    suspend fun clearAllCache()
}
