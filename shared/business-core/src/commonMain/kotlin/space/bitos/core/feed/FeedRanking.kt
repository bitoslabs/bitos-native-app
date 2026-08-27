package space.bitos.core.feed

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

object FeedRanking {
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
}
