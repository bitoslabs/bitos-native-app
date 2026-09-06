package space.bitos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowerIndexTest {

    private val account = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
    private val followerA = "e93fbf1000405bc8bb536a8ae37eebe349ebde8ecae3779ad3786def739aa301"
    private val followerB = "aa".repeat(32)

    private fun contactEvent(
        author: String,
        pTags: List<String>,
        createdAt: Long = 1_000,
        kind: Int = NostrKinds.CONTACT_LIST,
    ) = NostrEvent(
        id = EventId.parse("33".repeat(32))!!,
        pubkey = Pubkey.parse(author)!!,
        createdAt = createdAt,
        kind = kind,
        tags = pTags.map { listOf("p", it) },
        content = "",
        signature = null,
        receivedFromRelay = null,
    )

    @Test
    fun addsFollowersFromForeignContactLists() {
        val index = FollowerIndex()
        assertTrue(index.absorb(contactEvent(followerA, listOf(account)), account))
        assertTrue(index.absorb(contactEvent(followerB, listOf(account, followerA)), account))
        assertEquals(2, index.count())
        assertTrue(index.contains(followerA))
        assertTrue(index.contains(followerB))
    }

    @Test
    fun newestHeadWinsAndUnfollowRemoves() {
        val index = FollowerIndex()
        index.absorb(contactEvent(followerA, listOf(account), createdAt = 1_000), account)

        // Stale relay copy: older head must not change the projection.
        assertFalse(index.absorb(contactEvent(followerA, emptyList(), createdAt = 900), account))
        assertTrue(index.contains(followerA))

        // Newer head without our p-tag is an unfollow.
        assertTrue(index.absorb(contactEvent(followerA, listOf(followerB), createdAt = 2_000), account))
        assertFalse(index.contains(followerA))
        assertEquals(0, index.count())

        // A still-newer head that re-adds us restores the follower.
        assertTrue(index.absorb(contactEvent(followerA, listOf(account), createdAt = 3_000), account))
        assertEquals(1, index.count())
    }

    @Test
    fun ignoresOwnContactListAndWrongKinds() {
        val index = FollowerIndex()
        // The account's own kind-3 is the following list, not a follower.
        assertFalse(index.absorb(contactEvent(account, listOf(followerA)), account))
        // Non-contact-list events never project into followers.
        assertFalse(index.absorb(contactEvent(followerA, listOf(account), kind = NostrKinds.SHORT_TEXT_NOTE), account))
        assertEquals(0, index.count())
    }

    @Test
    fun boundsHostileFanIn() {
        val index = FollowerIndex(max = 3)
        val others = listOf(followerA, followerB, "bb".repeat(32), "cc".repeat(32))
        others.forEach { author ->
            index.absorb(contactEvent(author, listOf(account)), account)
        }
        assertEquals(3, index.count())
        // The bound only caps NEW additions; a tracked follower's newer head
        // still reconciles.
        assertTrue(index.absorb(contactEvent(followerA, emptyList(), createdAt = 2_000), account))
        assertFalse(index.contains(followerA))
    }

    @Test
    fun clearResetsProjection() {
        val index = FollowerIndex()
        index.absorb(contactEvent(followerA, listOf(account)), account)
        index.clear()
        assertEquals(0, index.count())
        assertEquals(emptyList(), index.pubkeys())
    }
}
