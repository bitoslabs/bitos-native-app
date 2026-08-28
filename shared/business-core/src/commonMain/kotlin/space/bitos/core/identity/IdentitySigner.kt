package space.bitos.core.identity

import space.bitos.core.crypto.SchnorrSigning
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.Sha256EventHasher

/** How an account signs. */
enum class SignerKind {
    /** Secret key held in the platform secure store (Keychain/Keystore). */
    LOCAL_KEY,

    /** NIP-46 remote bunker signer (later). */
    REMOTE_NIP46,

    /** NIP-55 external Android signer app (later). */
    EXTERNAL_NIP55,

    /** No signer: browse-only account state. */
    READ_ONLY,
}

/** Non-secret account projection safe for UI state and persistence. */
data class AccountIdentity(
    val pubkeyHex: String,
    val npub: String,
    val signerKind: SignerKind,
    val createdAt: Long,
)

/**
 * Identity signer port (ID-001 / SBC-008).
 *
 * Signs 32-byte message hashes (event IDs) and returns hex signatures.
 * Implementations NEVER expose secret key bytes through this interface;
 * feature code can request a signature and read the public key, nothing
 * else. Remote (NIP-46/NIP-55) implementations arrive later behind the
 * same port.
 */
interface IdentitySigner {
    fun signerKind(): SignerKind

    /** hex64 public key of this signer. */
    fun publicKeyHex(): String

    /**
     * Signs [message32]; returns the hex128 signature or null when the
     * signer refuses (locked, denied, unavailable).
     */
    suspend fun sign(message32: ByteArray): String?
}

/**
 * Deterministic signer for tests and fixtures (SBC-008). Never used for
 * production accounts; the test key is public by construction.
 */
class DeterministicTestSigner(
    secretHex: String,
    private val hasher: EventHasher = Sha256EventHasher,
) : IdentitySigner {

    private val secret: ByteArray = run {
        require(secretHex.length == 64) { "secret must be hex64" }
        val bytes = ByteArray(32)
        for (index in 0 until 32) {
            bytes[index] = secretHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        bytes
    }

    override fun signerKind(): SignerKind = SignerKind.LOCAL_KEY

    override fun publicKeyHex(): String =
        SchnorrSigning.publicKey(secret, hasher)!!.toHexString()

    override suspend fun sign(message32: ByteArray): String? =
        SchnorrSigning.sign(message32, secret, ByteArray(32), hasher)?.let(::bytesToHex)

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
