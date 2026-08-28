package space.bitos.core.feed

import space.bitos.core.model.EventId
import space.bitos.core.model.NostrEvent
import space.bitos.core.model.NostrKinds
import space.bitos.core.model.Pubkey
import space.bitos.core.model.RelayUrl
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import space.bitos.core.publish.NoteComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepostParserTest {

    private val reposter = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
    private val originalAuthor = "e93fbf1000405bc8bb536a8ae37eebe349ebde8ecae3779ad3786def739aa301"
    private val relay = RelayUrl.parse("wss://relay.test")!!
    private val composer = NoteComposer(clock = { 1_710_000_000 })

    @Test
    fun resolvesEmbeddedRepostWithAttribution() = kotlinx.coroutines.runBlocking {
        // Build a verified original note, then embed its signed frame in a repost.
        val originalSigner = space.bitos.core.identity.DeterministicTestSigner(
            "4b1aa1a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d",
        )
        val original = composer.composeTextNote(originalSigner.publicKeyHex(), "original content here")!!
        val originalSig = originalSigner.sign(original.messageBytes())!!
        val originalFrame = composer.publishMessage(original, originalSig)!!
        // Extract the event JSON from the client frame.
        val originalJson = originalFrame.removePrefix("""["EVENT",""" ).removeSuffix("]")

        // The repost embeds the original in content (common client pattern).
        val repost = NostrEvent(
            id = EventId.parse("55".repeat(32))!!,
            pubkey = Pubkey.parse(reposter)!!,
            createdAt = 1_710_000_100,
            kind = NostrKinds.REPOST,
            tags = listOf(listOf("e", original.idHex), listOf("p", originalAuthor)),
            content = originalJson,
            signature = null,
            receivedFromRelay = relay,
        )
        val resolved = RepostParser.resolve(repost)
        assertNotNull(resolved)
        assertEquals(originalSigner.publicKeyHex(), resolved.first.pubkey.value)
        assertEquals("original content here", resolved.first.content)
        assertEquals(reposter, resolved.second) // attribution

        // FeedNote.from routes reposts: content from the original, repostedBy set.
        val note = FeedNote.from(repost)
        assertEquals("original content here", note.content)
        assertEquals(originalSigner.publicKeyHex(), note.pubkey)
        assertEquals(reposter, note.repostedBy)
    }

    @Test
    fun rejectsForgedEmbeddedEvent() {
        // Tampered embedded event: wrong ID — verification fails, nothing displays.
        val forged = """{"id":"${"0".repeat(64)}","pubkey":"$originalAuthor","created_at":1710000000,"kind":1,"tags":[],"content":"forged"}"""
        val repost = NostrEvent(
            id = EventId.parse("66".repeat(32))!!,
            pubkey = Pubkey.parse(reposter)!!,
            createdAt = 1_710_000_100,
            kind = NostrKinds.REPOST,
            tags = listOf(listOf("e", "0".repeat(64))),
            content = forged,
            signature = null,
            receivedFromRelay = relay,
        )
        assertNull(RepostParser.resolve(repost))
        // FeedNote renders it as invisible (protocol payload, empty content).
        val note = FeedNote.from(repost)
        assertTrue(note.isProtocolPayload)
        assertEquals("", note.content)
    }

    @Test
    fun emptyContentRepostYieldsNothing() {
        val repost = NostrEvent(
            id = EventId.parse("77".repeat(32))!!,
            pubkey = Pubkey.parse(reposter)!!,
            createdAt = 1_710_000_100,
            kind = NostrKinds.REPOST,
            tags = listOf(listOf("e", "0".repeat(64)), listOf("p", originalAuthor)),
            content = "",
            signature = null,
            receivedFromRelay = relay,
        )
        assertNull(RepostParser.resolve(repost))
    }
}
