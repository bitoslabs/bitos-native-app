package space.bitos.core.crypto

import space.bitos.core.nostr.EventHasher

/**
 * NIP-44 v2 — encrypted payloads for Secure DMs (NIP-17). 1:1 port of
 * `nostr-tools/nip44` (the implementation the legacy Flutter app and web
 * client use — same conversation/message key derivation, ChaCha20,
 * power-of-two padding and HMAC-SHA256 MAC).
 *
 *  • **Conversation key** — ECDH x-only shared point → HKDF-extract
 *    (SHA-256, salt `nip44-v2`).
 *  • **Message keys** — HKDF-expand(conversationKey, info = 32-byte nonce,
 *    76 bytes) → chachaKey(32) ‖ chachaNonce(12) ‖ hmacKey(32).
 *  • **Cipher** — IETF ChaCha20 (RFC 8439).
 *  • **Padding** — u16BE length prefix (u32BE ≥64 KiB), padded to the
 *    next power-of-two chunk (min 32).
 *  • **MAC** — HMAC-SHA256(hmacKey, nonce ‖ ciphertext); payload =
 *    base64(0x02 ‖ nonce ‖ ciphertext ‖ mac).
 */
object Nip44 {

    private const val VERSION: Byte = 2
    private const val MIN_PLAINTEXT = 1
    private const val MAX_PLAINTEXT = 65_536 // V1 chat bound (per-message)
    private const val SALT = "nip44-v2"

    class Failure(reason: String) : Exception(reason)

    // ── Conversation key ─────────────────────────────────────────────

    /**
     * Shared conversation key between `privkeyA` and `pubkeyB` (both hex).
     * ECDH x-only point → HKDF-extract(SHA-256, salt nip44-v2).
     */
    fun conversationKey(hexPrivateKey: String, hexPubkey: String, hasher: EventHasher): ByteArray? {
        val secret = hexToBytes(hexPrivateKey) ?: return null
        val pubkey = hexToBytes(hexPubkey) ?: return null
        if (secret.size != 32 || pubkey.size != 32) return null
        // Lift the recipient's x-only pubkey to a point (even-Y convention).
        val pubPoint = Secp256k1.liftX(Fp256.fromBytes32(pubkey)) ?: return null
        val shared = Secp256k1.multiply(Fp256.fromBytes32(secret), pubPoint)
        val affine = Secp256k1.toAffine(shared) ?: return null
        val sharedX = Fp256.toBytes32(affine.first)
        return hmacSha256(hasher, sharedX, SALT.encodeToByteArray())
    }

    // ── Padding ──────────────────────────────────────────────────────

    fun calcPaddedLen(len: Int): Int {
        if (len < 1) throw Failure("expected positive integer")
        if (len <= 32) return 32
        var nextPower = 1 shl (bitLength(len - 1) + 1)
        if (nextPower > 256) nextPower = 512
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((len - 1) / chunk + 1)
    }

    private fun bitLength(n: Int): Int {
        var bits = 0
        var v = n
        while (v > 0) {
            bits++
            v = v shr 1
        }
        return bits - 1
    }

    internal fun pad(plaintext: String): ByteArray {
        val unpadded = plaintext.encodeToByteArray()
        val len = unpadded.size
        if (len < MIN_PLAINTEXT || len > MAX_PLAINTEXT) throw Failure("invalid plaintext size")
        val paddedLen = calcPaddedLen(len)
        val prefix = if (len >= 65_536) {
            byteArrayOf(0, 0, (len shr 24).toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte())
        } else {
            byteArrayOf((len shr 8).toByte(), len.toByte())
        }
        return prefix + unpadded + ByteArray(paddedLen - len)
    }

