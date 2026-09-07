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

    @Test
    fun powNoteReproducesTheMinedIdByteForByte() {
        // The pow publish contract: mineChunk commits the nonce tag LAST, so
        // composeTextNoteWithPow must serialize the same tag order — the
        // published id is the mined id and keeps the difficulty claim.
        val baseTags = listOf(listOf("t", "pow"), listOf("p", pubkey))
        val target = 12
        val createdAt = 1_710_000_000L
        val mined = space.bitos.core.nostr.Pow.mineChunk(
            Sha256EventHasher, pubkey, createdAt, 1, baseTags, "pow note", target, 0, 500_000,
        )!!
        val note = composer.composeTextNoteWithPow(pubkey, "pow note", mined.nonce, target, createdAt, baseTags)!!
        assertEquals(mined.idHex, note.idHex)
        // NIP-01 shape: each tag is a list; the nonce tag rides last.
        assertEquals(baseTags + listOf(space.bitos.core.nostr.Pow.nonceTag(mined.nonce, target)), note.tags)
        assertTrue(space.bitos.core.nostr.Pow.difficulty(note.idHex) >= target)
    }

    @Test
    fun powCommentIsKind1111AndReproducesTheMinedIdByteForByte() {
        // Same contract for NIP-22 comments: the mined template carries the
        // commentTags base tags, the nonce tag rides last, and the published
        // kind-1111 id is the mined id.
        val baseTags = NoteComposer.commentTags(targetEventId = parentId, targetPubkey = author, targetKind = 22, parentEventId = null, parentPubkey = null, content = "great clip #bitz")!!
        val target = 10
        val createdAt = 1_710_000_000L
        val mined = space.bitos.core.nostr.Pow.mineChunk(
            Sha256EventHasher, pubkey, createdAt, 1_111, baseTags, "great clip #bitz", target, 0, 500_000,
        )!!
        val comment = composer.composeCommentWithPow(pubkey, "great clip #bitz", mined.nonce, target, createdAt, baseTags)!!
        assertEquals(1_111, comment.kind)
        assertEquals(mined.idHex, comment.idHex)
        assertEquals(baseTags + listOf(space.bitos.core.nostr.Pow.nonceTag(mined.nonce, target)), comment.tags)
        assertTrue(space.bitos.core.nostr.Pow.difficulty(comment.idHex) >= target)
    }

    @Test
    fun powCommentRejectsInvalidInputs() {
        val tags = listOf(listOf("E", parentId), listOf("P", author))
        assertNull(composer.composeCommentWithPow(pubkey, "x", 1, 8, 0, tags))
        assertNull(composer.composeCommentWithPow(pubkey, "  ", 1, 8, 1_700_000_000, tags))
        assertNull(composer.composeCommentWithPow("NOPE", "x", 1, 8, 1_700_000_000, tags))
    }

    private fun testMedia(): space.bitos.core.model.UploadedMedia =
        space.bitos.core.model.UploadedMedia(
            "https://cdn.example/m.mp4", "a".repeat(64), "video/mp4",
            1_048_576, 608, 1080, 30_000, null,
        )

    @Test
    fun powMemeVideoNoteReproducesTheMinedIdWithNonceLast() {
        // The meme PoW publish contract (mirror of the story path):
        // mineChunk hashes the exact kind-22 template; composing with the
        // mined nonce at the mining timestamp must reproduce that id.
        val minedAt = 1_710_000_000L
        val base = NoteComposer(clock = { minedAt }).composeMemeVideoNote(
            pubkey, "gm #nostr", "", null, portrait = true, testMedia(),
        )!!
        val target = 10
        val mined = space.bitos.core.nostr.Pow.mineChunk(
            Sha256EventHasher,
            base.pubkeyHex, base.createdAtSeconds, base.kind, base.tags, base.content,
            target, 0, 500_000,
        )!!
        val published = NoteComposer(clock = { 1_709_999_000 }).composeMemeVideoNoteWithPow(
            pubkey, "gm #nostr", "", null, portrait = true, testMedia(),
            nonce = mined.nonce, targetDifficulty = target, createdAtSeconds = minedAt,
        )!!
        assertEquals(mined.idHex, published.idHex)
        assertTrue(space.bitos.core.nostr.Pow.difficulty(published.idHex) >= target)
        // The nonce tag is APPENDED last, carrying its target (NIP-13).
        assertEquals(listOf("nonce", mined.nonce.toString(), target.toString()), published.tags.last())
        assertEquals(minedAt, published.createdAtSeconds)
        // Non-pow composition stays clock-driven and nonce-free.
        val plain = composer.composeMemeVideoNote(
            pubkey, "gm #nostr", "", null, portrait = true, testMedia(),
        )!!
        assertTrue(plain.tags.none { it.firstOrNull() == "nonce" })
        assertEquals(1_710_000_000, plain.createdAtSeconds)
    }

    @Test
    fun powMemePictureNoteReproducesTheMinedIdWithNonceLast() {
        val minedAt = 1_710_000_000L
        val media = space.bitos.core.model.UploadedMedia(
            "https://cdn.example/p.png", "b".repeat(64), "image/png",
            2048, 608, 1080,
        )
        val base = NoteComposer(clock = { minedAt }).composeMemePictureNote(
            pubkey, "pic #art", "", null, media,
        )!!
        val target = 8
        val mined = space.bitos.core.nostr.Pow.mineChunk(
            Sha256EventHasher,
            base.pubkeyHex, base.createdAtSeconds, base.kind, base.tags, base.content,
            target, 0, 500_000,
        )!!
        val published = NoteComposer(clock = { 1_709_999_000 }).composeMemePictureNoteWithPow(
            pubkey, "pic #art", "", null, media,
            nonce = mined.nonce, targetDifficulty = target, createdAtSeconds = minedAt,
        )!!
        assertEquals(mined.idHex, published.idHex)
        assertTrue(space.bitos.core.nostr.Pow.difficulty(published.idHex) >= target)
        assertEquals(listOf("nonce", mined.nonce.toString(), target.toString()), published.tags.last())
    }
}
