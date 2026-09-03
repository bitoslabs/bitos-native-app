package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MEM-001 fixture battery (plan MST-019): the web `schema.test.ts` cases
 * ported branch-for-branch onto the native wire codec, plus the native-only
 * guarantees (passthrough preservation, local ⇄ wire conversion).
 */
class MemeWireCodecTest {

    private val nowMs = 1_700_000_000_000L

    // ── normalizeOverlay parity ──────────────────────────────────────────

    @Test
    fun fillsSaneDefaultsForAMinimalOverlay() {
        val doc = decode(
            """{"schema":"com.bitos.bitz.meme","version":1,
               "overlays":[{"text":"gm nostr"}]}""",
        )
        val overlay = doc!!.overlays.single()
        assertEquals(0.5f, overlay.x)
        assertEquals(0.5f, overlay.y)
        assertEquals(0.09f, overlay.size)
        assertEquals("impact", overlay.font)
        assertEquals(true, overlay.caps)
        assertEquals(true, overlay.stroke)
        assertEquals(false, overlay.bar)
        assertTrue(overlay.id.isNotBlank())
    }

    @Test
    fun dropsBlankAndNonObjectOverlayRows() {
        val doc = decode(
            """{"schema":"com.bitos.bitz.meme","version":1,
               "overlays":["text", null, 7, {"text":"   "}, {}]}""",
        )
        assertTrue(doc!!.overlays.isEmpty())
    }

    @Test
    fun clampsPositionAndSizeIntoLegalRanges() {
        val doc = decode(
            """{"schema":"com.bitos.bitz.meme","version":1,
               "overlays":[{"text":"x","x":-3,"y":42,"size":5},
                           {"text":"x","size":0.0001}]}""",
        )
        val wide = doc!!.overlays[0]
        assertEquals(0f, wide.x)
        assertEquals(1f, wide.y)
        assertEquals(0.22f, wide.size)
        assertEquals(0.03f, doc.overlays[1].size)
    }

    @Test
    fun truncatesMarathonCaptionsToTheCap() {
        val doc = decode(
            """{"schema":"com.bitos.bitz.meme","version":1,
               "overlays":[{"text":"${"a".repeat(MemeWire.MAX_OVERLAY_CHARS + 50)}"}]}""",
        )
        assertEquals(MemeWire.MAX_OVERLAY_CHARS, doc!!.overlays.single().text.length)
    }

    @Test
    fun garbageColorsFallBackButValidHexIsKeptVerbatim() {
        val doc = decode(
            """{"schema":"com.bitos.bitz.meme","version":1,
               "overlays":[{"text":"x","color":"javascript"},
                           {"text":"x","color":"#FDE047"}]}""",
        )
        assertEquals("#ffffff", doc!!.overlays[0].color)
        assertEquals("#FDE047", doc.overlays[1].color, "case preserved (web parity)")
    }

    @Test
    fun unknownFontsCoerceToImpactAndUnknownFlagsToDefaults() {
        val doc = decode(
            """{"schema":"com.bitos.bitz.meme","version":1,
               "overlays":[{"text":"x","font":"comic-sans","caps":"nope"}]}""",
        )
        val overlay = doc!!.overlays.single()
        assertEquals("impact", overlay.font)
        assertEquals(true, overlay.caps, "web `!!'nope'` = true")
    }

    @Test
    fun dropsNegativeOrNonFiniteTimingWindows() {
        val doc = decode(
            """{"schema":"com.bitos.bitz.meme","version":1,
               "overlays":[{"text":"x","startMs":-100},
                           {"text":"x","startMs":"soon"},
                           {"text":"x","startMs":2000,"endMs":1000}]}""",
        )
        assertNull(doc!!.overlays[0].startMs)
        assertNull(doc.overlays[1].startMs)
        assertNull(doc.overlays[2].startMs, "inverted window clears to always-visible")
        assertNull(doc.overlays[2].endMs)
    }

    @Test
    fun unknownFieldsArePRESERVEDInNativePassthrough() {
        // Web drops unknown fields (their test asserts absence); native is
        // strictly safer — future-web data survives a native round-trip.
        val wire = """{"schema":"com.bitos.bitz.meme","version":1,
               "overlays":[{"text":"x","hacked":true,"emoji":"🚀"}],
               "futureRootField":{"deep":[1,2,3]}}"""
        val normalized = MemeWireCodec.normalize(wire, nowMs)!!
        assertTrue(normalized.contains("\"hacked\":true"), normalized)
        assertTrue(normalized.contains("\"emoji\":\"🚀\""), normalized)
        assertTrue(normalized.contains("\"futureRootField\""), normalized)
    }

