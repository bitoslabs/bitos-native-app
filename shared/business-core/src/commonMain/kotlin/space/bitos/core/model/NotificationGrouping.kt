package space.bitos.core.model

/**
 * APP-012 notification grouping (pure, deterministic): newest-first items
 * bucketed into UTC day sections, with iOS-style aggregation — same-kind
 * actors on the same note collapse to "A and N others liked your note".
 * Follows, replies and mentions never aggregate (each act is its own row).
 *
 * Sections are keyed by UTC epoch day so platforms map [NotificationSection.epochDay]
 * to Today/Yesterday/weekday labels natively (presentation stays out of the
 * shared core).
 */
data class NotificationGroup(
    /** Oldest item id in the group — stable identity while the group lives. */
    val id: String,
    val kind: NotificationKind,
    /** Distinct actor pubkeys, newest actor first, display-capped. */
    val actors: List<String>,
    /** Distinct actor count ("and N others" uses this − shown actors). */
    val actorCount: Int,
    val targetEventId: String?,
    /** Summary of the newest item in the group. */
    val sampleSummary: String,
    /** Newest actor timestamp in the group (section/group ordering key). */
    val newestAt: Long,
    /** All item ids in the group, newest first (mark-read covers these). */
    val itemIds: List<String>,
    /** Summed zap msat for ZAP groups (bolt11 amounts), else 0. */
    val totalMsat: Long = 0,
)

data class NotificationSection(
    /** Days since 1970-01-01 UTC. 0 = today relative to `nowSeconds`. */
    val epochDay: Long,
    val groups: List<NotificationGroup>,
)

object NotificationSections {

    const val SECONDS_PER_DAY = 86_400L
    const val MAX_SECTIONS = 14
    const val MAX_GROUPS_PER_SECTION = 100
    const val MAX_ACTORS_SHOWN = 4

    fun epochDayOf(createdAtSeconds: Long): Long =
        if (createdAtSeconds >= 0) createdAtSeconds / SECONDS_PER_DAY else -((-createdAtSeconds + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY)

    /**
     * Groups [items] (any order) into bounded day sections, newest day and
     * newest group first. Items older than [MAX_SECTIONS] days (relative to
     * [nowSeconds]) are dropped.
     */
    fun sections(items: List<NotificationItem>, nowSeconds: Long): List<NotificationSection> {
        val today = epochDayOf(nowSeconds)
        val newestFirst = items.sortedByDescending { it.createdAt }
        val dayBuckets = LinkedHashMap<Long, MutableList<NotificationItem>>()
        for (item in newestFirst) {
            val day = epochDayOf(item.createdAt)
            if (day < today - (MAX_SECTIONS - 1) || day > today + 1) continue
            dayBuckets.getOrPut(day) { mutableListOf() }.add(item)
        }
        return dayBuckets.entries
            .sortedByDescending { it.key }
            .map { (day, dayItems) -> NotificationSection(epochDay = day, groups = groups(dayItems)) }
    }

    /**
     * Aggregation pass over one day's items (already newest-first).
     * Reaction/repost/zap items sharing a kind+target collapse; everything
     * else stays a single-item group. Output is bounded and newest-group
     * first.
     */
    fun groups(dayItems: List<NotificationItem>): List<NotificationGroup> {
        class Accumulator(val kind: NotificationKind, val targetEventId: String?) {
            var newestSummary = ""
            var newestAt = 0L
            var oldestId = ""
            var totalMsat = 0L
            val actors = mutableListOf<String>() // newest actor first, distinct
            val itemIds = mutableListOf<String>() // newest first
        }

        val groups = mutableListOf<Accumulator>()
        val byKey = HashMap<String, Accumulator>()
        var soloIndex = 0

        for (item in dayItems) {
            val key = if (aggregates(item.kind) && item.targetEventId != null) {
                "${item.kind}:${item.targetEventId}"
            } else {
                "solo:${soloIndex++}"
            }
            val accumulator = byKey.getOrPut(key) {
                Accumulator(item.kind, item.targetEventId).also { groups += it }
            }
            if (accumulator.itemIds.isEmpty()) {
                accumulator.newestSummary = item.summary
                accumulator.newestAt = item.createdAt
            } else {
                accumulator.newestAt = maxOf(accumulator.newestAt, item.createdAt)
            }
            if (item.authorPubkey.isNotEmpty() && item.authorPubkey !in accumulator.actors) {
                accumulator.actors.add(item.authorPubkey) // newest-first: items arrive newest-first
            }
            accumulator.itemIds.add(item.id)
            accumulator.oldestId = item.id
            accumulator.totalMsat += item.amountMsat ?: 0
        }

        return groups
            .map { accumulator ->
                NotificationGroup(
                    id = accumulator.oldestId,
                    kind = accumulator.kind,
                    actors = accumulator.actors.take(MAX_ACTORS_SHOWN),
                    actorCount = accumulator.actors.size,
                    targetEventId = accumulator.targetEventId,
                    sampleSummary = accumulator.newestSummary,
                    newestAt = accumulator.newestAt,
                    itemIds = accumulator.itemIds,
                    totalMsat = accumulator.totalMsat,
                )
            }
            .sortedByDescending { it.newestAt }
            .take(MAX_GROUPS_PER_SECTION)
    }

    /**
     * Kinds that collapse per target note. Zaps aggregate per note even
     * though actor pubkeys are zap-server keys (actorCount counts receipts).
     */
    private fun aggregates(kind: NotificationKind): Boolean =
        kind == NotificationKind.REACTION || kind == NotificationKind.REPOST || kind == NotificationKind.ZAP
}
