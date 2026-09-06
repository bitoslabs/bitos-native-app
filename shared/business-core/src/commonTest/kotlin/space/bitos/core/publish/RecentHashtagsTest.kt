package space.bitos.core.publish

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recent-hashtags ledger (composer + meme details "Recent" chips): normalize
 * shape, merge recency, bounded + junk-tolerant versioned JSON, and the
 * caption scan the publish paths record from.
 */
class RecentHashtagsTest {

    @Test
    fun normalizeStripsHashAndRejectsJunk() {
        assertEquals("meme", RecentHashtags.normalize("#Meme"))
        assertEquals("ab", RecentHashtags.normalize("AB"))
        assertEquals("nostr", RecentHashtags.normalize(" nostr "))
        assertEquals(null, RecentHashtags.normalize("#x"))          // too short
        assertEquals(null, RecentHashtags.normalize("#has way"))    // whitespace
        assertEquals(null, RecentHashtags.normalize("#tag!"))       // punctuation
        assertEquals(null, RecentHashtags.normalize(""))
    }

    @Test
    fun mergeBumpsToNewestAndCaps() {
        val seed = listOf(
            RecentHashtags.Entry("art", 10),
            RecentHashtags.Entry("bitz", 20),
            RecentHashtags.Entry("code", 30),
        )
        val merged = RecentHashtags.merge(seed, listOf("#bitz", "fresh"), nowMs = 100)
        // bitz bumped to front with the fresh stamp; unknown "fresh" joins it.
        assertEquals(listOf("bitz", "fresh", "code", "art"), merged.map { it.tag })
        assertEquals(100L, merged.first { it.tag == "bitz" }.usedAtMs)
        // Cap holds at MAX_ENTRIES.
        val many = RecentHashtags.merge(
            emptyList(),
            (1..80).map { "tag$it" },
            nowMs = 5,
        )
        assertEquals(RecentHashtags.MAX_ENTRIES, many.size)
    }

    @Test
    fun suggestionsExcludeWhatThePostAlreadyCarries() {
        val entries = listOf("art", "bitz", "code").mapIndexed { i, t -> RecentHashtags.Entry(t, 30L - i) }
        assertEquals(listOf("art", "code"), RecentHashtags.suggestions(entries, exclude = setOf("#Bitz")))
        assertTrue(RecentHashtags.suggestions(entries, exclude = setOf("art", "bitz", "code")).isEmpty())
    }

    @Test
    fun hashtagsInScansCaptions() {
        assertEquals(
            listOf("meme", "nostr"),
            RecentHashtags.hashtagsIn("loving this #meme on #Nostr #meme #no!"),
        )
        // @mentions and bare words never register.
        assertTrue(RecentHashtags.hashtagsIn("@alice plain text").isEmpty())
    }

    @Test
    fun versionedJsonRoundTripsAndDropsJunk() {
        val entries = RecentHashtags.merge(emptyList(), listOf("meme", "nostr"), nowMs = 42)
        val json = RecentHashtags.toJson(entries)
        assertEquals(entries, RecentHashtags.fromJson(json))
        // Wrong version / malformed / oversized input all decode safely.
        assertTrue(RecentHashtags.fromJson("""{"v":2,"entries":[]}""").isEmpty())
        assertTrue(RecentHashtags.fromJson("not json").isEmpty())
        assertTrue(RecentHashtags.fromJson(null).isEmpty())
        val junk = """
            {"v":1,"entries":[{"t":"ok","u":7},{"t":"#bad tag","u":8},{"t":"missingu"},[],{"t":"fine","u":"x"}]}
        """.trimIndent()
        assertEquals(listOf("ok"), RecentHashtags.fromJson(junk).map { it.tag })
    }
}
