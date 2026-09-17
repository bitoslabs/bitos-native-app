package space.bitos.core.identity

import space.bitos.core.model.NostrLimits
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher

/**
 * One event-approval request (SBC-008a): the canonical, bounded unsigned
 * event plus the account scope it must be signed under. Feature code builds
 * this; the signer implementation (local key, NIP-46 bunker, NIP-55 app)
 * approves and returns a [SignEventResult]. Nothing secret ever crosses the
 * contract — secrets stay inside native signer implementations.
 */
data class SignEventRequest(
    /** Correlation id chosen by the caller; echoed through every transport. */
    val requestId: String,
    /** hex64 account the event MUST be signed for. */
    val accountPubkeyHex: String,
    val createdAtSeconds: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
) {
    companion object {
        private val requestIdShape = Regex("^[A-Za-z0-9._:-]{1,64}$")
        private val hex64 = Regex("^[0-9a-f]{64}$")

        /** Structured rejection of an out-of-bounds request (never throws). */
        fun validate(request: SignEventRequest): String? {
            if (!requestIdShape.matches(request.requestId)) return "request_id"
            if (!hex64.matches(request.accountPubkeyHex)) return "account_pubkey"
            if (request.createdAtSeconds <= 0L) return "created_at"
            if (request.kind !in 0..65_535) return "kind"
            if (request.tags.size > NostrLimits.MAX_TAGS) return "tags"
            request.tags.forEach { tag ->
                if (tag.isEmpty() || tag.size > NostrLimits.MAX_TAG_ITEMS) return "tags"
                if (tag.any { it.length > NostrLimits.MAX_TAG_ITEM_LENGTH }) return "tags"
            }
            if (request.content.length > NostrLimits.MAX_CONTENT_LENGTH) return "content"
            return null
        }
    }

    /** Canonical NIP-01 event id (the BIP-340 message). */
    fun eventIdHex(hasher: EventHasher = Sha256EventHasher): String =
        NostrEventCodec.computeId(
            hasher, accountPubkeyHex, createdAtSeconds, kind, tags, content,
        )

    /**
     * The unsigned wire form sent to NIP-46 bunkers and NIP-55 signer apps:
     * exactly `{"kind":…,"content":…,"tags":…,"created_at":…}` — no id, no
     * pubkey, no signature (NIP-46 §sign_event; NIP-55 payload form).
     */
    fun unsignedEventJson(): String {
        val builder = StringBuilder(64 + content.length)
        builder.append("{\"kind\":").append(kind)
            .append(",\"content\":\"").append(escapeJson(content)).append("\",\"tags\":")
        appendTagsJson(builder, tags)
        builder.append(",\"created_at\":").append(createdAtSeconds).append('}')
        return builder.toString()
    }
}

/** A fully signed event produced by an approved [SignEventRequest]. */
data class SignedEvent(
    val idHex: String,
    val pubkeyHex: String,
    val createdAtSeconds: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val signatureHex: String,
)

/**
 * Typed outcome of one event-approval attempt (SBC-008a). Stable outcome
 * codes are the only thing diagnostics may observe; descriptions never
 * include event payloads, signatures or keys.
 */
sealed interface SignEventResult {

    /** Machine-stable code for telemetry/logging (aggregate-safe). */
    val outcomeCode: String

    /** The signer approved; [event] passed [SignedEventValidator]. */
    data class Signed(val event: SignedEvent) : SignEventResult {
        override val outcomeCode: String get() = CODE
        companion object { const val CODE = "signed" }
    }

    /** The signer or its user refused (denial — not an error). */
    data class Denied(val reasonCode: String? = null) : SignEventResult {
        override val outcomeCode: String get() = CODE
        companion object { const val CODE = "denied" }
    }

    /** No approval before the request deadline. */
    data class TimedOut(val reasonCode: String? = null) : SignEventResult {
        override val outcomeCode: String get() = CODE
        companion object { const val CODE = "timeout" }
    }

    /** Signer cannot serve the request now (absent app, dead relay, flag off). */
    data class Unavailable(val reasonCode: String) : SignEventResult {
        override val outcomeCode: String get() = CODE
        companion object { const val CODE = "unavailable" }
    }

    /** A response arrived but failed validation; nothing was published. */
    data class Rejected(val reasonCode: String) : SignEventResult {
        override val outcomeCode: String get() = CODE
        companion object { const val CODE = "rejected" }
    }

    /** The user cancelled the wait (not an error; draft stays). */
    data object Cancelled : SignEventResult {
        override val outcomeCode: String get() = "cancelled"
    }

    /** The request itself was out of bounds. */
    data class Invalid(val reasonCode: String) : SignEventResult {
        override val outcomeCode: String get() = CODE
        companion object { const val CODE = "invalid" }
    }
}

/**
 * Public signer port for feature code (SBC-008a). Replaces the raw
 * `IdentitySigner.sign(message32)` surface at the feature boundary: NIP-46
 * and NIP-55 approve EVENT JSON and may return a fully signed event, so the
 * contract carries the canonical request and an independently verifiable
 * result. Implementations never expose secret key bytes.
 */
interface EventApprovalSigner {
    fun signerKind(): SignerKind

    /** hex64 public key of the account this signer serves. */
    fun publicKeyHex(): String

    /**
     * Requests approval of [request]; the caller MUST treat [SignEventResult.Signed]
     * as the only publishable outcome and dispatch through the existing
     * publisher with the returned event.
     */
    suspend fun approveEvent(request: SignEventRequest): SignEventResult
}

internal fun escapeJson(value: String): String {
    if (value.none { it == '"' || it == '\\' || it < ' ' }) return value
    val out = StringBuilder(value.length + 8)
    value.forEach { char ->
        when {
            char == '"' -> out.append("\\\"")
            char == '\\' -> out.append("\\\\")
            char == '\n' -> out.append("\\n")
            char == '\r' -> out.append("\\r")
            char == '\t' -> out.append("\\t")
            char < ' ' -> out.append("\\u").append(hex4(char.code))
            else -> out.append(char)
        }
    }
    return out.toString()
}

internal fun appendTagsJson(builder: StringBuilder, tags: List<List<String>>) {
    builder.append('[')
    tags.forEachIndexed { tagIndex, tag ->
        if (tagIndex > 0) builder.append(',')
        builder.append('[')
        tag.forEachIndexed { itemIndex, item ->
            if (itemIndex > 0) builder.append(',')
            builder.append('"').append(escapeJson(item)).append('"')
        }
        builder.append(']')
    }
    builder.append(']')
}

private fun hex4(code: Int): String {
    val digits = "0123456789abcdef"
    return charArrayOf(digits[(code shr 12) and 0xf], digits[(code shr 8) and 0xf], digits[(code shr 4) and 0xf], digits[code and 0xf]).concatToString()
}
