package space.bitos.core.identity.nip46

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import space.bitos.core.identity.SignEventRequest
import space.bitos.core.identity.appendTagsJson
import space.bitos.core.identity.escapeJson
import space.bitos.core.model.NostrLimits
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher

/** NIP-46 RPC method allowlist for the active slice (SBC-008b). */
object Nip46Methods {
    const val CONNECT = "connect"
    const val GET_PUBLIC_KEY = "get_public_key"
    const val SIGN_EVENT = "sign_event"

    /** Only the methods the event-signing slice may request. */
    val ALLOWED = setOf(CONNECT, GET_PUBLIC_KEY, SIGN_EVENT)

    fun isAllowed(method: String): Boolean = method in ALLOWED
}

/** Decoded NIP-46 JSON-RPC request envelope. */
data class Nip46RequestEnvelope(
    val id: String,
    val method: String,
    val params: List<String>,
)

/** Decoded NIP-46 JSON-RPC response envelope (result XOR error). */
data class Nip46ResponseEnvelope(
    val id: String,
    val result: String?,
    val error: String?,
) {
    val isError: Boolean get() = error != null
}

/** An unsigned kind-24133 transport event awaiting the transport-key signature. */
data class Nip46UnsignedEvent(
    val json: String,
    val idHex: String,
)

/**
 * Pure NIP-46 wire codec (SBC-008b): envelope encode/decode with strict
 * bounds, kind-24133 event building/parsing, and the relay filter for the
 * response subscription. All content here is transported NIP-44-encrypted
 * by the caller ([Nip46Crypto]) — this codec never sees plaintext keys.
 */
object Nip46Wire {

    const val KIND = 24_133
    const val MAX_ENVELOPE = NostrLimits.MAX_CONTENT_LENGTH
    private const val MAX_ID = 128

    // ── Envelopes ────────────────────────────────────────────────────

    fun encodeRequest(id: String, method: String, params: List<String>): String? {
        if (id.isEmpty() || id.length > MAX_ID) return null
        if (!Nip46Methods.isAllowed(method)) return null
        if (params.size > 8) return null
        params.forEach { if (it.length > MAX_ENVELOPE) return null }
        return buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", buildJsonArray { params.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }.toString()
    }

    fun encodeResponse(id: String, result: String?, error: String? = null): String? {
        if (id.isEmpty() || id.length > MAX_ID) return null
        if (result == null && error == null) return null
        return buildJsonObject {
            put("id", id)
            if (result != null) put("result", result)
            if (error != null) put("error", error)
        }.toString()
    }

