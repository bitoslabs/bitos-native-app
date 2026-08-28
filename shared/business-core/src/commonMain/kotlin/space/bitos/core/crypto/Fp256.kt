package space.bitos.core.crypto

/**
 * Fixed-width 256-bit unsigned arithmetic over the secp256k1 prime field
 * p = 2^256 - 2^32 - 977. Values are canonical (< p) little-endian ULongArray(4).
 *
 * Pure and allocation-heavy by design: correctness and cross-platform
 * determinism are the requirements (SBC-004/SBC-006); speedups (Montgomery,
 * windowed scalar multiplication) are an optimization lane behind the same
 * tests. Verification handles public data only.
 */
internal object Fp256 {

    val P = ulongArrayOf(
        0xFFFFFFFEFFFFFC2FUL,
        0xFFFFFFFFFFFFFFFFUL,
        0xFFFFFFFFFFFFFFFFUL,
        0xFFFFFFFFFFFFFFFFUL,
    )

    /** 2^32 + 977, the small cofactor of p used for fast reduction. */
    private const val C = 0x1000003D1UL

    /** p - 2, exponent for Fermat inversion. */
    private val P_MINUS_2 = ulongArrayOf(
        0xFFFFFFFEFFFFFC2DUL,
        0xFFFFFFFFFFFFFFFFUL,
        0xFFFFFFFFFFFFFFFFUL,
        0xFFFFFFFFFFFFFFFFUL,
    )

    /** (p + 1) / 4, exponent for square roots (p ≡ 3 mod 4). */
    private val P_PLUS_1_DIV_4 = ulongArrayOf(
        0xFFFFFFFFBFFFFF0CUL,
        0xFFFFFFFFFFFFFFFFUL,
        0xFFFFFFFFFFFFFFFFUL,
        0x3FFFFFFFFFFFFFFFUL,
    )

    val ZERO = ULongArray(4)
    val ONE = ulongArrayOf(1UL, 0UL, 0UL, 0UL)

    // ------------------------------------------------------------------
    // Comparison and conversion
    // ------------------------------------------------------------------

    fun isZero(a: ULongArray): Boolean = a[0] == 0UL && a[1] == 0UL && a[2] == 0UL && a[3] == 0UL

    /** Unsigned compare: -1 if a < b, 0 if equal, 1 if a > b. */
    fun cmp(a: ULongArray, b: ULongArray): Int {
        for (i in 3 downTo 0) {
            if (a[i] != b[i]) return if (a[i] < b[i]) -1 else 1
        }
        return 0
    }

    fun equals(a: ULongArray, b: ULongArray): Boolean = cmp(a, b) == 0

    /** Big-endian 32 bytes -> limbs. */
    fun fromBytes32(bytes: ByteArray): ULongArray {
        require(bytes.size == 32)
        val out = ULongArray(4)
        for (limb in 0..3) {
            var v = 0UL
            for (i in 0..7) {
                v = (v shl 8) or bytes[limb * 8 + i].toUByte().toULong()
            }
            out[3 - limb] = v
        }
        return out
    }

    /** Limbs -> big-endian 32 bytes. */
    fun toBytes32(a: ULongArray): ByteArray {
        val out = ByteArray(32)
        for (limb in 0..3) {
            for (i in 0..7) {
                out[limb * 8 + i] = (a[3 - limb] shr (8 * (7 - i))).toByte()
            }
        }
        return out
    }

    fun fromHex64(hex: String): ULongArray = fromBytes32(parseHex(hex, 64))

