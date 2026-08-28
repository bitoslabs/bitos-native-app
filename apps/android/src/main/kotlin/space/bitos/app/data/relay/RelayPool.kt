package space.bitos.app.data.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import space.bitos.core.model.RelayUrl

/**
 * Relay pool (REL-001): owns websocket lifetime, reconnect policy and the
 * merged frame stream. Subscription fan-out (REQ/CLOSE) is broadcast to every
 * connected transport; exactly one pool manages sockets per app process.
 */
class RelayPool(
    private val scope: CoroutineScope,
    urls: List<RelayUrl>,
    private val transportFactory: (RelayUrl, () -> Unit) -> RelayTransport,
) {
    private val lock = Any()
    private val transports = mutableMapOf<RelayUrl, RelayTransport>()
    private val relayOrder = mutableListOf<RelayUrl>()
    private val reconnectJobs = mutableMapOf<RelayUrl, Job>()
    private val attempts = mutableMapOf<RelayUrl, Int>()
    private val collected = mutableSetOf<RelayUrl>()

    private val mutableStates = MutableStateFlow<Map<RelayUrl, RelayConnectionState>>(emptyMap())

    // A small replay window de-races subscription timing (collectors are
    // launched asynchronously): a frame arriving between launch and collect
    // is still delivered. Subscribers dedupe by event id, so replaying relay
    // frames to late subscribers is harmless.
    private val mutableFrames = MutableSharedFlow<RelayFrame>(
        replay = 32,
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<RelayFrame> = mutableFrames

    /** Live per-relay connection states (status dots in the relays manager). */
    val statesFlow: StateFlow<Map<RelayUrl, RelayConnectionState>> = mutableStates.asStateFlow()

    val states: Map<RelayUrl, RelayConnectionState>
        get() = snapshot().mapValues { it.value.state.value }

    private var started = false

    init {
        urls.forEach { url ->
            relayOrder += url
            install(url)
        }
    }

    fun start() {
        started = true
        snapshot().values.forEach(RelayTransport::connect)
    }

    /**
     * Adds a relay to the pool (relays manager): installs the transport,
     * merges its frames/states and connects immediately when the pool is
     * already running. Idempotent.
     */
    fun add(url: RelayUrl) {
        synchronized(lock) {
            if (url !in relayOrder) relayOrder += url
        }
        install(url)
    }

    /** Removes a relay: cancels reconnects, closes the socket, drops state. */
    fun remove(url: RelayUrl) {
        val transport = synchronized(lock) {
            reconnectJobs.remove(url)?.cancel()
            attempts.remove(url)
            collected.remove(url)
            relayOrder.remove(url)
            transports.remove(url)
        }
        transport?.close()
        mutableStates.value = mutableStates.value - url
    }

    fun broadcast(message: String) = snapshot().values.forEach { it.send(message) }

    /** Targeted send: only relays in [urls] receive the message (write-role routing). */
    fun sendTo(urls: List<RelayUrl>, message: String) {
        val targets = snapshot()
        urls.forEach { url -> targets[url]?.send(message) }
    }

    /** First configured connected relay, used for latency-sensitive reads. */
    fun primaryRelay(): RelayUrl? = synchronized(lock) {
        relayOrder.firstOrNull { transports[it]?.state?.value == RelayConnectionState.CONNECTED }
    }

    /** Configured relays excluding [primary], preserving pool order. */
    fun fallbackRelays(primary: RelayUrl?): List<RelayUrl> = synchronized(lock) {
        relayOrder.filter { it != primary && it in transports }
    }

    fun shutdown() {
        val current = snapshot()
        synchronized(lock) {
            reconnectJobs.values.forEach(Job::cancel)
            reconnectJobs.clear()
            transports.clear()
            collected.clear()
        }
        current.values.forEach { it.close() }
    }

    /** Installs + optionally connects one relay transport (init and [add]). */
    private fun install(url: RelayUrl) {
        val transport = synchronized(lock) {
            transports.getOrPut(url) {
                attempts[url] = 0
                transportFactory(url) { scheduleReconnect(url) }
            }
        }
        if (synchronized(lock) { collected.add(url) }) {
            transport.frames
                .onEach(mutableFrames::tryEmit)
                .launchIn(scope)
            transport.state
                .onEach { state -> mutableStates.value = mutableStates.value + (url to state) }
                .launchIn(scope)
            mutableStates.value = mutableStates.value + (url to transport.state.value)
        }
        if (started) transport.connect()
    }

    private fun scheduleReconnect(relayUrl: RelayUrl) {
        val attempt = (attempts[relayUrl] ?: 0) + 1
        attempts[relayUrl] = attempt
        synchronized(lock) { reconnectJobs.remove(relayUrl) }?.cancel()
        val job = scope.launch {
            delay(reconnectDelayMillis(attempt))
            snapshot()[relayUrl]?.connect()
        }
        synchronized(lock) { reconnectJobs[relayUrl] = job }
    }

    private fun snapshot(): Map<RelayUrl, RelayTransport> = synchronized(lock) { transports.toMap() }
}
