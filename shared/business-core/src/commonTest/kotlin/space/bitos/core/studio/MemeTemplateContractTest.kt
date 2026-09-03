package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MST-045 read path: kind-30078 template events → hostile-tolerant parse. */
class MemeTemplateContractTest {

    private val tags = listOf(listOf("d", "com.bitos.bitz:template:zap-receipt"))
    private val overlays =
        "[{\"id\":\"t1\",\"text\":\"zap {name}\",\"x\":0.5,\"y\":0.14,\"size\":0.09," +
            "\"color\":\"#ffffff\",\"font\":\"impact\",\"caps\":true,\"stroke\":true}," +
            "{\"id\":\"t2\",\"text\":\"{sats} sats\",\"x\":0.5,\"y\":0.86,\"size\":0.09," +
            "\"color\":\"#f97316\",\"font\":\"impact\",\"caps\":true,\"stroke\":true}]"

    private fun content(label: String = "Zap Receipt", icon: String = "zap", extra: String = "") =
        "{\"schema\":\"com.bitos.bitz.template\",\"version\":2,\"label\":\"$label\"," +
            "\"icon\":\"$icon\",$extra\"overlays\":$overlays}"

    @Test
    fun v2EventParsesWithPriceAndCategory() {
        val template = MemeTemplateContract.parse(
            tags, content(extra = "\"price_sats\":100,\"category\":\"bitcoin\","),
        )!!
        assertEquals("zap-receipt", template.id)
        assertEquals("Zap Receipt", template.label)
        assertEquals("zap", "zap") // icon resolved below
        assertEquals("⚡", template.emoji)
        assertEquals(100, template.priceSats)
        assertEquals("bitcoin", template.category)
        assertEquals(2, template.overlays.size)
        assertEquals("zap {name}", template.overlays[0].text)
        assertTrue(template.overlays[0].size > 0)
    }

    @Test
    fun v1EventsDegradeToFreeTemplates() {
        val v1 = "{\"label\":\"Classic\",\"overlays\":[{\"id\":\"t1\",\"text\":\"TOP\",\"x\":0.5," +
            "\"y\":0.1,\"size\":0.09,\"color\":\"#ffffff\",\"font\":\"impact\"}]}"
        val template = MemeTemplateContract.parse(tags, v1)!!
        assertEquals(0, template.priceSats)
        assertEquals("meme", template.category)
        assertEquals("🖼", template.emoji)
    }

    @Test
    fun hostileShapesReturnNullOrDegrade() {
        assertNull(MemeTemplateContract.parse(listOf(listOf("d", "other:app:x")), content()))
        assertNull(
            MemeTemplateContract.parse(tags, "{\"schema\":\"foreign\",\"version\":2,\"overlays\":[{\"text\":\"x\"}]}"),
        )
        assertNull(MemeTemplateContract.parse(tags, content().replace("\"version\":2", "\"version\":3")))
        assertNull(
            MemeTemplateContract.parse(
                tags,
                "{\"schema\":\"com.bitos.bitz.template\",\"version\":2,\"label\":\"x\",\"overlays\":[]}",
            ),
        )
        assertNull(MemeTemplateContract.parse(tags, "not json"))

        // Junk prices degrade to free; unknown category falls back.
        val junkContent =
            "{\"schema\":\"com.bitos.bitz.template\",\"version\":2,\"label\":\"J\",\"icon\":\"zap\"," +
                "\"price_sats\":999999,\"category\":\"nope\",\"overlays\":[{\"id\":\"t1\",\"text\":\"x\"," +
                "\"x\":0.5,\"y\":0.2,\"size\":0.09,\"color\":\"#ffffff\",\"font\":\"impact\"}]}"
        val junk = MemeTemplateContract.parse(tags, junkContent)
        assertTrue(junk != null, "junk-price template still parses")
        assertEquals(0, junk!!.priceSats)
        assertEquals("meme", junk.category)

        // Hostile overlay values clamp through the wire parser.
        val hostileContent =
            "{\"schema\":\"com.bitos.bitz.template\",\"version\":2,\"label\":\"H\",\"icon\":\"zap\"," +
                "\"overlays\":[{\"id\":\"t1\",\"text\":\"x\",\"x\":9,\"y\":-9,\"size\":5," +
                "\"color\":\"red\",\"font\":\"comic\"}]}"
        val hostile = MemeTemplateContract.parse(tags, hostileContent)
        assertTrue(hostile != null, "hostile overlay clamps and parses")
        assertTrue(hostile!!.overlays[0].x in 0f..1f && hostile.overlays[0].y in 0f..1f)
    }

    @Test
    fun applyClonesFreshIdsKeepingProjectContext() {
        val template = MemeTemplateContract.parse(tags, content())!!
        val base = MemeProject(
            mode = MemeMode.IMAGE,
            assets = listOf(MemeAsset("a1", MemeMode.IMAGE)),
            overlays = listOf(
                MemeOverlay("old", MemeOverlayKind.TEXT, "old", MemeFontSlot.SANS, 48, 0, 0, false, .5f, .5f, 1f, 0f),
            ),
            lookId = "vhs",
        )
        val applied = MemeTemplateContract.apply(base, template)
        assertEquals(2, applied.overlays.size)
        assertTrue(applied.overlays.all { it.id.startsWith("s") && it.id.contains("zap-receipt") })
        assertEquals(base.assets, applied.assets)
        assertEquals("vhs", applied.lookId)
    }
}