    fun decodeRequest(content: String): Nip46RequestEnvelope? {
        if (content.isEmpty() || content.length > MAX_ENVELOPE) return null
        return try {
            val root = Json.parseToJsonElement(content).jsonObject
            Nip46RequestEnvelope(
                id = root["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() && it.length <= MAX_ID } ?: return null,
                method = root["method"]?.jsonPrimitive?.contentOrNull ?: return null,
                params = root["params"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull ?: return null } ?: return null,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun decodeResponse(content: String): Nip46ResponseEnvelope? {
        if (content.isEmpty() || content.length > MAX_ENVELOPE) return null
        return try {
            val root = Json.parseToJsonElement(content).jsonObject
            val result = root["result"]?.jsonPrimitive?.contentOrNull
            val error = root["error"]?.jsonPrimitive?.contentOrNull
            // A response with neither field is malformed (spec: error's
            // presence indicates error; otherwise result must exist).
            if (result == null && error == null) return null
            Nip46ResponseEnvelope(
                id = root["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() && it.length <= MAX_ID } ?: return null,
                result = result,
                error = error,
            )
        } catch (_: Exception) {
            null
        }
    }

    // ── kind-24133 events ───────────────────────────────────────────

    /**
     * The unsigned request event: kind 24133, p-tag = signer pubkey,
     * content = NIP-44 payload. The native adapter signs it with the
     * client transport key; [Nip46Crypto] produced the content.
     */
    fun buildRequestEvent(
        fromPubkeyHex: String,
        toSignerPubkeyHex: String,
        encryptedContent: String,
        createdAtSeconds: Long,
        hasher: EventHasher = Sha256EventHasher,
    ): Nip46UnsignedEvent? {
        if (!fromPubkeyHex.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (!toSignerPubkeyHex.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (encryptedContent.isEmpty() || encryptedContent.length > MAX_ENVELOPE) return null
        if (createdAtSeconds <= 0L) return null
        val tags = listOf(listOf("p", toSignerPubkeyHex))
        val json = buildString(160 + encryptedContent.length) {
            append("{\"kind\":").append(KIND)
            append(",\"content\":\"").append(escapeJson(encryptedContent)).append("\",\"tags\":")
            appendTagsJson(this, tags)
            append(",\"created_at\":").append(createdAtSeconds)
            append(",\"pubkey\":\"").append(fromPubkeyHex).append("\"}")
        }
        val id = NostrEventCodec.computeId(hasher, fromPubkeyHex, createdAtSeconds, KIND, tags, encryptedContent)
        return Nip46UnsignedEvent(json, id)
    }

    /** One decoded inbound kind-24133 event after the relay trust gate. */
    data class TransportEvent(
        val pubkeyHex: String,
        val pTagHex: String?,
        val createdAtSeconds: Long,
        val content: String,
    )

    fun parseTransportEvent(eventJson: String): TransportEvent? {
        if (eventJson.isEmpty() || eventJson.length > NostrLimits.MAX_EVENT_BYTES) return null
        return try {
            val root = Json.parseToJsonElement(eventJson).jsonObject
            val pubkey = root["pubkey"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return null
            if (!pubkey.matches(Regex("^[0-9a-f]{64}$"))) return null
            val kind = root["kind"]?.jsonPrimitive?.longOrNull?.toInt() ?: return null
            if (kind != KIND) return null
            val content = root["content"]?.jsonPrimitive?.contentOrNull ?: return null
            val pTag = root["tags"]?.jsonArray
                ?.firstOrNull { tag -> tag.jsonArray.firstOrNull()?.jsonPrimitive?.contentOrNull == "p" }
                ?.jsonArray?.getOrNull(1)?.jsonPrimitive?.contentOrNull
            TransportEvent(pubkey, pTag, root["created_at"]?.jsonPrimitive?.longOrNull ?: 0L, content)
        } catch (_: Exception) {
            null
        }
    }

    // ── Relay subscription ──────────────────────────────────────────

    /**
     * The response-lane filter: kind 24133 addressed to our transport
     * pubkey. When the signer pubkey is known (bunker URI) the `authors`
     * pin tightens it; pairing leaves it open because the peer is unknown.
     */
    fun responseFilter(clientTransportPubkeyHex: String, signerPubkeyHex: String?, limit: Int = 1): String? {
        if (!clientTransportPubkeyHex.matches(Regex("^[0-9a-f]{64}$"))) return null
        signerPubkeyHex?.let { if (!it.matches(Regex("^[0-9a-f]{64}$"))) return null }
        val builder = StringBuilder("{\"kinds\":[").append(KIND).append("],\"#p\":[\"")
            .append(clientTransportPubkeyHex).append("\"]")
        signerPubkeyHex?.let { builder.append(",\"authors\":[\"").append(it).append("\"]") }
        builder.append(",\"limit\":").append(limit.coerceIn(1, 50)).append('}')
        return builder.toString()
    }

    /** `["REQ", id, filter]` frame for the response lane. */
    fun requestFrame(subscriptionId: String, filterJson: String): String =
        """["REQ","$subscriptionId",$filterJson]"""

    /** `["CLOSE", id]` frame. */
    fun closeFrame(subscriptionId: String): String =
        """["CLOSE","$subscriptionId"]"""

    // ── Method payloads ─────────────────────────────────────────────

    /** connect params: [signerPubkey, optional secret, optional perms]. */
    fun connectParams(signerPubkeyHex: String, secret: String?): List<String> =
        listOfNotNull(signerPubkeyHex, secret)

    /** sign_event params: [json_stringified unsigned event] (NIP-46 form). */
    fun signEventParams(request: SignEventRequest): List<String> =
        listOf(request.unsignedEventJson())
}
