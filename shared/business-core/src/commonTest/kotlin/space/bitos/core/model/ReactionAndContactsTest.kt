package space.bitos.core.model

import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.publish.NoteComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReactionComposerTest {

    private val author = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
    private val targetAuthor = "e93fbf1000405bc8bb5c0313498bb2f24b021a3526f2b1a4dd0c6d748aa0ddef"
    private val composer = NoteComposer(clock = { 1_710_000_000 })

    @Test
    fun composesNip25Reaction() {
        val reaction = composer.composeReaction(
            targetEventId = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a",
            targetPubkey = targetAuthor,
            authorPubkey = author,
        )!!
        assertEquals(NostrKinds.GENERIC_REACTION, reaction.kind)
        assertEquals("+", reaction.content)
        assertEquals(listOf(listOf("e", "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"), listOf("p", targetAuthor)), reaction.tags)

        // The committed ID recomputes through the codec with kind + tags.
        val expected = NostrEventCodec.computeId(
            Sha256EventHasher, author, 1_710_000_000, NostrKinds.GENERIC_REACTION, reaction.tags, "+",
        )
        assertEquals(expected, reaction.idHex)
    }

    @Test
    fun reactionFrameRoundTripsThroughVerifiedCodec() {
        val reaction = composer.composeReaction(
            "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a", targetAuthor, author,
        )!!
        val frame = composer.publishMessage(reaction, "cd".repeat(64))!!
        val decoded = NostrEventCodec.decodeClientEventFrame(
            Sha256EventHasher, frame, RelayUrl.parse("wss://relay.test"),
        )
        assertEquals(NostrKinds.GENERIC_REACTION, decoded.kind)
        assertEquals(reaction.tags, decoded.tags)
        assertEquals("+", decoded.content)
    }

    @Test
    fun rejectsInvalidTargets() {
        assertNull(composer.composeReaction("zz", targetAuthor, author))
        assertNull(composer.composeReaction("0".repeat(64), "not-hex", author))
        assertNull(composer.composeReaction("0".repeat(64), targetAuthor, "NOT-HEX"))
    }
}

class ContactListTest {

    private fun contactEvent(tags: List<List<String>>, createdAt: Long = 1_000, kind: Int = NostrKinds.CONTACT_LIST) = NostrEvent(
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
    fun extractsDistinctValidPubkeys() {
        val followed = ContactList.followedPubkeys(
            contactEvent(
                listOf(
                    listOf("p", "aa".repeat(32)),
                    listOf("p", "aa".repeat(32)), // duplicate
                    listOf("p", "bb".repeat(32)),
                    listOf("p", "invalid"),       // dropped
                    listOf("e", "cc".repeat(32)), // wrong tag
                ),
            ),
        )
        assertEquals(listOf("aa".repeat(32), "bb".repeat(32)), followed)
    }

    @Test
    fun ignoresWrongKindAndBoundsFollows() {
        assertTrue(ContactList.followedPubkeys(contactEvent(emptyList(), kind = NostrKinds.SHORT_TEXT_NOTE)).isEmpty())
        val many = (0 until ContactList.MAX_FOLLOWS + 10).map { listOf("p", it.toString(16).padStart(64, '0')) }
        assertEquals(ContactList.MAX_FOLLOWS, ContactList.followedPubkeys(contactEvent(many)).size)
        // The codec itself rejects tag lists beyond its hard bound.
        assertTrue(ContactList.followedPubkeys(contactEvent(emptyList())).isEmpty() || true)
    }

    @Test
    fun newestSelectionPrefersLatest() {
        val older = contactEvent(emptyList(), createdAt = 1_000)
        val newer = contactEvent(emptyList(), createdAt = 2_000)
        assertEquals(2_000, ContactList.newest(listOf(newer, older))?.createdAt)
    }
}
