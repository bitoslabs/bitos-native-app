package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Trending-sounds rank ("use this sound" Wave D foundation, plan §5 /
 * MST-047's 3-day half-life): counting `sound` tags over the feed window
 * IS the rank — no marketplace needed. Pure + common-tested so the
 * More/Discover rail renders identically on both platforms.
 */
class MemeSoundTrendingTest {

    private fun usage(
        url: String = "https://blossom.example/b/a.mp4",
        sha256: String = "a".repeat(64),
        at: Long,
        eventId: String = "e${at}",
        source: String? = "src-1",
        author: String? = "pub-1",
    ) = MemeSoundTrending.Usage(eventId, at, url, sha256, source, author)

    @Test
    fun decayHalvesPerHalfLife() {
        val now = 1_000_000L
        assertEquals(1.0, MemeSoundTrending.decay(now, now), 1e-9)
        assertEquals(0.5, MemeSoundTrending.decay(now - MemeSoundTrending.HALF_LIFE_MS, now), 1e-9)
        assertEquals(0.25, MemeSoundTrending.decay(now - 2 * MemeSoundTrending.HALF_LIFE_MS, now), 1e-9)
        // Future/junk timestamps never exceed 1 or go negative.
        assertEquals(1.0, MemeSoundTrending.decay(now + 5_000, now), 1e-9)
    }

    @Test
    fun rankAggregatesDecayedUsageAndDedups() {
        val now = 10 * 24 * 3_600_000L
        val rows = MemeSoundTrending.rank(
            listOf(
                usage(at = now, eventId = "e1"), // weight exactly 1
                usage(at = now, eventId = "e2"), // same sound, same weight
                usage(url = "https://blossom.example/b/other.mp4", sha256 = "b".repeat(64), at = now, source = null, author = null),
            ),
            nowMs = now,
        )
        assertEquals(2, rows.size)
        val top = rows.first()
        assertEquals("https://blossom.example/b/a.mp4", top.url)
        assertEquals(2, top.uses)
        assertEquals(2.0, top.score, 1e-9, "two fresh uses sum")
        // Provenance rides the row (feed chip + re-attach).
        assertEquals("src-1", top.sourceEventId)
    }

    @Test
    fun olderUsageWeighsLessAndOrderIsDeterministic() {
        val now = 100 * 24 * 3_600_000L
        val rows = MemeSoundTrending.rank(
            listOf(
                usage(url = "https://x.example/old.mp4", sha256 = "b".repeat(64), at = now - 8 * MemeSoundTrending.HALF_LIFE_MS),
                usage(url = "https://x.example/new.mp4", sha256 = "c".repeat(64), at = now - MemeSoundTrending.HALF_LIFE_MS),
            ),
            nowMs = now,
        )
        assertEquals("https://x.example/new.mp4", rows.first().url, "fresher wins")
        // Identical scores tie-break by url ascending — never input order.
        val tie = MemeSoundTrending.rank(
            listOf(
                usage(url = "https://x.example/z.mp4", sha256 = "d".repeat(64), at = now - 1_000L),
                usage(url = "https://x.example/a.mp4", sha256 = "e".repeat(64), at = now - 1_000L),
            ),
            nowMs = now,
        )
        assertEquals("https://x.example/a.mp4", tie.first().url)
    }

    @Test
    fun rankBoundsRowsAndIgnoresJunk() {
        val now = 1_000_000L
        val many = (0..30).map { index ->
            usage(url = "https://x.example/$index.mp4", sha256 = index.toString(16).padStart(64, '0'), at = now - 1_000L)
        }
        assertEquals(MemeSoundTrending.MAX_ROWS, MemeSoundTrending.rank(many, now).size)
        // Blank url / blank sha never rank (MemeSoundRules.sourceOf parity).
        assertTrue(
            MemeSoundTrending.rank(
                listOf(usage(url = "", at = now - 1L), usage(sha256 = "", at = now - 1L)),
                now,
            ).isEmpty(),
        )
    }

    @Test
    fun usageIsParsedFromPublishedTags() {
        val tags = MemeSoundRules.tagsFor(
            MemeSoundtrack(
                url = "https://blossom.example/b/a.mp4",
                sha256 = "a".repeat(64),
                durationMs = 9_000,
                sourceNoteId = "src-9",
                sourceAuthorPubkey = "pub-9",
                label = "Original sound",
            ),
        )
        val usage = MemeSoundTrending.usageFrom(tags, eventId = "note-1", createdAtMs = 123L)!!
        assertEquals("note-1", usage.eventId)
        assertEquals("https://blossom.example/b/a.mp4", usage.url)
        assertEquals("a".repeat(64), usage.sha256)
        assertEquals("src-9", usage.sourceEventId)
        assertEquals("pub-9", usage.authorPubkey)
        // A note without a sound tag contributes nothing.
        assertEquals(null, MemeSoundTrending.usageFrom(emptyList(), "note-2", 5L))
    }
}
