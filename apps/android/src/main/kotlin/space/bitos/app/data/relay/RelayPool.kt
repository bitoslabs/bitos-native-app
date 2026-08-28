package space.bitos.app.data.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
    transportFactory: (RelayUrl, () -> Unit) -> RelayTransport,
) {
    private val lock = Any()
    private val transports = mutableMapOf<RelayUrl, RelayTransport>()
    private val reconnectJobs = mutableMapOf<RelayUrl, Job>()
    private val attempts = mutableMapOf<RelayUrl, Int>()
    private val collected = mutableSetOf<RelayUrl>()

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

    val states: Map<RelayUrl, RelayConnectionState>
        get() = snapshot().mapValues { it.value.state.value }

    init {
        urls.forEach { relayUrl ->
            val transport = synchronized(lock) {
                transports.getOrPut(relayUrl) {
                    attempts[relayUrl] = 0
                    transportFactory(relayUrl) { scheduleReconnect(relayUrl) }
                }
            }
            if (synchronized(lock) { collected.add(relayUrl) }) {
                transport.frames
                    .onEach(mutableFrames::tryEmit)
                    .launchIn(scope)
            }
        }
    }

    fun start() = snapshot().values.forEach(RelayTransport::connect)

    fun broadcast(message: String) = snapshot().values.forEach { it.send(message) }

    /** Targeted send: only relays in [urls] receive the message (write-role routing). */
    fun sendTo(urls: List<RelayUrl>, message: String) {
        val targets = snapshot()
        urls.forEach { url -> targets[url]?.send(message) }
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
