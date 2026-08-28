package space.bitos.core.identity

import space.bitos.core.crypto.SchnorrSigning
import space.bitos.core.crypto.SchnorrVerification
import space.bitos.core.nostr.Sha256EventHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vectors generated independently with @noble/curves (BIP-340) and
 * nostr-tools NIP-19; regeneration documented in the repo docs.
 */
class SchnorrSigningTest {

    private val hasher = Sha256EventHasher

    private fun bytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { ((it.toInt() and 0xf0) ushr 4).toString(16) + (it.toInt() and 0x0f).toString(16) }

    @Test
    fun derivesReferencePublicKeys() {
        assertEquals(
            "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001",
            hex(SchnorrSigning.publicKey(bytes("d2ad3c3c9e7b0f4f6a1c2d3e4f5061728394a5b6c7d8e9f0a1b2c3d4e5f60718"), hasher)!!),
        )
        assertEquals(
            "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", // G for sk=1
            hex(SchnorrSigning.publicKey(bytes("0000000000000000000000000000000000000000000000000000000000000001"), hasher)!!),
        )
    }

    @Test
    fun signsDeterministicallyMatchingReference() {
        val cases = listOf(
            Triple(
                "d2ad3c3c9e7b0f4f6a1c2d3e4f5061728394a5b6c7d8e9f0a1b2c3d4e5f60718",
                "10cf5a33e757be81a5b4c933c93ecb895667c6f202814d4291ab6b15d99a1d8a",
                "1e22f5b27ad14c461d6156a0c2b19cbaf77899d2ed803d1f3c0a13e04cebf201c19276d5a6a73921da5fa770449f7971e882d7809e1b0c067dcb13a91d26c4c8",
            ),
            Triple(
                "0000000000000000000000000000000000000000000000000000000000000001",
                "abababababababababababababababababababababababababababababababab",
                "bebb1c3e3365243b0e3c9ea351104529a7d42196a727df8c05a1a03a6ed8f30241465504cba13b782247b83191a1149b3090b8e30ddb67450a62db39fbc8ee93",
            ),
            Triple(
                "4b1aa1a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d",
                "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd",
                "e7724c33fcb7154c8a856ef281793d6181a6cad007e45984e8fe42b2cc1e95d1a8e27c25808d1ee950d7115fc00ea2e53e3fd4ae3abc3c60808e15de31b68319",
            ),
        )
        for ((sk, msg, expectedSig) in cases) {
            val aux = "00".repeat(32)
            val auxForCase = when (msg) {
                "abababababababababababababababababababababababababababababababab" -> "11".repeat(32)
                "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd" -> "ff".repeat(32)
                else -> aux
            }
            val signature = SchnorrSigning.sign(bytes(msg), bytes(sk), bytes(auxForCase), hasher)!!
            assertEquals(expectedSig, hex(signature))
            // Round-trip through the shared verifier.
            val pubkey = SchnorrSigning.publicKey(bytes(sk), hasher)!!
            assertTrue(SchnorrVerification.verify(hasher, pubkey, bytes(msg), signature))
        }
    }

    @Test
    fun rejectsInvalidSecrets() {
        assertNull(SchnorrSigning.publicKey(ByteArray(32), hasher))
        assertNull(SchnorrSigning.publicKey(bytes("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"), hasher)) // n
        assertNull(SchnorrSigning.sign(ByteArray(32), ByteArray(32), ByteArray(32), hasher))
        assertNull(SchnorrSigning.sign(ByteArray(31), ByteArray(32), ByteArray(32), hasher))
    }

    @Test
    fun testSignerMatchesSignatures() = kotlinx.coroutines.runBlocking {
        val signer = DeterministicTestSigner("0000000000000000000000000000000000000000000000000000000000000001")
        assertEquals(
            "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            signer.publicKeyHex(),
        )
        val message = bytes("abababababababababababababababababababababababababababababababab")
        val signature = signer.sign(message)
        // Zero-aux deterministic signing: same input -> same bytes, and the
        // signature verifies against the reference-verified public key.
        assertEquals(signature, signer.sign(message))
        assertTrue(
            SchnorrVerification.verify(
                hasher,
                bytes("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"),
                message,
                bytes(signature!!),
            ),
        )
        assertEquals(SignerKind.LOCAL_KEY, signer.signerKind())
    }
}

class NostrKeyCodecTest {

    @Test
    fun encodesReferenceNpubs() {
        assertEquals(
            "npub194667yy2sqh4h4vlwssg7g5smhmqx4x9hgtfdjun8e46l30kxqqselzc9y",
            NostrKeyCodec.npub("2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"),
        )
        assertEquals(
            "npub1lycg5qvjtrp3qjf5f7zl382j9x6nrjz9sdhenvyxq8c3808qxmus6gq266",
            NostrKeyCodec.npub("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"),
        )
    }

    @Test
    fun roundTripsNpubAndNsec() {
        val pubkey = "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001"
        assertEquals(pubkey, NostrKeyCodec.parseNpub(NostrKeyCodec.npub(pubkey)!!))
        val secret = "d2ad3c3c9e7b0f4f6a1c2d3e4f5061728394a5b6c7d8e9f0a1b2c3d4e5f60718"
        assertEquals(
            "nsec162knc0y70v8576su95ly75rpw2peffdkclvwnu9pktpafe0kquvqh3ydrs",
            NostrKeyCodec.nsec(secret),
        )
        assertEquals(secret, NostrKeyCodec.parseNsec("nsec162knc0y70v8576su95ly75rpw2peffdkclvwnu9pktpafe0kquvqh3ydrs"))
        assertEquals(
            "nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsmhltgl",
            NostrKeyCodec.nsec("0".repeat(63) + "1"),
        )
    }

    @Test
    fun acceptsUppercaseAndRejectsMalformedInput() {
        assertEquals(
            "2d75af108a802f5bd59f74208f2290ddf60354c5ba1696cb933e6bafc5f63001",
            NostrKeyCodec.parseNpub("NPUB194667YY2SQH4H4VLWSSG7G5SMHMQX4X9HGTFDJUN8E46L30KXQQSELZC9Y"),
        )
        // Corrupted checksum.
        assertNull(NostrKeyCodec.parseNpub("npub194667yy2sqh4h4vlwssg7g5smhmqx4x9hgtfdjun8e46l30kxqqselzc9z"))
        // Mixed case is rejected per BIP-173.
        assertNull(NostrKeyCodec.parseNpub("Npub194667yy2sqh4h4vlwssg7g5smhmqx4x9hgtfdjun8e46l30kxqqselzc9y"))
        // Wrong hrp.
        assertNull(NostrKeyCodec.parseNsec("npub194667yy2sqh4h4vlwssg7g5smhmqx4x9hgtfdjun8e46l30kxqqselzc9y"))
        assertNull(NostrKeyCodec.parseNpub("nsec162knc0y70v8576su95ly75rpw2peffdkclvwnu9pktpafe0kquvqh3ydrs"))
        // Truncated / oversized / non-bech32.
        assertNull(NostrKeyCodec.parseNpub("npub1"))
        assertNull(NostrKeyCodec.parseNpub("npub1" + "q".repeat(80)))
        assertNull(NostrKeyCodec.parseNpub("not-a-key"))
    }

    @Test
    fun rejectsInvalidHexInput() {
        assertNull(NostrKeyCodec.npub("zz")) // wrong length
        assertNull(NostrKeyCodec.npub("z".repeat(64))) // non-hex
        assertNull(NostrKeyCodec.nsec("f".repeat(63)))
    }
}
