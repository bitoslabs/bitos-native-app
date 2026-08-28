package space.bitos.app.identity

import space.bitos.core.crypto.SchnorrSigning
import space.bitos.core.identity.IdentitySigner
import space.bitos.core.identity.SignerKind
import space.bitos.core.nostr.Sha256EventHasher
import java.security.SecureRandom

/**
 * Local-key signer (ID-001 implementation for Android). Wraps the shared
 * BIP-340 signing math; the secret transits only as a constructor argument
 * from SecureKeyStore and is never exposed through the port.
 */
class LocalKeySigner(private val secretHex: String) : IdentitySigner {

    private val secret = run {
        require(secretHex.length == 64)
        ByteArray(32) { index -> secretHex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    override fun signerKind(): SignerKind = SignerKind.LOCAL_KEY

    override fun publicKeyHex(): String =
        hex(SchnorrSigning.publicKey(secret, Sha256EventHasher)!!)

    override suspend fun sign(message32: ByteArray): String? {
        // CSPRNG aux per signature; the nonce stays unpredictable per BIP-340.
        val aux = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return SchnorrSigning.sign(message32, secret, aux, Sha256EventHasher)?.let(::hex)
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { ((it.toInt() and 0xf0) ushr 4).toString(16) + (it.toInt() and 0x0f).toString(16) }
}
