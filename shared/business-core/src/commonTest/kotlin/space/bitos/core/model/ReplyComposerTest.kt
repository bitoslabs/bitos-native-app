package space.bitos.core.model

import space.bitos.core.feed.FeedNote
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.publish.NoteComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReplyComposerTest {

    private val author = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
    private val targetAuthor = "e93fbf1000405bc8bb536a8ae37eebe349ebde8ecae3779ad3786def739aa301"
    private val targetId = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
    private val composer = NoteComposer(clock = { 1_710_000_000 })

    @Test
    fun composesNip10ReplyWithMarkerAndRelayHint() {
        val reply = composer.composeReply(
            "  great post!  ", targetId, targetAuthor, author, relayHint = "wss://relay.damus.io",
        )!!
        assertEquals(NostrKinds.SHORT_TEXT_NOTE, reply.kind)
        assertEquals("great post!", reply.content)
        assertEquals(
            listOf(
                listOf("e", targetId, "wss://relay.damus.io", "reply"),
                listOf("p", targetAuthor),
            ),
            reply.tags,
        )
        // The committed ID recomputes through the codec with the reply tags.
        val expected = NostrEventCodec.computeId(
            Sha256EventHasher, author, 1_710_000_000, NostrKinds.SHORT_TEXT_NOTE, reply.tags, "great post!",
        )
        assertEquals(expected, reply.idHex)
    }

    @Test
    fun replyFrameRoundTripsThroughVerifiedCodec() {
        val reply = composer.composeReply("nice one", targetId, targetAuthor, author)!!
        val frame = composer.publishMessage(reply, "ef".repeat(64))!!
        val decoded = NostrEventCodec.decodeClientEventFrame(
            Sha256EventHasher, frame, RelayUrl.parse("wss://relay.test"),
        )
        assertEquals(reply.tags, decoded.tags)
        assertEquals("nice one", decoded.content)
        // The reply's replyTo resolves through the NIP-10 marker.
        val note = FeedNote.from(decoded)
        assertEquals(targetId, note.replyTo)
    }

    @Test
    fun rejectsInvalidReplies() {
        assertNull(composer.composeReply("", targetId, targetAuthor, author))
        assertNull(composer.composeReply("   ", targetId, targetAuthor, author))
        assertNull(composer.composeReply("x".repeat(NoteComposer.MAX_NOTE_LENGTH + 1), targetId, targetAuthor, author))
        assertNull(composer.composeReply("ok", "bad-id", targetAuthor, author))
        assertNull(composer.composeReply("ok", targetId, "zz", author))
    }
}
