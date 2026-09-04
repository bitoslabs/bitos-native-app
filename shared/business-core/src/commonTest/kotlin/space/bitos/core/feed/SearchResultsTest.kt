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
}
