package space.bitos.core.feed

import space.bitos.core.settings.BitzModeSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * APP-007 Bitz surface rules: search match/merge policy, explore-grid
 * paging bounds and the deterministic share-copy builder.
 */
class BitzTest {

    private fun entry(id: String, content: String = "", author: String = "") =
        BitzSearch.Entry(id = id, content = content, authorName = author)

    // MARK: - Search policy

    @Test
    fun blankOrOversizedQueryIsTheIdleState() {
        assertNull(BitzSearch.normalizedQuery(""))
        assertNull(BitzSearch.normalizedQuery("   "))
        assertNull(BitzSearch.normalizedQuery("x".repeat(BitzSearch.QUERY_MAX + 1)))
        assertEquals("bitcoin", BitzSearch.normalizedQuery("  bitcoin "))
    }

    @Test
    fun localMatchesAreCaseInsensitiveOverContentAndAuthor() {
        val window = listOf(
            entry("a", content = "Orange pill the bitcoin world", author = "satoshi"),
            entry("b", content = "gm nostr", author = "Bitcoiner"),
            entry("c", content = "cat picture", author = "alice"),
        )
        val matches = BitzSearch.localMatches("bitcoin", window)
        assertEquals(listOf("a", "b"), matches.map { it.id })
        assertTrue(BitzSearch.localMatches("", window).isEmpty())
    }

    @Test
    fun localMatchesAreBoundedAndSkipEmptyIds() {
        val window = (0..30).map { entry(if (it == 5) "" else "n$it", content = "zap") }
        val matches = BitzSearch.localMatches("zap", window)
        assertEquals(BitzSearch.LOCAL_MATCH_LIMIT, matches.size)
        assertFalse(matches.any { it.id.isEmpty() })
    }

    @Test
    fun mergeDedupesByIdKeepingLocalFirstAndBoundsOutput() {
        val local = listOf(entry("1", "kept"), entry("2", "dupe"))
        val relay = (2..60).map { entry("$it", "relay") }
        val merged = BitzSearch.merge(local, relay)
        assertEquals("1", merged.first().id)
        assertEquals(1, merged.count { it.id == "2" })
        assertEquals(BitzSearch.RESULT_LIMIT, merged.size)
        // Local order leads even when relay supplied the same ids.
        assertEquals(local, merged.take(local.size))
    }

    @Test
    fun resultsCombineMatchAndMerge() {
        val window = listOf(entry("a", content = "lightning"), entry("b", content = "cat"))
        val relay = listOf(entry("c", content = "lightning strikes"))
        val results = BitzSearch.results("lightning", window, relay)
        assertEquals(listOf("a", "c"), results.map { it.id })
        assertTrue(BitzSearch.results("", window, relay).isEmpty())
    }

    // MARK: - Explore paging (spec §3.7: 24 initial + 10 per load-more)

    @Test
    fun explorePagesAreTwentyFourInitialThenEighteenEach() {
        assertEquals(BitzExplore.INITIAL_PAGE, BitzExplore.visibleCount(0))
        assertEquals(24, BitzExplore.INITIAL_PAGE)
        assertEquals(10, BitzExplore.MORE_PAGE)
        assertEquals(34, BitzExplore.visibleCount(1))
        assertEquals(44, BitzExplore.visibleCount(2))
        // Hostile counters clamp instead of overflowing the grid.
        assertEquals(BitzExplore.visibleCount(100), BitzExplore.visibleCount(250))
    }

    @Test
    fun exploreHasMoreOnlyWhileTheWindowExceedsVisibleTiles() {
        assertTrue(BitzExplore.hasMore(windowSize = 30, visibleCount = 24))
        assertFalse(BitzExplore.hasMore(windowSize = 24, visibleCount = 24))
        assertFalse(BitzExplore.hasMore(windowSize = 10, visibleCount = 24))
    }

    @Test
    fun legacyQuerySeparatesNativeVideoKindsFromTextFallback() {
        assertEquals(
            listOf(
                """{"kinds":[21,22],"limit":16}""",
                """{"kinds":[1],"limit":48}""",
                """{"kinds":[6,0],"limit":80}""",
            ),
            BitzQuery.initialFilters(),
        )
        assertEquals(
            listOf(
                """{"kinds":[21,22],"limit":16,"until":123}""",
                """{"kinds":[1],"limit":48,"until":123}""",
            ),
            BitzQuery.olderFilters(123),
        )
    }

    @Test
    fun compactCountsMatchLegacyFormat() {
        // Raw below 1,000; one-decimal K/M above (legacy `_formatCount`).
        assertEquals("0", BitzFormat.count(0))
        assertEquals("7", BitzFormat.count(7))
        assertEquals("999", BitzFormat.count(999))
        assertEquals("1.0K", BitzFormat.count(1_000))
        assertEquals("1.2K", BitzFormat.count(1_234))
        assertEquals("1.2M", BitzFormat.count(1_234_567))
        assertEquals("0", BitzFormat.count(-5))
    }

