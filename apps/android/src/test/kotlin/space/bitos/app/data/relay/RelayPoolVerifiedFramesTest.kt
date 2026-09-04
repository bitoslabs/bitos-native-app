package space.bitos.app.data.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import space.bitos.core.model.RelayUrl
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adapter-contract test for the pool's decode-once stage (audit Phase 2):
 * every store consumes [RelayPool.verifiedFrames], so the stage must deliver
 * ID-verified, signature-verified EVENT frames plus EOSE ids, and drop junk
 * — exactly once per frame, no matter how many stores subscribe.
 */
class RelayPoolVerifiedFramesTest {

    private val hasher = Sha256EventHasher
    private val relay = RelayUrl.parse("wss://relay.test")!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var transport: FakeRelayTransport
    private lateinit var pool: RelayPool

    @BeforeTest
    fun setUp() {
        transport = FakeRelayTransport(relay)
        pool = RelayPool(scope, listOf(relay)) { _, _ -> transport }
    }

    @AfterTest
    fun tearDown() {
        pool.shutdown()
        scope.cancel()
    }

    @Test
    fun verifiesSignedFramesAndCarriesSubscriptionId() = runBlocking {
        val events = CopyOnWriteArrayList<VerifiedPoolFrame.Verified>()
        val job = scope.launch { pool.verifiedFrames.collect { gated -> (gated as? VerifiedPoolFrame.Verified)?.let(events::add) } }
        pool.start()
        transport.emit(VALID_TEXT_NOTE_MESSAGE)
        withTimeout(20_000) {
            while (events.isEmpty()) kotlinx.coroutines.delay(10)
        }
        val gated = events.single()
        assertEquals("sub1", gated.subscriptionId)
        assertEquals(relay, gated.relay)
        assertTrue(NostrEventCodec.verifySignature(hasher, gated.event))
        job.cancel()
    }

    @Test
    fun deliversEoseAndDropsJunk() = runBlocking {
        val received = CopyOnWriteArrayList<VerifiedPoolFrame>()
        val job = scope.launch { pool.verifiedFrames.collect { received.add(it) } }
        pool.start()
        transport.emit("""["NOTICE","hello"]""")
        transport.emit("""["EOSE","bitos-older-3"]""")
        transport.emit("""["EVENT","sub1",{"kind":1}]""") // malformed event
        withTimeout(20_000) {
            pool.verifiedFrames.first { it is VerifiedPoolFrame.Eose }
        }
        kotlinx.coroutines.delay(100) // let any junk that would slip through arrive
        assertTrue(received.none { it is VerifiedPoolFrame.Verified })
        val eose = received.single() as VerifiedPoolFrame.Eose
        assertEquals("bitos-older-3", eose.subscriptionId)
        job.cancel()
    }

    @Test
    fun verifiesEachFrameOnceRegardlessOfStoreCount() = runBlocking {
        // Two independent store-style subscribers; both see the same verified
        // value while the frame was decoded exactly once upstream.
        val first = CopyOnWriteArrayList<VerifiedPoolFrame>()
        val second = CopyOnWriteArrayList<VerifiedPoolFrame>()
        val jobA = scope.launch { pool.verifiedFrames.collect { first.add(it) } }
        val jobB = scope.launch { pool.verifiedFrames.collect { second.add(it) } }
        pool.start()
        transport.emit(VALID_TEXT_NOTE_MESSAGE)
        withTimeout(20_000) {
            pool.verifiedFrames.first { it is VerifiedPoolFrame.Verified }
        }
        kotlinx.coroutines.delay(100)
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        val a = assertIs<VerifiedPoolFrame.Verified>(first[0])
        val b = assertIs<VerifiedPoolFrame.Verified>(second[0])
        assertEquals(a.event.id, b.event.id)
        jobA.cancel()
        jobB.cancel()
    }

    private class FakeRelayTransport(private val relayUrl: RelayUrl) : RelayTransport {
        private val mutableState = MutableStateFlow(RelayConnectionState.DISCONNECTED)
        private val mutableFrames = MutableSharedFlow<RelayFrame>(
            replay = 8,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val state: StateFlow<RelayConnectionState> = mutableState
        override val frames: SharedFlow<RelayFrame> = mutableFrames
        val sent = CopyOnWriteArrayList<String>()

        override fun connect() {
            mutableState.value = RelayConnectionState.CONNECTED
        }

        override fun send(message: String): Boolean {
            sent.add(message)
            return true
        }

        override fun close(code: Int, reason: String) {
            mutableState.value = RelayConnectionState.DISCONNECTED
        }

        fun emit(message: String) {
            mutableFrames.tryEmit(RelayFrame(relayUrl, message))
        }
    }

    private companion object {
        // Verbatim signed relay frame from contracts/nostr/fixtures.
        const val VALID_TEXT_NOTE_MESSAGE =
            """["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"gm from BitOS","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}]"""
    }
}