    // ── normalizeProject parity ──────────────────────────────────────────

    @Test
    fun rejectsForeignSchemasAndNonObjects() {
        assertNull(decode("nope"))
        assertNull(decode("""{"schema":"com.other.meme","version":1}"""))
        assertNull(decode("""{"schema":"com.bitos.bitz.meme","version":2}"""))
        assertNull(decode("""{"schema":"com.bitos.bitz.meme","version":0}"""))
    }

    @Test
    fun acceptsAMissingVersionAsV1() {
        // web num(p.version, 1): non-numeric/missing versions default to 1.
        val doc = decode("""{"schema":"com.bitos.bitz.meme","overlays":[]}""")
        assertNotNull(doc)
    }

    @Test
    fun normalizesOverlaysAndCapsTheCount() {
        val rows = (0 until MemeWire.MAX_OVERLAYS + 5).joinToString(",") {
            """{"text":"m$it"}"""
        }
        val doc = decode(
            """{"schema":"com.bitos.bitz.meme","version":1,"overlays":[$rows]}""",
        )
        assertEquals(MemeWire.MAX_OVERLAYS, doc!!.overlays.size)
    }

    @Test
    fun updatedAtIsAlwaysRestampedOnLoad() {
        val doc = decode(
            """{"schema":"com.bitos.bitz.meme","version":1,"overlays":[],
               "createdAt":42,"updatedAt":999}""",
        )
        assertEquals(42L, doc!!.createdAt)
        assertEquals(nowMs, doc.updatedAt)
    }

    // ── visibility window ([start, end) semantics) ───────────────────────

    @Test
    fun visibilityHonorsHalfOpenWindows() {
        val doc = decode(
            """{"schema":"com.bitos.bitz.meme","version":1,
               "overlays":[{"text":"always"},{"text":"mid","startMs":1000,"endMs":2000},
                           {"text":"late","startMs":1500}]}""",
        )
        val (always, windowed, late) = doc!!.overlays
        assertTrue(doc.visibleAt(always, 0) && doc.visibleAt(always, 123_456))
        assertFalse(doc.visibleAt(windowed, 999))
        assertTrue(doc.visibleAt(windowed, 1000), "inclusive start")
        assertTrue(doc.visibleAt(windowed, 1999))
        assertFalse(doc.visibleAt(windowed, 2000), "exclusive end")
        assertFalse(doc.visibleAt(late, 1499))
        assertTrue(doc.visibleAt(late, 1500))
        assertTrue(doc.visibleAt(late, 1_000_000_000))
    }

    // ── sfx cue parsing (wire fidelity now; synthesis lands in M4) ──────

    @Test
    fun cuesParseClampAndDrop() {
        val doc = decode(
            """{"schema":"com.bitos.bitz.meme","version":1,"overlays":[],
               "sfxCues":[
                 {"sfx":"coin","atMs":1500,"gain":2},
                 {"sfx":"custom","atMs":10},
                 {"sfx":"not-a-real-sfx","atMs":10},
                 {"sfx":"boom","atMs":-5,"gain":0.5,"lane":9,"id":"b1"}
               ]}""",
        )
        assertEquals(2, doc!!.sfxCues.size, "custom-without-soundId and unknown ids drop")
        val coin = doc.sfxCues[0]
        assertEquals("coin", coin.sfx)
        assertEquals(1500L, coin.atMs)
        assertEquals(1f, coin.gain, "gain clamps to 1")
        assertNull(coin.lane)
        val boom = doc.sfxCues[1]
        assertEquals(0L, boom.atMs, "atMs ≤ 0 lands at 0")
        assertEquals(3, boom.lane, "lane floors and caps at 3")
    }

    // ── local ⇄ wire converters (plan §3.2) ─────────────────────────────

    @Test
    fun wireSizeConvertsToTheLocalPixelReference() {
        assertEquals(97, MemeWireConvert.wireSizeToLocalPx(0.09f), "web doc: 0.09 → 97 px")
        assertEquals(33, MemeWireConvert.wireSizeToLocalPx(0.031f))
        assertEquals(0.0898f, MemeWireConvert.localSizeToWireFraction(97), 0.001f)
        // The tighter web bound wins on export (local 12 px < web 32 px).
        assertEquals(0.03f, MemeWireConvert.localSizeToWireFraction(12), 0.0001f)
    }

