package space.bitos.core.identity.nip46

import space.bitos.core.identity.SignEventRequest
import space.bitos.core.identity.SignEventResult
import space.bitos.core.identity.SignedEvent
import space.bitos.core.identity.SignedEventValidator

/** One in-flight NIP-46 RPC over the relay lane (SBC-008b). */
data class Nip46Rpc(
    val requestId: String,
    val method: String,
    val deadlineMs: Long,
    /** The signer the response MUST come from; null during nostrconnect pairing. */
    val expectedSignerPubkeyHex: String?,
    /** sign_event only: the originating request for tamper validation. */
    val originating: SignEventRequest? = null,
)

/** Terminal per-RPC outcome surfaced to the app; never carries payloads. */
sealed interface Nip46Outcome {
    /** connect succeeded ("ack" or the echoed pairing secret). */
    data class ConnectAcknowledged(
        val echoedSecret: String?,
        /** Pairing only: the signer pubkey learned from the inbound connect. */
        val signerPubkeyHex: String? = null,
    ) : Nip46Outcome

    /** get_public_key succeeded with a valid hex64 account key. */
    data class PublicKeyObtained(val accountPubkeyHex: String) : Nip46Outcome

    /** sign_event succeeded and passed the independent validator. */
    data class EventApproved(val event: SignedEvent) : Nip46Outcome

    data class Failed(val result: SignEventResult) : Nip46Outcome
}

/** Side effects the native adapter executes; the reducer stays pure. */
sealed interface Nip46Command {
    data class OpenResponseLane(val requestFrame: String) : Nip46Command
    data class CloseResponseLane(val closeFrame: String) : Nip46Command
    /** Adapter signs with the transport key and sends the event frame. */
    data class SendRequest(val unsignedEvent: Nip46UnsignedEvent) : Nip46Command
    /** Adapter NIP-44-encrypts, signs and sends this response envelope (pairing ack). */
    data class SendPlainResponse(val envelopeJson: String) : Nip46Command
    data object TeardownSockets : Nip46Command
}

data class Nip46SessionState(
    val subscriptionId: String? = null,
    val rpc: Nip46Rpc? = null,
    val finished: Nip46Outcome? = null,
    /** auth_url challenge notice (spec §auth): stay in-flight, surface a hint. */
    val awaitingAuthUrl: Boolean = false,
) {
    val busy: Boolean get() = rpc != null && finished == null
}

/**
 * Pure NIP-46 connection/request reducer (SBC-008b): request correlation,
 * response interpretation and lifecycle commands. Every response must
 * match the in-flight request id and (when known) the expected signer
 * pubkey; stale, malformed, foreign or replayed inputs are ignored, and a
 * sign_event result reaches [Nip46Outcome.EventApproved] only through
 * [SignedEventValidator]. One active RPC per correlation id — a second
 * [begin] while busy is refused.
 */
object Nip46SessionReducer {

    private val hex64 = Regex("^[0-9a-f]{64}$")

    /** Starts one RPC; returns null-state when the lane is busy. */
    fun begin(
        state: Nip46SessionState,
        rpc: Nip46Rpc,
        unsignedEvent: Nip46UnsignedEvent,
        subscriptionId: String,
        clientTransportPubkeyHex: String,
    ): Pair<Nip46SessionState, List<Nip46Command>>? {
        if (state.busy) return null
        if (!Nip46Methods.isAllowed(rpc.method)) return null
        val commands = mutableListOf<Nip46Command>()
        if (state.subscriptionId != subscriptionId) {
            state.subscriptionId?.let { commands += Nip46Command.CloseResponseLane(Nip46Wire.closeFrame(it)) }
            val filter = Nip46Wire.responseFilter(clientTransportPubkeyHex, rpc.expectedSignerPubkeyHex)
                ?: return null
            commands += Nip46Command.OpenResponseLane(Nip46Wire.requestFrame(subscriptionId, filter))
        }
        commands += Nip46Command.SendRequest(unsignedEvent)
        return Nip46SessionState(
            subscriptionId = subscriptionId,
            rpc = rpc,
            finished = null,
            awaitingAuthUrl = false,
        ) to commands
    }

    /**
     * A decrypted, source-authenticated NIP-46 response envelope arrived.
     * Decryption and relay-trust gating happen in the adapter; this decides
     * whether it answers the in-flight RPC.
     */
    fun onResponse(
        state: Nip46SessionState,
        fromPubkeyHex: String,
        envelope: Nip46ResponseEnvelope,
    ): Pair<Nip46SessionState, List<Nip46Command>> {
        val rpc = state.rpc
        if (rpc == null || state.finished != null) return state to emptyList() // stale
        if (rpc.expectedSignerPubkeyHex != null && fromPubkeyHex != rpc.expectedSignerPubkeyHex) {
            return state to emptyList() // foreign source
        }
        if (envelope.id != rpc.requestId) return state to emptyList() // mismatched/replayed id

        if (envelope.isError) {
            return finish(state, Nip46Outcome.Failed(mapRpcError(envelope.error))) to emptyList()
        }
        val result = envelope.result ?: return finish(state, Nip46Outcome.Failed(
            SignEventResult.Unavailable("empty_result"),
        )) to emptyList()

        return when (rpc.method) {
            Nip46Methods.CONNECT -> {
                val echoed = result.takeIf { it != "ack" && it.length <= Nip46Uri.MAX_SECRET_LENGTH }
                finish(state, Nip46Outcome.ConnectAcknowledged(echoed)) to emptyList()
            }
            Nip46Methods.GET_PUBLIC_KEY ->
                if (hex64.matches(result)) {
                    finish(state, Nip46Outcome.PublicKeyObtained(result)) to emptyList()
                } else {
                    finish(state, Nip46Outcome.Failed(SignEventResult.Rejected("bad_pubkey_result"))) to emptyList()
                }
            Nip46Methods.SIGN_EVENT -> {
                val origin = rpc.originating
                    ?: return finish(state, Nip46Outcome.Failed(SignEventResult.Invalid("missing_origin"))) to emptyList()
                val (signed, reason) = SignedEventValidator.validateJson(origin, result)
                if (reason != null) {
                    finish(state, Nip46Outcome.Failed(SignEventResult.Rejected(reason))) to emptyList()
                } else {
                    finish(state, Nip46Outcome.EventApproved(signed)) to emptyList()
                }
            }
            else -> finish(state, Nip46Outcome.Failed(SignEventResult.Invalid("method"))) to emptyList()
        }
    }

