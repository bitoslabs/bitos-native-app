package space.bitos.core.model

/**
 * NIP-57 zap objects. The client path COMPOSES kind-9734 zap requests
 * (signed by the payer, handed to the recipient's LNURL server via the
 * `nostr` param — never published to relays by us) and COUNTS kind-9735
 * receipts that fan in through the verified gate. APP-012 additionally
 * recovers the msat amount from the receipt's bolt11 tag and the verified
 * sender from the embedded (signed) 9734 request.
 */
object ZapReceipt {
    const val REQUEST_KIND = 9_734
    const val RECEIPT_KIND = 9_735

    /** Target event id of a zap receipt's `e` tag, when present. */
    fun targetEventId(event: NostrEvent): String? =
        if (event.kind != RECEIPT_KIND) null
        else event.tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
            ?.takeIf { it.length == 64 }

    /**
     * Zap amount in millisatoshi from the receipt's `bolt11` tag HRP
     * (BOLT-11 multiplier table). Null when absent or unparsable.
     */
    fun amountMillisats(event: NostrEvent): Long? {
        if (event.kind != RECEIPT_KIND) return null
        val invoice = event.tags.firstOrNull { it.firstOrNull() == "bolt11" }?.getOrNull(1) ?: return null
        return Bolt11.amountMillisats(invoice)
    }

    /**
     * Verified sender (payer) pubkey from the receipt's `description` tag:
     * the embedded kind-9734 zap request must decode through the client
     * gate (canonical ID + BIP-340 signature) and target [recipientPubkey]
     * through its `p` tag — otherwise the receipt says nothing about who
     * paid and we attribute nothing.
     */
    fun senderPubkey(event: NostrEvent, recipientPubkey: String): String? {
        if (event.kind != RECEIPT_KIND) return null
        val description = event.tags.firstOrNull { it.firstOrNull() == "description" }?.getOrNull(1) ?: return null
        val frame = "[\"EVENT\",$description]"
        val request = try {
            space.bitos.core.nostr.NostrEventCodec.decodeClientEventFrame(
                space.bitos.core.nostr.Sha256EventHasher, frame, null,
            )
        } catch (_: space.bitos.core.nostr.NostrEventCodec.Rejected) {
            return null
        }
        if (request.kind != REQUEST_KIND) return null
        val targetsRecipient = request.tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == recipientPubkey }
        if (!targetsRecipient) return null
        return request.pubkey.value
    }

    /**
     * APP-014 paid-matching key: the embedded 9734's canonical event id
     * (verified through the same client gate as [senderPubkey]). The zap
     * sheet compares this against its own request id — an exact match is
     * our receipt, not another user's zap to the same note.
     */
    fun embeddedRequestId(event: NostrEvent): String? {
        if (event.kind != RECEIPT_KIND) return null
        val description = event.tags.firstOrNull { it.firstOrNull() == "description" }?.getOrNull(1) ?: return null
        val frame = "[\"EVENT\",$description]"
        return try {
            space.bitos.core.nostr.NostrEventCodec.decodeClientEventFrame(
                space.bitos.core.nostr.Sha256EventHasher, frame, null,
            ).takeIf { it.kind == REQUEST_KIND }?.id?.value
        } catch (_: space.bitos.core.nostr.NostrEventCodec.Rejected) {
            null
        }
    }
}

/**
 * BOLT-11 human-readable-part amount parsing (APP-012): the invoice amount
 * lives in the HRP before the `1` separator with a unit multiplier.
 * Mainnet `lnbc` HRP only (zaps are mainnet); everything after `1` is
 * opaque to us. Bounded and overflow-safe.
 */
object Bolt11 {
    private const val MAX_INVOICE_LENGTH = 4_096
    private const val MAX_MILLISATS = 1_000_000_000_000_000L // 10,000 BTC in msat

    /**
     * APP-014 invoice expiry (epoch seconds) from the data-part TLV:
     * timestamp (type 3) + expiry (type 6, default 3600 s). Null when the
     * invoice does not parse. Bounded and overflow-safe.
     */
    fun expirySeconds(invoice: String): Long? {
        if (invoice.length !in 20..MAX_INVOICE_LENGTH) return null
        val separator = invoice.lastIndexOf('1')
        if (separator < 4 || separator == invoice.length - 7) return null
        if (!invoice.substring(0, 4).equals("lnbc", ignoreCase = true)) return null
        val dataPart = invoice.substring(separator + 1).dropLast(6) // strip checksum
        if (dataPart.isEmpty()) return null
        val hasLower = dataPart.any { it in 'a'..'z' }
        val hasUpper = dataPart.any { it in 'A'..'Z' }
        if (hasLower && hasUpper) return null
        val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        val words = IntArray(dataPart.length) { index ->
            charset.indexOf(dataPart[index].lowercaseChar()).takeIf { it >= 0 } ?: return null
        }
        // 5-bit words → bytes (regroup; trailing bits must be zero-padding).
        val totalBits = words.size * 5
        val bytes = ByteArray(totalBits / 8)
        for (index in bytes.indices) {
            var value = 0
            for (offset in 0..7) {
                val bitIndex = index * 8 + offset
                val word = words[bitIndex / 5]
                val bitInWord = 4 - (bitIndex % 5)
                value = (value shl 1) or ((word shr bitInWord) and 1)
            }
            bytes[index] = value.toByte()
        }
        // TLV walk: [type][len][value]… with the BOLT-11 types we need.
        var index = 0
        var timestamp = 0L
        var expiry = 3_600L
        while (index + 1 < bytes.size) {
            val type = bytes[index].toInt() and 0xff
            val length = bytes[index + 1].toInt() and 0xff
            if (length < 0 || index + 2 + length > bytes.size) return null
            when (type) {
                3 -> {
                    if (length > 7) return null
                    var value = 0L
                    for (byteIndex in 0 until length) {
                        value = (value shl 8) or (bytes[index + 2 + byteIndex].toLong() and 0xff)
                    }
                    timestamp = value
                }
                6 -> {
                    if (length > 7) return null
                    var value = 0L
                    for (byteIndex in 0 until length) {
                        value = (value shl 8) or (bytes[index + 2 + byteIndex].toLong() and 0xff)
                    }
                    expiry = value
                }
            }
            index += 2 + length
        }
        if (timestamp <= 0) return null
        return timestamp + expiry
    }