    @Test
    fun wireToLocalMapsStylesStickersAndWindows() {
        val doc = decode(
            """{"schema":"com.bitos.bitz.meme","version":1,
               "overlays":[
                 {"id":"t1","text":"TOP TEXT","x":0.1,"y":0.2,"size":0.11,
                  "color":"#22d3ee","font":"mono","caps":true,"stroke":true,
                  "startMs":250,"endMs":9000,"fx":"pop"},
                 {"id":"s1","text":"🚀","caps":false,"stroke":false}
               ]}""",
        )!!
        val local = MemeWireConvert.wireToLocal(doc)
        assertEquals(MemeMode.IMAGE, local.mode)
        val text = local.overlays[0]
        assertEquals(MemeOverlayKind.TEXT, text.kind)
        assertEquals(119, text.size, "0.11 × 1080 = 119 px")
        assertEquals(MemeFontSlot.MONO, text.font)
        assertEquals(2, text.outline, "stroke=true → default 2 px outline")
        assertEquals(null, text.caps, "web default true → local implicit null")
        assertEquals(250L, text.startMs)
        assertEquals(9000L, text.endMs)
        assertEquals(MemeOverlayFx.POP, text.fx)
        // Emoji-only + stroke=false + caps=false = sticker (web parity rule).
        val sticker = local.overlays[1]
        assertEquals(MemeOverlayKind.STICKER, sticker.kind)
        assertEquals(0, sticker.outline)
    }

    @Test
    fun localToWireDropsBlankRowsCapsTheCountAndKeepsLocalExtrasInPassthrough() {
        val project = MemeProject(
            mode = MemeMode.IMAGE,
            overlays = List(14) { index ->
                MemeOverlay(
                    id = "o$index",
                    kind = MemeOverlayKind.TEXT,
                    text = if (index == 0) "  " else "t$index",
                    font = MemeFontSlot.IMPACT,
                    size = 97,
                    colorIndex = 0,
                    outline = 2,
                    shadow = index == 1,
                    x = 0.5f,
                    y = 0.5f,
                    scale = if (index == 1) 1.5f else 1f,
                    rotationDeg = if (index == 1) -12f else 0f,
                )
            },
        )
        val wire = MemeWireConvert.localToWire(project, nowMs)
        assertEquals(12, wire.overlays.size, "blank row drops, then the wire cap bites")
        assertTrue(wire.overlays.none { it.id == "o0" }, "blank-text overlay dropped")
        val styled = wire.overlays.first { it.id == "o1" }
        assertEquals(true, styled.stroke)
        assertFalse(styled.bar)
        assertTrue(styled.passthrough.containsKey("scale"), "local scale rides passthrough")
        assertTrue(styled.passthrough.containsKey("rot"))
        assertTrue(styled.passthrough.containsKey("shadow"))
        assertEquals("image", wire.mediaKind)
    }

    @Test
    fun localToWireDropsImageLayersButTheLocalStoreKeepsThem() {
        val project = MemeProject(
            mode = MemeMode.VIDEO,
            overlays = listOf(
                MemeOverlay(
                    id = "t1", kind = MemeOverlayKind.TEXT, text = "gm",
                    font = MemeFontSlot.IMPACT, size = 97, colorIndex = 0,
                    outline = 2, shadow = false, x = 0.5f, y = 0.5f,
                    scale = 1f, rotationDeg = 0f,
                ),
                MemeOverlay(
                    id = "L1", kind = MemeOverlayKind.IMAGE, text = "",
                    font = MemeFontSlot.SANS, size = MemeRules.DEFAULT_IMAGE_SIZE,
                    colorIndex = 0, outline = 0, shadow = false,
                    x = 0.4f, y = 0.6f, scale = 1f, rotationDeg = 0f,
                    assetId = "a1",
                ),
            ),
        )
        val wire = MemeWireConvert.localToWire(project, nowMs)
        assertEquals(listOf("t1"), wire.overlays.map { it.id }, "layers ride the local store, never the interop wire")
        assertEquals("video", wire.mediaKind)
        val local = MemeProjectContract.decode(MemeProjectContract.encode(project))!!
        assertEquals(MemeOverlayKind.IMAGE, local.overlays[1].kind)
        assertEquals("a1", local.overlays[1].assetId, "slot persistence keeps the layer")
    }

