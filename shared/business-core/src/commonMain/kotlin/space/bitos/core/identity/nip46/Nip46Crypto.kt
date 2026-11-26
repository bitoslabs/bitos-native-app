package space.bitos.core.identity.nip46

import space.bitos.core.crypto.Nip44
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.Sha256EventHasher

/**
 * NIP-44 transport wrap for NIP-46 traffic (SBC-008b). The client
 * transport secret crosses ONLY as a function argument — never into
 * returned state, never persisted, never logged. The nonce is injected by
 * the platform CSPRNG; tests inject deterministic bytes.
 */
object Nip46Crypto {

    /** NIP-44 payload for a plaintext envelope, or null on key failure. */
    fun encrypt(
        plaintextEnvelope: String,
        transportSecretHex: String,
        peerPubkeyHex: String,
        random32: () -> ByteArray,
        hasher: EventHasher = Sha256EventHasher,
    ): String? {
        if (plaintextEnvelope.isEmpty() || plaintextEnvelope.length > Nip46Wire.MAX_ENVELOPE) return null
        val conversationKey = Nip44.conversationKey(transportSecretHex, peerPubkeyHex, hasher) ?: return null
        val nonce = random32()
        if (nonce.size != 32) return null
        return try {
            Nip44.encrypt(plaintextEnvelope, conversationKey, nonce, hasher)
        } catch (_: Nip44.Failure) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Plaintext envelope from a NIP-44 payload, or null when undecryptable. */
    fun decrypt(
        payload: String,
        transportSecretHex: String,
        peerPubkeyHex: String,
        hasher: EventHasher = Sha256EventHasher,
    ): String? {
        if (payload.isEmpty() || payload.length > Nip46Wire.MAX_ENVELOPE * 2) return null
        val conversationKey = Nip44.conversationKey(transportSecretHex, peerPubkeyHex, hasher) ?: return null
        return try {
            Nip44.decrypt(payload, conversationKey, hasher)
        } catch (_: Nip44.Failure) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
