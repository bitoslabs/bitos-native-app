package space.bitos.core.feed

import space.bitos.core.model.ProfileMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * APP-010 results fan-in: People (distinct authors by result count) and
 * Hashtags (top by occurrence) from verified results.
 */
class SearchResultsTest {

    private fun note(id: String, pubkey: String, vararg tags: String) = FeedNote(
        id = id, pubkey = pubkey, content = "c", createdAt = 1, kind = 1,
        replyTo = null, hashtags = tags.toList(), mentions = emptyList(),
        mediaUrls = emptyList(), isProtocolPayload = false,
    )

    private fun profile(pubkey: String, name: String, nip05: String? = null) = ProfileMetadata(
        pubkey = space.bitos.core.model.Pubkey.parse(pubkey)!!,
        name = name, displayName = null, about = null,
        picture = null, nip05 = nip05, lud16 = null,
    )

    @Test
    fun peopleRanksByResultCountWithProfileNames() {
        val a = "aa".repeat(32)
        val b = "bb".repeat(32)
        val notes = listOf(note("1", a), note("2", a), note("3", b))
        val people = SearchResults.people(notes, mapOf(a to profile(a, "Alice", "alice@bitos.space")))
        assertEquals(2, people.size)
        assertEquals("Alice", people[0].displayName)
        assertEquals("alice@bitos.space", people[0].nip05)
        assertEquals(2, people[0].noteCount)
        // Unknown author falls back to short pubkey.
        assertTrue(people[1].displayName.startsWith("bb"))
    }

    @Test
    fun peopleCapsAtBound() {
        val pubkeys = (0 until 30).map { it.toString(16).padStart(64, '0') }
        val notes = pubkeys.mapIndexed { index, key -> note("n$index", key) }
        assertEquals(SearchResults.MAX_PEOPLE, SearchResults.people(notes, emptyMap()).size)
    }

    @Test
    fun hashtagsCountCaseInsensitivelyAndRank() {
        val notes = listOf(
            note("1", "aa".repeat(32), "Bitcoin", "Lightning"),
            note("2", "bb".repeat(32), "bitcoin"),
            note("3", "cc".repeat(32), "lightning", "nostr"),
        )
        val hits = SearchResults.hashtags(notes)
        assertEquals(listOf("bitcoin" to 2, "lightning" to 2, "nostr" to 1), hits.map { it.tag to it.count })
    }

    @Test
    fun hashtagsDropMachineCoordinationTags() {
        // Web humanTags parity: `udal-*` swarm tags never reach the Hashtags
        // tab — they would crowd out human topics.
        val notes = listOf(
            note("1", "aa".repeat(32), "nostr", "udal-friend-aede0a98e7fd3ffef77db169c0ccaaa1"),
            note("2", "bb".repeat(32), "udal-peer-2348e984dab2c63dfbdab100aa1a3974"),
        )
        val hits = SearchResults.hashtags(notes)
        assertEquals(listOf("nostr" to 1), hits.map { it.tag to it.count })
    }

    @Test
    fun localSearchMatchesCaptionAndExactHashtag() {
        val target = note("1", "aa".repeat(32), "Bitcoin", "Lightning").copy(content = "Good morning, Nostr")
        assertTrue(SearchResults.matches(target, "morning"))
        assertTrue(SearchResults.matches(target, "#bitcoin"))
        assertTrue(!SearchResults.matches(target, "#bit"))
    }

    @Test
    fun queryTagClassifiesSingleHashtagTokens() {
        // NIP-50 leaves `#` undefined in search strings — the client must
        // classify `#tag` queries and recall them via the NIP-01 `#t` filter.
        assertEquals("laostr", SearchResults.queryTag("#laostr"))
        assertEquals("laostr", SearchResults.queryTag("  #LaoStr  "))
        assertEquals("bit_2", SearchResults.queryTag("#Bit_2"))
        assertEquals(null, SearchResults.queryTag("laostr"))
        assertEquals(null, SearchResults.queryTag("#"))
        assertEquals(null, SearchResults.queryTag("#two words"))
        assertEquals(null, SearchResults.queryTag("#no-hyphens"))
        assertEquals(null, SearchResults.queryTag("#" + "a".repeat(SearchResults.MAX_TAG_LENGTH + 1)))
    }

