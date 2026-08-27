package space.bitos.core.feed

import kotlin.test.Test
import kotlin.test.assertTrue

class FeedRankingTest {
    @Test
    fun negativeFeedbackCanOverridePositiveSignals() {
        val score = FeedRanking.score(
            FeedSignals(
                recency = 1.0,
                authorAffinity = 1.0,
                topicMatch = 1.0,
                trust = 1.0,
                verifiedZap = 0.0,
                negativeFeedback = 1.0,
            ),
        )
        assertTrue(score.contributions.getValue("negativeFeedback") < 0)
    }
}
