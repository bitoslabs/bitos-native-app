package space.bitos.core.identity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import space.bitos.core.crypto.SchnorrVerification
import space.bitos.core.model.NostrLimits
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher

/**
 * Independent verification of a signer-returned event (SBC-008a): a
 * signature for a CHANGED payload, a DIFFERENT pubkey or an unexpected
 * request is rejected and can never enter the publisher. Runs in the
 * adapter before any success is dispatched — the same gate for local-key
 * self-checks, NIP-46 bunkers and NIP-55 signer apps.
 */
object SignedEventValidator {

    /** Bounded parse of a signed-event JSON object; null on any violation. */
    fun parseSignedEvent(eventJson: String): SignedEvent? {
        if (eventJson.isEmpty() || eventJson.length > NostrLimits.MAX_EVENT_BYTES) return null
        return try {
            val root = Json.parseToJsonElement(eventJson).jsonObject
            val hex64 = Regex("^[0-9a-f]{64}$")
            val hex128 = Regex("^[0-9a-f]{128}$")
            val id = root["id"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return null
            val pubkey = root["pubkey"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return null
            val signature = root["sig"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return null
            if (!hex64.matches(id) || !hex64.matches(pubkey) || !hex128.matches(signature)) return null
            val createdAt = root["created_at"]?.jsonPrimitive?.longOrNull ?: return null
            val kind = root["kind"]?.jsonPrimitive?.longOrNull?.toInt() ?: return null
            if (createdAt <= 0L || kind !in 0..65_535) return null
            val tags = root["tags"]?.jsonArray?.map { tagElement ->
                tagElement.jsonArray.map { it.jsonPrimitive.contentOrNull ?: return null }
            } ?: return null
            if (tags.size > NostrLimits.MAX_TAGS) return null
            tags.forEach { tag ->
                if (tag.isEmpty() || tag.size > NostrLimits.MAX_TAG_ITEMS) return null
                if (tag.any { it.length > NostrLimits.MAX_TAG_ITEM_LENGTH }) return null
            }
            val content = root["content"]?.jsonPrimitive?.contentOrNull ?: return null
            if (content.length > NostrLimits.MAX_CONTENT_LENGTH) return null
            SignedEvent(id, pubkey, createdAt, kind, tags, content, signature)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Full gate for a signer-returned event against the originating
     * request: exact pubkey/kind/content/tags/created_at match, canonical
     * id recomputation and BIP-340 verification. Returns null-reject codes
     * that never leak payload bytes.
     */
    fun validate(request: SignEventRequest, event: SignedEvent, hasher: EventHasher = Sha256EventHasher): String? {
        if (event.pubkeyHex != request.accountPubkeyHex) return "wrong_pubkey"
        if (event.kind != request.kind) return "payload_mismatch.kind"
        if (event.content != request.content) return "payload_mismatch.content"
        if (event.tags != request.tags) return "payload_mismatch.tags"
        if (event.createdAtSeconds != request.createdAtSeconds) return "payload_mismatch.created_at"
        if (event.idHex != request.eventIdHex(hasher)) return "bad_id"
        val pubkeyBytes = hexToBytes(event.pubkeyHex, 32) ?: return "bad_pubkey_encoding"
        val idBytes = hexToBytes(event.idHex, 32) ?: return "bad_id_encoding"
        val signatureBytes = hexToBytes(event.signatureHex, 64) ?: return "bad_signature_encoding"
        if (!SchnorrVerification.verify(hasher, pubkeyBytes, idBytes, signatureBytes)) return "bad_signature"
        return null
    }

    /** Parse + validate in one step; null reason = accepted. */
    fun validateJson(request: SignEventRequest, eventJson: String, hasher: EventHasher = Sha256EventHasher): Pair<SignedEvent, String?> {
        val event = parseSignedEvent(eventJson)
            ?: return Pair(SignedEvent("", "", 0, 0, emptyList(), "", ""), "malformed_event")
        return Pair(event, validate(request, event, hasher))
    }

    private fun hexToBytes(hex: String, size: Int): ByteArray? {
        if (hex.length != size * 2) return null
        val bytes = ByteArray(size)
        for (index in hex.indices) {
            val digit = hex[index].digitFromHex() ?: return null
            if (index % 2 == 0) bytes[index / 2] = (digit shl 4).toByte() else bytes[index / 2] = (bytes[index / 2].toInt() or digit).toByte()
        }
        return bytes
    }

    private fun Char.digitFromHex(): Int? = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        else -> null
    }
}
