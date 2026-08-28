package space.bitos.core.model

import space.bitos.core.nostr.Nip27
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * APP-014 bolt11 expiry parse: timestamp (TLV 3) + expiry (TLV 6, default
 * 3600 s) from the bech32 data part. Synthetic invoices are built with the
 * internal bech32 encoder so no fixture depends on a paid invoice.
 */
class Bolt11ExpiryTest {

    private fun tlv(type: Int, value: ByteArray): ByteArray =
        byteArrayOf(type.toByte(), value.size.toByte()) + value

    private fun u32(value: Long): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun invoice(vararg tlvs: ByteArray): String = Nip27.encodeEntity(
        "lnbc",
        tlvs.reduce { acc, bytes -> acc + bytes },
    )

    @Test
    fun parsesTimestampPlusExplicitExpiry() {
        val invoice = invoice(tlv(3, u32(1_700_000_000)), tlv(6, byteArrayOf(120)))
        assertEquals(1_700_000_120L, Bolt11.expirySeconds(invoice))
    }

    @Test
    fun expiryDefaultsTo3600Seconds() {
        val invoice = invoice(tlv(3, u32(1_700_000_000)))
        assertEquals(1_700_003_600L, Bolt11.expirySeconds(invoice))
    }

    @Test
    fun rejectsMalformedInput() {
        assertNull(Bolt11.expirySeconds("not-an-invoice"))
        assertNull(Bolt11.expirySeconds("lntb1" + "q".repeat(40))) // non-mainnet hrp
        // No timestamp field → no expiry.
        assertNull(Bolt11.expirySeconds(invoice(tlv(6, byteArrayOf(60)))))
    }
}
