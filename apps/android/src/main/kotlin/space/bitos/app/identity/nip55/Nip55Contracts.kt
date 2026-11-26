package space.bitos.app.identity.nip55

import space.bitos.core.identity.SignEventRequest
import space.bitos.core.identity.SignedEventValidator

/**
 * Pure NIP-55 contract (ID-007a/b): intent SPEC construction and result
 * EXTRA parsing as plain data — no android.content types, so every rule
 * is unit-testable and the host activity only converts spec ⇄ Intent.
 *
 * Wire facts (NIP-55): requests are ACTION_VIEW with data
 * `nostrsigner:<payload>` + extras `type`, `id`, `current_user`; results
 * carry extras `result`, `id`, `event` (sign_event), `signature`,
 * `package` (get_public_key) and `rejected` ("true" on user refusal —
 * delivered with RESULT_OK; a non-OK code is a signer FAILURE, not a
 * refusal).
 */
object Nip55Contracts {

    const val TYPE_GET_PUBLIC_KEY = "get_public_key"
    const val TYPE_SIGN_EVENT = "sign_event"

    const val EXTRA_TYPE = "type"
    const val EXTRA_ID = "id"
    const val EXTRA_CURRENT_USER = "current_user"
    const val EXTRA_RESULT = "result"
    const val EXTRA_EVENT = "event"
    const val EXTRA_SIGNATURE = "signature"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_REJECTED = "rejected"

    /** One outbound request as plain data; the host turns it into an Intent. */
    data class RequestSpec(
        val signerPackage: String,
        val payload: String,
        val type: String,
        val requestId: String,
        val currentUserPubkeyHex: String,
    )

    /** get_public_key request spec (setup only — never in signing loops). */
    fun getPublicKeySpec(signerPackage: String): RequestSpec = RequestSpec(
        signerPackage = signerPackage,
        payload = "",
        type = TYPE_GET_PUBLIC_KEY,
        requestId = "gpk",
        currentUserPubkeyHex = "",
    )

    /** sign_event request spec carrying the unsigned event payload. */
    fun signEventSpec(request: SignEventRequest, signerPackage: String): RequestSpec? {
        SignEventRequest.validate(request)?.let { return null }
        return RequestSpec(
            signerPackage = signerPackage,
            payload = request.unsignedEventJson(),
            type = TYPE_SIGN_EVENT,
            requestId = request.requestId,
            currentUserPubkeyHex = request.accountPubkeyHex,
        )
    }

    /** Parsed signer result before validation. */
    sealed interface RawResult {
        data class SignedEventJson(val eventJson: String, val signatureHex: String?) : RawResult
        data class PublicKey(val pubkeyValue: String, val signerPackage: String?) : RawResult
        data object Refused : RawResult
        data object Failure : RawResult
        data object Empty : RawResult
    }

    /**
     * Parses activity-result extras. `resultCodeOk` false means the signer
     * app FAILED (crash/abort) — distinct from a user refusal, which
     * arrives as RESULT_OK with `rejected == "true"`.
     */
    fun parseResult(extras: Map<String, String>, resultCodeOk: Boolean, expectedType: String): RawResult {
        if (!resultCodeOk) return RawResult.Failure
        if (extras[EXTRA_REJECTED]?.lowercase() == "true") return RawResult.Refused
        return when (expectedType) {
            TYPE_GET_PUBLIC_KEY -> {
                val value = extras[EXTRA_RESULT]?.takeIf { it.isNotEmpty() } ?: return RawResult.Empty
                RawResult.PublicKey(value, extras[EXTRA_PACKAGE])
            }
            TYPE_SIGN_EVENT -> {
                val eventJson = extras[EXTRA_EVENT]?.takeIf { it.isNotEmpty() } ?: return RawResult.Empty
                RawResult.SignedEventJson(eventJson, extras[EXTRA_SIGNATURE])
            }
            else -> RawResult.Empty
        }
    }

    /** Setup gate: the returned key must be hex64 and from the pinned package. */
    fun validateSetupResult(result: RawResult, pinnedPackage: String): String? {
        if (result !is RawResult.PublicKey) return null
        if (result.signerPackage != null && result.signerPackage != pinnedPackage) return null
        val pubkey = result.pubkeyValue.lowercase()
        return pubkey.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
    }
}
