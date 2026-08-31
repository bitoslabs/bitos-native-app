package space.bitos.core.model

import space.bitos.core.publish.NoteComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NIP-51 interest set (kind 30015 d=interest) — web hashtag-follows parity. */
class InterestSetTest {

    private val author = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
    private val composer = NoteComposer(clock = { 1_710_000_000 })

    private fun event(tags: List<List<String>>, createdAt: Long = 1_000, kind: Int = InterestSet.KIND) = NostrEvent(
        id = EventId.parse("33".repeat(32))!!,
        pubkey = Pubkey.parse("44".repeat(32))!!,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = "",
        signature = null,
        receivedFromRelay = null,
    )

    @Test
    fun composesNormalizedDedupedInterestSet() {
        val list = composer.composeInterestSet(author, listOf("#Bitcoin", "bitcoin", "lightning", "x", ""))!!
        assertEquals(InterestSet.KIND, list.kind)
        // d coordinate first; # prefix stripped, case-folded, deduped, invalid dropped.
        assertEquals(
            listOf(listOf("d", "interest"), listOf("t", "bitcoin"), listOf("t", "lightning")),
            list.tags,
        )
        assertEquals("", list.content)
    }

    @Test
    fun parsesOnlyVerifiedInterestShapes() {
        val good = event(listOf(listOf("d", "interest"), listOf("t", "bitcoin"), listOf("t", "nostr")))
        assertEquals(listOf("bitcoin", "nostr"), InterestSet.followedHashtags(good))
        // Wrong d coordinate, wrong kind, or hostile tags parse empty.
        val wrongD = event(listOf(listOf("d", "other"), listOf("t", "bitcoin")))
        assertEquals(emptyList(), InterestSet.followedHashtags(wrongD))
        val wrongKind = event(listOf(listOf("d", "interest")), kind = 30_023)
        assertEquals(emptyList(), InterestSet.followedHashtags(wrongKind))
        val hostile = event(
            listOf(listOf("d", "interest"), listOf("t", "has space"), listOf("t", "t".repeat(61))),
        )
        // Space and over-length tags fail the NIP-01 charset bound.
        assertTrue(InterestSet.followedHashtags(hostile).isEmpty())
        // Newest-wins replaceable head.
        val older = event(listOf(listOf("d", "interest"), listOf("t", "old")), createdAt = 100)
        val newer = event(listOf(listOf("d", "interest"), listOf("t", "new")), createdAt = 200)
        assertEquals(newer, InterestSet.newest(listOf(older, newer)))
    }

    @Test
    fun composerRefusesBadAuthor() {
        assertNull(composer.composeInterestSet("zz", listOf("bitcoin")))
    }
}