    /**
     * spec auth challenge: `result == "auth_url"` with the URL in `error`.
     * The RPC stays in-flight; a proper response with the same id may
     * follow. Returns an updated state flagging the challenge for UX.
     */
    fun onAuthUrlChallenge(state: Nip46SessionState, envelope: Nip46ResponseEnvelope): Nip46SessionState {
        val rpc = state.rpc ?: return state
        if (state.finished != null || envelope.id != rpc.requestId) return state
        if (envelope.result != "auth_url") return state
        return state.copy(awaitingAuthUrl = true)
    }

    /** Deadline expiry of the in-flight RPC. */
    fun onExpired(state: Nip46SessionState, nowMs: Long): Pair<Nip46SessionState, List<Nip46Command>> {
        val rpc = state.rpc
        if (rpc == null || state.finished != null || nowMs < rpc.deadlineMs) return state to emptyList()
        return finish(state, Nip46Outcome.Failed(SignEventResult.TimedOut("deadline"))) to emptyList()
    }

    /** User cancelled the wait — not an error; everything tears down. */
    fun onCancelled(state: Nip46SessionState): Pair<Nip46SessionState, List<Nip46Command>> {
        if (state.finished != null && state.rpc == null) return state to emptyList()
        val next = finish(state, Nip46Outcome.Failed(SignEventResult.Cancelled))
        return next to teardownCommands(next)
    }

    /**
     * Relay switch mid-RPC: close the old lane, reopen on the new set and
     * resend the SAME request envelope (same id — idempotent for the
     * signer, re-correlatable for us).
     */
    fun onRelaysSwitched(
        state: Nip46SessionState,
        unsignedEvent: Nip46UnsignedEvent,
        subscriptionId: String,
        clientTransportPubkeyHex: String,
    ): Pair<Nip46SessionState, List<Nip46Command>> {
        val rpc = state.rpc
        if (rpc == null || state.finished != null) return state to emptyList()
        val commands = mutableListOf<Nip46Command>()
        state.subscriptionId?.let { commands += Nip46Command.CloseResponseLane(Nip46Wire.closeFrame(it)) }
        val filter = Nip46Wire.responseFilter(clientTransportPubkeyHex, rpc.expectedSignerPubkeyHex)
            ?: return state to emptyList()
        commands += Nip46Command.OpenResponseLane(Nip46Wire.requestFrame(subscriptionId, filter))
        commands += Nip46Command.SendRequest(unsignedEvent)
        return state.copy(subscriptionId = subscriptionId) to commands
    }

    /**
     * nostrconnect pairing: the signer introduces itself with a `connect`
     * REQUEST addressed to our transport key. We ack it and learn the
     * signer pubkey for the subsequent get_public_key.
     */
    fun onSignerConnectRequest(
        state: Nip46SessionState,
        fromPubkeyHex: String,
        request: Nip46RequestEnvelope,
        ourTransportPubkeyHex: String,
    ): Pair<Nip46SessionState, List<Nip46Command>> {
        if (state.busy) return state to emptyList()
        if (request.method != Nip46Methods.CONNECT) return state to emptyList()
        if (!hex64.matches(fromPubkeyHex)) return state to emptyList()
        // params[0] (when present) must name us.
        request.params.firstOrNull()?.let {
            if (it != ourTransportPubkeyHex) return state to emptyList()
        }
        val ack = Nip46Wire.encodeResponse(request.id, "ack") ?: return state to emptyList()
        val next = finish(state, Nip46Outcome.ConnectAcknowledged(echoedSecret = null, signerPubkeyHex = fromPubkeyHex))
        return next to listOf(Nip46Command.SendPlainResponse(ack))
    }

    /** Terminal teardown used by the adapter when the operation is over. */
    fun finish(state: Nip46SessionState, outcome: Nip46Outcome): Nip46SessionState =
        state.copy(rpc = null, finished = outcome, awaitingAuthUrl = false)

    fun teardownCommands(state: Nip46SessionState): List<Nip46Command> {
        val commands = mutableListOf<Nip46Command>()
        state.subscriptionId?.let { commands += Nip46Command.CloseResponseLane(Nip46Wire.closeFrame(it)) }
        commands += Nip46Command.TeardownSockets
        return commands
    }

    /** Maps a freeform NIP-46 error string to a stable typed outcome. */
    private fun mapRpcError(error: String?): SignEventResult {
        val lowered = error?.lowercase().orEmpty()
        return when {
            listOf("denied", "declined", "rejected", "unauthorized", "not authorized", "cancelled").any { it in lowered } ||
                "canceled" in lowered -> SignEventResult.Denied("signer")
            listOf("rate", "busy").any { it in lowered } -> SignEventResult.Unavailable("signer_busy")
            else -> SignEventResult.Unavailable("signer_error")
        }
    }
}
