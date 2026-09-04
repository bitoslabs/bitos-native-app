package space.bitos.app.data.feed

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
import space.bitos.core.model.RelayUrl
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Adapter-contract test for local Discover search: it filters normal verified
 * relay events and never creates a dedicated NIP-50 search subscription.
 */
class SearchRepositoryTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val relay = RelayUrl.parse("wss://relay.test")!!
    private lateinit var transport: FakeSearchTransport
    private lateinit var pool: RelayPool
    private lateinit var repository: SearchRepository

    @BeforeTest
    fun setUp() {
        transport = FakeSearchTransport(relay)
        pool = RelayPool(scope, listOf(relay)) { _, _ -> transport }
        repository = SearchRepository(scope, pool)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun searchesNormalVerifiedRelayEventsWithoutCreatingARequest(): Unit = runBlocking {
        repository.search("gm")
        delay(500) // shared debounce
        assertTrue(transport.sent.isEmpty())

        transport.emit(VALID_TEXT_NOTE_MESSAGE)
        val state = withTimeout(20_000) { repository.state.first { it.results.isNotEmpty() } }
        kotlin.test.assertEquals("gm from BitOS", state.results.single().content)
    }

    @Test
    fun ignoresNormalEventsThatDoNotMatchTheQuery(): Unit = runBlocking {
        repository.search("lightning")
        delay(500)
        transport.emit(VALID_TEXT_NOTE_MESSAGE)
        delay(100)
        assertTrue(repository.state.value.results.isEmpty())
    }

    private class FakeSearchTransport(private val relay: RelayUrl) : RelayTransport {
        private val mutableState = MutableStateFlow(RelayConnectionState.DISCONNECTED)
        private val mutableFrames = MutableSharedFlow<RelayFrame>(
            replay = 8,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val state: StateFlow<RelayConnectionState> = mutableState
        override val frames: SharedFlow<RelayFrame> = mutableFrames
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()

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

    private companion object {
        // Signed fixture from contracts/nostr/fixtures/verification-vectors.json.
        const val VALID_TEXT_NOTE_MESSAGE =
            """["EVENT","sub1",{"kind":1,"created_at":1710000000,"tags":[["t","bitcoin"]],"content":"gm from BitOS","pubkey":"2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001","id":"10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a","sig":"1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8"}]"""
    }
}