    @Test
    fun compactSatsHideWhenEmptyAndRoundFromMillisats() {
        assertNull(BitzFormat.sats(0))
        assertNull(BitzFormat.sats(-10))
        // 1,500 msat → 1 sat (rounded down, min 1 when > 0).
        assertEquals("1", BitzFormat.sats(1_500))
        assertEquals("21", BitzFormat.sats(21_000))
        assertEquals("1.2K", BitzFormat.sats(1_234_000))
    }

    // MARK: - Share copy

    @Test
    fun shareTextCarriesBoundedExcerptAndAttribution() {
        assertEquals(
            "orange pill\n\n— npub1abc · BitOS",
            NoteShare.text("orange pill", "npub1abc"),
        )
        // Newlines flatten, long content ellipsizes at the bound.
        val long = "x".repeat(NoteShare.CONTENT_EXCERPT_MAX + 50)
        val shared = NoteShare.text(long, "npub1abc")
        assertTrue(shared.startsWith("x".repeat(NoteShare.CONTENT_EXCERPT_MAX - 1) + "…"))
        assertTrue(shared.endsWith("— npub1abc · BitOS"))
        // Empty content still attributes honestly.
        assertEquals("— npub1abc · BitOS", NoteShare.text("   \n ", "npub1abc"))
    }

    // MARK: - Pagination walk policy (FED-004, study §2.2)

    @Test
    fun walkQueriesReelMediaKindsDeepAndTextShallow() {
        // Dedicated media kinds (20/21/22/34235/34236) queried deep; kind-1
        // shallow; both filters ride one request (per-relay-per-filter limit).
        assertEquals(
            listOf(20, 21, 22, 34_235, 34_236),
            BitzTimelinePolicy.MEDIA_KINDS,
        )
        assertEquals(
            listOf(
                """{"kinds":[20,21,22,34235,34236],"limit":80}""",
                """{"kinds":[1],"limit":120}""",
            ),
            BitzTimelinePolicy.initialFilters(),
        )
        assertEquals(
            listOf(
                """{"kinds":[20,21,22,34235,34236],"limit":60,"until":99}""",
                """{"kinds":[1],"limit":150,"until":99}""",
            ),
            BitzTimelinePolicy.batchFilters(99),
        )
        // `until` is exclusive: cursor = oldest - 1.
        assertEquals(99, BitzTimelinePolicy.cursor(100))
    }

    @Test
    fun walkStopsAtFreshBudgetOrBatchBound() {
        assertTrue(BitzTimelinePolicy.shouldContinue(foundFreshMedia = 0, batchesIssued = 0))
        assertTrue(BitzTimelinePolicy.shouldContinue(foundFreshMedia = 9, batchesIssued = 5))
        // 10 fresh media = the next prepared scroll buffer → stop.
        assertFalse(BitzTimelinePolicy.shouldContinue(foundFreshMedia = 10, batchesIssued = 0))
        // Batch cap reached → stop even under budget.
        assertFalse(BitzTimelinePolicy.shouldContinue(foundFreshMedia = 0, batchesIssued = 6))
        assertFalse(BitzTimelinePolicy.shouldContinue(foundFreshMedia = 9, batchesIssued = 6))
    }

    @Test
    fun cursorMovesMonotonicallyBackwardOnly() {
        // A relay re-sending newer events must never pull the cursor forward.
        assertEquals(89, BitzTimelinePolicy.advanceCursor(oldestInBatch = 90, current = 100))
        assertEquals(90, BitzTimelinePolicy.advanceCursor(oldestInBatch = 120, current = 90))
        // Null batch (nothing arrived) holds the cursor.
        assertEquals(100, BitzTimelinePolicy.advanceCursor(oldestInBatch = null, current = 100))
    }

    @Test
    fun relayStallDetectorTerminatesUntilIgnoringRelays() {
        // Nothing fresh AND no cursor advance = relay ignoring `until`.
        assertTrue(BitzTimelinePolicy.relayStalled(oldestInBatch = 120, previousCursor = 100, freshCount = 0))
        assertTrue(BitzTimelinePolicy.relayStalled(oldestInBatch = null, previousCursor = 100, freshCount = 0))
        // Fresh ids or a real backwards move keep the walk alive.
        assertFalse(BitzTimelinePolicy.relayStalled(oldestInBatch = 90, previousCursor = 100, freshCount = 0))
        assertFalse(BitzTimelinePolicy.relayStalled(oldestInBatch = 120, previousCursor = 100, freshCount = 3))
    }

    @Test
    fun legacyTabWiresParseBackToTheDefault() {
        // W2 trending/zapped tabs were removed (legacy Flutter parity: 3
        // tabs). Persisted wires must not crash the parser on old installs.
        assertEquals(BitzModeSetting.DEFAULT, BitzModeSetting.parse("trending"))
        assertEquals(BitzModeSetting.DEFAULT, BitzModeSetting.parse("zapped"))
        assertEquals(BitzModeSetting.DEFAULT, BitzModeSetting.parse("bogus"))
        assertEquals(BitzModeSetting.EXPLORE, BitzModeSetting.parse("explore"))
    }
}
