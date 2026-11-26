package space.bitos.core.identity.nip46

import space.bitos.core.identity.NostrKeyCodec
import space.bitos.core.model.RelayUrl

/**
 * NIP-46 connection target parsed from a `bunker://` or
 * `nostrconnect://` URI (SBC-008b). Public data only — the client
 * transport secret never appears here; it is generated and sealed
 * natively.
 */
data class Nip46Connection(
    /** hex64 pubkey of the remote signer (bunker). */
    val remotePubkeyHex: String,
    /** 1..[Nip46Uri.MAX_RELAYS] validated wss relays carrying signer traffic. */
    val relays: List<RelayUrl>,
    /** `secret` query param: single-use for bunker, pairing echo for nostrconnect. */
    val secret: String?,
    /** Optional comma-separated permission request (never broad by default). */
    val perms: String?,
)

/**
 * Pure URI codec for both NIP-46 connection methods. Bounds: signer relays
 * are `wss`-only (stricter than the feed policy), at most five, and the
 * whole URI is length-capped before any parsing.
 */
object Nip46Uri {

    const val MAX_RELAYS = 5
    const val MAX_URI_LENGTH = 2_048
    const val MAX_SECRET_LENGTH = 256

    /** `bunker://<hex|npub>?relay=wss://…&relay=…&secret=…` */
    fun parseBunker(uri: String): Nip46Connection? {
        val trimmed = uri.trim()
        if (trimmed.length !in 12..MAX_URI_LENGTH) return null
        val lowered = trimmed.lowercase()
        if (!lowered.startsWith("bunker://")) return null
        return parseCommon(trimmed.removePrefix("bunker://").removePrefix("BUNKER://"))
    }

    /** `nostrconnect://<client-pubkey>?relay=…&secret=…&perms=…` */
    fun parseNostrconnect(uri: String): Nip46Connection? {
        val trimmed = uri.trim()
        if (trimmed.length !in 18..MAX_URI_LENGTH) return null
        val lowered = trimmed.lowercase()
        if (!lowered.startsWith("nostrconnect://")) return null
        return parseCommon(trimmed.removePrefix("nostrconnect://").removePrefix("NOSTRCONNECT://"))
    }

    /**
     * Builds the `nostrconnect://` pairing URI/QR payload: client transport
     * pubkey + relays the client listens on + the pairing secret the bunker
     * must echo. URL-encoded exactly like reference implementations.
     */
    fun buildNostrconnect(
        clientTransportPubkeyHex: String,
        relays: List<RelayUrl>,
        secret: String,
        perms: String? = null,
        appName: String? = null,
    ): String? {
        if (!clientTransportPubkeyHex.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (relays.isEmpty() || relays.size > MAX_RELAYS) return null
        if (secret.isEmpty() || secret.length > MAX_SECRET_LENGTH) return null
        val builder = StringBuilder("nostrconnect://").append(clientTransportPubkeyHex)
        builder.append("?relay=").append(urlEncode(relays.first().value))
        relays.drop(1).forEach { relay ->
            builder.append("&relay=").append(urlEncode(relay.value))
        }
        builder.append("&secret=").append(urlEncode(secret))
        perms?.takeIf { it.isNotEmpty() }?.let { builder.append("&perms=").append(urlEncode(it)) }
        appName?.takeIf { it.isNotEmpty() }?.let { builder.append("&name=").append(urlEncode(it)) }
        val uri = builder.toString()
        return if (uri.length <= MAX_URI_LENGTH) uri else null
    }

    // ── internals ───────────────────────────────────────────────────

    private fun parseCommon(rest: String): Nip46Connection? {
        val queryStart = rest.indexOf('?')
        val keyPart = if (queryStart >= 0) rest.substring(0, queryStart) else rest
        val pubkey = parsePubkey(keyPart) ?: return null
        if (queryStart < 0) return null // relays are mandatory in both forms

        val relays = mutableListOf<RelayUrl>()
        var secret: String? = null
        var perms: String? = null
        val query = rest.substring(queryStart + 1)
        if (query.length > MAX_URI_LENGTH) return null
        query.split('&').forEach { pair ->
            val equals = pair.indexOf('=')
            if (equals <= 0) return@forEach
            val name = pair.substring(0, equals).lowercase()
            val value = urlDecode(pair.substring(equals + 1))
            when (name) {
                "relay" -> RelayUrl.parse(value)
                    ?.takeIf { it.value.startsWith("wss://") }
                    ?.let { if (relays.size < MAX_RELAYS) relays += it }
                "secret" -> if (value.isNotEmpty() && value.length <= MAX_SECRET_LENGTH) secret = value
                "perms" -> if (value.isNotEmpty() && value.length <= 256) perms = value
                else -> Unit // metadata params (name/url/image) are ignored
            }
        }
        if (relays.isEmpty()) return null
        return Nip46Connection(pubkey, relays.distinct(), secret, perms)
    }

    private fun parsePubkey(value: String): String? = when {
        value.matches(Regex("^[0-9a-f]{64}$")) -> value
        value.startsWith("npub1") -> NostrKeyCodec.parseNpub(value)
        else -> null
    }

    private fun urlEncode(value: String): String {
        val out = StringBuilder(value.length + 8)
        value.forEach { char ->
            when {
                char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
                    char == '-' || char == '.' || char == '_' || char == '~' -> out.append(char)
                else -> {
                    val bytes = char.toString().encodeToByteArray()
                    bytes.forEach { byte -> out.append('%').append(hexDigit((byte.toInt() shr 4) and 0xf)).append(hexDigit(byte.toInt() and 0xf)) }
                }
            }
        }
        return out.toString()
    }

    private fun urlDecode(value: String): String {
        if (!value.contains('%') && !value.contains('+')) return value
        val bytes = mutableListOf<Int>()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '%' && index + 2 < value.length -> {
                    val hi = value[index + 1].digitToIntOrNull(16) ?: return value
                    val lo = value[index + 2].digitToIntOrNull(16) ?: return value
                    bytes += (hi shl 4) or lo
                    index += 3
                }
                char == '+' -> {
                    bytes += ' '.code
                    index += 1
                }
                else -> {
                    bytes += char.code and 0xff
                    index += 1
                }
            }
        }
        return bytes.map { it.toByte() }.toByteArray().decodeToString()
    }

    private fun hexDigit(value: Int): Char = "0123456789ABCDEF"[value]
}
