package space.bitos.core.publish

import space.bitos.core.crypto.Nip44
import space.bitos.core.crypto.SchnorrSigning
import space.bitos.core.model.NostrEvent
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.Sha256EventHasher
import kotlin.random.Random

/**
 * NIP-17 Secure DMs (legacy Flutter `Nip17` / web `nostr-tools/nip17`
 * parity) — rumor → seal → gift wrap (NIP-59):
 *
 *  • **Rumor** (kind 14): the plaintext chat message — unsigned, lives
 *    only inside the seal.
 *  • **Seal** (kind 13): the rumor JSON, NIP-44 encrypted with the
 *    sender↔recipient conversation key — signed by the real sender.
 *  • **Gift wrap** (kind 1059): the seal JSON, NIP-44 encrypted to the
 *    recipient — signed by a fresh throwaway key, `created_at` randomized
 *    within the past two days so relays can't correlate timing.
 */
/** Platform-injectable clock; tests pin it deterministically. */
object SecureDmClock {
    var now: () -> Long = { 0L }
}

object SecureDmComposer {

    const val KIND_SEAL = 13
    const val KIND_PRIVATE_DM = 14
    const val KIND_GIFT_WRAP = 10_059

    private const val TWO_DAYS = 2 * 24 * 60 * 60

    /** Fully assembled Secure DM ready to publish (the gift wrap). */
    data class WrappedMessage(
        val rumor: NostrEvent,
        val seal: NostrEvent,
        val wrap: NostrEvent,
    )

    /**
     * Wraps one chat message for [recipientPubkey].
     * @param random injectable for tests (nonce + throwaway key + timestamps).
     */
    fun wrapMessage(
        senderPrivateKeyHex: String,
        recipientPubkey: String,
        content: String,
        hasher: EventHasher = Sha256EventHasher,
        random: Random = Random.Default,
        nowSeconds: Long = SecureDmClock.now(),
    ): WrappedMessage? {
        val secret = Nip44.hexToBytes(senderPrivateKeyHex) ?: return null
        if (secret.size != 32) return null
        val senderPubkey = Nip44.bytesToHex(SchnorrSigning.publicKey(secret, hasher) ?: return null)
        val now = nowSeconds

        // Rumor — kind 14, unsigned, tags the recipient.
        val rumorTags = listOf(listOf("p", recipientPubkey))
        val rumorId = space.bitos.core.nostr.NostrEventCodec.computeId(
            hasher, senderPubkey, now, KIND_PRIVATE_DM, rumorTags, content,
        )
        val rumor = NostrEvent(
            id = space.bitos.core.model.EventId.parse(rumorId) ?: return null,
            pubkey = space.bitos.core.model.Pubkey.parse(senderPubkey) ?: return null,
            createdAt = now,
            kind = KIND_PRIVATE_DM,
            tags = rumorTags,
            content = content,
            signature = null,
            receivedFromRelay = null,
        )

        // Seal — kind 13, rumor encrypted to the recipient, signed by the sender.
        val seal = sealRumor(rumor, senderPrivateKeyHex, recipientPubkey, hasher, random, nowSeconds) ?: return null

        // Gift wrap — kind 1059, seal encrypted with a throwaway key.
        val wrap = wrapSeal(seal, recipientPubkey, hasher, random, nowSeconds) ?: return null

        return WrappedMessage(rumor, seal, wrap)
    }

    /** Unwraps a gift wrap addressed to [myPrivateKeyHex] → the inner rumor. */
    fun unwrap(
        giftWrap: NostrEvent,
        myPrivateKeyHex: String,
        hasher: EventHasher = Sha256EventHasher,
    ): NostrEvent? {
        return try {
            // 1. Gift wrap → seal (conversation key: throwaway ↔ us).
            val wrapKey = Nip44.conversationKey(myPrivateKeyHex, giftWrap.pubkey.value, hasher) ?: return null
            val sealJson = Nip44.decrypt(giftWrap.content, wrapKey, hasher)
            val seal = parseInnerEvent(sealJson) ?: return null

            // 2. Seal → rumor (conversation key: real sender ↔ us).
            val sealKey = Nip44.conversationKey(myPrivateKeyHex, seal.pubkey.value, hasher) ?: return null
            val rumorJson = Nip44.decrypt(seal.content, sealKey, hasher)
            val rumor = parseInnerEvent(rumorJson) ?: return null

            if (rumor.kind != KIND_PRIVATE_DM) return null
            rumor
        } catch (_: Exception) {
            null
        }
    }

    // ── Internals ───────────────────────────────────────────────────