    internal fun unpad(padded: ByteArray): String {
        val firstTwo = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        val (unpaddedLen, prefixLen) = if (firstTwo == 0) {
            val len = ((padded[2].toInt() and 0xFF) shl 24) or ((padded[3].toInt() and 0xFF) shl 16) or
                ((padded[4].toInt() and 0xFF) shl 8) or (padded[5].toInt() and 0xFF)
            len to 6
        } else {
            firstTwo to 2
        }
        if (prefixLen + unpaddedLen > padded.size || padded.size != prefixLen + calcPaddedLen(unpaddedLen)) {
            throw Failure("invalid padding")
        }
        return padded.copyOfRange(prefixLen, prefixLen + unpaddedLen).decodeToString()
    }

    // ── Encrypt / decrypt ────────────────────────────────────────────

    fun encrypt(plaintext: String, convKey: ByteArray, nonce: ByteArray, hasher: EventHasher): String {
        require(convKey.size == 32 && nonce.size == 32)
        val keys = messageKeys(convKey, nonce, hasher)
        val padded = pad(plaintext)
        val ciphertext = ChaCha20.cipher(keys[0], keys[1], padded)
        val mac = hmacSha256(hasher, keys[2], nonce + ciphertext) ?: throw Failure("hmac failed")
        val payload = ByteArray(1 + 32 + ciphertext.size + 32)
        payload[0] = VERSION
        nonce.copyInto(payload, 1)
        ciphertext.copyInto(payload, 33)
        mac.copyInto(payload, 33 + ciphertext.size)
        return base64Encode(payload)
    }

