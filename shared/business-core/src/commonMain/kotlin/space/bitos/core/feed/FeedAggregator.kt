package space.bitos.core.feed

/**
 * Bounded, deterministic feed window shared by iOS and Android.
 *
 * Aggregation rules (product rules, therefore shared — REL-004/FED-006):
 * - identity is the verified event id; duplicates from relay reordering or
 *   overlapping pagination are dropped;
 * - the window is size-bounded and keeps the newest events by `created_at`,
 *   breaking ties on id for stable ordering;
 * - `prepend` preserves the caller's visible order for already-present ids so
 *   live arrivals do not reshuffle the screen.
 */
class FeedAggregator(private val maxItems: Int = 200) {

    private val notes = LinkedHashMap<String, FeedNote>()

    fun snapshot(): List<FeedNote> = notes.values.sortedWith(compareByDescending<FeedNote> { it.createdAt }.thenBy { it.id })

    fun size(): Int = notes.size

    /** Insert an event; returns false when it was a duplicate. */
    fun insert(note: FeedNote): Boolean {
        if (notes.containsKey(note.id)) return false
        notes[note.id] = note
        trimIfNeeded()
        return true
    }

    /**
     * Insert new arrivals while keeping [visibleOrder] stable for ids that
     * are already on screen. Unknown ids append below the visible window in
     * recency order, so live relay arrivals never reshuffle what is visible.
     */
    fun prepend(newNotes: List<FeedNote>, visibleOrder: List<String>): List<FeedNote> {
        val pinned = LinkedHashMap<String, FeedNote>()
        for (id in visibleOrder) {
            notes.remove(id)?.let { pinned[id] = it }
        }
        val fresh = newNotes
            .filter { !pinned.containsKey(it.id) }
            .sortedByDescending { it.createdAt }
        for (note in fresh) pinned[note.id] = note
        notes.clear()
        for (note in pinned.values.take(maxItems)) notes[note.id] = note
        trimIfNeeded()
        return notes.values.toList()
    }

    private fun trimIfNeeded() {
        while (notes.size > maxItems) {
            val oldest = notes.entries.minWith(compareBy({ it.value.createdAt }, { it.key }))
            notes.remove(oldest.key)
        }
    }
}

/**
 * Simple recency-only ranking used until the full signal pipeline (SBC-011)
 * lands. Deterministic; same inputs produce the same order on every platform.
 */
object RecencyRanker {
    fun score(note: FeedNote, nowSeconds: Long): Double {
        val age = (nowSeconds - note.createdAt).coerceAtLeast(0)
        val hours = age / 3600.0
        // Half-life of 12h, clamped to (0, 1].
        return 1.0 / (1.0 + hours / 12.0)
    }
}
