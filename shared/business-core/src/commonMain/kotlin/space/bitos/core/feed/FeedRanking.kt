package space.bitos.core.feed

import kotlin.math.ln
import kotlin.math.pow

data class FeedSignals(
    val recency: Double,
    val authorAffinity: Double,
    val topicMatch: Double,
    val trust: Double,
    val verifiedZap: Double,
    val negativeFeedback: Double,
)

data class ScoreBreakdown(
    val total: Double,
    val contributions: Map<String, Double>,
)

/**
 * Inputs the adapter can supply today. Missing feeds contribute 0 — the
 * ranker never invents data (TOPICS/WoT land with their data slices).
 */
data class RankingContext(
    val nowSeconds: Long,
    /** Account's followed pubkeys (kind-3 head) — affinity input. */
    val following: Set<String> = emptySet(),
    /** Verified kind-9735 zap counts per note id — zaps input. */
    val zapCounts: Map<String, Int> = emptyMap(),
    /** Kind-7 reaction counts per note id — engagement input. */
    val reactionCounts: Map<String, Int> = emptyMap(),
    /** Reply counts per note id (thread children) — engagement input. */
    val replyCounts: Map<String, Int> = emptyMap(),
)

/**
 * Client-side feed ranking (origin `algorithm-plan.md` parity).
 *
 * Pure and deterministic: same inputs → same order. A surface switch OFF
 * (or a null config) yields strict reverse-chronological order — never an
 * empty or randomly shuffled feed. Enabled weights are re-normalized so
 * turning a signal off re-balances the others (origin behavior).
 */
object FeedRanking {

    /** Fixed-weight scoring kept for the deterministic test corpus. */
    fun score(signals: FeedSignals): ScoreBreakdown {
        val contributions = linkedMapOf(
            "recency" to signals.recency.coerceIn(0.0, 1.0) * 0.24,
            "authorAffinity" to signals.authorAffinity.coerceIn(0.0, 1.0) * 0.22,
            "topicMatch" to signals.topicMatch.coerceIn(0.0, 1.0) * 0.18,
            "trust" to signals.trust.coerceIn(0.0, 1.0) * 0.18,
            "verifiedZap" to signals.verifiedZap.coerceIn(0.0, 1.0) * 0.08,
            "negativeFeedback" to -signals.negativeFeedback.coerceIn(0.0, 1.0) * 0.40,
        )
        return ScoreBreakdown(contributions.values.sum(), contributions)
    }

    /**
     * Ranks one surface's window. Off = chronological; ties break by
     * createdAt then id so the order is stable across recompositions.
     */
    fun rank(
        notes: List<FeedNote>,
        surface: AlgorithmSurface,
        snapshot: AlgorithmSnapshot,
        ctx: RankingContext,
    ): List<FeedNote> {
        val setting = snapshot.surfaces[surface]?.takeIf { it.enabled } ?: return chronological(notes)
        val active = setting.signals.values
            .filter { it.enabled && it.weight > 0.0 }
        if (active.isEmpty()) return chronological(notes)
        val totalWeight = active.sumOf { it.weight }

        val maxZaps = (ctx.zapCounts.values.maxOrNull() ?: 0).coerceAtLeast(1)
        val maxEngagement = (ctx.reactionCounts.values.sumOf { it }
            + ctx.replyCounts.values.sumOf { it }).coerceAtLeast(1)

        val ranked = notes
            .map { note ->
                var score = 0.0
                for ((signal, config) in setting.signals) {
                    if (!config.enabled || config.weight <= 0.0) continue
                    score += signalValue(
                        signal = signal,
                        note = note,
                        snapshot = snapshot,
                        ctx = ctx,
                        maxZaps = maxZaps,
                        maxEngagement = maxEngagement,
                    ) * config.weight / totalWeight
                }
                note to score
            }
            .sortedWith(
                compareByDescending<Pair<FeedNote, Double>> { it.second }
                    .thenByDescending { it.first.createdAt }
                    .thenBy { it.first.id },
            )
            .map { it.first }
        return if (setting.diversityEnabled) applyDiversity(ranked) else ranked
    }

    /**
     * Origin `diversity.ts` parity: a post-scoring author-clustering pass —
     * no author holds more than [MAX_CONSECUTIVE] adjacent slots; overflow
     * notes are REQUEUED (never dropped), so the set is only reordered.
     * Deterministic: same input → same output.
     */
    private const val MAX_CONSECUTIVE = 2

    private fun applyDiversity(ranked: List<FeedNote>): List<FeedNote> {
        val output = ArrayList<FeedNote>(ranked.size)
        val requeued = ArrayDeque<FeedNote>()
        var currentAuthor: String? = null
        var consecutive = 0

        fun push(note: FeedNote) {
            if (note.pubkey == currentAuthor && consecutive >= MAX_CONSECUTIVE) {
                requeued.addLast(note)
                return
            }
            if (note.pubkey != currentAuthor) {
                currentAuthor = note.pubkey
                consecutive = 1
            } else {
                consecutive++
            }
            output.add(note)
        }

        for (note in ranked) push(note)
        // Guarded drain: an author-heavy tail interleaves as far as possible;
        // whatever remains (pure same-author runs) appends in rank order —
        // notes are never dropped, and the loop can never cycle forever.
        var guard = 0
        val drainLimit = ranked.size * 2
        while (requeued.isNotEmpty() && guard < drainLimit) {
            push(requeued.removeFirst())
            guard++
        }
        output.addAll(requeued)
        requeued.clear()
        return output
    }

    private fun chronological(notes: List<FeedNote>): List<FeedNote> =
        notes.sortedWith(compareByDescending<FeedNote> { it.createdAt }.thenBy { it.id })

    private fun signalValue(
        signal: AlgorithmSignal,
        note: FeedNote,
        snapshot: AlgorithmSnapshot,
        ctx: RankingContext,
        maxZaps: Int,
        maxEngagement: Int,
    ): Double = when (signal) {
        AlgorithmSignal.RECENCY -> {
            val ageSeconds = (ctx.nowSeconds - note.createdAt).coerceAtLeast(0)
            // Exponential decay with the freshness half-life (Live 1h … Chill 3d).
            0.5.pow(ageSeconds.toDouble() / (snapshot.freshnessHours * 3_600.0))
        }
        AlgorithmSignal.ZAPS -> ln(1.0 + (ctx.zapCounts[note.id] ?: 0)) / ln(1.0 + maxZaps)
        AlgorithmSignal.ENGAGEMENT -> {
            val e = (ctx.reactionCounts[note.id] ?: 0) + (ctx.replyCounts[note.id] ?: 0)
            ln(1.0 + e) / ln(1.0 + maxEngagement)
        }
        AlgorithmSignal.AFFINITY -> if (note.pubkey in ctx.following) 1.0 else 0.0
        // Data feeds land with the topic-profile / WoT slices; honest zeros.
        AlgorithmSignal.TOPICS -> 0.0
        AlgorithmSignal.WOT -> 0.0
    }
}
