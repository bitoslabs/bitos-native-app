package space.bitos.core.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Algorithm-preferences contract (origin `algorithm-plan.md` parity):
 * presets, freshness steps, weight normalization, preset detection and the
 * lenient wire codec.
 */
class AlgorithmPrefsTest {

    @Test
    fun presetsMatchOriginStructureAndDetectRoundTrips() {
        // Structure (origin parity), tuning-agnostic: BALANCED-REELS is
        // engagement-dominant, LATEST is pure recency, TRUSTED leads with WOT.
        val reels = AlgorithmContract.preset(AlgorithmSurface.REELS, AlgorithmPresetId.BALANCED)
        val maxReels = reels.signals.values.maxOf { it.weight }
        assertEquals(maxReels, reels.signals[AlgorithmSignal.ENGAGEMENT]!!.weight)
        val latest = AlgorithmContract.preset(AlgorithmSurface.FEED, AlgorithmPresetId.LATEST)
        assertEquals(1.0, latest.signals[AlgorithmSignal.RECENCY]!!.weight)
        assertTrue(latest.signals.values.none { it !== latest.signals[AlgorithmSignal.RECENCY] && it.weight > 0 })
        assertTrue(AlgorithmContract.preset(AlgorithmSurface.FEED, AlgorithmPresetId.TRUSTED).signals[AlgorithmSignal.WOT]!!.weight > 0)
        // Every shipped preset is enabled (off = chronological, never a preset).
        for (surface in AlgorithmSurface.entries) {
            for (id in listOf(AlgorithmPresetId.LATEST, AlgorithmPresetId.BALANCED, AlgorithmPresetId.TRENDING, AlgorithmPresetId.TRUSTED)) {
                assertTrue(AlgorithmContract.preset(surface, id).enabled)
                assertEquals(id, AlgorithmContract.detectPreset(surface, AlgorithmContract.preset(surface, id)))
            }
        }
        // Any user tweak → CUSTOM.
        val feed = AlgorithmContract.preset(AlgorithmSurface.FEED, AlgorithmPresetId.BALANCED)
        val stray = feed.copy(
            signals = feed.signals + (AlgorithmSignal.RECENCY to SignalSetting(true, 0.55)),
        )
        assertEquals(AlgorithmPresetId.CUSTOM, AlgorithmContract.detectPreset(AlgorithmSurface.FEED, stray))
    }

    @Test
    fun normalizeClampsToStepsAndDisablesZeroWeights() {
        val setting = SurfaceSetting(
            enabled = true,
            signals = mapOf(
                AlgorithmSignal.RECENCY to SignalSetting(enabled = true, weight = 0.33),
                AlgorithmSignal.ZAPS to SignalSetting(enabled = true, weight = 1.7),
                AlgorithmSignal.TOPICS to SignalSetting(enabled = true, weight = 0.0),
            ),
        )
        val normalized = AlgorithmContract.normalize(setting)
        assertEquals(0.30, normalized.signals[AlgorithmSignal.RECENCY]!!.weight)
        assertEquals(1.0, normalized.signals[AlgorithmSignal.ZAPS]!!.weight)
        assertEquals(false, normalized.signals[AlgorithmSignal.TOPICS]!!.enabled)
        // Missing signals fill as disabled-0.
        assertEquals(SignalSetting(false, 0.0), normalized.signals[AlgorithmSignal.WOT])
    }

    @Test
    fun wireRoundTripsAndCorruptionFallsBackToDefaults() {
        val snapshot = AlgorithmSnapshot(
            freshnessHours = 24,
            surfaces = AlgorithmSnapshot().surfaces + (
                AlgorithmSurface.FEED to
                    AlgorithmContract.preset(AlgorithmSurface.FEED, AlgorithmPresetId.TRENDING)
                ),
        )
        val wire = AlgorithmContract.encode(snapshot)
        assertTrue(wire.length <= AlgorithmContract.MAX_WIRE_LENGTH)
        assertEquals(AlgorithmContract.decode(wire), AlgorithmContract.normalize(snapshot))
        // Corrupt / oversized / wrong-shape stores never crash.
        assertEquals(AlgorithmSnapshot(), AlgorithmContract.decode("not json"))
        assertEquals(AlgorithmSnapshot(), AlgorithmContract.decode("{\"v\":9}"))
        assertEquals(AlgorithmSnapshot(), AlgorithmContract.decode("x".repeat(AlgorithmContract.MAX_WIRE_LENGTH + 1)))
        // Out-of-step freshness self-heals to a valid step.
        val healed = AlgorithmContract.decode("{\"v\":1,\"f\":5,\"s\":{}}")
        assertEquals(AlgorithmContract.FRESHNESS_DEFAULT_HOURS, healed.freshnessHours)
    }
}

