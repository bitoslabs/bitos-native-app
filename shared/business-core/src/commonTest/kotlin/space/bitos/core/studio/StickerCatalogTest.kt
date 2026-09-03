package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Web `stickers.ts` port parity (plan MST-014): packs land verbatim so the
 * native sticker grid matches the web studio and future wire payloads.
 */
class StickerCatalogTest {

    @Test
    fun packsMatchTheWebCatalogShape() {
        assertEquals(6, StickerCatalog.PACKS.size)
        StickerCatalog.PACKS.forEach { pack ->
            assertEquals(8, pack.stickers.size, pack.id)
            assertTrue(pack.id.isNotBlank() && pack.label.isNotBlank())
        }
        // Bitcoin sign is a first-class sticker in the crypto pack.
        assertTrue(StickerCatalog.PACKS.first().stickers.contains("₿"))
        // Packs dedupe into the flat recents seed (⚡ and 📈 repeat).
        assertEquals(StickerCatalog.PACKS.flatMap { it.stickers }.toSet().size, StickerCatalog.ALL.size)
    }

    @Test
    fun recentsAreDedupedBoundedAndMostRecentFirst() {
        val history = listOf("🚀", "🔥", "🚀", "💀")
        val next = StickerCatalog.recents(history, "💎")
        assertEquals("💎", next.first())
        assertTrue(next.indexOf("🚀") < next.indexOf("🔥"))
        assertFalse(next.contains("👻"), "unknown entries never enter the grid")
        // The cap holds even with a saturated history.
        val saturated = StickerCatalog.recents(StickerCatalog.ALL + StickerCatalog.ALL, null)
        assertTrue(saturated.size <= StickerCatalog.MAX_RECENT_STICKERS)
        assertEquals(StickerCatalog.MAX_RECENT_STICKERS, saturated.size)
    }

    @Test
    fun emojiOnlyAcceptsCatalogGraphemesAndRejectsText() {
        StickerCatalog.ALL.forEach { sticker ->
            assertTrue(StickerCatalog.isEmojiOnly(sticker), sticker)
        }
        // Structural joins and variation selectors stay allowed.
        assertTrue(StickerCatalog.isEmojiOnly("🌪️"))
        assertTrue(StickerCatalog.isEmojiOnly(" ₿ "))
        assertFalse(StickerCatalog.isEmojiOnly(""))
        assertFalse(StickerCatalog.isEmojiOnly("gm"))
        assertFalse(StickerCatalog.isEmojiOnly("🚀a"))
        assertFalse(StickerCatalog.isEmojiOnly("a🚀"))
    }
}
