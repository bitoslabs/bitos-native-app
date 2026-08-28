package space.bitos.core.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * APP-022 QR encoder contract: deterministic, structurally valid matrices
 * for identity payloads (npub / nostr: links).
 */
class QrCodeTest {

    @Test
    fun matrixSizesMatchVersionGeometry() {
        // V1 (21) for short text, V6 (41) for a nostr: npub link.
        assertEquals(21, QrCode.encode("hello")!!.size)
        val link = "nostr:" + "npub1" + "a".repeat(58)
        assertEquals(41, QrCode.encode(link)!!.size)
        assertTrue(QrCode.encode(link)!!.all { it.size == 41 })
    }

    @Test
    fun finderAndTimingPatternsAreStructurallyPresent() {
        val m = QrCode.encode("bitos")!!
        val size = m.size
        // Finder rings: (0,0), (size-7,0), (0,size-7) dark borders + light inner.
        assertTrue(m[0][0] && m[0][6] && m[6][0] && m[6][6])
        assertTrue(m[3][3]) // center of top-left finder
        assertTrue(!m[0][7] && !m[7][0]) // separator light
        assertTrue(m[size - 7][0] && m[0][size - 7])
        // Timing strips alternate starting dark at (6,8) and (8,6).
        assertTrue(m[6][8] && !m[6][9] && m[6][10])
        assertTrue(m[8][6] && !m[9][6] && m[10][6])
        // Always-dark module.
        assertTrue(m[size - 8][8])
    }

    @Test
    fun deterministicAndPayloadSensitive() {
        val a = QrCode.encode("npub1abc")
        val b = QrCode.encode("npub1abc")
        val c = QrCode.encode("npub1abd")
        assertEquals(a, b, "same payload → identical matrix")
        assertTrue(a != c, "different payload → different matrix")
    }

    @Test
    fun capacityBoundsRejectOversize() {
        val npub = "npub1" + "a".repeat(58) // 63 bytes → V6 (byte capacity 86)
        assertEquals(37, QrCode.encode(npub)!!.size)
        // >86 bytes cannot fit V1–V6 at level M → null (invoice QRs land
        // with the higher-version table).
        assertNull(QrCode.encode("x".repeat(87)))
        assertNotNull(QrCode.encode("x".repeat(86)))
    }

    @Test
    fun darkProportionStaysBalanced() {
        val m = QrCode.encode("nostr:npub1" + "z".repeat(58))!!
        val dark = m.sumOf { row -> row.count { it } }
        val ratio = dark.toDouble() / (m.size * m.size)
        assertTrue(ratio in 0.35..0.65, "dark ratio $ratio unexpectedly skewed")
    }
}
