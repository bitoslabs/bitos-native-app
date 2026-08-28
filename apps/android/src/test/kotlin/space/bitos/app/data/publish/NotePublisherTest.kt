package space.bitos.app.data.publish

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import space.bitos.app.data.relay.RelayConnectionState
import space.bitos.app.data.relay.RelayFrame
import space.bitos.app.data.relay.RelayPool
import space.bitos.app.data.relay.RelayTransport
import space.bitos.core.identity.DeterministicTestSigner
import space.bitos.core.model.RelayUrl
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Pow
import space.bitos.core.nostr.Sha256EventHasher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Publish-pipeline contract test: the outgoing frame must decode and verify
 * through the same codec that gates the feed, and OK receipts must drive
 * terminal state (PUB-001 note path / PUB-008 partial-ack semantics).
 */
class NotePublisherTest {

    private val relay = RelayUrl.parse("wss://relay.test")!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var transport: FakeRelayTransport
    private lateinit var pool: RelayPool
    private lateinit var publisher: NotePublisher

    @BeforeTest
    fun setUp() {
        transport = FakeRelayTransport(relay)
        pool = RelayPool(scope, listOf(relay)) { _, _ -> transport }
        publisher = NotePublisher(scope, pool, clock = { 1_710_000_000 }, ackTimeoutMs = 250)
    }

    @AfterTest
    fun tearDown() {
        publisher.dismiss()
        scope.cancel()
    }

    @Test
    fun sendsVerifiedFrameAndCompletesOnFirstAcceptance() = runBlocking {
        val signer = DeterministicTestSigner("0000000000000000000000000000000000000000000000000000000000000001")
        publisher.publish("hello publish pipeline", signer, listOf(relay))
        val state = withTimeout(20_000) { publisher.state.first { it.inFlightId != null } }

        // The frame lands on the transport shortly after the state flips
        // (state is committed before sendTo); wait for it instead of racing.
        withTimeout(20_000) {
            while (transport.sent.none { it.startsWith("""["EVENT",""" ) }) delay(10)
        }
        val frame = transport.sent.single { it.startsWith("""["EVENT",""" ) }
        val decoded = NostrEventCodec.decodeClientEventFrame(Sha256EventHasher, frame, relay)
        assertEquals(signer.publicKeyHex(), decoded.pubkey.value)
        assertEquals("hello publish pipeline", decoded.content)
        assertEquals(state.inFlightId, decoded.id.value)

        // Relay accepts -> PUBLISHED (one acceptance is success).
        transport.emit("""["OK","${decoded.id.value}",true,""]""")
        val done = withTimeout(20_000) { publisher.state.first { it.result == PublishResult.PUBLISHED } }
        assertTrue(done.receipts.any { it.accepted == true })
    }

    @Test
    fun surfacesRejectionReasons() = runBlocking {
        val signer = DeterministicTestSigner("0000000000000000000000000000000000000000000000000000000000000001")
        publisher.publish("will be rejected", signer, listOf(relay))
        withTimeout(20_000) { publisher.state.first { it.inFlightId != null } }
        val frame = transport.sent.single { it.startsWith("""["EVENT",""" ) }
        val id = NostrEventCodec.decodeClientEventFrame(Sha256EventHasher, frame, relay).id.value

        transport.emit("""["OK","$id",false,"blocked: duplicate"]""")
        val rejected = withTimeout(20_000) { publisher.state.first { it.result == PublishResult.REJECTED } }
        assertEquals("blocked: duplicate", rejected.receipts.first { it.accepted == false }.message)
    }

    @Test
    fun publishesVerifiedKind7Reaction() = runBlocking {
        val signer = DeterministicTestSigner("0000000000000000000000000000000000000000000000000000000000000001")
        val targetId = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a"
        val targetAuthor = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
        publisher.publishReactionWith(targetId, targetAuthor, { signer }, listOf(relay))
        withTimeout(20_000) { publisher.state.first { it.inFlightId != null } }

        val frame = transport.sent.single { it.startsWith("""["EVENT",""" ) }
        val decoded = NostrEventCodec.decodeClientEventFrame(Sha256EventHasher, frame, relay)
        assertEquals(7, decoded.kind)
        assertEquals("+", decoded.content)
        assertEquals(listOf(listOf("e", targetId), listOf("p", targetAuthor)), decoded.tags)

        transport.emit("""["OK","${decoded.id.value}",true,""]""")
        val done = withTimeout(20_000) { publisher.state.first { it.result == PublishResult.PUBLISHED } }
        assertTrue(done.receipts.any { it.accepted == true })
    }

