package space.bitos.core.publish

import space.bitos.core.model.RelayUrl
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteComposerTest {

    private val composer = NoteComposer(clock = { 1_710_000_000 })
    private val pubkey = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"

    @Test
    fun composesIdVerifiedNote() {
        val note = composer.composeTextNote(pubkey, "  hello from the composer  \n")!!
        assertEquals("hello from the composer", note.content) // trimmed
        assertEquals(1_710_000_000, note.createdAtSeconds)

        // The committed ID must match an independent codec recompute.
        val expected = NostrEventCodec.computeId(
            Sha256EventHasher, pubkey, 1_710_000_000, 1, emptyList(), "hello from the composer",
        )
        assertEquals(expected, note.idHex)
        assertEquals(32, note.messageBytes().size)
    }

    @Test
    fun rejectsUnboundedContent() {
        assertNull(composer.composeTextNote(pubkey, "   "))
        assertNull(composer.composeTextNote(pubkey, ""))
        assertNull(composer.composeTextNote(pubkey, "x".repeat(NoteComposer.MAX_NOTE_LENGTH + 1)))
    }

    @Test
    fun publishMessageRoundTripsThroughTheVerifiedCodec() {
        val note = composer.composeTextNote(pubkey, "line1\nline2 \"quoted\"")!!
        val signature = "ab".repeat(64)
        val frame = composer.publishMessage(note, signature)!!
        assertTrue(frame.startsWith("""["EVENT",{"""))

        // Full round trip: the frame we send must decode + verify through the
        // same pipeline that gates the feed — our own note passes the gate.
        val relay = RelayUrl.parse("wss://relay.test")!!
        val decoded = NostrEventCodec.decodeClientEventFrame(Sha256EventHasher, frame, relay)
        assertEquals(note.idHex, decoded.id.value)
        assertEquals(note.content, decoded.content)
        assertEquals(signature, decoded.signature)

        // Malformed signatures are refused before anything is sent.
        assertNull(composer.publishMessage(note, "short"))
        assertNull(composer.publishMessage(note, "Z".repeat(128)))
    }

    @Test
    fun parsesOkReceipts() {
        val id = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
        val accepted = NoteComposer.parseOkMessage("""["OK","$id",true,""]""")!!
        assertTrue(accepted.accepted)
        assertEquals(id, accepted.eventId)

        val rejected = NoteComposer.parseOkMessage("""["OK","$id",false,"duplicate: already have this event"]""")!!
        assertEquals(false, rejected.accepted)
        assertEquals("duplicate: already have this event", rejected.message)

        assertNull(NoteComposer.parseOkMessage("""["NOTICE","x"]"""))
        assertNull(NoteComposer.parseOkMessage("""["OK","not-hex",true,""]"""))
        assertNull(NoteComposer.parseOkMessage("""["OK","$id","yes",""]"""))
        assertNull(NoteComposer.parseOkMessage("not json"))
    }

    // ── APP-009 reply tags (legacy `publishReply` / web feed.reply) ────

    private val author = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
    private val rootId = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
    private val parentId = "6bbba7020543b6d2fbd740a5a387cd92054716342d2b6389692fec5257f5e7fd"

    @Test
    fun replyTagsAlwaysEmitBothMarkers() {
        // Root reply (root == target): the id repeats in both markers.
        val tags = NoteComposer.replyTags(rootId, rootId, author, emptyList(), "gm")!!
        assertEquals(listOf("e", rootId, "", "root"), tags[0])
        assertEquals(listOf("e", rootId, "", "reply"), tags[1])
        assertEquals(listOf("p", author), tags[2])
    }

    @Test
    fun replyTagsCarryParticipantsAndContentEntities() {
        val participant = "aa".repeat(32)
        val inlineMention = "bb".repeat(32)
        val npub = space.bitos.core.identity.NostrKeyCodec.npub(inlineMention)!!
        val tags = NoteComposer.replyTags(
            rootEventId = rootId,
            targetEventId = parentId,
            targetPubkey = author,
            targetPTags = listOf(participant, "not-hex", participant),
            content = "gm #bitcoin nostr:$npub",
        )!!
        assertEquals(listOf("e", rootId, "", "root"), tags[0])
        assertEquals(listOf("e", parentId, "", "reply"), tags[1])
        // Author first, then the target's p-tags, deduped; non-hex dropped.
        assertEquals(listOf("p", author), tags[2])
        assertEquals(listOf("p", participant), tags[3])
        // Hashtag + NIP-27 entity tags from the content ride after.
        assertTrue(tags.contains(listOf("t", "bitcoin")))
        assertTrue(tags.contains(listOf("p", inlineMention)))
    }

    @Test
    fun replyTagsDedupeContentEntitiesAgainstMarkersAndCapParticipants() {
        // A hostile target with many p-tags: participants cap at 16.
        val many = (1..40).map { it.toString(16).padStart(64, '0') }
        val tags = NoteComposer.replyTags(rootId, parentId, author, many, "#gm")!!
        assertEquals(NoteComposer.MAX_REPLY_PARTICIPANTS, tags.count { it[0] == "p" })
        // Content hashtag dedupes by tag; markers stay first two.
        assertEquals("e", tags[0][0])
        assertEquals("e", tags[1][0])
        assertTrue(tags.contains(listOf("t", "gm")))
    }

    @Test
    fun replyTagsRejectInvalidIds() {
        assertNull(NoteComposer.replyTags("nope", parentId, author, emptyList(), "x"))
        assertNull(NoteComposer.replyTags(rootId, "nope", author, emptyList(), "x"))
        assertNull(NoteComposer.replyTags(rootId, parentId, "NOPE", emptyList(), "x"))
    }
}
