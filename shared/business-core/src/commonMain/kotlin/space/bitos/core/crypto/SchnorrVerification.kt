package space.bitos.core.crypto

import space.bitos.core.nostr.EventHasher

/**
 * BIP-340 Schnorr signature verification over secp256k1.
 *
 * `verify` follows the verification algorithm from BIP-340 exactly:
 *
 *  1. P = lift_x(P_x); fail if it fails (x not on curve)
 *  2. fail if r >= p or s >= n
 *  3. e = int(tag_hash(P_x ‖ m)) mod n, tag = "BIP0340/challenge"
 *  4. R = s·G - e·P; fail if R is infinity, R_y odd, or R_x != r
 *
 * Correctness is locked by the independently generated fixture vectors in
 * `contracts/nostr/fixtures/verification-vectors.json` (nostr-tools /
 * @noble/curves) that run on the JVM and native Apple test lanes.
 */
object SchnorrVerification {

    private val TAG = "BIP0340/challenge".encodeToByteArray()

    /**
     * @param pubkey    32-byte x-only public key
     * @param message   32-byte message (the event ID hash)
     * @param signature 64-byte signature (r ‖ s)
     */
    fun verify(hasher: EventHasher, pubkey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (pubkey.size != 32 || message.size != 32 || signature.size != 64) return false

        val px = Fp256.fromBytes32(pubkey)
        if (Fp256.cmp(px, Fp256.P) >= 0) return false
        val r = Fp256.fromBytes32(signature.copyOfRange(0, 32))
        val s = Fp256.fromBytes32(signature.copyOfRange(32, 64))
        if (Fp256.isZero(r) || Fp256.cmp(r, Fp256.P) >= 0) return false
        if (Fp256.isZero(s) || Secp256k1.cmpN(s) >= 0) return false

        val pPoint = Secp256k1.liftX(px) ?: return false

        val eBytes = Bip340.taggedHash(hasher, "BIP0340/challenge", signature.copyOfRange(0, 32) + pubkey + message)
        val e = Secp256k1.modN(Fp256.fromBytes32(eBytes))

        // R = s·G - e·P
        val sG = Secp256k1.multiply(s, Secp256k1.generator())
        val eP = Secp256k1.multiply(e, pPoint)
        val rPoint = Secp256k1.add(sG, Secp256k1.negate(eP))

        val affine = Secp256k1.toAffine(rPoint) ?: return false // infinity
        if ((affine.second[0] and 1UL) == 1UL) return false     // R_y must be even
        return Fp256.equals(affine.first, r)
    }
}
