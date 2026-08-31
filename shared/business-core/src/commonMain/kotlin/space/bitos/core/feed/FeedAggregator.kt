package space.bitos.core.feed

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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
 * Each update publishes an immutable window with a compare-and-set. Relay
 * collection, cache hydration, and UI projection can run in separate native
 * coroutines, so mutating an ArrayList in place would let `snapshot` iterate
 * while another coroutine changes it. A snapshot therefore always reads one
 * complete window revision. Canonical order remains incremental, and each
 * revision caches its snapshot after its first read.
 */
@OptIn(ExperimentalAtomicApi::class)
class FeedAggregator(private val maxItems: Int = 200) {

    private data class Window(
        val notes: Map<String, FeedNote> = emptyMap(),
        /** Ids in canonical order: `created_at` desc, then id asc. */
        val orderedIds: List<String> = emptyList(),
        val sortedCache: List<FeedNote>? = null,
    )

    private val window = AtomicReference(Window())

    fun snapshot(): List<FeedNote> {
        val current = window.load()
        current.sortedCache?.let { return it }
        val snapshot = buildList(current.orderedIds.size) {
            for (id in current.orderedIds) current.notes[id]?.let(::add)
        }
        // Caching is an optimization only. If an insert won the race, its
        // revision intentionally keeps its own empty cache.
        window.compareAndSet(current, current.copy(sortedCache = snapshot))
        return snapshot
    }

    fun size(): Int = window.load().notes.size

    /** Insert an event; returns false when it was a duplicate. */
    fun insert(note: FeedNote): Boolean {
        while (true) {
            val current = window.load()
            if (note.id in current.notes) return false

            val notes = current.notes.toMutableMap().apply { put(note.id, note) }
            val orderedIds = current.orderedIds.toMutableList().apply {
                add(insertionIndex(note, current), note.id)
            }
            trimIfNeeded(notes, orderedIds)
            val next = Window(notes = notes, orderedIds = orderedIds)
            if (window.compareAndSet(current, next)) return true
        }
    }

    /**
     * Insert new arrivals while keeping [visibleOrder] stable for ids that
     * are already on screen. Unknown ids append below the visible window in
     * recency order, so live relay arrivals never reshuffle what is visible.
     */
    fun prepend(newNotes: List<FeedNote>, visibleOrder: List<String>): List<FeedNote> {
        while (true) {
            val current = window.load()
            val pinned = LinkedHashMap<String, FeedNote>()
            for (id in visibleOrder) {
                current.notes[id]?.let { pinned[id] = it }
            }
            val fresh = newNotes
                .filter { !pinned.containsKey(it.id) }
                .sortedByDescending { it.createdAt }
            for (note in fresh) pinned[note.id] = note

            val notes = LinkedHashMap<String, FeedNote>().apply {
                putAll(pinned.entries.take(maxItems).associate { it.toPair() })
            }
            val orderedIds = notes.values
                .sortedWith(compareByDescending<FeedNote> { it.createdAt }.thenBy { it.id })
                .map(FeedNote::id)
            val next = Window(notes = notes, orderedIds = orderedIds)
            if (window.compareAndSet(current, next)) return notes.values.toList()
        }
    }

    /** True when [a] precedes [b] in canonical order (newer, then smaller id). */
    private fun precedes(a: FeedNote, b: FeedNote): Boolean =
        if (a.createdAt != b.createdAt) a.createdAt > b.createdAt else a.id < b.id

    /** First index whose note follows [note] — the binary-search slot. */
    private fun insertionIndex(note: FeedNote, current: Window): Int {
        var low = 0
        var high = current.orderedIds.size
        while (low < high) {
            val mid = (low + high) ushr 1
            val other = current.notes[current.orderedIds[mid]]
            if (other == null || precedes(note, other)) high = mid else low = mid + 1
        }
        return low
    }

    /** Evicts the oldest id, with the documented first-id tie behavior. */
    private fun trimIfNeeded(notes: MutableMap<String, FeedNote>, orderedIds: MutableList<String>) {
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
