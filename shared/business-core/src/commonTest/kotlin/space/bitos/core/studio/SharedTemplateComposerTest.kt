package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import space.bitos.core.publish.NoteComposer

/**
 * MST-045 publish-your-own: kind-30078 template events. The composer's
 * output must round-trip through `MemeTemplateContract.parse` — the
 * shared rail sees exactly what this built (plan §3.5 wire).
 */
class SharedTemplateComposerTest {

    private val author = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun overlay(text: String, y: Float = 0.15f) = MemeOverlay(
        id = "o$text.length", kind = MemeOverlayKind.TEXT, text = text,
        font = MemeFontSlot.IMPACT, size = 96, colorIndex = 0, outline = 4,
        shadow = false, x = 0.5f, y = y, scale = 1f, rotationDeg = 0f,
    )

    private fun compose(
        templateId: String = "zap-receipt",
        label: String = "Zap Receipt",
        icon: String = "zap",
        priceSats: Long = 0L,
        category: String = "meme",
        overlays: List<MemeOverlay> = listOf(overlay("zap {name}"), overlay("{sats} sats", 0.85f)),
    ) = NoteComposer(clock = { 1_700_000_000L }).composeSharedTemplate(
        authorPubkey = author,
        templateId = templateId,
        label = label,
        icon = icon,
        overlays = overlays,
        priceSats = priceSats,
        category = category,
    )

    @Test
    fun composedEventRoundTripsThroughTheRailContract() {
        val note = compose(priceSats = 100, category = "bitcoin")!!
        assertEquals(30_078, note.kind)
        // Only the d-tag rides the event — everything else is content.
        assertEquals(listOf(listOf("d", "com.bitos.bitz:template:zap-receipt")), note.tags)

        val template = MemeTemplateContract.parse(note.tags, note.content)!!
        assertEquals("zap-receipt", template.id)
        assertEquals("Zap Receipt", template.label)
        assertEquals("⚡", template.emoji)
        assertEquals(100, template.priceSats)
        assertEquals("bitcoin", template.category)
        assertEquals(2, template.overlays.size)
        assertEquals("zap {name}", template.overlays[0].text)
        assertEquals("{sats} sats", template.overlays[1].text)
        assertTrue(template.overlays[0].size > 0)
    }

    @Test
    fun defaultsOmitPriceAndCategory() {
        val note = compose()!!
        val content = note.content
        assertTrue(!content.contains("price_sats"), content)
        assertTrue(!content.contains("category"), content)
        val template = MemeTemplateContract.parse(note.tags, content)!!
        assertEquals(0, template.priceSats)
        assertEquals("meme", template.category)
    }

    @Test
    fun contractViolationsReturnNull() {
        assertNull(compose(icon = "sparkles")) // outside the 8-id allowlist
        assertNull(compose(priceSats = 21 + 1)) // off-tier (22)
        assertNull(compose(priceSats = 1_000_000)) // junk price
        assertNull(compose(label = "   ")) // blank
        assertNull(compose(templateId = "UPPER")) // slug only
        assertNull(compose(templateId = "")) // blank
        assertNull(compose(templateId = "a".repeat(49))) // > 48
        assertNull(compose(overlays = emptyList())) // nothing publishable
        assertNull(compose(overlays = listOf(overlay("  ")))) // blank text
        assertNull(
            NoteComposer(clock = { 1L }).composeSharedTemplate(
                authorPubkey = "zz", templateId = "ok", label = "L",
                icon = "zap", overlays = listOf(overlay("x")),
            ),
        ) // junk pubkey
    }

    @Test
    fun imageAndStickerOverlaysDropTextOnesSurvive() {
        val sticker = overlay("🚀").copy(kind = MemeOverlayKind.STICKER)
        val image = overlay("x").copy(kind = MemeOverlayKind.IMAGE)
        val note = compose(overlays = listOf(image, overlay("TOP"), sticker))!!
        val template = MemeTemplateContract.parse(note.tags, note.content)!!
        // The wire converter keeps stickers as text overlays; the IMAGE
        // kind drops — the same rule localToWire applies everywhere.
        assertEquals(listOf("TOP", "🚀"), template.overlays.map { it.text })
    }

    @Test
    fun idIsStableForPinnedClock() {
        assertEquals(compose()!!.idHex, compose()!!.idHex)
    }

    @Test
    fun publishMessageCarriesTheSignedKind30078Frame() {
        val composer = NoteComposer(clock = { 1_700_000_000L })
        val unsigned = compose()!!
        val frame = composer.publishMessage(unsigned, "ab".repeat(64))
            ?: return kotlin.test.fail("no frame")
        assertTrue(frame.contains("\"kind\":30078"), frame)
        assertTrue(frame.contains("\"id\":\"${unsigned.idHex}\""), frame)
    }
}
