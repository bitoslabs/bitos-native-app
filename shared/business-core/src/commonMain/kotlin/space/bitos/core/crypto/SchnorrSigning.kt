package space.bitos.core.crypto

import space.bitos.core.nostr.EventHasher

/**
 * BIP-340 Schnorr signing over secp256k1 (deterministic nonces derived from
 * the caller-provided aux). Signing math lives here, once, in pure Kotlin;
 * KEY MANAGEMENT stays platform-native (Keychain / Android Keystore) — the
 * shared `IdentitySigner` implementations hand bytes to these functions and
 * never expose secrets in state (ID-001).
 *
 * Locked by independently generated vectors (@noble/curves) alongside the
 * existing verification suite.
 */
object SchnorrSigning {

    /** @param secretKey 32 bytes, big-endian. */
    fun publicKey(secretKey: ByteArray, hasher: EventHasher): ByteArray? {
        if (secretKey.size != 32) return null
        val d = Fp256.fromBytes32(secretKey)
        if (Fp256.isZero(d) || Secp256k1.cmpN(d) >= 0) return null
        val affine = Secp256k1.toAffine(Secp256k1.multiply(d, Secp256k1.generator())) ?: return null
        return Fp256.toBytes32(affine.first)
    }

    /**
     * @param message  32-byte message hash (the event ID).
     * @param secretKey 32-byte secret.
     * @param auxRand  32-byte aux entropy for nonce derivation (platform
     *        CSPRNG; deterministic only in tests).
     * @return 64-byte signature (r ‖ s), or null on invalid inputs / zero nonce.
     */
    fun sign(message: ByteArray, secretKey: ByteArray, auxRand: ByteArray, hasher: EventHasher): ByteArray? {
        if (message.size != 32 || secretKey.size != 32 || auxRand.size != 32) return null

        var d = Fp256.fromBytes32(secretKey)
        if (Fp256.isZero(d) || Secp256k1.cmpN(d) >= 0) return null

        // P = d·G; if P.y odd, d' = n - d.
        val pPoint = Secp256k1.multiply(d, Secp256k1.generator())
        val pAffine = Secp256k1.toAffine(pPoint) ?: return null
        if ((pAffine.second[0] and 1UL) == 1UL) {
            d = Scalar256.negateModN(d)
        }
        val pxBytes = Fp256.toBytes32(pAffine.first)

        // t = d' XOR tagged_hash("BIP0340/aux", aux)
        val tHash = Bip340.taggedHash(hasher, "BIP0340/aux", auxRand)
        val t = Fp256.toBytes32(d).mapIndexed { index, byte -> (byte.toInt() xor tHash[index].toInt()).toByte() }.toByteArray()

        // rand = tagged_hash("BIP0340/nonce", t ‖ P_x ‖ m)
        val rand = Bip340.taggedHash(hasher, "BIP0340/nonce", t + pxBytes + message)
        var k = Secp256k1.modN(Fp256.fromBytes32(rand))
        if (Fp256.isZero(k)) return null

        // R = k'·G; if R.y odd, k' = n - k.
        val rPoint = Secp256k1.multiply(k, Secp256k1.generator())
        val rAffine = Secp256k1.toAffine(rPoint) ?: return null
        if ((rAffine.second[0] and 1UL) == 1UL) {
            k = Scalar256.negateModN(k)
        }
        val rBytes = Fp256.toBytes32(rAffine.first)

        // e = tagged_hash("BIP0340/challenge", R_x ‖ P_x ‖ m) mod n
        val e = Secp256k1.modN(Fp256.fromBytes32(Bip340.taggedHash(hasher, "BIP0340/challenge", rBytes + pxBytes + message)))

        // s = (k' + e·d') mod n
        val s = Scalar256.addModN(k, Scalar256.mulModN(e, d))
        if (Fp256.isZero(s)) return null

        return rBytes + Fp256.toBytes32(s)
    }
}
