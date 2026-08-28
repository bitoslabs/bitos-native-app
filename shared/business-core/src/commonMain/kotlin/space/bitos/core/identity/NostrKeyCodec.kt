package space.bitos.core.identity

/**
 * NIP-19 bech32 key codecs (npub/nsec) with strict validation (ID-004).
 * Encoding/decoding rules follow BIP-173: lowercase or uppercase only (no
 * mixing), checksum constant 1, bounded length.
 */
object NostrKeyCodec {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val MAX_LENGTH = 90

    /** hex64 pubkey -> npub. */
    fun npub(pubkeyHex: String): String? {
        val bytes = hexToBytes(pubkeyHex, 32) ?: return null
        return encodeBech32("npub", bytesToWords(bytes))
    }

    /** npub -> hex64 pubkey. */
    fun parseNpub(encoded: String): String? = decodeKey("npub", encoded)?.let(::bytesToHex64)

    /** hex64 secret -> nsec. Exists for key-backup display and tests. */
    fun nsec(secretHex: String): String? {
        val bytes = hexToBytes(secretHex, 32) ?: return null
        return encodeBech32("nsec", bytesToWords(bytes))
    }

    /** nsec -> hex64 secret. Only for the import transaction. */
    fun parseNsec(encoded: String): String? = decodeKey("nsec", encoded)?.let(::bytesToHex64)

    // ------------------------------------------------------------------
    // Bech32 (BIP-173)

    private fun encodeBech32(hrp: String, words: IntArray): String? {
        if (words.size != 52) return null // 32 bytes -> 256 bits -> ceil + checksum
        val checksum = bech32Checksum(hrp, words)
        return hrp + "1" + (words + checksum).joinToString("") { CHARSET[it].toString() }
    }

    private fun decodeKey(expectedHrp: String, encoded: String): ByteArray? {
        if (encoded.length > MAX_LENGTH || encoded.length < 10) return null
        val hasLower = encoded.any { it in 'a'..'z' }
        val hasUpper = encoded.any { it in 'A'..'Z' }
        if (hasLower && hasUpper) return null
        val normalized = encoded.lowercase()
        val separator = normalized.lastIndexOf('1')
        if (separator < 1 || separator + 7 > normalized.length) return null
        val hrp = normalized.substring(0, separator)
        if (hrp != expectedHrp) return null
        val dataPart = normalized.substring(separator + 1)
        val words = IntArray(dataPart.length) { index ->
            val position = CHARSET.indexOf(dataPart[index])
            if (position < 0) return null
            position
        }
        if (!bech32Verify(hrp, words)) return null
        val payload = words.copyOfRange(0, words.size - 6)
        return wordsToBytes(payload) ?: return null
    }

    private fun bech32Polymod(values: IntArray): Int {
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (value in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor value
            for (i in 0..4) {
                if ((top ushr i) and 1 == 1) chk = chk xor generators[i]
            }
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
        return IntArray(6) { index -> (polymod ushr (5 * (5 - index))) and 31 }
    }

    private fun bech32Verify(hrp: String, data: IntArray): Boolean =
        bech32Polymod(hrpExpand(hrp) + data) == 1

    // ------------------------------------------------------------------
    // Base64-less byte/word conversions (arithmetic, common-safe)

    private fun bytesToWords(bytes: ByteArray): IntArray {
        // 8-bit -> 5-bit groups MSB-first, zero-padded at the tail.
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

    private fun wordsToBytes(words: IntArray): ByteArray? {
        val totalBits = words.size * 5
        val remainder = totalBits % 8
        if (remainder >= 5) return null
        if (remainder > 0 && words.last() and ((1 shl remainder) - 1) != 0) return null // padding must be zero
        val byteCount = totalBits / 8
        if (byteCount != 32) return null
        val bytes = ByteArray(byteCount)
        for (index in 0 until byteCount) {
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

    private fun hexToBytes(hex: String, expectBytes: Int): ByteArray? {
        if (hex.length != expectBytes * 2) return null
        val out = ByteArray(expectBytes)
        for (index in 0 until expectBytes) {
            val hi = hexDigit(hex[index * 2]) ?: return null
            val lo = hexDigit(hex[index * 2 + 1]) ?: return null
            out[index] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    private fun bytesToHex64(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(64)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(digits[value shr 4]).append(digits[value and 0x0f])
        }
        return out.toString()
    }
}
