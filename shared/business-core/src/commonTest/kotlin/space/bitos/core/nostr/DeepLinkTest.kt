package space.bitos.core.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T16 inbound deep links (spec §1.2): `nostr:` entities route to profiles
 * or threads; `lightning:` invoices open the viewer. Classification is
 * shared so both platforms resolve identical URIs identically.
 */
class DeepLinkTest {

    private val pubkeyHex = "ab".repeat(32)

    private fun npub(hex: String = pubkeyHex): String = Nip27.encodeEntity("npub", hexBytes(hex))

    @Test
    fun classifiesNostrNpubLinks() {
        val target = DeepLinks.classify("nostr:${npub()}")
        assertEquals(DeepLinks.Target.Author(pubkeyHex), target)
        // Scheme is case-insensitive.
        assertEquals(
            DeepLinks.Target.Author(pubkeyHex),
            DeepLinks.classify("NOSTR:${npub()}"),
        )
    }

    @Test
    fun classifiesNoteReferencesWithAndWithoutPrefix() {
        val note1 = Nip27.encodeEntity("note", hexBytes("11".repeat(32)))
        assertEquals(
            DeepLinks.Target.Note(note1),
            DeepLinks.classify("nostr:$note1"),
        )
        assertEquals(
            DeepLinks.Target.Note(note1),
            DeepLinks.classify(note1),
        )
    }

    @Test
    fun classifiesLightningInvoices() {
        val uri = "lightning:lnbcrt1234567890abcdef"
        assertEquals(DeepLinks.Target.Lightning(uri), DeepLinks.classify(uri))
    }

    @Test
    fun rejectsNonDeepLinksAndHostileInput() {
        assertNull(DeepLinks.classify(""))
        assertNull(DeepLinks.classify("https://example.com"))
        assertNull(DeepLinks.classify("nostr:"))
        assertNull(DeepLinks.classify("nostr:nonsense"))
        assertNull(DeepLinks.classify("npub1invalid"))
        assertNull(DeepLinks.classify("nostr:" + "x".repeat(3_000)))
    }

    @Test
    fun bridgeJsonShapeIsStable() {
        val json = space.bitos.core.bridge.BusinessCoreBridge().deepLinkJson("nostr:${npub()}")
        assertTrue(json != null && json.contains("\"kind\":\"author\"") && json.contains(pubkeyHex))
        assertNull(space.bitos.core.bridge.BusinessCoreBridge().deepLinkJson("mailto:a@b.c"))
    }

    private fun hexBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