    fun decrypt(payload: String, convKey: ByteArray, hasher: EventHasher): String {
        if (payload.length < 132) throw Failure("invalid payload length")
        val data = try {
            base64Decode(payload)
        } catch (_: Failure) {
            throw Failure("invalid base64")
        }
        if (data.size < 99) throw Failure("invalid data length")
        if (data[0] != VERSION) throw Failure("unknown encryption version ${data[0]}")
        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)
        val keys = messageKeys(convKey, nonce, hasher)
        val calculated = hmacSha256(hasher, keys[2], nonce + ciphertext) ?: throw Failure("hmac failed")
        if (!constantTimeEqual(calculated, mac)) throw Failure("invalid MAC")
        val padded = ChaCha20.cipher(keys[0], keys[1], ciphertext)
        return unpad(padded)
    }

    // chachaKey(32) ‖ chachaNonce(12) ‖ hmacKey(32) = 76 bytes.
    private fun messageKeys(convKey: ByteArray, nonce: ByteArray, hasher: EventHasher): Array<ByteArray> {
        val expanded = hkdfExpand(hasher, convKey, nonce, 76)
        return arrayOf(
            expanded.copyOfRange(0, 32),
            expanded.copyOfRange(32, 44),
            expanded.copyOfRange(44, 76),
        )
    }

    // ── ChaCha20 (RFC 8439, IETF) ────────────────────────────────────

    internal object ChaCha20 {
        private val SIGMA = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)

        fun cipher(key: ByteArray, nonce12: ByteArray, message: ByteArray): ByteArray {
            require(key.size == 32 && nonce12.size == 12)
            val state = IntArray(16)
            val block = ByteArray(64)
            val out = ByteArray(message.size)
            var counter = 1
            for (offset in message.indices step 64) {
                chachaBlock(state, block, key, nonce12, counter++)
                for (i in 0 until minOf(64, message.size - offset)) {
                    out[offset + i] = (message[offset + i].toInt() xor block[i].toInt()).toByte()
                }
            }
            return out
        }

        private fun chachaBlock(state: IntArray, out: ByteArray, key: ByteArray, nonce: ByteArray, counter: Int) {
            for (i in 0 until 4) state[i] = SIGMA[i]
            for (i in 0 until 8) {
                state[4 + i] = (key[i * 4].toInt() and 0xFF) or ((key[i * 4 + 1].toInt() and 0xFF) shl 8) or
                    ((key[i * 4 + 2].toInt() and 0xFF) shl 16) or ((key[i * 4 + 3].toInt() and 0xFF) shl 24)
            }
            state[12] = counter
            for (i in 0 until 3) {
                state[13 + i] = (nonce[i * 4].toInt() and 0xFF) or ((nonce[i * 4 + 1].toInt() and 0xFF) shl 8) or
                    ((nonce[i * 4 + 2].toInt() and 0xFF) shl 16) or ((nonce[i * 4 + 3].toInt() and 0xFF) shl 24)
            }
            val working = state.copyOf()
            for (round in 0 until 10) {
                quarterRound(working, 0, 4, 8, 12)
                quarterRound(working, 1, 5, 9, 13)
                quarterRound(working, 2, 6, 10, 14)
                quarterRound(working, 3, 7, 11, 15)
                quarterRound(working, 0, 5, 10, 15)
                quarterRound(working, 1, 6, 11, 12)
                quarterRound(working, 2, 7, 8, 13)
                quarterRound(working, 3, 4, 9, 14)
            }
            for (i in 0 until 16) {
                val v = working[i] + state[i]
                out[i * 4] = (v and 0xFF).toByte()
                out[i * 4 + 1] = ((v shr 8) and 0xFF).toByte()
                out[i * 4 + 2] = ((v shr 16) and 0xFF).toByte()
                out[i * 4 + 3] = ((v shr 24) and 0xFF).toByte()
            }
        }

        private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]; x[d] = rotl(x[d] xor x[a], 16)
            x[c] += x[d]; x[b] = rotl(x[b] xor x[c], 12)
            x[a] += x[b]; x[d] = rotl(x[d] xor x[a], 8)
            x[c] += x[d]; x[b] = rotl(x[b] xor x[c], 7)
        }

        private fun rotl(v: Int, bits: Int): Int = (v shl bits) or (v ushr (32 - bits))
    }

    // ── HKDF (RFC 5869) ──────────────────────────────────────────────

    internal fun hkdfExpand(hasher: EventHasher, prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var t = ByteArray(0)
        var counter = 1
        var pos = 0
        while (pos < length) {
            t = hmacSha256(hasher, prk, t + info + byteArrayOf(counter.toByte())) ?: throw Failure("hkdf failed")
            val take = minOf(32, length - pos)
            t.copyInto(out, pos, 0, take)
            pos += take
            counter++
        }
        return out
    }

    // ── HMAC-SHA256 ──────────────────────────────────────────────────

    internal fun hmacSha256(hasher: EventHasher, key: ByteArray, message: ByteArray): ByteArray? {
        val blockSize = 64
        var k = key
        if (k.size > blockSize) k = hasher.sha256(k)
        if (k.size < blockSize) k = k + ByteArray(blockSize - k.size)
        val oKey = ByteArray(blockSize) { (k[it].toInt() xor 0x5c).toByte() }
        val iKey = ByteArray(blockSize) { (k[it].toInt() xor 0x36).toByte() }
        return hasher.sha256(oKey + hasher.sha256(iKey + message))
    }

    private fun constantTimeEqual(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    // ── Hex / base64 (common) ────────────────────────────────────────

    internal fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || hex.length !in 2..4096) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val byte = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            out[i] = byte.toByte()
        }
        return out
    }

    internal fun bytesToHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val v = byte.toInt() and 0xff
            out.append(digits[v shr 4]).append(digits[v and 0xf])
        }
        return out.toString()
    }

    internal fun base64Encode(data: ByteArray): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder()
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            out.append(table[(b0 shr 2) and 0x3F])
            out.append(table[((b0 shl 4) or (b1 shr 4)) and 0x3F])
            out.append(if (i + 1 < data.size) table[((b1 shl 2) or (b2 shr 6)) and 0x3F] else '=')
            out.append(if (i + 2 < data.size) table[b2 and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }

    internal fun base64Decode(text: String): ByteArray {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = text.filter { it != '=' && it != '\n' && it != '\r' }
        val out = ByteArray(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var pos = 0
        for (char in clean) {
            val v = table.indexOf(char)
            if (v < 0) throw Failure("invalid base64 character: $char")
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[pos++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(pos)
    }
}