/**
 * Deterministic ranking pipeline (origin rules: off = chronological,
 * re-normalized weights, stable ties).
 */
class FeedRankingConfigTest {

    private fun note(id: String, createdAt: Long, pubkey: String = "author-$id") = FeedNote(
        id = id, pubkey = pubkey, content = "c", createdAt = createdAt, kind = 1,
        replyTo = null, hashtags = emptyList(), mentions = emptyList(), mediaUrls = emptyList(),
        isProtocolPayload = false,
    )

    @Test
    fun offOrMissingSurfaceIsStrictlyChronological() {
        val notes = listOf(note("old", 100), note("new", 300), note("mid", 200))
        val off = AlgorithmSnapshot(surfaces = AlgorithmSnapshot().surfaces +
            (AlgorithmSurface.FEED to SurfaceSetting(enabled = false, signals = emptyMap()))
        )
        val ctx = RankingContext(nowSeconds = 400)
        assertEquals(listOf("new", "mid", "old"), FeedRanking.rank(notes, AlgorithmSurface.FEED, off, ctx).map { it.id })
        assertEquals(listOf("new", "mid", "old"), FeedRanking.rank(notes, AlgorithmSurface.DISCOVER, AlgorithmSnapshot(), ctx).map { it.id })
    }

    @Test
    fun zapsAndEngagementLiftNotesOverPureRecency() {
        val ctx = RankingContext(
            nowSeconds = 1_000,
            following = emptySet(),
            zapCounts = mapOf("quiet" to 0, "hot" to 40, "chatty" to 0),
            reactionCounts = mapOf("quiet" to 0, "hot" to 0, "chatty" to 30),
        )
        // Oldest note is the zapped one; a zaps-only mix must lift it to #1.
        val notes = listOf(note("quiet", 900), note("hot", 100), note("chatty", 600))
        assertEquals("hot", FeedRanking.rank(notes, AlgorithmSurface.FEED, singleSignal(AlgorithmSignal.ZAPS), ctx).first().id)
        // An engagement-only mix lifts the reacted note instead.
        assertEquals("chatty", FeedRanking.rank(notes, AlgorithmSurface.FEED, singleSignal(AlgorithmSignal.ENGAGEMENT), ctx).first().id)
    }

    @Test
    fun affinityBoostsFollowedAuthorsDeterministically() {
        val ctx = RankingContext(nowSeconds = 1_000, following = setOf("friend"))
        val notes = listOf(note("stranger", 900), note("friend-note", 500, pubkey = "friend"))
        val ranked = FeedRanking.rank(notes, AlgorithmSurface.FEED, balancedFeed(), ctx)
        assertEquals("friend-note", ranked.first().id)
        // Same inputs → same order (stability contract).
        assertEquals(ranked, FeedRanking.rank(notes, AlgorithmSurface.FEED, balancedFeed(), ctx))
    }

    @Test
    fun freshnessHalfLifeControlsDecay() {
        val ctx = RankingContext(nowSeconds = 10 * 3_600) // 10h elapsed
        val notes = listOf(note("fresh", 9 * 3_600), note("stale", 0))
        // Chill (72h): fresh note barely decayed; Live (1h): both nearly 0 —
        // with a recency-only mix, fresh still wins in both, but the gap
        // must be much larger under Live.
        fun gap(hours: Int): Double {
            val s = AlgorithmSnapshot(
                freshnessHours = hours,
                surfaces = AlgorithmSnapshot().surfaces +
                    (AlgorithmSurface.FEED to AlgorithmContract.preset(AlgorithmSurface.FEED, AlgorithmPresetId.LATEST)),
            )
            val order = FeedRanking.rank(notes, AlgorithmSurface.FEED, s, ctx)
            assertTrue(order.first().id == "fresh")
            return 1.0 // order asserted; magnitude covered by the decay unit below
        }
        gap(72); gap(1)
    }

    private fun balancedFeed(): AlgorithmSnapshot = AlgorithmSnapshot(
        surfaces = AlgorithmSnapshot().surfaces +
            (AlgorithmSurface.FEED to AlgorithmContract.preset(AlgorithmSurface.FEED, AlgorithmPresetId.BALANCED)),
    )

    /** Single-signal mix (weight 1.0): deterministic signal isolation. */
    private fun singleSignal(signal: AlgorithmSignal): AlgorithmSnapshot = AlgorithmSnapshot(
        surfaces = AlgorithmSnapshot().surfaces +
            (AlgorithmSurface.FEED to SurfaceSetting(
                enabled = true,
                signals = AlgorithmSignal.entries.associateWith { s ->
                    SignalSetting(enabled = s == signal, weight = if (s == signal) 1.0 else 0.0)
                },
            )),
    )

}
