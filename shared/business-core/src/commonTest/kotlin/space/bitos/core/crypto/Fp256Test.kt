package space.bitos.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Field arithmetic vectors computed independently (node BigInt) and locked
 * here so every target (JVM host, macOS native, iOS via the framework)
 * executes the same low-level truths.
 */
class Fp256Test {

    private val pMinus1 = Fp256.sub(Fp256.ZERO, Fp256.ONE) // p - 1
    private val pMinus2 = Fp256.sub(pMinus1, Fp256.ONE)

    @Test
    fun mul64HandlesMaximalOperands() {
        val max = ULong.MAX_VALUE
        val product = Fp256.mul64(max, max)
        // (2^64-1)^2 = 2^128 - 2^65 + 1 = (2^64-2)·2^64 + 1
        assertEquals(1UL, product[0])
        assertEquals(0xFFFFFFFFFFFFFFFEUL, product[1])

        val zero = Fp256.mul64(0UL, max)
        assertEquals(0UL, zero[0])
        assertEquals(0UL, zero[1])

        val small = Fp256.mul64(3UL, 5UL)
        assertEquals(15UL, small[0])
        assertEquals(0UL, small[1])
    }

    @Test
    fun multiplicationMatchesReferenceVectors() {
        // (p-1)^2 mod p = 1
        assertFp("0000000000000000000000000000000000000000000000000000000000000001", Fp256.sqr(pMinus1),
        )
        // (p-2)^3 mod p
        assertFp("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc27", Fp256.mul(Fp256.sqr(pMinus2), pMinus2),
        )
        // 2^130 mod p = 0x40 · 2^124, via a sparse exponent
        assertFp(
            "0000000000000000000000000000000400000000000000000000000000000000",
            Fp256.pow(
                Fp256.fromHex64("0000000000000000000000000000000000000000000000000000000000000002"),
                ulongArrayOf(130UL, 0UL, 0UL, 0UL),
            ),
        )
        // Gx^2 mod p
        assertFp("8550e7d238fcf3086ba9adcf0fb52a9de3652194d06cb5bb38d50229b854fc49", Fp256.sqr(
                Fp256.fromHex64("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798".lowercase()),
            ),
        )
    }

    @Test
    fun reduce512CanonicalizesAboveP() {
        // p + 5 mod p = 5
        val t = ULongArray(8)
        t[0] = Fp256.P[0] + 5UL // p's low limb has room: 0xFFFFFC2F + 5 does not wrap
        t[1] = Fp256.P[1]; t[2] = Fp256.P[2]; t[3] = Fp256.P[3]
        val reduced = Fp256.reduce512(t)
        assertFp("0000000000000000000000000000000000000000000000000000000000000005", reduced)
        assertTrue(Fp256.cmp(reduced, Fp256.P) < 0)
    }

    @Test
    fun inversionRoundTrips() {
        val gx = Fp256.fromHex64("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
        assertTrue(Fp256.equals(Fp256.ONE, Fp256.mul(Fp256.inv(gx), gx)))
        assertTrue(Fp256.equals(Fp256.ONE, Fp256.mul(Fp256.inv(pMinus1), pMinus1)))
    }

    @Test
    fun sqrtDetectsResidues() {
        // 132 = 5^3 + 7 is a non-residue mod p (x = 5 is not on the curve).
        val x5 = Fp256.fromHex64("0000000000000000000000000000000000000000000000000000000000000005")
        assertNull(Fp256.sqrt(Fp256.cubePlus7(x5)))
        // 7 itself is a non-residue mod p.
        val seven = Fp256.fromHex64("0000000000000000000000000000000000000000000000000000000000000007")
        assertNull(Fp256.sqrt(seven))
        // Gx IS on the curve: x^3+7 must have a root.
        val gx = Fp256.fromHex64("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
        val root = Fp256.sqrt(Fp256.cubePlus7(gx))
        assertEquals(true, root != null)
        // The two roots are ±y and exactly one of them is the known Gy.
        val gy = Fp256.fromHex64("483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8")
        assertTrue(Fp256.equals(root!!, gy) || Fp256.equals(Fp256.sub(Fp256.P, root), gy))
    }

    @Test
    fun addSubRoundTripWithinField() {
        val a = Fp256.fromHex64("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        val b = Fp256.fromHex64("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210")
        assertTrue(Fp256.equals(a, Fp256.sub(Fp256.add(a, b), b)))
        assertTrue(Fp256.equals(Fp256.ZERO, Fp256.sub(a, a)))
        // a - b = a + (p - b) when a < b
        assertTrue(Fp256.equals(Fp256.sub(b, a), Fp256.add(Fp256.sub(Fp256.ZERO, a), b)))
    }

    @Test
    fun generatorIsOnCurveWithEvenY() {
        val lifted = Secp256k1.liftX(
            Fp256.fromHex64("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"),
        )
        assertEquals(true, lifted != null)
        val affine = Secp256k1.toAffine(lifted!!)
        assertEquals(true, affine != null)
        // BIP-340 lift_x selects the even y; the generator's y is even already.
        assertEquals(0UL, affine!!.second[0] and 1UL)
    }

    @Test
    fun scalarMultiplicationOfGeneratorMatchesKnownDouble() {
        // 2G has a well-known x coordinate.
        val two = ulongArrayOf(2UL, 0UL, 0UL, 0UL)
        val affine = Secp256k1.toAffine(Secp256k1.multiply(two, Secp256k1.generator()))!!
        assertFp("c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5", affine.first,
        )
    }
    /** ULongArray has no structural equals; compare through the field API. */
    private fun assertFp(expectedHex: String, actual: ULongArray) {
        val expected = Fp256.fromHex64(expectedHex.lowercase())
        assertTrue(Fp256.equals(expected, actual), "expected " + expectedHex.lowercase() + " but got " + Fp256.toHex(actual))
    }

}