    @Test
    fun nativeRoundTripSurvivesWireToLocalToWire() {
        val original = decode(
            """{"schema":"com.bitos.bitz.meme","version":1,
               "overlays":[
                 {"id":"t1","text":"wen laser eyes","x":0.31,"y":0.77,
                  "size":0.11,"color":"#22d3ee","font":"mono","caps":false,
                  "stroke":true,"bar":true,"startMs":250,"endMs":9000,"fx":"spin"}
               ],
               "caption":"gm","mediaKind":"image"}""",
        )!!
        val local = MemeWireConvert.wireToLocal(original)
        val back = MemeWireConvert.localToWire(local, nowMs)
        val overlay = back.overlays.single()
        assertEquals("t1", overlay.id)
        assertEquals("wen laser eyes", overlay.text)
        assertEquals(0.31f, overlay.x, 0.0001f)
        assertEquals(0.77f, overlay.y, 0.0001f)
        // Size quantizes to whole canvas px on the local side (≤ 0.5 px error).
        assertEquals(original.overlays.single().size, overlay.size, 0.0005f)
        assertEquals("mono", overlay.font)
        assertEquals(false, overlay.caps)
        assertEquals(true, overlay.stroke)
        assertEquals(true, overlay.bar)
        assertEquals(250L, overlay.startMs)
        assertEquals(9000L, overlay.endMs)
        assertEquals("spin", overlay.fx)
        assertEquals("gm", back.caption)
    }

    @Test
    fun paletteAndWireHexNearestMappingIsStable() {
        // Index 0 = local white → web white; index 1 = local black → web black.
        assertEquals("#ffffff", MemeWireConvert.nearestWireHex(0))
        assertEquals("#000000", MemeWireConvert.nearestWireHex(1))
        // The web orange #f97316 lands on the local orange family.
        val orangeIndex = MemeWireConvert.nearestPaletteIndex("#f97316")
        assertTrue(orangeIndex in 2..3, "web orange → local orange pair, got $orangeIndex")
        // Garbage hex falls back to index 0 (white) — never throws.
        assertEquals(0, MemeWireConvert.nearestPaletteIndex("javascript"))
    }

    @Test
    fun contractsFixtureDecodesAndConverts() {
        // Verbatim from contracts/meme/wire-document-v1.json (repo rule:
        // protocol changes ship fixtures; the fixture pins the interop wire).
        val fixture = """{"schema":"com.bitos.bitz.meme","version":1,"overlays":[
            {"id":"t1","text":"WHEN THE FEE MARKET OPENS","x":0.5,"y":0.18,
             "size":0.09,"color":"#ffffff","font":"impact","caps":true,
             "stroke":true,"bar":false,"fx":"pop"},
            {"id":"t2","text":"wen laser eyes","x":0.31,"y":0.77,"size":0.11,
             "color":"#22d3ee","font":"mono","caps":false,"stroke":true,
             "bar":true,"startMs":250,"endMs":9000,"fx":"spin"},
            {"id":"s1","text":"🚀","x":0.85,"y":0.15,"size":0.18,"color":"#ffffff",
             "font":"sans","caps":false,"stroke":false,"bar":false,"fx":"spin"}],
            "caption":"First cross-client meme — renders identically from the wire on web and native",
            "mediaKind":"image","createdAt":1700000000000,"updatedAt":1700000000000}"""
        val doc = decode(fixture)
        assertEquals(3, doc!!.overlays.size)
        assertEquals("First cross-client meme — renders identically from the wire on web and native", doc.caption)

        val local = MemeWireConvert.wireToLocal(doc)
        assertEquals(3, local.overlays.size)
        // The rocket rides as a sticker (emoji-only, stroke-free, no caps).
        assertEquals(MemeOverlayKind.STICKER, local.overlays[2].kind)
        // The classic caption keeps its cyan via nearest-match.
        assertTrue(local.overlays[1].colorIndex in 8..9, "web cyan → local cyan pair")

        // And the local round-trip stays sticker-faithful.
        val back = MemeWireConvert.localToWire(local, nowMs)
        assertEquals(3, back.overlays.size)
        assertTrue(back.overlays[2].passthrough.containsKey("sticker"))
    }

    private fun decode(json: String): MemeWireDocument? = MemeWireCodec.decode(json, nowMs)
}
