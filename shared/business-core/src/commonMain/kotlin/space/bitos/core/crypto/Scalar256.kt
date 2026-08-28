package space.bitos.core.crypto

/**
 * Scalar arithmetic mod n (the secp256k1 group order) for signing.
 * Verification never needs mod-n multiplication; signing computes
 * (k' + e·d') mod n, so a generic 512-bit -> mod-n reduction is provided.
 * Shift-subtract long division: simple, correct, once per signature.
 */
internal object Scalar256 {

    /** (a + b) mod n; inputs and result canonical (< n or any < 2^256). */
    fun addModN(a: ULongArray, b: ULongArray): ULongArray {
        val t = ULongArray(5)
        var carry = 0UL
        for (i in 0..3) {
            val s1 = a[i] + carry
            val w1 = if (carry == 1UL && s1 == 0UL && a[i] == ULong.MAX_VALUE) 1UL else 0UL
            val s2 = s1 + b[i]
            val w2 = if (s2 < b[i] && b[i] != 0UL) 1UL else 0UL
            t[i] = s2
            carry = w1 + w2
        }
        t[4] = carry
        return reduce5ModN(t)
    }

    /** (n - a) mod n for a in (0, n). */
    fun negateModN(a: ULongArray): ULongArray {
        val t = ULongArray(4)
        var borrow = 0UL
        for (i in 0..3) {
            t[i] = Secp256k1.N[i] - a[i] - borrow
            borrow = if (Secp256k1.N[i] < a[i] || (Secp256k1.N[i] == a[i] && borrow == 1UL)) 1UL else 0UL
        }
        return t
    }

    /** a·b mod n via schoolbook multiply then shift-subtract reduction. */
    fun mulModN(a: ULongArray, b: ULongArray): ULongArray {
        val product = Fp256.mul512(a, b)
        // r = 0; for each bit of the 512-bit product MSB->LSB:
        //   r = (r << 1) | bit; if r >= n: r -= n
        val r = ULongArray(5)
        for (bitIndex in 511 downTo 0) {
            shiftLeft1(r)
            val limb = bitIndex / 64
            val bitInLimb = bitIndex % 64
            val bit = (product[limb] shr bitInLimb) and 1UL
            r[0] = r[0] or bit
            if (cmp5(r, Secp256k1.N) >= 0) {
                sub5MinusN(r)
            }
        }
        return r.copyOfRange(0, 4)
    }

    private fun shiftLeft1(r: ULongArray) {
        for (i in 4 downTo 1) {
            r[i] = (r[i] shl 1) or (r[i - 1] shr 63)
        }
        r[0] = r[0] shl 1
    }

    private fun cmp5(a: ULongArray, n: ULongArray): Int {
        if (a[4] != 0UL) return 1
        for (i in 3 downTo 0) {
            if (a[i] != n[i]) return if (a[i] < n[i]) -1 else 1
        }
        return 0
    }

    private fun sub5MinusN(r: ULongArray) {
        var borrow = 0UL
        for (i in 0..3) {
            val diff = r[i] - Secp256k1.N[i] - borrow
            borrow = if (r[i] < Secp256k1.N[i] || (r[i] == Secp256k1.N[i] && borrow == 1UL)) 1UL else 0UL
            r[i] = diff
        }
        // a >= n guaranteed by caller, so the 5th limb clears to 0.
        r[4] = 0UL
    }

    private fun reduce5ModN(t: ULongArray): ULongArray {
        var value = t.copyOf()
        while (cmp5(value, Secp256k1.N) >= 0) {
            var borrow = 0UL
            for (i in 0..3) {
                val diff = value[i] - Secp256k1.N[i] - borrow
                borrow = if (value[i] < Secp256k1.N[i] || (value[i] == Secp256k1.N[i] && borrow == 1UL)) 1UL else 0UL
                value[i] = diff
            }
            // The borrow out of the low limbs clears the carry limb; without
            // this the loop never terminates when the sum exceeded 2^256.
            value[4] = value[4] - borrow
        }
        return value.copyOfRange(0, 4)
    }
}