    /** msat per unit for each BOLT-11 multiplier (and whole BTC). */
    private val unitMillisats = mapOf(
        "" to 100_000_000_000L,
        "m" to 100_000_000L,
        "u" to 100_000L,
        "n" to 100L,
        "p" to 1L, // pico = 0.1 msat; only whole-msat invoices parse
    )

    /**
     * Parses the msat amount from an invoice HRP like `lnbc20u1…`.
     * Pico-amounts that are not whole msat return null. Bounds: length
     * 20..4096, ≤9 amount digits, result ≤ 1e15 msat.
     */
    fun amountMillisats(invoice: String): Long? {
        if (invoice.length !in 20..MAX_INVOICE_LENGTH) return null
        // The bech32 data part never contains '1', so the HRP separator is
        // the final '1' (amount digits may themselves contain '1').
        val separator = invoice.lastIndexOf('1')
        if (separator < 5 || separator == invoice.length - 1) return null
        val hrp = invoice.substring(0, separator)
        if (!hrp.startsWith("lnbc")) return null
        val amountPart = hrp.substring(4)
        if (amountPart.isEmpty()) return null // amount-less invoice
        val unit = amountPart.last().let { last -> if (last in "munp") last.toString() else "" }
        val digits = if (unit.isEmpty()) amountPart else amountPart.dropLast(1)
        if (digits.isEmpty() || digits.length > 9 || digits.any { it !in '0'..'9' }) return null
        if (digits.all { it == '0' }) return null
        val perUnit = unitMillisats[unit] ?: return null
        val amount = digits.toLongOrNull() ?: return null
        if (perUnit == 1L && unit == "p" && amount % 10 != 0L) return null // 0.1-msat granularity
        val msat = if (unit == "p") amount / 10 else amount * perUnit
        if (msat !in 1..MAX_MILLISATS) return null
        return msat
    }
}

/**
 * Pure LNURL-pay (LUD-06/NIP-57) message parsing and URL building. HTTP is
 * platform-side; every JSON shape is bounded and hostile-input tolerant.
 */
object LnurlPay {

    class Invalid(reason: String) : Exception(reason)

    data class PayRequest(
        val callback: String,
        val minSendableMillisats: Long,
        val maxSendableMillisats: Long,
        val allowsNostr: Boolean,
        val nostrPubkey: String?,
    ) {
        init {
            require(callback.startsWith("https://")) { "callback must be HTTPS" }
            require(callback.length <= 2048)
            require(minSendableMillisats in 1..1_000_000_000_000L)
            require(maxSendableMillisats >= minSendableMillisats)
            nostrPubkey?.let { require(it.length == 64) }
        }
    }

    data class Invoice(val paymentRequest: String) {
        init {
            require(paymentRequest.startsWith("lnbc", ignoreCase = true)) { "not a bolt11 invoice" }
            require(paymentRequest.length in 20..4096)
        }
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** Parses the LNURL-pay params document served at /.well-known/lnurlp/<user>. */
    fun parsePayRequest(body: String): PayRequest? = try {
        val root = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject ?: return null
        val tag = (root["tag"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (tag != "payRequest") return null
        PayRequest(
            callback = (root["callback"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null,
            minSendableMillisats = (root["minSendable"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: return null,
            maxSendableMillisats = (root["maxSendable"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: return null,
            allowsNostr = (root["allowsNostr"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
            nostrPubkey = (root["nostrPubkey"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotEmpty() },
        )
    } catch (_: Exception) {
        null
    }

    /** Builds the callback URL with amount, optional nostr event, and lnurl. */
    fun buildCallbackUrl(
        payRequest: PayRequest,
        amountMillisats: Long,
        nostrEventJson: String?,
        lnurlHint: String,
    ): String? {
        if (amountMillisats !in payRequest.minSendableMillisats..payRequest.maxSendableMillisats) return null
        if (nostrEventJson != null && (!payRequest.allowsNostr || nostrEventJson.length > 65_536)) return null
        val base = payRequest.callback + (if (payRequest.callback.contains('?')) '&' else '?')
        val amount = "amount=$amountMillisats"
        val nostr = nostrEventJson?.let { "&nostr=" + encodeQuery(it) } ?: ""
        val hint = lnurlHint.takeIf { it.isNotBlank() }?.let { "&lnurl=" + encodeQuery(it) } ?: ""
        return base + amount + nostr + hint
    }

    /** Parses the callback response carrying the bolt11 invoice. */
    fun parseInvoice(body: String): Invoice? = try {
        val root = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject ?: return null
        val pr = (root["pr"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        // Explicit relay errors are surfaced as null (caller shows failure).
        if ((root["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "ERROR") return null
        Invoice(pr)
    } catch (_: Exception) {
        null
    }

    private fun encodeQuery(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and 0xff
            when {
                c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                    c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code -> append(c.toChar())
                else -> {
                    append('%')
                    append("0123456789ABCDEF"[c ushr 4])
                    append("0123456789ABCDEF"[c and 0x0f])
                }
            }
        }
    }
}
