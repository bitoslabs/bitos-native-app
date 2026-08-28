package space.bitos.core.model

import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.publish.NoteComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FollowRepostComposerTest {

    private val author = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
    private val other = "e93fbf1000405bc8bb536a8ae37eebe349ebde8ecae3779ad3786def739aa301"
    private val g = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
    private val composer = NoteComposer(clock = { 1_710_000_000 })
    private val targetId = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"

    @Test
    fun composesFollowList() {
        val list = composer.composeFollowList(author, listOf(other, g, other, "invalid", author))!!
        assertEquals(NostrKinds.CONTACT_LIST, list.kind)
        assertEquals("", list.content)
        // Duplicates collapse, invalid hex and self-follow drop, order kept.
        assertEquals(listOf(listOf("p", other), listOf("p", g)), list.tags)

        val expected = NostrEventCodec.computeId(
            Sha256EventHasher, author, 1_710_000_000, NostrKinds.CONTACT_LIST, list.tags, "",
        )
        assertEquals(expected, list.idHex)
    }

    @Test
    fun followsAreBounded() {
        val many = (0 until ContactList.MAX_FOLLOWS + 20)
            .map { it.toString(16).padStart(64, '0') }
        val list = composer.composeFollowList(author, many)!!
        assertEquals(ContactList.MAX_FOLLOWS, list.tags.size)
    }

    @Test
    fun followListRoundTripsThroughVerifiedCodec() {
        val list = composer.composeFollowList(author, listOf(other))!!
        val frame = composer.publishMessage(list, "99".repeat(64))!!
        val decoded = NostrEventCodec.decodeClientEventFrame(
            Sha256EventHasher, frame, RelayUrl.parse("wss://relay.test"),
        )
        assertEquals(NostrKinds.CONTACT_LIST, decoded.kind)
        assertEquals(listOf(listOf("p", other)), decoded.tags)
        // The repository's ContactList projection extracts the same set.
        assertEquals(listOf(other), ContactList.followedPubkeys(decoded))
    }

    @Test
    fun composesRepost() {
        val repost = composer.composeRepost(targetId, other, author)!!
        assertEquals(NostrKinds.REPOST, repost.kind)
        assertEquals("", repost.content)
        assertEquals(listOf(listOf("e", targetId, "", ""), listOf("p", other)), repost.tags)

        val frame = composer.publishMessage(repost, "88".repeat(64))!!
        val decoded = NostrEventCodec.decodeClientEventFrame(
            Sha256EventHasher, frame, RelayUrl.parse("wss://relay.test"),
        )
        assertEquals(NostrKinds.REPOST, decoded.kind)
        assertEquals(repost.tags, decoded.tags)
    }

    @Test
    fun rejectsInvalidSocialCompositions() {
        assertNull(composer.composeFollowList("zz", listOf(other)))
        assertNull(composer.composeRepost("bad", other, author))
        assertNull(composer.composeRepost(targetId, "bad", author))
        assertNull(composer.composeRepost(targetId, other, "bad"))
    }
}
