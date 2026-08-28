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

    /** Newest cached events (any kind), ordered by created_at desc, id. */
    suspend fun recentEvents(limit: Int): List<NostrEvent>

    /** Keep only the newest [maxRows] rows. */
    suspend fun pruneToLimit(maxRows: Int)
}
