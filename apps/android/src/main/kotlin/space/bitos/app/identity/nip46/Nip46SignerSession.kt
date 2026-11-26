package space.bitos.app.identity.nip46

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import space.bitos.app.data.relay.RelayConnectionState
import space.bitos.app.data.relay.RelayFrame
import space.bitos.app.data.relay.RelayTransport
import space.bitos.app.data.relay.reconnectDelayMillis
import space.bitos.core.crypto.SchnorrSigning
import space.bitos.core.identity.EventApprovalSigner
import space.bitos.core.identity.Nip46AccountMetadata
import space.bitos.core.identity.SignEventRequest
import space.bitos.core.identity.SignEventResult
import space.bitos.core.identity.SignerKind
import space.bitos.core.identity.nip46.Nip46Command
import space.bitos.core.identity.nip46.Nip46Crypto
import space.bitos.core.identity.nip46.Nip46Methods
import space.bitos.core.identity.nip46.Nip46Outcome
import space.bitos.core.identity.nip46.Nip46Rpc
import space.bitos.core.identity.nip46.Nip46SessionReducer
import space.bitos.core.identity.nip46.Nip46SessionState
import space.bitos.core.identity.nip46.Nip46Uri
import space.bitos.core.identity.nip46.Nip46UnsignedEvent
import space.bitos.core.identity.nip46.Nip46Wire
import space.bitos.core.model.RelayUrl
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher
import java.security.SecureRandom

/** Live connection phase surfaced to the awaiting-signer UI. */
enum class Nip46SessionPhase { IDLE, CONNECTING, AWAITING_SIGNER, CLOSED }

data class Nip46SessionUiState(
    val phase: Nip46SessionPhase = Nip46SessionPhase.IDLE,
    /** auth_url challenge arrived: the user must finish approval in the signer app. */
    val awaitingAuthUrl: Boolean = false,
)

/** Setup handshake result (ID-006b): the bunker-confirmed account key or a typed failure. */
sealed interface Nip46SetupResult {
    data class Connected(val accountPubkeyHex: String) : Nip46SetupResult
    data class Failed(val result: SignEventResult) : Nip46SetupResult
}

/**
 * Narrow port over the platform secret store for the NIP-46 client
 * transport key — the only secret this session ever touches, sealed by
 * the Keystore behind it (ID-006a).
 */
interface Nip46TransportSecretStore {
    /** Loads or creates the sealed hex64 transport secret; null when the store fails. */
    fun loadOrCreateTransportSecret(accountPubkeyHex: String): String?

    /** ID-006d: account removal wipes the sealed slot. */
    fun removeTransportSecret(accountPubkeyHex: String)
}

/**
 * NIP-46 relay-lane session (ID-006a): dedicated cancellable transport for
 * one account's bunker — its own sockets and budget, NEVER the feed
 * `RelayPool`. Sockets exist only while a foreground request is waiting;
 * approval, denial, timeout, cancellation and account switch all end with
 * an explicit CLOSE + teardown. Every response passes the shared reducer's
 * correlation gate and (for sign_event) the independent signed-event
 * validator before anything is publishable.
 */