    @Test
    fun tagRequestBuildsBoundedNip01TagFilter() {
        val req = SearchResults.tagRequest("sub1", "laostr", listOf(1, 21, 22), 50)
        assertEquals("""["REQ","sub1",{"kinds":[1,21,22],"#t":["laostr"],"limit":50}]""", req)
        // Bounds: kinds filtered to valid 16-bit values (8 max), limit coerced.
        val bounded = SearchResults.tagRequest("sub2", "tag", listOf(1, 99_999, 22, 30, 40, 50, 60, 70, 80), 500)
        assertEquals("""["REQ","sub2",{"kinds":[1,22,30,40,50,60,70,80],"#t":["tag"],"limit":100}]""", bounded)
        // Only bounded single-tag tokens produce a REQ.
        assertEquals(null, SearchResults.tagRequest("sub3", "", listOf(1), 50))
        assertEquals(null, SearchResults.tagRequest("sub4", "a b", listOf(1), 50))
        assertEquals(null, SearchResults.tagRequest("sub5", "tag", emptyList(), 50))
    }

    @Test
    fun relaySearchRequestBuildsWebParityFilters() {
        // Free text: NIP-50 `search` (query as typed) + `#t` (lowercased
        // term doubles as the tag) + recent-sample fallback, OR'd in one REQ.
        val kinds = listOf(1, 20, 21, 22, 34235, 34236)
        assertEquals(
            """["REQ","s1",""" +
                """{"kinds":[1,20,21,22,34235,34236],"search":"LaoStr","limit":180},""" +
                """{"kinds":[1,20,21,22,34235,34236],"#t":["laostr"],"limit":180},""" +
                """{"kinds":[1,20,21,22,34235,34236],"limit":240}]""",
            SearchResults.relaySearchRequest("s1", "LaoStr", kinds),
        )
    }

    @Test
    fun relaySearchRequestSkipsNip50ForHashtagQueries() {
        // `#` is undefined in NIP-50 search strings: hashtag queries carry
        // only the indexed `#t` filter plus the fallback sample.
        assertEquals(
            """["REQ","s2",""" +
                """{"kinds":[1],"#t":["laostr"],"limit":180},""" +
                """{"kinds":[1],"limit":240}]""",
            SearchResults.relaySearchRequest("s2", " #LaoStr ", listOf(1)),
        )
    }

    @Test
    fun relaySearchRequestDropsTagFilterForMultiWordQueries() {
        // Multi-word terms cannot index as a single `t` tag; recall rides on
        // the NIP-50 filter plus the fallback sample.
        assertEquals(
            """["REQ","s3",""" +
                """{"kinds":[1],"search":"two words","limit":180},""" +
                """{"kinds":[1],"limit":240}]""",
            SearchResults.relaySearchRequest("s3", "two words", listOf(1)),
        )
    }

    @Test
    fun relaySearchRequestBoundsInputs() {
        assertEquals(null, SearchResults.relaySearchRequest("s4", "   ", listOf(1)))
        assertEquals(null, SearchResults.relaySearchRequest("s5", "q".repeat(SearchResults.MAX_QUERY_LENGTH + 1), listOf(1)))
        assertEquals(null, SearchResults.relaySearchRequest("s6", "laostr", emptyList()))
        assertEquals(null, SearchResults.relaySearchRequest("s7", "laostr", listOf(99_999)))
        // Invalid 16-bit kinds are dropped, not fatal.
        assertEquals(
            """["REQ","s8",""" +
                """{"kinds":[1],"search":"laostr","limit":180},""" +
                """{"kinds":[1],"#t":["laostr"],"limit":180},""" +
                """{"kinds":[1],"limit":240}]""",
            SearchResults.relaySearchRequest("s8", "laostr", listOf(1, 99_999)),
        )
    }
}
