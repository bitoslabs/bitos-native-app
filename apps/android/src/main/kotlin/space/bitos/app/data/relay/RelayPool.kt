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
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher

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

    /**
     * Decode-once stage (performance audit Phase 2): ONE collector decodes
     * and verifies every raw frame, and EVENT-consuming stores collect
     * [verifiedFrames] instead of re-parsing [frames] independently — one
     * JSON parse + ID hash + BIP-340 per frame per process instead of one
     * per store. Replay mirrors [frames] so late subscribers catch up.
     */
    private val mutableVerifiedFrames = MutableSharedFlow<VerifiedPoolFrame>(
        replay = 32,
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val verifiedFrames: SharedFlow<VerifiedPoolFrame> = mutableVerifiedFrames

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
        // The shared trust gate. Hasher is the canonical object — the
        // verify-once outcome cache in the shared codec makes hasher
        // identity irrelevant across stores.
        scope.launch {
            frames.collect { frame ->
                val gated = space.bitos.app.diagnostics.PerfTrace.section(
                    space.bitos.app.diagnostics.PerfTrace.RELAY_DECODE,
                ) { decodeVerifiedFrame(frame) }
                if (gated != null) mutableVerifiedFrames.tryEmit(gated)
            }
        }
    }

    /**
     * Protocol trust gate for one frame: EOSE subscription ids pass
     * through; EVENT frames must decode and pass ID + BIP-340 verification
     * (non-negotiable principle 2 — unverified events never reach display
     * state). Malformed frames drop silently.
     */
    private fun decodeVerifiedFrame(frame: RelayFrame): VerifiedPoolFrame? {
        NostrEventCodec.relayEoseSubscriptionId(frame.message)?.let { subId ->
            return VerifiedPoolFrame.Eose(subscriptionId = subId, relay = frame.relay)
        }
        return runCatching {
            val decoded = NostrEventCodec.decodeRelayEventFrame(Sha256EventHasher, frame.message, frame.relay)
            if (!NostrEventCodec.verifySignature(Sha256EventHasher, decoded.event)) return null
            VerifiedPoolFrame.Verified(
                event = decoded.event,
                subscriptionId = decoded.subscriptionId,
                relay = frame.relay,
                message = frame.message,
            )
        }.getOrNull()
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

    /** Connected read relays at request time, used to aggregate EOSE safely. */
    fun connectedRelays(): Set<RelayUrl> = synchronized(lock) {
        relayOrder.filterTo(linkedSetOf()) {
            transports[it]?.state?.value == RelayConnectionState.CONNECTED
        }
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