class Nip46SignerSession(
    private val scope: CoroutineScope,
    private val metadata: Nip46AccountMetadata,
    private val accountPubkeyHex: String,
    private val secretStore: Nip46TransportSecretStore,
    private val transportFactory: (RelayUrl, () -> Unit) -> RelayTransport,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
    private val connectTimeoutMs: Long = 10_000,
    private val connectAttempts: Int = 3,
    private val signTimeoutMs: Long = 120_000,
    private val setupTimeoutMs: Long = 30_000,
) : EventApprovalSigner {

    private val hasher = Sha256EventHasher
    private val mutex = Mutex()

    private val mutableUiState = MutableStateFlow(Nip46SessionUiState())
    val uiState: StateFlow<Nip46SessionUiState> = mutableUiState.asStateFlow()

    private val sockets = mutableMapOf<RelayUrl, RelayTransport>()
    private var collectJob: Job? = null

    @Volatile
    private var state: Nip46SessionState = Nip46SessionState()

    @Volatile
    private var lastSent: Nip46UnsignedEvent? = null

    @Volatile
    private var transportSecretHex: String? = null

    @Volatile
    private var transportPubkeyHex: String? = null

    override fun signerKind(): SignerKind = SignerKind.REMOTE_NIP46

    override fun publicKeyHex(): String = accountPubkeyHex

    /** Ensures the client transport key exists (sealed per-account slot). */
    fun ensureTransportKey(): Boolean {
        if (transportSecretHex != null) return true
        val secret = secretStore.loadOrCreateTransportSecret(accountPubkeyHex) ?: return false
        val pubkey = SchnorrSigning.publicKey(hexBytes(secret), hasher) ?: return false
        transportSecretHex = secret
        transportPubkeyHex = hex(pubkey)
        return true
    }

    /** The `nostrconnect://` pairing URI for this session's transport key. */
    fun pairingUri(secret: String, appName: String): String? {
        if (!ensureTransportKey()) return null
        return Nip46Uri.buildNostrconnect(
            clientTransportPubkeyHex = transportPubkeyHex!!,
            relays = metadata.relays.mapNotNull { RelayUrl.parse(it) },
            secret = secret,
            appName = appName,
        )
    }

    /**
     * bunker:// setup handshake: `connect` then `get_public_key`. The
     * caller MUST compare the returned key with the user-confirmed account
     * before saving anything (ID-006b).
     */
    suspend fun connectAndGetPublicKey(pairingSecret: String?): Nip46SetupResult {
        if (!ensureTransportKey()) return Nip46SetupResult.Failed(SignEventResult.Unavailable("transport_key"))
        when (val connect = rpcOutcome(Nip46Methods.CONNECT, Nip46Wire.connectParams(metadata.bunkerPubkeyHex, pairingSecret), null)) {
            is Nip46Outcome.ConnectAcknowledged -> Unit
            is Nip46Outcome.Failed -> return Nip46SetupResult.Failed(connect.result)
            else -> return Nip46SetupResult.Failed(SignEventResult.Unavailable("unexpected_result"))
        }
        return when (val key = rpcOutcome(Nip46Methods.GET_PUBLIC_KEY, emptyList(), null)) {
            is Nip46Outcome.PublicKeyObtained -> Nip46SetupResult.Connected(key.accountPubkeyHex)
            is Nip46Outcome.Failed -> Nip46SetupResult.Failed(key.result)
            else -> Nip46SetupResult.Failed(SignEventResult.Unavailable("unexpected_result"))
        }
    }

    override suspend fun approveEvent(request: SignEventRequest): SignEventResult {
        SignEventRequest.validate(request)?.let { return SignEventResult.Invalid(it) }
        if (request.accountPubkeyHex != accountPubkeyHex) return SignEventResult.Rejected("wrong_pubkey")
        if (!ensureTransportKey()) return SignEventResult.Unavailable("transport_key")
        return mutex.withLock {
            when (val outcome = rpcOutcome(Nip46Methods.SIGN_EVENT, Nip46Wire.signEventParams(request), request)) {
                is Nip46Outcome.EventApproved -> SignEventResult.Signed(outcome.event)
                is Nip46Outcome.Failed -> outcome.result
                else -> SignEventResult.Unavailable("unexpected_result")
            }
        }
    }

    /**
     * One deadline-driven RPC: open the lane, send the request, wait for
     * the correlated response. Sockets live only inside this window.
     */
    private suspend fun rpcOutcome(method: String, params: List<String>, origin: SignEventRequest?): Nip46Outcome? {
        val secret = transportSecretHex ?: return Nip46Outcome.Failed(SignEventResult.Unavailable("transport_key"))
        val clientPubkey = transportPubkeyHex ?: return Nip46Outcome.Failed(SignEventResult.Unavailable("transport_key"))
        val requestId = randomRequestId()
        val envelope = Nip46Wire.encodeRequest(requestId, method, params)
            ?: return Nip46Outcome.Failed(SignEventResult.Invalid("envelope"))
        val encrypted = Nip46Crypto.encrypt(envelope, secret, metadata.bunkerPubkeyHex, ::random32)
            ?: return Nip46Outcome.Failed(SignEventResult.Invalid("encrypt"))
        val subscriptionId = "nip46-${randomHex(8)}"
        val rpc = Nip46Rpc(
            requestId = requestId,
            method = method,
            deadlineMs = clockMs() + requestDeadlineMs(method),
            expectedSignerPubkeyHex = metadata.bunkerPubkeyHex,
            originating = origin,
        )
        val unsigned = Nip46Wire.buildRequestEvent(clientPubkey, metadata.bunkerPubkeyHex, encrypted, nowSeconds())
            ?: return Nip46Outcome.Failed(SignEventResult.Invalid("event"))

        mutableUiState.value = Nip46SessionUiState(phase = Nip46SessionPhase.CONNECTING)
        if (!ensureSocketsConnected()) {
            withContext(NonCancellable) { teardown() }
            mutableUiState.value = Nip46SessionUiState(phase = Nip46SessionPhase.CLOSED)
            return Nip46Outcome.Failed(SignEventResult.Unavailable("relay_unreachable"))
        }

        val begin = Nip46SessionReducer.begin(state, rpc, unsigned, subscriptionId, clientPubkey)
        if (begin == null) {
            withContext(NonCancellable) { teardown() }
            mutableUiState.value = Nip46SessionUiState(phase = Nip46SessionPhase.CLOSED)
            return Nip46Outcome.Failed(SignEventResult.Unavailable("busy"))
        }
        state = begin.first
        lastSent = unsigned
        executeCommands(begin.second, secret, clientPubkey)

        mutableUiState.value = Nip46SessionUiState(phase = Nip46SessionPhase.AWAITING_SIGNER)
        val outcome = waitForOutcome(rpc.deadlineMs)
            ?: run {
                val (expired, _) = Nip46SessionReducer.onExpired(state, clockMs())
                state = expired
                expired.finished
            }
            // A stalled clock must still surface a deadline, never a null.
            ?: Nip46Outcome.Failed(SignEventResult.TimedOut("deadline"))

        withContext(NonCancellable) { teardown() }
        mutableUiState.value = Nip46SessionUiState(phase = Nip46SessionPhase.CLOSED)
        return outcome
    }

    /** Polls the (collector-driven) reducer state until terminal or deadline. */
    private suspend fun waitForOutcome(deadlineMs: Long): Nip46Outcome? {
        val remaining = deadlineMs - clockMs()
        if (remaining <= 0) return null
        return withTimeoutOrNull(remaining) {
            while (true) {
                state.finished?.let { return@withTimeoutOrNull it }
                if (state.awaitingAuthUrl && !mutableUiState.value.awaitingAuthUrl) {
                    mutableUiState.value = mutableUiState.value.copy(awaitingAuthUrl = true)
                }
                delay(25)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    /**
     * User-visible cancel of the wait (the awaiting sheet's Cancel action).
     * Deliberately NOT mutex-guarded: the in-flight [approveEvent] polls
     * the reducer state, so flipping it to terminal here unblocks the
     * waiter; both paths then run the idempotent teardown.
     */
    suspend fun cancelWait() {
        val (next, commands) = Nip46SessionReducer.onCancelled(state)
        state = next
        transportSecretHex?.let { secret ->
            transportPubkeyHex?.let { clientPubkey -> executeCommands(commands, secret, clientPubkey) }
        }
        withContext(NonCancellable) { teardown() }
        mutableUiState.value = Nip46SessionUiState(phase = Nip46SessionPhase.CLOSED)
    }

    /** ID-006d: session teardown on switch/sign-out/timeout/app exit. */
    suspend fun shutdown() {
        if (state.busy) cancelWait() else withContext(NonCancellable) { teardown() }
    }

    /** ID-006d: account removal wipes the sealed transport key slot. */
    fun wipeTransportSecret() {
        secretStore.removeTransportSecret(accountPubkeyHex)
        transportSecretHex = null
        transportPubkeyHex = null
    }

    // ── sockets ─────────────────────────────────────────────────────

    /**
     * Connects the bunker relays with capped jitter retries; ≥1 connected
     * socket is enough to carry the request (broadcast reaches all
     * connected). Retries happen ONLY inside a waiting request window —
     * there is no idle or perpetual connection.
     */
    private suspend fun ensureSocketsConnected(): Boolean {
        val relays = metadata.relays.mapNotNull { RelayUrl.parse(it) }
        if (relays.isEmpty()) return false
        relays.forEach { relay ->
            val transport = synchronized(sockets) {
                sockets.getOrPut(relay) { transportFactory(relay) { } }
            }
            var attempt = 0
            while (attempt < connectAttempts) {
                if (transport.state.value == RelayConnectionState.CONNECTED) break
                transport.connect()
                val connected = withTimeoutOrNull(connectTimeoutMs) {
                    transport.state.first { it == RelayConnectionState.CONNECTED }
                }
                if (connected != null) break
                attempt++
                if (attempt < connectAttempts) delay(reconnectDelayMillis(attempt, kotlin.random.Random(random.nextLong())))
            }
        }
        // Subscribe AFTER the socket set exists — the collector snapshots it.
        startFrameCollector()
        return synchronized(sockets) { sockets.values.any { it.state.value == RelayConnectionState.CONNECTED } }
    }

    private fun startFrameCollector() {
        if (collectJob?.isActive == true) return
        collectJob = scope.launch {
            // Re-check the socket set; each transport's frames flow on its
            // own child job so one relay can never starve another.
            val current = synchronized(sockets) { sockets.values.toList() }
            current.forEach { transport ->
                transport.frames
                    .onEach { frame -> onRelayFrame(frame) }
                    .launchIn(this)
            }
        }
    }

    /** One raw relay frame: trust-gate, decrypt, correlate via the reducer. */
    internal fun onRelayFrame(frame: RelayFrame) {
        val secret = transportSecretHex ?: return
        val clientPubkey = transportPubkeyHex ?: return
        val decoded = try {
            NostrEventCodec.decodeRelayEventFrame(hasher, frame.message, frame.relay)
        } catch (_: NostrEventCodec.Rejected) {
            return
        } catch (_: Exception) {
            return
        }
        if (decoded.event.kind != Nip46Wire.KIND) return
        if (decoded.event.pubkey.value != metadata.bunkerPubkeyHex) return
        if (!NostrEventCodec.verifySignature(hasher, decoded.event)) return
        val pTag = decoded.event.tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1) ?: return
        if (pTag != clientPubkey) return

        val plaintext = Nip46Crypto.decrypt(decoded.event.content, secret, decoded.event.pubkey.value) ?: return
        val request = Nip46Wire.decodeRequest(plaintext)
        if (request != null) {
            val (next, commands) = Nip46SessionReducer.onSignerConnectRequest(
                state, decoded.event.pubkey.value, request, clientPubkey,
            )
            state = next
            executeCommands(commands, secret, clientPubkey)
            return
        }
        val response = Nip46Wire.decodeResponse(plaintext) ?: return
        state = Nip46SessionReducer.onAuthUrlChallenge(state, response)
        val (next, commands) = Nip46SessionReducer.onResponse(state, decoded.event.pubkey.value, response)
        state = next
        executeCommands(commands, secret, clientPubkey)
    }

    private fun executeCommands(commands: List<Nip46Command>, secret: String, clientPubkey: String) {
        commands.forEach { command ->
            when (command) {
                is Nip46Command.OpenResponseLane -> broadcast(command.requestFrame)
                is Nip46Command.CloseResponseLane -> broadcast(command.closeFrame)
                is Nip46Command.SendRequest -> sendSignedEvent(command.unsignedEvent, secret, clientPubkey)
                is Nip46Command.SendPlainResponse -> {
                    val encrypted = Nip46Crypto.encrypt(command.envelopeJson, secret, metadata.bunkerPubkeyHex, ::random32)
                    val unsigned = encrypted?.let {
                        Nip46Wire.buildRequestEvent(clientPubkey, metadata.bunkerPubkeyHex, it, nowSeconds())
                    }
                    if (unsigned != null) sendSignedEvent(unsigned, secret, clientPubkey)
                }
                is Nip46Command.TeardownSockets -> Unit // handled at rpc() end
            }
        }
    }

    /** Signs the kind-24133 event with the transport key and sends the frame. */
    private fun sendSignedEvent(unsigned: Nip46UnsignedEvent, secret: String, clientPubkey: String) {
        val signature = SchnorrSigning.sign(hexBytes(unsigned.idHex), hexBytes(secret), random32(), hasher) ?: return
        val frame = buildString(unsigned.json.length + 96) {
            append("[\"EVENT\",{")
            append("\"id\":\"").append(unsigned.idHex).append("\",")
            // The inner fields of the canonical unsigned object (both braces stripped).
            append(unsigned.json.substring(1, unsigned.json.length - 1))
            append(",\"sig\":\"").append(hex(signature)).append("\"}]")
        }
        broadcast(frame)
    }

    private fun broadcast(frame: String) {
        synchronized(sockets) { sockets.values.toList() }.forEach { it.send(frame) }
    }

    /** CLOSE subscription + socket teardown; idempotent. */
    private suspend fun teardown() {
        state.subscriptionId?.let { broadcast(Nip46Wire.closeFrame(it)) }
        collectJob?.cancel()
        collectJob = null
        val current = synchronized(sockets) {
            val copy = sockets.values.toList()
            sockets.clear()
            copy
        }
        current.forEach { it.close() }
        state = state.copy(subscriptionId = null)
    }

    private fun requestDeadlineMs(method: String): Long = when (method) {
        Nip46Methods.SIGN_EVENT -> signTimeoutMs
        else -> setupTimeoutMs
    }

    private fun random32(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    private fun randomRequestId(): String = randomHex(16)

    private fun randomHex(chars: Int): String {
        val digits = "0123456789abcdef"
        return buildString(chars) { repeat(chars) { append(digits[random.nextInt(16)]) } }
    }

    private fun nowSeconds(): Long = clockMs() / 1_000

    private fun hexBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { ((it.toInt() and 0xf0) ushr 4).toString(16) + (it.toInt() and 0x0f).toString(16) }
}

/** Production adapter: the transport key is sealed by the Android Keystore. */
class SecureNip46TransportSecretStore(
    private val store: space.bitos.app.identity.SecureKeyStore,
) : Nip46TransportSecretStore {
    override fun loadOrCreateTransportSecret(accountPubkeyHex: String): String? {
        store.loadNip46TransportSecret(accountPubkeyHex)?.let { return it }
        return store.generateSecretHex().also { store.storeNip46TransportSecret(it, accountPubkeyHex) }
    }

    override fun removeTransportSecret(accountPubkeyHex: String) {
        store.removeNip46TransportSecret(accountPubkeyHex)
    }
}
