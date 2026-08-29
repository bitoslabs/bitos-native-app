package space.bitos.core.feed

/**
 * APP-009 live per-note tallies (spec §3.9 "live deltas on root AND
 * replies"): relay-reconciled counts of reactions, reposts and zap
 * receipts targeting one note, merged from verified events and bounded
 * per note (a hostile fan-in cannot grow state without limit). Pure
 * merge rule — the repos own subscription and persistence.
 */
data class NoteTally(
    val reactions: Int = 0,
    val reposts: Int = 0,
    val zaps: Int = 0,
    /** Summed zap amount in msat when receipts carry a bolt11 amount. */
    val zapMillisats: Long = 0,
)

object NoteTallies {

    /** Per-target bound (matches the zap/reply windows). */
    const val MAX_TRACKED_NOTES = 32

    /** Merge one verified tally-affecting event into the current value. */
    fun merge(
        current: NoteTally?,
        kind: Int,
        amountMillisats: Long? = null,
    ): NoteTally {
        val base = current ?: NoteTally()
        return when (kind) {
            7 -> base.copy(reactions = (base.reactions + 1).coerceAtMost(Int.MAX_VALUE - 1))
            6 -> base.copy(reposts = (base.reposts + 1).coerceAtMost(Int.MAX_VALUE - 1))
            9_735 -> base.copy(
                zaps = (base.zaps + 1).coerceAtMost(Int.MAX_VALUE - 1),
                zapMillisats = (base.zapMillisats + (amountMillisats ?: 0)).coerceAtMost(Long.MAX_VALUE / 2),
            )
            else -> base
        }
    }

    /** Insertion-order eviction for the bounded window (oldest out). */
    fun <K> evict(keys: LinkedHashSet<K>, key: K) {
        keys.add(key)
        if (keys.size > MAX_TRACKED_NOTES) keys.remove(keys.first())
    }
}
