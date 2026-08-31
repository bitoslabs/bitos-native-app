package space.bitos.core.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun dismissedNotesNeverSurfaceAndDemotionsReorderDeterministically() {
        // A ranking-enabled snapshot: recency-only, diversity off.
        val snapshot = AlgorithmSnapshot(
            freshnessHours = 24,
            surfaces = mapOf(
                AlgorithmSurface.FEED to SurfaceSetting(
                    enabled = true,
                    diversityEnabled = false,
                    signals = mapOf(
                        AlgorithmSignal.RECENCY to SignalSetting(enabled = true, weight = 1.0),
                    ),
                ),
            ),
        )
        val author = "aa".repeat(32)
        val other = "bb".repeat(32)
        fun note(id: Char, pubkey: String, createdAt: Long, hashtags: List<String> = emptyList()) =
            FeedNote(
                id = "$id".repeat(64), pubkey = pubkey, content = "x", createdAt = createdAt,
                kind = 1, replyTo = null, hashtags = hashtags,
                mentions = emptyList(), mediaUrls = emptyList(), isProtocolPayload = false,
            )

        val old = note('1', author, 1_000, listOf("bitcoin"))
        val fresh = note('2', other, 2_000)
        val base = listOf(old, fresh)

        // 1) Hide: the dismissed note is the ranker's one intentional drop.
        val hidden = FeedRanking.rank(
            base, AlgorithmSurface.FEED, snapshot,
            RankingContext(nowSeconds = 2_000, dismissedNoteIds = setOf(old.id)),
        )
        assertEquals(listOf(fresh.id), hidden.map { it.id })

        // 2) Show-less-from: a demoted fresh author ranks below an old one.
        val demotedAuthor = FeedRanking.rank(
            base, AlgorithmSurface.FEED, snapshot,
            RankingContext(nowSeconds = 2_000, mutedAuthors = setOf(other)),
        )
        assertEquals(old.id, demotedAuthor.first().id)

        // 3) Show-less-about: a tag demotion is softer but still deterministic.
        val demotedTag = FeedRanking.rank(
            base, AlgorithmSurface.FEED, snapshot,
            RankingContext(nowSeconds = 2_000, mutedTags = setOf("bitcoin")),
        )
        assertEquals(fresh.id, demotedTag.first().id)
        // Stacking both demotions drops the note below either alone.
        val stacked = FeedRanking.rank(
            base, AlgorithmSurface.FEED, snapshot,
            RankingContext(nowSeconds = 2_000, mutedAuthors = setOf(author), mutedTags = setOf("bitcoin")),
        )
        assertEquals(fresh.id, stacked.first().id)
        assertFalse(stacked.isEmpty())
    }
}
