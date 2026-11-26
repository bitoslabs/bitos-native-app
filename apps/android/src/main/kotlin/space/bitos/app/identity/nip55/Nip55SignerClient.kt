package space.bitos.app.identity.nip55

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import space.bitos.core.identity.EventApprovalSigner
import space.bitos.core.identity.SignEventRequest
import space.bitos.core.identity.SignEventResult
import space.bitos.core.identity.SignerKind
import space.bitos.core.identity.SignedEventValidator

/**
 * NIP-55 signer client (ID-007b): implements the typed event-approval
 * contract over foreground intents. The HOST (activity composition)
 * watches [activeRequest], launches the spec as an Intent and feeds the
 * activity result back through [deliverResult]; the pending request is
 * durable data (id + origin JSON) so rotation/process death can restore
 * the same wait instead of losing the draft. The PINNED package rides
 * every spec — discovery and get_public_key never re-run in signing
 * loops.
 */
class Nip55SignerClient(
    private val accountPubkeyHex: String,
    private val pinnedPackage: String,
    private val timeoutMs: Long = 120_000,
    private val now: () -> Long = { System.currentTimeMillis() },
) : EventApprovalSigner {

    data class Pending(
        val spec: Nip55Contracts.RequestSpec,
        /** Origin JSON for the validator (sign_event only). */
        val originJson: String,
        val startedAtMs: Long,
    )

    private val mutableActiveRequest = MutableStateFlow<Pending?>(null)
    val activeRequest: StateFlow<Pending?> = mutableActiveRequest.asStateFlow()

    private var waiter: CompletableDeferred<SignEventResult>? = null
    private var origin: SignEventRequest? = null

    override fun signerKind(): SignerKind = SignerKind.EXTERNAL_NIP55

    override fun publicKeyHex(): String = accountPubkeyHex

    override suspend fun approveEvent(request: SignEventRequest): SignEventResult {
        SignEventRequest.validate(request)?.let { return SignEventResult.Invalid(it) }
        if (request.accountPubkeyHex != accountPubkeyHex) return SignEventResult.Rejected("wrong_pubkey")
        val spec = Nip55Contracts.signEventSpec(request, pinnedPackage)
            ?: return SignEventResult.Invalid("spec")
        val deferred = CompletableDeferred<SignEventResult>()
        origin = request
        waiter = deferred
        mutableActiveRequest.value = Pending(
            spec = spec,
            originJson = originJson(request),
            startedAtMs = now(),
        )
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run {
                clearPending()
                SignEventResult.TimedOut("signer")
            }
        return result
    }

    /**
     * Host feeds the activity result here. `sourcePackage` is the package
     * the result actually came from; a mismatch with the pin is rejected —
     * a different app must never satisfy the wait.
     */
    fun deliverResult(extras: Map<String, String>, resultCodeOk: Boolean, sourcePackage: String?) {
        val pending = mutableActiveRequest.value ?: return
        val current = waiter ?: return
        if (sourcePackage != null && sourcePackage != pinnedPackage) {
            current.complete(SignEventResult.Rejected("wrong_package"))
            clearPending()
            return
        }
        when (val raw = Nip55Contracts.parseResult(extras, resultCodeOk, Nip55Contracts.TYPE_SIGN_EVENT)) {
            is Nip55Contracts.RawResult.Refused -> current.complete(SignEventResult.Denied("signer"))
            is Nip55Contracts.RawResult.Failure -> current.complete(SignEventResult.Unavailable("signer_failed"))
            is Nip55Contracts.RawResult.Empty -> current.complete(SignEventResult.Rejected("malformed_result"))
            is Nip55Contracts.RawResult.SignedEventJson -> {
                val originRequest = origin
                if (originRequest == null) {
                    current.complete(SignEventResult.Invalid("missing_origin"))
                } else {
                    val (signed, reason) = SignedEventValidator.validateJson(originRequest, raw.eventJson)
                    if (reason != null) {
                        current.complete(SignEventResult.Rejected(reason))
                    } else {
                        current.complete(SignEventResult.Signed(signed))
                    }
                }
            }
            is Nip55Contracts.RawResult.PublicKey -> current.complete(SignEventResult.Rejected("unexpected_result"))
        }
        clearPending()
    }

    /** User cancelled the wait — not an error; the draft stays. */
    fun cancelWait() {
        waiter?.complete(SignEventResult.Cancelled)
        clearPending()
    }

    private fun clearPending() {
        mutableActiveRequest.value = null
        waiter = null
        origin = null
    }

    private fun originJson(request: SignEventRequest): String =
        space.bitos.core.identity.SignEventRequestJson.encode(request)
}
