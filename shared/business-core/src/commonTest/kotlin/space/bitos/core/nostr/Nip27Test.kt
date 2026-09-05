package space.bitos.core.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import space.bitos.core.feed.FeedNote
import space.bitos.core.identity.NostrKeyCodec

/**
 * NIP-27 rich-content tokenizer contract (unified feature spec §3.5,
 * APP-005): entities (bare / nostr: / @ forms), links, hashtags, inert
 * invalids, adjacency merging and the bridge JSON shape.
 */
class Nip27Test {

    private val pubkeyHex = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
    private val eventIdHex = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
    private val npub = NostrKeyCodec.npub(pubkeyHex)!!
    private val note1 = Nip27.encodeEntity("note", hexBytes(eventIdHex))

    private fun hexBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun plainTextIsASingleRun() {
        assertEquals(listOf(RichToken.Text("just words")), Nip27.tokenize("just words"))
    }

    @Test
    fun bareNpubBecomesProfileEntity() {
        val tokens = Nip27.tokenize("hello $npub bye")
        assertEquals(
            listOf(
                RichToken.Text("hello "),
                RichToken.Nostr(npub, RichToken.Entity.PROFILE, pubkeyHex),
                RichToken.Text(" bye"),
            ),
            tokens,
        )
    }

    @Test
    fun nostrPrefixedAndAtPrefixedFormsAreRecognized() {
        val tokens = Nip27.tokenize("nostr:$npub @$npub")
        val entities = tokens.filterIsInstance<RichToken.Nostr>()
        assertEquals(2, entities.size)
        assertEquals(2, entities.count { it.entity == RichToken.Entity.PROFILE && it.hex == pubkeyHex })
        // The separating space stays a text run between the two entities.
        assertTrue(tokens.filterIsInstance<RichToken.Text>().any { it.value == " " })
    }

    @Test
    fun note1AndNeventBecomeNoteEntities() {
        val nevent = Nip27.encodeEntity(
            "nevent",
            // TLV: type 0, len 32, id bytes.
            byteArrayOf(0, 0, 32) + hexBytes(eventIdHex),
        )
        val tokens = Nip27.tokenize("root $note1 relay $nevent end")
        val entities = tokens.filterIsInstance<RichToken.Nostr>()
        assertEquals(2, entities.size)
        assertEquals(RichToken.Nostr(note1, RichToken.Entity.NOTE, eventIdHex), entities[0])
        assertEquals(RichToken.Nostr(nevent, RichToken.Entity.NOTE, eventIdHex), entities[1])
    }

    @Test
    fun nprofilePubkeyComesFromTlvAndNaddrCarriesNoHex() {
        val nprofile = Nip27.encodeEntity(
            "nprofile",
            byteArrayOf(0, 0, 32) + hexBytes(pubkeyHex),
        )
        val naddr = Nip27.encodeEntity("naddr", byteArrayOf(0, 0, 3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()))
        val tokens = Nip27.tokenize("$nprofile $naddr")
        val entities = tokens.filterIsInstance<RichToken.Nostr>()
        assertEquals(RichToken.Nostr(nprofile, RichToken.Entity.PROFILE, pubkeyHex), entities[0])
        assertEquals(RichToken.Nostr(naddr, RichToken.Entity.ADDRESS, null), entities[1])
    }

    @Test
    fun invalidChecksumStaysInertText() {
        val broken = npub.dropLast(1) + if (npub.last() == 'q') 'p' else 'q'
        val tokens = Nip27.tokenize("see $broken ok")
        assertTrue(tokens.none { it is RichToken.Nostr }, "invalid entity must not become a token")
        assertTrue(tokens.any { it is RichToken.Text && it.value.contains(broken) })
    }

    @Test
    fun linksAndHashtagsTokenizeWithKeptWhitespace() {
        val tokens = Nip27.tokenize("check https://bitos.space/about now #bitcoin (and #lightning)")
        assertEquals(RichToken.Link("https://bitos.space/about"), tokens[1])
        assertEquals(RichToken.Text(" now "), tokens[2])
        assertEquals(RichToken.Hashtag("bitcoin"), tokens[3])
        assertTrue(tokens.contains(RichToken.Hashtag("lightning")))
        // '(' before '#' is preserved as text.
        assertTrue(tokens.filterIsInstance<RichToken.Text>().any { it.value.contains("(and ") })
    }

    @Test
    fun adjacentTextRunsMerge() {
        val tokens = Nip27.tokenize("$note1 tail")
        assertEquals(
            listOf(
                RichToken.Nostr(note1, RichToken.Entity.NOTE, eventIdHex),
                RichToken.Text(" tail"),
            ),
            tokens,
        )
    }

    @Test
    fun tokensJsonShapeIsStableForTheBridge() {
        val json = Nip27.tokensJson("hi $npub go https://x.example/a #tag")
        assertTrue(json.startsWith("[") && json.endsWith("]"))
        assertTrue(json.contains("\"k\":\"t\",\"v\":\"hi \""))
        assertTrue(json.contains("\"k\":\"n\",\"v\":\"$npub\",\"e\":\"profile\",\"x\":\"$pubkeyHex\""))
        assertTrue(json.contains("\"k\":\"l\",\"v\":\"https://x.example/a\""))
        assertTrue(json.contains("\"k\":\"h\",\"v\":\"tag\""))
    }
}

/**
 * APP-005 NIP-36 content-warning projection on FeedNote.
 */
class FeedNoteContentWarningTest {

    private fun note(tags: List<List<String>>, content: String = "x"): FeedNote {
        val event = space.bitos.core.model.NostrEvent(
            id = space.bitos.core.model.EventId.parse("0".repeat(64))!!,
            pubkey = space.bitos.core.model.Pubkey.parse("aa".repeat(32))!!,
            createdAt = 1_710_000_000L,
            kind = 1,
            tags = tags,
            content = content,
            signature = null,
            receivedFromRelay = null,
        )
        return FeedNote.from(event)
    }

    @Test
    fun contentWarningTagMarksTheNote() {
        assertTrue(note(listOf(listOf("content-warning"))).contentWarning)
        assertTrue(note(listOf(listOf("content-warning", "reason"))).contentWarning)
    }

    @Test
    fun labelFormMarksTheNote() {
        assertTrue(note(listOf(listOf("L", "content warning"))).contentWarning)
    }

    @Test
    fun ordinaryTagsDoNotMarkTheNote() {
        assertTrue(!note(emptyList()).contentWarning)
        assertTrue(!note(listOf(listOf("t", "content"))).contentWarning)
    }

    @Test
    fun legacySensitiveHashtagsFallBackToTheContentWarningGate() {
        assertTrue(note(listOf(listOf("t", "NSFW"))).contentWarning)
        assertTrue(note(emptyList(), "behind the scenes #Porn").contentWarning)
        assertTrue(note(emptyList(), "warning #nudity").contentWarning)
        assertTrue(note(emptyList(), "safe discussion of explicit permissions").contentWarning.not())
    }
}
