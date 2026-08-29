package space.bitos.core.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.bitos.core.model.EventId
import space.bitos.core.model.NostrEvent
import space.bitos.core.model.NostrLimits
import space.bitos.core.model.Pubkey
import space.bitos.core.model.RelayUrl

/**
 * NIP-01 canonical event serialization, strict bounded decoding and ID
 * verification. Shared by iOS, Android and fixtures so every platform agrees
 * byte-for-byte on what an event ID commits to.
 *
 * Signature (Schnorr BIP-340) verification is intentionally out of scope
 * here: it is the SBC-006 crypto-spike decision and lives behind a separate
 * verifier port so it can be an audited platform implementation.
 */
object NostrEventCodec {

    class Rejected(reason: String) : Exception(reason)

    private val json = Json { ignoreUnknownKeys = true }

    private val hex64 = Regex("^[0-9a-f]{64}$")
    private val hex128 = Regex("^[0-9a-f]{128}$")

    // ---------------------------------------------------------------------
    // Canonical serialization + ID
    // ---------------------------------------------------------------------

    /** NIP-01 JSON string escaping: exact byte parity is required for hashing. */
    fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (char in value) {
            when (char) {
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                else ->
                    if (char.code < 0x20) {
                        out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(char)
                    }
            }
        }
        return out.toString()
    }

    fun serializeForId(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): String {
        val builder = StringBuilder()
        builder.append("[0,\"").append(pubkey).append("\",")
            .append(createdAt).append(',').append(kind).append(',')
        builder.append('[')
        tags.forEachIndexed { tagIndex, tag ->
            if (tagIndex > 0) builder.append(',')
            builder.append('[')
            tag.forEachIndexed { itemIndex, item ->
                if (itemIndex > 0) builder.append(',')
                builder.append('"').append(escape(item)).append('"')
            }
            builder.append(']')
        }
        builder.append(']')
        builder.append(",\"").append(escape(content)).append("\"]")
        return builder.toString()
    }

    fun computeId(
        hasher: EventHasher,
        event: NostrEvent,
    ): String = computeId(hasher, event.pubkey.value, event.createdAt, event.kind, event.tags, event.content)

    fun computeId(
        hasher: EventHasher,
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): String = hasher.sha256(serializeForId(pubkey, createdAt, kind, tags, content).encodeToByteArray()).toLowercaseHex()

    /** True when the event's declared ID matches its canonical content hash. */
    fun verifyId(hasher: EventHasher, event: NostrEvent): Boolean =
        hex64.matches(event.id.value) && computeId(hasher, event) == event.id.value

    /**
     * BIP-340 Schnorr signature verification over the event ID (SBC-006).
     * The second display-trust stage after [verifyId]: unsigned events and
     * events whose signature does not verify return false and must not reach
     * projection surfaces (non-negotiable principle 2).
     */
    fun verifySignature(hasher: EventHasher, event: NostrEvent): Boolean {
        val signature = event.signature ?: return false
        val pubkeyBytes = parseHexBytes(event.pubkey.value, 32) ?: return false
        val idBytes = parseHexBytes(event.id.value, 32) ?: return false
        val signatureBytes = parseHexBytes(signature, 64) ?: return false
        return space.bitos.core.crypto.SchnorrVerification.verify(
            hasher, pubkeyBytes, idBytes, signatureBytes,
        )
    }

    private fun parseHexBytes(hex: String, expectBytes: Int): ByteArray? {
        if (hex.length != expectBytes * 2) return null
        val out = ByteArray(expectBytes)
        for (i in hex.indices) {
            val digit = when (hex[i]) {
                in '0'..'9' -> hex[i] - '0'
                in 'a'..'f' -> hex[i] - 'a' + 10
                else -> return null
            }
            if (i % 2 == 0) out[i / 2] = (digit shl 4).toByte() else out[i / 2] = (out[i / 2] + digit).toByte()
        }
        return out
    }

    // ---------------------------------------------------------------------
    // Relay message decoding
    // ---------------------------------------------------------------------

    /**
     * Decode a `["EVENT", <subId>, <event>]` relay message into a verified
     * domain event. Rejects structural violations, oversized payloads and
     * ID mismatches; tolerant of unknown top-level fields per NIP-01.
     */
    fun decodeRelayEvent(hasher: EventHasher, message: String, source: RelayUrl?): NostrEvent {
        if (message.length > NostrLimits.MAX_EVENT_BYTES) throw Rejected("event exceeds size bound")
        val element = try {
            json.parseToJsonElement(message)
        } catch (_: Exception) {
            throw Rejected("message is not valid JSON")
        }
        val array = element as? JsonArray ?: throw Rejected("relay message is not an array")
        if (array.size != 3 || array[0].jsonPrimitive.content != "EVENT") throw Rejected("not an EVENT message")
        val event = array[2].jsonObject
        return decode(hasher, event, source)
    }

    /**
     * Returns the subscription id for a structurally valid relay EVENT frame.
     * This lets feed repositories distinguish an older-page response from a
     * live arrival without trusting or projecting the event payload.
     */
    fun relayEventSubscriptionId(message: String): String? {
        if (message.length > NostrLimits.MAX_EVENT_BYTES) return null
        return try {
            val array = json.parseToJsonElement(message) as? JsonArray ?: return null
            if (array.size != 3 || array[0].jsonPrimitive.content != "EVENT") return null
            val subscription = array[1] as? JsonPrimitive ?: return null
            if (!subscription.isString) return null
            subscription.content.takeIf { it.length <= NostrLimits.MAX_SUBSCRIPTION_ID_LENGTH }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decodes the client-to-relay publish form `["EVENT", event]` — used to
     * verify a frame before sending it (our own notes pass our own gate).
     */
    fun decodeClientEventFrame(hasher: EventHasher, message: String, source: RelayUrl?): NostrEvent {
        if (message.length > NostrLimits.MAX_EVENT_BYTES) throw Rejected("event exceeds size bound")
        val element = try {
            json.parseToJsonElement(message)
        } catch (_: Exception) {
            throw Rejected("message is not valid JSON")
        }
        val array = element as? JsonArray ?: throw Rejected("relay message is not an array")
        if (array.size != 2 || array[0].jsonPrimitive.content != "EVENT") throw Rejected("not a client EVENT frame")
        return decode(hasher, array[1].jsonObject, source)
    }

    fun decodeEventObject(hasher: EventHasher, eventJson: String, source: RelayUrl?): NostrEvent {
        val element = try {
            json.parseToJsonElement(eventJson)
        } catch (_: Exception) {
            throw Rejected("event is not valid JSON")
        }
        return decode(hasher, element.jsonObject, source)
    }

    private fun decode(hasher: EventHasher, event: JsonObject, source: RelayUrl?): NostrEvent {
        val id = string(event["id"]) ?: throw Rejected("missing id")
        val pubkey = string(event["pubkey"]) ?: throw Rejected("missing pubkey")
        if (!hex64.matches(id)) throw Rejected("id is not lowercase hex64")
        if (!hex64.matches(pubkey)) throw Rejected("pubkey is not lowercase hex64")

        val createdAt = long(event["created_at"]) ?: throw Rejected("missing created_at")
        if (createdAt < 0 || createdAt > 4_102_444_800L) throw Rejected("created_at out of range")

        val kind = int(event["kind"]) ?: throw Rejected("missing kind")
        if (kind < 0 || kind > 65_535) throw Rejected("kind out of range")

        val content = string(event["content"]) ?: throw Rejected("missing content")
        if (content.length > NostrLimits.MAX_CONTENT_LENGTH) throw Rejected("content exceeds bound")

        val sig = string(event["sig"])?.takeIf { hex128.matches(it) }

        val tags = decodeTags(event["tags"] ?: throw Rejected("missing tags"))

        val decoded = NostrEvent(
            id = EventId.parse(id) ?: throw Rejected("unparsable id"),
            pubkey = Pubkey.parse(pubkey) ?: throw Rejected("unparsable pubkey"),
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            signature = sig,
            receivedFromRelay = source,
        )
        if (!verifyId(hasher, decoded)) throw Rejected("event id hash mismatch")
        return decoded
    }

    private fun decodeTags(element: JsonElement): List<List<String>> {
        val array = element as? JsonArray ?: throw Rejected("tags is not an array")
        if (array.size > NostrLimits.MAX_TAGS) throw Rejected("tags exceed bound")
        return array.map { tagElement ->
            val tag = tagElement as? JsonArray ?: throw Rejected("tag is not an array")
            if (tag.size > NostrLimits.MAX_TAG_ITEMS) throw Rejected("tag items exceed bound")
            tag.map { item ->
                val primitive = item as? JsonPrimitive ?: throw Rejected("tag item is not a string")
                val value = primitive.content
                if (value.length > NostrLimits.MAX_TAG_ITEM_LENGTH) throw Rejected("tag item exceeds bound")
                value
            }
        }
    }

    /** Build a NIP-01 `["REQ", subId, filterJson]` subscription message. */
    fun encodeRequest(subscriptionId: String, filterJson: String): String {
        if (subscriptionId.length > NostrLimits.MAX_SUBSCRIPTION_ID_LENGTH) throw Rejected("subscription id exceeds bound")
        return "[\"REQ\",\"" + escape(subscriptionId) + "\"," + filterJson + "]"
    }

    /** Build one NIP-01 REQ carrying multiple bounded filters. */
    fun encodeRequest(subscriptionId: String, filterJsons: List<String>): String {
        if (subscriptionId.length > NostrLimits.MAX_SUBSCRIPTION_ID_LENGTH) throw Rejected("subscription id exceeds bound")
        if (filterJsons.isEmpty() || filterJsons.size > 8) throw Rejected("filter count is outside bound")
        return "[\"REQ\",\"" + escape(subscriptionId) + "\"," + filterJsons.joinToString(",") + "]"
    }

    /**
     * APP-012 ids REQ (origin-note fetch): hex-64 ids only, capped at 100
     * per request (relay convention). Null when nothing valid remains.
     */
    fun encodeIdsRequest(subscriptionId: String, ids: List<String>): String? {
        val bounded = ids.filter { id -> id.length == 64 && id.all { it.isDigit() || it in 'a'..'f' } }.take(100)
        if (bounded.isEmpty()) return null
        return encodeRequest(subscriptionId, "{\"ids\":[" + bounded.joinToString(",") { "\"" + it + "\"" } + "]}")
    }

    /**
     * APP-012/APP-018 blocked-list REQ (NIP-51 kind 10004): one hex
     * author, newest head only. Null when the pubkey is malformed.
     */
    fun encodeBlockListRequest(subscriptionId: String, accountPubkey: String): String? {
        if (accountPubkey.length != 64 || !accountPubkey.all { it.isDigit() || it in 'a'..'f' }) return null
        return encodeRequest(subscriptionId, "{\"kinds\":[10004],\"authors\":[\"$accountPubkey\"],\"limit\":1}")
    }

    /** Build a NIP-01 `["CLOSE", subId]` message. */
    fun encodeClose(subscriptionId: String): String =
        "[\"CLOSE\",\"" + escape(subscriptionId) + "\"]"

    private fun string(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun long(element: JsonElement?): Long? =
        (element as? JsonPrimitive)?.content?.toLongOrNull()

    private fun int(element: JsonElement?): Int? =
        (element as? JsonPrimitive)?.content?.toIntOrNull()
}
