package space.bitos.core.crypto

import space.bitos.core.nostr.Sha256EventHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * NIP-44 v2: conversation key derivation (ECDH x-only → HKDF-extract
 * nip44-v2), padding rule, ChaCha20 round-trip, encrypt→decrypt
 * round-trip with the deterministic hasher, MAC verification, and
 * rejection of tampered payloads.
 */
class Nip44Test {

    private val hasher = Sha256EventHasher
    // Deterministic test keys (a-repeated and b-repeated hex).
    private val privA = "a".repeat(64)
    private val privB = "b".repeat(64)
    private val pubA = SchnorrSigning.publicKey(Nip44.hexToBytes(privA)!!, hasher)!!.let(Nip44::bytesToHex)
    private val pubB = SchnorrSigning.publicKey(Nip44.hexToBytes(privB)!!, hasher)!!.let(Nip44::bytesToHex)

    @Test
    fun conversationKeyIsSymmetric() {
        val keyAtoB = Nip44.conversationKey(privA, pubB, hasher)!!
        val keyBtoA = Nip44.conversationKey(privB, pubA, hasher)!!
        // ECDH is symmetric — both derive the same conversation key.
        assertTrue(keyAtoB.contentEquals(keyBtoA), "conversation keys must be symmetric")
        assertEquals(32, keyAtoB.size)
    }

    @Test
    fun paddedLengthFollowsPowerOfTwoChunks() {
        assertEquals(32, Nip44.calcPaddedLen(1))
        assertEquals(32, Nip44.calcPaddedLen(32))
        assertEquals(64, Nip44.calcPaddedLen(33))
        assertEquals(64, Nip44.calcPaddedLen(64))
        assertEquals(96, Nip44.calcPaddedLen(65))
        assertEquals(96, Nip44.calcPaddedLen(96))
        assertFailsWith<Nip44.Failure> { Nip44.calcPaddedLen(0) }
    }

    @Test
    fun encryptDecryptRoundTrips() {
        val convKey = Nip44.conversationKey(privA, pubB, hasher)!!
        val nonce = ByteArray(32) { it.toByte() } // deterministic
        val payload = Nip44.encrypt("Hello, secure world!", convKey, nonce, hasher)
        // Payload is base64, starts with version byte 0x02.
        assertTrue(payload.length >= 132)
        assertEquals("A", payload.substring(0, 1)) // base64 of 0x02 starts with 'A'

        val decrypted = Nip44.decrypt(payload, convKey, hasher)
        assertEquals("Hello, secure world!", decrypted)

        // The other party decrypts with the same conversation key.
        val convKeyB = Nip44.conversationKey(privB, pubA, hasher)!!
        assertEquals("Hello, secure world!", Nip44.decrypt(payload, convKeyB, hasher))
    }

    @Test
    fun encryptDecryptRoundTripsUnicode() {
        val convKey = Nip44.conversationKey(privB, pubA, hasher)!!
        val nonce = ByteArray(32) { (it * 7).toByte() }
        val message = "ສະບາຍດີ BitOS ⚡ 🚀 — emoji + Lao + mixed"
        val payload = Nip44.encrypt(message, convKey, nonce, hasher)
        assertEquals(message, Nip44.decrypt(payload, convKey, hasher))
    }

    @Test
    fun tamperedPayloadFailsMac() {
        val convKey = Nip44.conversationKey(privA, pubB, hasher)!!
        val nonce = ByteArray(32) { 0x42 }
        val payload = Nip44.encrypt("secret", convKey, nonce, hasher)
        // Flip a byte in the base64 payload.
        val tampered = payload.substring(0, 10) +
            (if (payload[10] == 'A') 'B' else 'A') + payload.substring(11)
        assertFailsWith<Nip44.Failure> { Nip44.decrypt(tampered, convKey, hasher) }
    }

    @Test
    fun wrongConversationKeyFailsMac() {
        val convKey = Nip44.conversationKey(privA, pubB, hasher)!!
        val wrongKey = Nip44.conversationKey(privB, SchnorrSigning.publicKey(ByteArray(32) { 0x01.toByte() }, hasher)!!.let(Nip44::bytesToHex), hasher)!!
        val nonce = ByteArray(32) { 0x11 }
        val payload = Nip44.encrypt("secret", convKey, nonce, hasher)
        assertFailsWith<Nip44.Failure> { Nip44.decrypt(payload, wrongKey, hasher) }
    }

    @Test
    fun hkdfExpandProducesDeterministicOutput() {
        val prk = Nip44.conversationKey(privA, pubB, hasher)!!
        val info = ByteArray(32) { it.toByte() }
        val a = Nip44.hkdfExpand(hasher, prk, info, 76)
        val b = Nip44.hkdfExpand(hasher, prk, info, 76)
        assertTrue(a.contentEquals(b))
        assertEquals(76, a.size)
    }

    @Test
    fun chacha20RoundTrips() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(12) { (it * 3).toByte() }
        val message = "The quick brown fox jumps over the lazy dog".encodeToByteArray()
        val cipher = Nip44.ChaCha20.cipher(key, nonce, message)
        val plain = Nip44.ChaCha20.cipher(key, nonce, cipher)
        assertTrue(plain.contentEquals(message))
        assertTrue(!cipher.contentEquals(message))
    }
}