    @Test
    fun publishesVerifiedKind22MediaNote() = runBlocking {
        val signer = DeterministicTestSigner("0000000000000000000000000000000000000000000000000000000000000001")
        val media = space.bitos.core.model.UploadedMedia(
            url = "https://cdn.example/v.mp4",
            sha256Hex = "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a",
            mimeType = "video/mp4",
            sizeBytes = 42_000,
            width = 1080,
            height = 1920,
        )
        publisher.publishMediaNote("my first video", media, { signer }, listOf(relay))
        withTimeout(20_000) { publisher.state.first { it.inFlightId != null } }

        withTimeout(20_000) {
            while (transport.sent.none { it.startsWith("""["EVENT",""" ) }) delay(10)
        }
        val frame = transport.sent.single { it.startsWith("""["EVENT",""" ) }
        val decoded = NostrEventCodec.decodeClientEventFrame(Sha256EventHasher, frame, relay)
        assertEquals(22, decoded.kind)
        assertEquals("my first video", decoded.content)
        val imeta = decoded.tags.first { it.first() == "imeta" }
        assertTrue(imeta.any { it == "url https://cdn.example/v.mp4" })
        assertTrue(imeta.any { it == "dim 1080x1920" })
        // The published note parses back through the feed's media extractor.
        val extracted = space.bitos.core.model.MediaMetadata.fromEvent(decoded)!!
        assertEquals("https://cdn.example/v.mp4", extracted.url)
        assertEquals("video/mp4", extracted.mimeType)
    }

    @Test
    fun signerRefusalNeverSends() = runBlocking {
        val refusing = object : space.bitos.core.identity.IdentitySigner {
            override fun signerKind() = space.bitos.core.identity.SignerKind.LOCAL_KEY
            override fun publicKeyHex() = "00".repeat(32)
            override suspend fun sign(message32: ByteArray): String? = null
        }
        publisher.publish("no signature", refusing, listOf(relay))
        val refused = withTimeout(20_000) { publisher.state.first { it.result != null } }
        assertEquals(PublishResult.SIGNING_REFUSED, refused.result)
        assertTrue(transport.sent.none { it.startsWith("""["EVENT",""" ) })
    }

    /**
     * APP-008 PoW path: a note mined over the publisher's template must
     * publish with the committed nonce tag, recompute its id through the
     * canonical codec (verified gate) and carry a signature that verifies.
     */
    @Test
    fun powPublishCarriesNonceTagIdAndSignature() = runBlocking {
        val signer = DeterministicTestSigner("0000000000000000000000000000000000000000000000000000000000000001")
        val content = "pow round trip"
        val template = space.bitos.core.publish.NoteComposer(clock = { 1_710_000_000 })
            .composeTextNote(signer.publicKeyHex(), content) ?: error("template")
        val target = 12
        val hit = Pow.mineChunk(
            Sha256EventHasher,
            template.pubkeyHex,
            template.createdAtSeconds,
            template.kind,
            template.tags,
            template.content,
            target,
            0,
            500_000,
        ) ?: error("mining hit")

        publisher.publishPowWith(content, hit.nonce, target, template.createdAtSeconds, { signer }, listOf(relay))
        withTimeout(20_000) {
            while (transport.sent.none { it.startsWith("""["EVENT",""" ) }) delay(10)
        }
        val frame = transport.sent.single { it.startsWith("""["EVENT",""" ) }
        val decoded = NostrEventCodec.decodeClientEventFrame(Sha256EventHasher, frame, relay)

        assertEquals(signer.publicKeyHex(), decoded.pubkey.value)
        assertEquals(listOf("nonce", hit.nonce.toString(), target.toString()), decoded.tags.single { it.firstOrNull() == "nonce" })
        assertTrue(Pow.difficulty(decoded.id.value) >= target, "mined id must meet the target")
        assertTrue(NostrEventCodec.verifySignature(Sha256EventHasher, decoded), "signature must verify over the pow id")
    }
}

private class FakeRelayTransport(private val relay: RelayUrl) : RelayTransport {
    private val mutableState = MutableStateFlow(RelayConnectionState.DISCONNECTED)
    private val mutableFrames = MutableSharedFlow<RelayFrame>(replay = 8, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val state: StateFlow<RelayConnectionState> = mutableState
    override val frames: SharedFlow<RelayFrame> = mutableFrames
    val sent = mutableListOf<String>()

    override fun connect() {
        mutableState.value = RelayConnectionState.CONNECTED
    }

    override fun send(message: String): Boolean {
        sent += message
        return true
    }

    override fun close(code: Int, reason: String) {
        mutableState.value = RelayConnectionState.DISCONNECTED
    }

    fun emit(message: String) {
        mutableFrames.tryEmit(RelayFrame(relay, message))
    }
}