    /** Diagnostic hex (lowercase, big-endian) for test messages. */
    fun toHex(a: ULongArray): String {
        val digits = "0123456789abcdef"
        val bytes = toBytes32(a)
        val out = StringBuilder(64)
        for (byte in bytes) {
            val v = byte.toInt() and 0xff
            out.append(digits[v shr 4]).append(digits[v and 0x0f])
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Add / subtract (mod p, canonical results)
    // ------------------------------------------------------------------

    fun add(a: ULongArray, b: ULongArray): ULongArray {
        val t = ULongArray(4)
        var carry = 0UL
        for (i in 0..3) {
            // Two-step addition with exact wrap detection per step.
            val s1 = a[i] + carry
            val w1 = if (carry == 1UL && s1 == 0UL && a[i] == ULong.MAX_VALUE) 1UL else 0UL
            val s2 = s1 + b[i]
            val w2 = if (s2 < b[i] && b[i] != 0UL) 1UL else 0UL
            t[i] = s2
            carry = w1 + w2
        }
        return if (carry == 1UL || cmp(t, P) >= 0) rawSubWithBorrow(t, P) else t
    }

    fun sub(a: ULongArray, b: ULongArray): ULongArray {
        if (cmp(a, b) >= 0) return rawSubWithBorrow(a, b)
        // a < b: a - b ≡ a + (p - b) (mod p), and the sum stays below 2^256
        // because a < b implies a + (p - b) < p.
        return rawAddNoCarry(a, rawSubWithBorrow(P, b))
    }

    /** a - b on limbs where a >= b, or where the wrap is consumed by the caller. */
    private fun rawSubWithBorrow(a: ULongArray, b: ULongArray): ULongArray {
        val t = ULongArray(4)
        var borrow = 0UL
        for (i in 0..3) {
            val diff = a[i] - b[i] - borrow
            // Borrow when a[i] < b[i], or equal with an incoming borrow.
            borrow = if (a[i] < b[i] || (a[i] == b[i] && borrow == 1UL)) 1UL else 0UL
            t[i] = diff
        }
        return t
    }

    /** Adds two values whose sum is known to stay below 2^256. */
    private fun rawAddNoCarry(a: ULongArray, b: ULongArray): ULongArray {
        val t = ULongArray(4)
        var carry = 0UL
        for (i in 0..3) {
            val s1 = a[i] + carry
            val w1 = if (carry == 1UL && s1 == 0UL && a[i] == ULong.MAX_VALUE) 1UL else 0UL
            val s2 = s1 + b[i]
            val w2 = if (s2 < b[i] && b[i] != 0UL) 1UL else 0UL
            t[i] = s2
            carry = w1 + w2
        }
        return t
    }

    // ------------------------------------------------------------------
    // Multiplication (mod p)
    // ------------------------------------------------------------------

    /** Full 64x64 -> 128-bit product. Returns [lo, hi]. */
    fun mul64(a: ULong, b: ULong): ULongArray {
        val aLo = a and 0xFFFFFFFFUL
        val aHi = a shr 32
        val bLo = b and 0xFFFFFFFFUL
        val bHi = b shr 32
        val p0 = aLo * bLo
        val p1 = aLo * bHi
        val p2 = aHi * bLo
        val p3 = aHi * bHi
        val cy = (p0 shr 32) + (p1 and 0xFFFFFFFFUL) + (p2 and 0xFFFFFFFFUL)
        val lo = (p0 and 0xFFFFFFFFUL) or ((cy and 0xFFFFFFFFUL) shl 32)
        val hi = p3 + (p1 shr 32) + (p2 shr 32) + (cy shr 32)
        return ulongArrayOf(lo, hi)
    }

    /**
     * Adds [value] at limb [index], propagating carry upward while it lives.
     * Correct for any accumulation whose true sum fits the array width;
     * schoolbook 4x4 products always fit 8 limbs.
     */
    private fun addInto(a: ULongArray, index: Int, value: ULong) {
        var i = index
        var pending = value
        while (pending != 0UL && i < a.size) {
            val s = a[i] + pending
            val wrapped = if (pending != 0UL && s < pending) 1UL else 0UL
            a[i] = s
            pending = wrapped
            i++
        }
    }

    /** Schoolbook 4x4 limb multiply -> 8 limbs. */
    fun mul512(a: ULongArray, b: ULongArray): ULongArray {
        val t = ULongArray(8)
        for (i in 0..3) {
            if (a[i] == 0UL) continue
            for (j in 0..3) {
                if (b[j] == 0UL) continue
                val prod = mul64(a[i], b[j])
                addInto(t, i + j, prod[0])
                addInto(t, i + j + 1, prod[1])
            }
        }
        return t
    }

    /** Reduce an 8-limb value mod p using 2^256 ≡ C (mod p). */
    fun reduce512(t: ULongArray): ULongArray {
        val r = t.copyOf()
        var folds = 0
        while ((r[4] != 0UL || r[5] != 0UL || r[6] != 0UL || r[7] != 0UL) && folds < 6) {
            val up = ulongArrayOf(r[4], r[5], r[6], r[7])
            r[4] = 0UL; r[5] = 0UL; r[6] = 0UL; r[7] = 0UL
            for (i in 0..3) {
                if (up[i] == 0UL) continue
                val prod = mul64(up[i], C)
                addInto(r, i, prod[0])
                addInto(r, i + 1, prod[1])
            }
            folds++
        }
        val out = r.copyOfRange(0, 4)
        return if (cmp(out, P) >= 0) rawSubWithBorrow(out, P) else out
    }

    fun mul(a: ULongArray, b: ULongArray): ULongArray = reduce512(mul512(a, b))

    fun sqr(a: ULongArray): ULongArray = mul(a, a)

    /** a^exponent mod p by square-and-multiply over all 256 exponent bits. */
    fun pow(a: ULongArray, exponent: ULongArray): ULongArray {
        var result = ONE
        for (i in 255 downTo 0) {
            result = sqr(result)
            if ((exponent[i / 64] shr (i % 64)) and 1UL == 1UL) {
                result = mul(result, a)
            }
        }
        return result
    }

    fun inv(a: ULongArray): ULongArray = pow(a, P_MINUS_2)

    /**
     * Square root mod p. Returns null when [a] is not a quadratic residue.
     * p ≡ 3 (mod 4), so the root, when it exists, is a^((p+1)/4).
     */
    fun sqrt(a: ULongArray): ULongArray? {
        if (isZero(a)) return ZERO
        val y = pow(a, P_PLUS_1_DIV_4)
        return if (equals(sqr(y), a)) y else null
    }

    /** x^3 + 7 (the secp256k1 curve equation). */
    fun cubePlus7(x: ULongArray): ULongArray {
        val seven = ulongArrayOf(7UL, 0UL, 0UL, 0UL)
        return add(mul(sqr(x), x), seven)
    }

    private fun parseHex(hex: String, expectLength: Int): ByteArray {
        require(hex.length == expectLength)
        val out = ByteArray(expectLength / 2)
        for (i in out.indices) {
            out[i] = ((hexDigit(hex[i * 2]) shl 4) or hexDigit(hex[i * 2 + 1])).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("non-hex character")
    }
}
