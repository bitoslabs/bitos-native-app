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
 * Adapter-contract test for the NIP-50 search scopes (Bitz discovery/query
 * standard, web docs/SYSTEM.md): Bitz searches the standard NIP-68/NIP-71
 * media kinds only — never kind-1 text notes; Discover keeps its general
 * text+video kind set.
 */
class SearchRepositoryTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val relay = RelayUrl.parse("wss://relay.test")!!
    private lateinit var transport: FakeSearchTransport
    private lateinit var pool: RelayPool
    private lateinit var repository: SearchRepository

    @BeforeTest
    fun setUp() {
        transport = FakeSearchTransport()
        pool = RelayPool(scope, listOf(relay)) { _, _ -> transport }
        repository = SearchRepository(scope, pool)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun bitzScopeQueriesStandardMediaKindsOnly(): Unit = runBlocking {
        repository.search("lightning", SearchScope.BITZ_MEDIA)
        val request = awaitSearchRequest()
        assertTrue(
            request.contains("\"kinds\":[20,21,22,34235,34236],\"search\":\"lightning\",\"limit\":50"),
            request,
        )
        assertTrue(!request.contains("\"kinds\":[1]"), request)
    }

    @Test
    fun generalScopeKeepsTextAndVideoKinds(): Unit = runBlocking {
        repository.search("gm")
        val request = awaitSearchRequest()
        assertTrue(request.contains("\"kinds\":[1,21,22],\"search\":\"gm\",\"limit\":50"), request)
    }

    /** Waits out the shared 400 ms relay debounce for the first REQ. */
    private fun awaitSearchRequest(): String {
        var request: String? = null
        runBlocking {
            withTimeout(20_000) {
                while (request == null) {
                    request = transport.sent.lastOrNull { it.contains("bitos-search-") }
                    if (request == null) delay(10)
                }
            }
        }
        return request!!
    }

    private class FakeSearchTransport : RelayTransport {
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
    }
}
