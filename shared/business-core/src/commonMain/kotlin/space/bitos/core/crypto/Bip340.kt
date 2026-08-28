package space.bitos.core.crypto

import space.bitos.core.nostr.EventHasher

/** BIP-340 tagged hashes shared by signing and verification. */
internal object Bip340 {
    fun taggedHash(hasher: EventHasher, tag: String, data: ByteArray): ByteArray {
        val tagDigest = hasher.sha256(tag.encodeToByteArray())
        return hasher.sha256(tagDigest + tagDigest + data)
    }
}