    private fun sealRumor(
        rumor: NostrEvent,
        senderPrivateKeyHex: String,
        recipientPubkey: String,
        hasher: EventHasher,
        random: Random,
        nowSeconds: Long,
    ): NostrEvent? {
        val conversationKey = Nip44.conversationKey(senderPrivateKeyHex, recipientPubkey, hasher) ?: return null
        val sealed = Nip44.encrypt(serializeInner(rumor), conversationKey, randomNonce(random), hasher)
        val senderPubkey = Nip44.bytesToHex(
            SchnorrSigning.publicKey(Nip44.hexToBytes(senderPrivateKeyHex)!!, hasher) ?: return null,
        )
        val timestamp = randomTimestamp(random, nowSeconds)
        val tags = emptyList<List<String>>()
        val id = space.bitos.core.nostr.NostrEventCodec.computeId(hasher, senderPubkey, timestamp, KIND_SEAL, tags, sealed)
        val signature = SchnorrSigning.sign(
            Nip44.hexToBytes(id)!!,
            Nip44.hexToBytes(senderPrivateKeyHex)!!,
            randomNonce(random),
            hasher,
        ) ?: return null
        return NostrEvent(
            id = space.bitos.core.model.EventId.parse(id) ?: return null,
            pubkey = space.bitos.core.model.Pubkey.parse(senderPubkey) ?: return null,
            createdAt = timestamp,
            kind = KIND_SEAL,
            tags = tags,
            content = sealed,
            signature = Nip44.bytesToHex(signature),
            receivedFromRelay = null,
        )
    }

    private fun wrapSeal(
        seal: NostrEvent,
        recipientPubkey: String,
        hasher: EventHasher,
        random: Random,
        nowSeconds: Long,
    ): NostrEvent? {
        val throwawaySecret = ByteArray(32) { random.nextInt(256).toByte() }
        val throwawayHex = Nip44.bytesToHex(throwawaySecret)
        val throwawayPubkey = Nip44.bytesToHex(SchnorrSigning.publicKey(throwawaySecret, hasher) ?: return null)
        val conversationKey = Nip44.conversationKey(throwawayHex, recipientPubkey, hasher) ?: return null
        val wrapped = Nip44.encrypt(serializeInner(seal), conversationKey, randomNonce(random), hasher)
        val timestamp = randomTimestamp(random, nowSeconds)
        val tags = listOf(listOf("p", recipientPubkey))
        val id = space.bitos.core.nostr.NostrEventCodec.computeId(hasher, throwawayPubkey, timestamp, KIND_GIFT_WRAP, tags, wrapped)
        val signature = SchnorrSigning.sign(
            Nip44.hexToBytes(id)!!,
            throwawaySecret,
            randomNonce(random),
            hasher,
        ) ?: return null
        return NostrEvent(
            id = space.bitos.core.model.EventId.parse(id) ?: return null,
            pubkey = space.bitos.core.model.Pubkey.parse(throwawayPubkey) ?: return null,
            createdAt = timestamp,
            kind = KIND_GIFT_WRAP,
            tags = tags,
            content = wrapped,
            signature = Nip44.bytesToHex(signature),
            receivedFromRelay = null,
        )
    }

    /** Web parity: `created_at` randomized within the last 48 h. */
    private fun randomTimestamp(random: Random, nowSeconds: Long): Long =
        nowSeconds - random.nextLong(TWO_DAYS.toLong())

    private fun randomNonce(random: Random): ByteArray = ByteArray(32) { random.nextInt(256).toByte() }

    /** JSON layout of inner events — content last (nostr-tools parity). */
    private fun serializeInner(event: NostrEvent): String = buildString {
        append('{')
        append("\"id\":\"${event.id.value}\",")
        append("\"pubkey\":\"${event.pubkey.value}\",")
        append("\"created_at\":${event.createdAt},")
        append("\"kind\":${event.kind},")
        append("\"tags\":[")
        event.tags.forEachIndexed { i, tag ->
            if (i > 0) append(',')
            append('[')
            tag.forEachIndexed { j, value ->
                if (j > 0) append(',')
                append('"')
                append(value.replace("\\", "\\\\").replace("\"", "\\\""))
                append('"')
            }
            append(']')
        }
        append("],")
        append("\"content\":\"${event.content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\",")
        append("\"sig\":\"${event.signature ?: ""}\"")
        append('}')
    }

    /** Minimal inner-event JSON parse (id, pubkey, created_at, kind, tags, content). */
    internal fun parseInnerEvent(json: String): NostrEvent? = try {
        kotlinx.serialization.json.Json.parseToJsonElement(json).let { root ->
            val obj = root as? kotlinx.serialization.json.JsonObject ?: return null
            val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
            val pubkey = (obj["pubkey"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
            val createdAt = (obj["created_at"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: return null
            val kind = (obj["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: return null
            val content = (obj["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
            val tags = (obj["tags"] as? kotlinx.serialization.json.JsonArray)?.map { tagElement ->
                (tagElement as? kotlinx.serialization.json.JsonArray)?.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" } ?: emptyList()
            } ?: emptyList()
            val sig = (obj["sig"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            NostrEvent(
                id = space.bitos.core.model.EventId.parse(id) ?: return null,
                pubkey = space.bitos.core.model.Pubkey.parse(pubkey) ?: return null,
                createdAt = createdAt,
                kind = kind,
                tags = tags,
                content = content,
                signature = sig,
                receivedFromRelay = null,
            )
        }
    } catch (_: Exception) {
        null
    }
}
