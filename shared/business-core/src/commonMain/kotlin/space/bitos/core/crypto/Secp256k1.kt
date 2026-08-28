package space.bitos.core.crypto

/**
 * secp256k1 group operations (Jacobian coordinates) and BIP-340 Schnorr
 * verification. Pure Kotlin in commonMain: one implementation, one set of
 * reference vectors, executed identically on Android, iOS, JVM and native
 * test lanes (SBC-006).
 *
 * The event ID (NIP-01 canonical hash) is the signed message. Verification
 * handles public data only; signing is NOT implemented here — signing lives
 * behind the identity/signer ports and never enters shared feed code.
 */
internal object Secp256k1 {

    /** Group order n. */
    val N = ulongArrayOf(
        0xBFD25E8CD0364141UL,
        0xBAAEDCE6AF48A03BUL,
        0xFFFFFFFFFFFFFFFEUL,
        0xFFFFFFFFFFFFFFFFUL,
    )

    private val GX = Fp256.fromHex64(
        "79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798",
    )
    private val GY = Fp256.fromHex64(
        "483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8",
    )

    /** Point at infinity: any (x, y, 0). */
    internal class Jac(val x: ULongArray, val y: ULongArray, val z: ULongArray) {
        val isInfinite: Boolean get() = Fp256.isZero(z)
    }

    private val INFINITY = Jac(Fp256.ONE, Fp256.ONE, Fp256.ZERO)
    private val G = Jac(GX, GY, Fp256.ONE)

    // ------------------------------------------------------------------
    // Point arithmetic (dbl-2009-l / add-2007-bl, a = 0)
    // ------------------------------------------------------------------

    fun double(p: Jac): Jac {
        if (p.isInfinite || Fp256.isZero(p.y)) return INFINITY
        val a = Fp256.sqr(p.x)
        val b = Fp256.sqr(p.y)
        val c = Fp256.sqr(b)
        val t = Fp256.sqr(Fp256.add(p.x, b))
        val d = Fp256.sub(t, Fp256.add(a, c))          // (X+B)^2 - A - C
        val d2 = Fp256.add(d, d)                        // D = 2*d
        val e = Fp256.add(Fp256.add(a, a), a)           // E = 3A
        val f = Fp256.sqr(e)
        val x3 = Fp256.sub(f, Fp256.add(d2, d2))        // F - 2D
        val c8 = mulSmall(c, 8UL)                       // 8C
        val y3 = Fp256.sub(Fp256.mul(e, Fp256.sub(d2, x3)), c8)
        val z3 = Fp256.mul(Fp256.add(p.y, p.y), p.z)    // Z3 = 2*Y*Z
        return Jac(x3, y3, z3)
    }

    private fun mulSmall(a: ULongArray, k: ULong): ULongArray {
        var result = Fp256.ZERO
        var acc = a
        var remaining = k
        while (remaining > 0UL) {
            if (remaining and 1UL == 1UL) result = Fp256.add(result, acc)
            acc = Fp256.add(acc, acc)
            remaining = remaining shr 1
        }
        return result
    }

    fun negate(p: Jac): Jac = Jac(p.x, Fp256.sub(Fp256.ZERO, p.y), p.z)

    fun add(p: Jac, q: Jac): Jac {
        if (p.isInfinite) return q
        if (q.isInfinite) return p
        val z1z1 = Fp256.sqr(p.z)
        val z2z2 = Fp256.sqr(q.z)
        val u1 = Fp256.mul(p.x, z2z2)
        val u2 = Fp256.mul(q.x, z1z1)
        val s1 = Fp256.mul(p.y, Fp256.mul(q.z, z2z2))
        val s2 = Fp256.mul(q.y, Fp256.mul(p.z, z1z1))
        val h = Fp256.sub(u2, u1)
        val r = Fp256.sub(s2, s1)
        if (Fp256.isZero(h)) {
            return if (Fp256.isZero(r)) double(p) else INFINITY
        }
        val h2 = Fp256.add(h, h)
        val i = Fp256.sqr(h2)
        val j = Fp256.mul(h, i)
        val r2 = Fp256.add(r, r)
        val v = Fp256.mul(u1, i)
        val x3 = Fp256.sub(Fp256.sub(Fp256.sqr(r2), j), Fp256.add(v, v))
        val s1j2 = Fp256.add(Fp256.mul(s1, j), Fp256.mul(s1, j))  // 2*S1*J
        val y3 = Fp256.sub(Fp256.mul(r2, Fp256.sub(v, x3)), s1j2)
        val zSum = Fp256.sub(Fp256.sub(Fp256.sqr(Fp256.add(p.z, q.z)), z1z1), z2z2)
        val z3 = Fp256.mul(zSum, h)
        return Jac(x3, y3, z3)
    }

    /** Simple double-and-add; k is any 256-bit value. */
    fun multiply(k: ULongArray, point: Jac): Jac {
        var acc = INFINITY
        for (i in 255 downTo 0) {
            acc = double(acc)
            if ((k[i / 64] shr (i % 64)) and 1UL == 1UL) {
                acc = add(acc, point)
            }
        }
        return acc
    }

    /** Affine (x, y); null for infinity. */
    fun toAffine(p: Jac): Pair<ULongArray, ULongArray>? {
        if (p.isInfinite) return null
        val zInv = Fp256.inv(p.z)
        val zInv2 = Fp256.sqr(zInv)
        val zInv3 = Fp256.mul(zInv2, zInv)
        return Pair(Fp256.mul(p.x, zInv2), Fp256.mul(p.y, zInv3))
    }

    /**
     * BIP-340 lift_x: the curve point with x-coordinate [x] and EVEN y.
     * Null when x^3 + 7 is not a quadratic residue.
     */
    fun liftX(x: ULongArray): Jac? {
        if (Fp256.cmp(x, Fp256.P) >= 0) return null
        val y = Fp256.sqrt(Fp256.cubePlus7(x)) ?: return null
        val evenY = if ((y[0] and 1UL) == 1UL) Fp256.sub(Fp256.P, y) else y
        return Jac(x.copyOf(), evenY, Fp256.ONE.copyOf())
    }

    fun generator(): Jac = Jac(GX.copyOf(), GY.copyOf(), Fp256.ONE.copyOf())

    /** Unsigned compare against the group order. */
    fun cmpN(a: ULongArray): Int = Fp256.cmp(a, N)

    /** a mod n for a hash-derived 256-bit value (at most one subtraction). */
    fun modN(a: ULongArray): ULongArray =
        if (Fp256.cmp(a, N) >= 0) minusN(a) else a.copyOf()

    private fun minusN(a: ULongArray): ULongArray {
        val t = ULongArray(4)
        var borrow = 0UL
        for (i in 0..3) {
            t[i] = a[i] - N[i] - borrow
            borrow = if (a[i] < N[i] || (a[i] == N[i] && borrow == 1UL)) 1UL else 0UL
        }
        return t
    }
}
