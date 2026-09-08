package space.bitos.core.studio

import kotlin.math.pow

/**
 * Trending-sounds rank ("use this sound" Wave D foundation, plan
 * docs/product/use-this-sound-plan.md §5): the APP-021 bootstrap —
 * counting `sound` tags over the feed window IS the usage rank, no
 * marketplace required. Pure + common-tested; both platform rails
 * render the same rows in the same order.
 *
 * Weighting: each borrow decays with a 3-day half-life (MST-047
 * parity); a sound's score is the SUM over its uses, so a steady
 * trickle can outlast a one-day spike. Ties break by url ascending —
 * never by input order.
 */
object MemeSoundTrending {

    /** MST-047 parity: 3-day half-life. */
    const val HALF_LIFE_MS = 3L * 24 * 3_600_000

    /** Rail rows rendered per surface. */
    const val MAX_ROWS = 20

    /** Hostile-input bound on usages fed through [rank]. */
    const val MAX_USAGES = 2_000

    /** One published borrow parsed from a note's tags. */
    data class Usage(
        val eventId: String,
        val createdAtMs: Long,
        val url: String,
        val sha256: String,
        val sourceEventId: String?,
        val authorPubkey: String?,
    )

    /** One ranked sound for the rail (feed chip + re-attach use the
     *  provenance fields; the UI re-attaches by URL — Wave C path). */
    data class Row(
        val url: String,
        val sha256: String,
        val sourceEventId: String?,
        val score: Double,
        val uses: Int,
    )

    /** Exponential decay weight in (0, 1]; future timestamps clamp to 1. */
    fun decay(createdAtMs: Long, nowMs: Long, halfLifeMs: Long = HALF_LIFE_MS): Double {
        if (halfLifeMs <= 0) return 1.0
        val age = (nowMs - createdAtMs).coerceAtLeast(0)
        return 0.5.pow(age.toDouble() / halfLifeMs)
    }

    /** Parses one note's borrow from its published tags; null = no sound. */
    fun usageFrom(tags: List<List<String>>, eventId: String, createdAtMs: Long): Usage? {
        val source = MemeSoundRules.sourceOf(tags) ?: return null
        if (source.url.isBlank()) return null
        val sha = tags.firstOrNull { it.firstOrNull() == "sound" && it.size >= 3 }
            ?.getOrNull(2)?.take(MemeSoundRules.MAX_SHA_LENGTH)
            ?.takeIf { it.isNotBlank() } ?: return null
        return Usage(
            eventId = eventId,
            createdAtMs = createdAtMs,
            url = source.url,
            sha256 = sha,
            sourceEventId = source.eventId,
            authorPubkey = source.pubkey,
        )
    }

    /** Aggregates usages into ranked rows (score desc, then url asc). */
    fun rank(usages: List<Usage>, nowMs: Long, maxRows: Int = MAX_ROWS): List<Row> {
        if (usages.isEmpty()) return emptyList()
        val score = HashMap<String, Double>()
        val uses = HashMap<String, Int>()
        val meta = HashMap<String, Usage>()
        for (usage in usages.take(MAX_USAGES)) {
            if (usage.url.isBlank() || usage.sha256.isBlank()) continue
            val key = "${usage.url}\u0000${usage.sha256}"
            score[key] = (score[key] ?: 0.0) + decay(usage.createdAtMs, nowMs)
            uses[key] = (uses[key] ?: 0) + 1
            // First-seen provenance wins (stable across recomputes).
            if (!meta.containsKey(key)) meta[key] = usage
        }
        return score.entries
            .map { (key, value) ->
                val first = meta.getValue(key)
                Row(
                    url = first.url,
                    sha256 = first.sha256,
                    sourceEventId = first.sourceEventId,
                    score = value,
                    uses = uses.getValue(key),
                )
            }
            .sortedWith(compareByDescending<Row> { it.score }.thenBy { it.url })
            .take(maxRows.coerceAtLeast(0))
    }
}
