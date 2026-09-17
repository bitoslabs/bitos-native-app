package space.bitos.core.identity

import space.bitos.core.crypto.SchnorrSigning
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.Sha256EventHasher

/**
 * Local-key [EventApprovalSigner] (SBC-008a): wraps the shared BIP-340
 * math. The secret transits only the constructor argument from the
 * platform secure store and never enters shared state; per-signature aux
 * entropy is INJECTED by the platform (CSPRNG) so the shared module stays
 * free of platform randomness. Deterministic aux is for tests only.
 */
class LocalKeyEventSigner(
    secretHex: String,
    private val random32: () -> ByteArray,
    private val hasher: EventHasher = Sha256EventHasher,
) : EventApprovalSigner {

    private val secret: ByteArray = run {
        require(secretHex.length == 64) { "secret must be hex64" }
        ByteArray(32) { index -> secretHex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private val publicKeyCache: String by lazy {
        SchnorrSigning.publicKey(secret, hasher)!!.toLowercaseHex()
    }

    override fun signerKind(): SignerKind = SignerKind.LOCAL_KEY

    override fun publicKeyHex(): String = publicKeyCache

    override suspend fun approveEvent(request: SignEventRequest): SignEventResult {
        SignEventRequest.validate(request)?.let { field ->
            return SignEventResult.Invalid(field)
        }
        if (request.accountPubkeyHex != publicKeyCache) {
            return SignEventResult.Rejected("wrong_pubkey")
        }
        val message = hexToBytes32(request.eventIdHex(hasher)) ?: return SignEventResult.Invalid("bad_id")
        val aux = random32()
        val signature = SchnorrSigning.sign(message, secret, aux, hasher)
            ?: return SignEventResult.Unavailable("signing_failed")
        return SignEventResult.Signed(
            SignedEvent(
                idHex = request.eventIdHex(hasher),
                pubkeyHex = publicKeyCache,
                createdAtSeconds = request.createdAtSeconds,
                kind = request.kind,
                tags = request.tags,
                content = request.content,
                signatureHex = signature.toLowercaseHex(),
            ),
        )
    }

    private fun hexToBytes32(hex: String): ByteArray? {
        if (hex.length != 64) return null
        return try {
            ByteArray(32) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun ByteArray.toLowercaseHex(): String =
        joinToString("") { ((it.toInt() and 0xf0) ushr 4).toString(16) + (it.toInt() and 0x0f).toString(16) }
}
