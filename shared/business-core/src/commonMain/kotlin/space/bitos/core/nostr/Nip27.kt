package space.bitos.core.nostr

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * NIP-27 rich-content tokenizer (unified feature spec §3.5, APP-005):
 * splits note content into text, links, hashtags and nostr entities.
 *
 * Entities: npub/nprofile (PROFILE), note/nevent (NOTE), naddr (ADDRESS)
 * in bare, `nostr:`-prefixed and `@`-prefixed forms. Invalid bech32
 * (bad checksum/mixed case) stays inert text. TLV payloads are walked
 * only far enough to recover the type-0 identifier (pubkey / event id)
 * when present; naddr coordinates carry no hex by design.
 */
sealed interface RichToken {
    data class Text(val value: String) : RichToken
    data class Link(val url: String) : RichToken
    data class Hashtag(val tag: String) : RichToken
    data class Nostr(
        /** Raw entity as written (without `nostr:`/`@` prefix). */
        val raw: String,
        val entity: Entity,
        /** Decoded hex pubkey/event id when the type-0 value is 32 bytes. */
        val hex: String?,
        /** APP-008 NIP-33 coordinate (`kind:pubkey:d`) for naddr entities. */
        val coordinate: String? = null,
    ) : RichToken

    enum class Entity { PROFILE, NOTE, ADDRESS }
}

