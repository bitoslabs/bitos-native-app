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
 *
 * Performance (docs/engineering/performance-audit-and-plan.md §2.2): the
 * canonical order is maintained incrementally — `insert` places the id by
 * binary search (no re-sort per event) and eviction removes the true minimum
 * from the tail. `snapshot` is a walk of the ordered ids, cached until the
 * next mutation, so burst absorption never pays O(n log n) per frame.
 */
class FeedAggregator(private val maxItems: Int = 200) {

    private val notes = LinkedHashMap<String, FeedNote>()

    /** Ids in canonical order: `created_at` desc, then id asc. */
    private val orderedIds = ArrayList<String>(maxItems.coerceAtLeast(16))
    private var sortedCache: List<FeedNote>? = null

    fun snapshot(): List<FeedNote> =
        sortedCache ?: buildList {
            for (id in orderedIds) notes[id]?.let(::add)
        }.also { sortedCache = it }

    fun size(): Int = notes.size

    /** Insert an event; returns false when it was a duplicate. */
    fun insert(note: FeedNote): Boolean {
        if (notes.containsKey(note.id)) return false
        notes[note.id] = note
        orderedIds.add(insertionIndex(note.id), note.id)
        sortedCache = null
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
        rebuildOrder()
        trimIfNeeded()
        return notes.values.toList()
    }

    /** True when [a] precedes [b] in canonical order (newer, then smaller id). */
    private fun precedes(a: FeedNote, b: FeedNote): Boolean =
        if (a.createdAt != b.createdAt) a.createdAt > b.createdAt else a.id < b.id

    /** First index whose note [id] itself precedes — the binary-search slot. */
    private fun insertionIndex(id: String): Int {
        val note = notes[id] ?: return orderedIds.size
        var low = 0
        var high = orderedIds.size
        while (low < high) {
            val mid = (low + high) ushr 1
            val other = notes[orderedIds[mid]]
            if (other == null || precedes(note, other)) high = mid else low = mid + 1
        }
        return low
    }

    /** Re-derives [orderedIds] from current contents (prepend path). */
    private fun rebuildOrder() {
        orderedIds.clear()
        for (note in notes.values.sortedWith(compareByDescending<FeedNote> { it.createdAt }.thenBy { it.id })) {
            orderedIds.add(note.id)
        }
        sortedCache = null
    }

    /**
     * Evicts the minimum by (`created_at`, id) — the first id of the oldest
     * same-timestamp run, matching the eviction rule this class has always
     * documented. Ties are rare; the scan stops at the run's start.
     */
    private fun trimIfNeeded() {
        while (notes.size > maxItems && orderedIds.isNotEmpty()) {
            var index = orderedIds.size - 1
            val oldestAt = notes[orderedIds[index]]?.createdAt ?: break
            while (index > 0) {
                val previous = notes[orderedIds[index - 1]] ?: break
                if (previous.createdAt != oldestAt) break
                index--
            }
            notes.remove(orderedIds.removeAt(index))
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