object Nip27 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val MAX_ENTITY_LENGTH = 300

    // Entity first, so `nostr:npub1…` wins over a URL swallowing it.
    private val pattern = Regex(
        "(?:nostr:)?@?(npub1|nprofile1|note1|nevent1|naddr1)[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{6,}" +
            "|https?://\\S+" +
            "|(?:^|[\\s(\\[])#([\\p{L}\\p{N}_-]{2,60})"
    )

    fun tokenize(content: String): List<RichToken> {
        val out = mutableListOf<RichToken>()
        var cursor = 0
        for (match in pattern.findAll(content)) {
            val start = match.range.first
            if (start > cursor) out += RichToken.Text(content.substring(cursor, start))
            val value = match.value
            val hashtag = match.groupValues[2]
            when {
                hashtag.isNotEmpty() -> {
                    // Re-emit the captured leading whitespace if present.
                    if (value.length > hashtag.length + 1) {
                        out += RichToken.Text(value.substring(0, value.length - hashtag.length - 1))
                    }
                    out += RichToken.Hashtag(hashtag)
                }
                value.startsWith("http", ignoreCase = true) -> out += RichToken.Link(value)
                else -> out += tokenizeEntity(value)
            }
            cursor = match.range.last + 1
        }
        if (cursor < content.length) out += RichToken.Text(content.substring(cursor))
        return mergeText(out)
    }

    private fun tokenizeEntity(candidate: String): RichToken {
        var raw = candidate
        var prefixed = false
        if (raw.startsWith("nostr:")) { raw = raw.removePrefix("nostr:"); prefixed = true }
        if (raw.startsWith("@")) { raw = raw.removePrefix("@"); prefixed = true }
        if (!prefixed && raw == candidate && candidate.startsWith("@")) return RichToken.Text(candidate)

        val hrp = raw.substringBefore('1')
        val bytes = decodeBech32(hrp, raw) ?: return RichToken.Text(candidate)
        return when (hrp) {
            "npub" -> RichToken.Nostr(raw, RichToken.Entity.PROFILE, bytesToHex(bytes))
            "nprofile" -> RichToken.Nostr(raw, RichToken.Entity.PROFILE, tlvType0Hex(bytes))
            "note" -> RichToken.Nostr(raw, RichToken.Entity.NOTE, bytesToHex(bytes))
            "nevent" -> RichToken.Nostr(raw, RichToken.Entity.NOTE, tlvType0Hex(bytes))
            "naddr" -> RichToken.Nostr(raw, RichToken.Entity.ADDRESS, null, tlvType0Coordinate(bytes))
            else -> RichToken.Text(candidate)
        }
    }

    /** Adjacent text fragments (from prefix splits) merge into one run. */
    private fun mergeText(tokens: List<RichToken>): List<RichToken> {
        val out = mutableListOf<RichToken>()
        for (token in tokens) {
            val last = out.lastOrNull()
            if (token is RichToken.Text && last is RichToken.Text) {
                out[out.size - 1] = last.copy(value = last.value + token.value)
            } else if (token !is RichToken.Text || token.value.isNotEmpty()) {
                out += token
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Bridge encoding: compact stable JSON for platform renderers.
    // {"k":"t|l|h|n","v":…,"e":"profile|note|address","x":hex?}
    // ------------------------------------------------------------------

    fun tokensJson(content: String): String =
        tokenize(content).joinToString(prefix = "[", separator = ",", postfix = "]") { token ->
            when (token) {
                is RichToken.Text -> obj("t", token.value)
                is RichToken.Link -> obj("l", token.url)
                is RichToken.Hashtag -> obj("h", token.tag)
                is RichToken.Nostr -> buildJsonObject {
                    put("k", JsonPrimitive("n"))
                    put("v", JsonPrimitive(token.raw))
                    put("e", JsonPrimitive(token.entity.name.lowercase()))
                    token.hex?.let { put("x", JsonPrimitive(it)) }
                }.toString()
            }
        }

    private fun obj(kind: String, value: String) = buildJsonObject {
        put("k", JsonPrimitive(kind))
        put("v", JsonPrimitive(value))
    }.toString()

    // ------------------------------------------------------------------
    // Variable-length bech32 (BIP-173; the key codec is 32-byte-strict by
    // contract, entities are not — TLV payloads vary).
    // ------------------------------------------------------------------

    internal fun decodeBech32(expectedHrp: String, encoded: String): ByteArray? {
        if (encoded.length < 8 || encoded.length > MAX_ENTITY_LENGTH) return null
        val hasLower = encoded.any { it in 'a'..'z' }
        val hasUpper = encoded.any { it in 'A'..'Z' }
        if (hasLower && hasUpper) return null
        val normalized = encoded.lowercase()
        val separator = normalized.lastIndexOf('1')
        if (separator < 1 || separator + 7 > normalized.length) return null
        if (normalized.substring(0, separator) != expectedHrp) return null
        val dataPart = normalized.substring(separator + 1)
        val words = IntArray(dataPart.length) { CHARSET.indexOf(dataPart[it]).takeIf { p -> p >= 0 } ?: return null }
        if (bech32Verify(normalized.substring(0, separator), words)) {
            return wordsToBytesVariable(words.copyOfRange(0, words.size - 6))
        }
        return null
    }

    /** Test/vector helper: encodes raw bytes for an entity hrp. */
    internal fun encodeEntity(hrp: String, bytes: ByteArray): String {
        val words = bytesToWordsVariable(bytes)
        val checksum = bech32Checksum(hrp, words)
        return hrp + "1" + (words + checksum).joinToString("") { CHARSET[it].toString() }
    }

    private fun bech32Polymod(values: IntArray): Int {
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (value in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor value
            for (i in 0..4) if ((top shr i) and 1 == 1) chk = chk xor generators[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val expanded = IntArray(hrp.length * 2 + 1)
        hrp.forEachIndexed { index, char ->
            expanded[index] = char.code ushr 5
            expanded[hrp.length + 1 + index] = char.code and 31
        }
        expanded[hrp.length] = 0
        return expanded
    }

    private fun bech32Checksum(hrp: String, data: IntArray): IntArray {
        val values = hrpExpand(hrp) + data + IntArray(6)
        val polymod = bech32Polymod(values) xor 1
        return IntArray(6) { index -> (polymod shr (5 * (5 - index))) and 31 }
    }

    private fun bech32Verify(hrp: String, data: IntArray): Boolean =
        bech32Polymod(hrpExpand(hrp) + data) == 1

    private fun bytesToWordsVariable(bytes: ByteArray): IntArray {
        val bitCount = bytes.size * 8
        val wordCount = (bitCount + 4) / 5
        val words = IntArray(wordCount)
        for (index in 0 until wordCount) {
            var value = 0
            for (offset in 0 until 5) {
                val bitIndex = index * 5 + offset
                val byteIndex = bitIndex / 8
                val bitInByte = 7 - (bitIndex % 8)
                val bit = if (byteIndex < bytes.size) (bytes[byteIndex].toInt() shr bitInByte) and 1 else 0
                value = (value shl 1) or bit
            }
            words[index] = value
        }
        return words
    }

    private fun wordsToBytesVariable(words: IntArray): ByteArray? {
        val totalBits = words.size * 5
        val remainder = totalBits % 8
        if (remainder >= 5) return null
        if (remainder > 0 && words.last() and ((1 shl remainder) - 1) != 0) return null
        val bytes = ByteArray(totalBits / 8)
        for (index in bytes.indices) {
            var value = 0
            for (offset in 0 until 8) {
                val bitIndex = index * 8 + offset
                val wordIndex = bitIndex / 5
                val bitInWord = 4 - (bitIndex % 5)
                value = (value shl 1) or ((words[wordIndex] shr bitInWord) and 1)
            }
            bytes[index] = value.toByte()
        }
        return bytes
    }

    // ------------------------------------------------------------------
    // TLV (NIP-19): [type:u8, length:u16 BE, value]. Only type 0 is needed.
    // ------------------------------------------------------------------

    private fun tlvType0Hex(bytes: ByteArray): String? {
        var index = 0
        while (index + 3 <= bytes.size) {
            val type = bytes[index].toInt() and 0xff
            val length = ((bytes[index + 1].toInt() and 0xff) shl 8) or (bytes[index + 2].toInt() and 0xff)
            index += 3
            if (index + length > bytes.size) return null
            if (type == 0 && length == 32) return bytesToHex(bytes.copyOfRange(index, index + 32))
            index += length
        }
        return null
    }

    /** APP-008: naddr type-0 payload is the ASCII `kind:pubkey:d` coordinate. */
    private fun tlvType0Coordinate(bytes: ByteArray): String? {
        var index = 0
        while (index + 3 <= bytes.size) {
            val type = bytes[index].toInt() and 0xff
            val length = ((bytes[index + 1].toInt() and 0xff) shl 8) or (bytes[index + 2].toInt() and 0xff)
            index += 3
            if (index + length > bytes.size) return null
            if (type == 0 && length in 3..256) {
                val text = bytes.copyOfRange(index, index + length).decodeToString()
                val parts = text.split(':')
                if (parts.size == 3 && parts[0].toIntOrNull() != null &&
                    parts[1].length == 64 && parts[2].isNotEmpty()
                ) {
                    return text
                }
            }
            index += length
        }
        return null
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(digits[value shr 4]).append(digits[value and 0x0f])
        }
        return out.toString()
    }
}
