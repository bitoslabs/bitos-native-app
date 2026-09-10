package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Export geometry goldens (plan MST-016; web `render.ts` parity). The plan
 * is pure data — exact pixel values are pinned here and both platform
 * rasterizers consume it, so the geometry half of the per-platform golden
 * contract runs on every push; the pixel hashing itself needs a device
 * rasterizer and is exercised by the manual QA matrix.
 */
class MemeExportRulesTest {

    private fun overlay(
        text: String = "gm",
        size: Int = 97,
        outline: Int = 2,
        caps: Boolean? = null,
        shadow: Boolean = false,
        bar: Boolean? = null,
        rot: Float = 0f,
        kind: MemeOverlayKind = MemeOverlayKind.TEXT,
    ) = MemeOverlay(
        id = "o1", kind = kind, text = text, font = MemeFontSlot.IMPACT,
        size = size, colorIndex = 0, outline = outline, shadow = shadow,
        x = 0.5f, y = 0.5f, scale = 1f, rotationDeg = rot, caps = caps,
        bar = bar,
    )

    // ── targetSize parity (web render.ts) ────────────────────────────────

    @Test
    fun outputSizeCapsTheLongEdgeAndNeverUpscales() {
        // Portrait 9:16 source → 1080 long edge.
        assertEquals(608 to 1080, MemeExportRules.outputSize(1080, 1920))
        // Landscape 16:9.
        assertEquals(1080 to 608, MemeExportRules.outputSize(1920, 1080))
        // Smaller sources stay as-is (no upscale), evened.
        assertEquals(800 to 600, MemeExportRules.outputSize(800, 600))
        assertEquals(800 to 600, MemeExportRules.outputSize(801, 601), "odd dims evened")
        // Degenerate → portrait default.
        assertEquals(1080 to 1920, MemeExportRules.outputSize(0, 0))
    }

    // ── exportPlan goldens (exact px) ───────────────────────────────────

    @Test
    fun planPositionsAndSizesScaleByTheHeightReference() {
        val project = MemeProject(mode = MemeMode.IMAGE, overlays = listOf(overlay()))
        val plan = MemeExportRules.exportPlan(project, canvasWidth = 608, canvasHeight = 1080)
        val item = plan.single()
        // 608-wide portrait: height reference = 1080 → 1:1 px.
        assertEquals(304f, item.centerX, 0.001f)
        assertEquals(540f, item.centerY, 0.001f)
        assertEquals(97f, item.fontSizePx, 0.001f)
        assertEquals(4f, item.outlinePx, "outline scales with the same reference (2 px × 2)")
        assertEquals("GM", item.text, "caps default true → display uppercase")
    }

    @Test
    fun planCarriesTheBackgroundBarAndEnvelopeExposesIt() {
        val project = MemeProject(
            mode = MemeMode.IMAGE,
            overlays = listOf(overlay(bar = true), overlay()),
        )
        val plan = MemeExportRules.exportPlan(project, canvasWidth = 608, canvasHeight = 1080)
        assertEquals(true, plan[0].bar)
        assertEquals(false, plan[1].bar, "null stays off")
        val envelope = MemeExportRules.exportEnvelope(project, 608, 1080)
        assertTrue("\"bar\":true" in envelope, "rasterizers read the flag from the envelope JSON")
    }

    @Test
    fun landscapeCanvasScalesFontsByItsShorterHeight() {
        val project = MemeProject(mode = MemeMode.IMAGE, overlays = listOf(overlay(size = 108)))
        val plan = MemeExportRules.exportPlan(project, canvasWidth = 1080, canvasHeight = 608)
        val item = plan.single()
        assertEquals(108f * 608f / 1080f, item.fontSizePx, 0.01f, "web: px = size × canvas.height / 1080")
        assertEquals(540f, item.centerX, 0.001f)
        assertEquals(304f, item.centerY, 0.001f)
    }

    @Test
    fun capsStrokeShadowAndStickerRules() {
        val project = MemeProject(
            mode = MemeMode.IMAGE,
            overlays = listOf(
                overlay(text = "keep case", caps = false),
                overlay(outline = 0),
                overlay(shadow = true, rot = -12f),
                overlay(text = "🚀", kind = MemeOverlayKind.STICKER, size = 216),
            ),
        )
        val plan = MemeExportRules.exportPlan(project, 608, 1080)
        assertEquals(false, plan[0].shadow)
        assertEquals("keep case", plan[0].text, "caps=false keeps source case")
        assertEquals(0f, plan[1].outlinePx)
        assertEquals(4294967295L, plan[1].color, "palette index 0 = white ARGB")
        assertTrue(plan[2].shadow && plan[2].rotationDeg == -12f)
        // Stickers: no outline, emoji survives the caps transform.
        assertTrue(plan[3].sticker)
        assertEquals(0f, plan[3].outlinePx)
        assertEquals("🚀", plan[3].text)
    }

    @Test
    fun multilineSplitsAndTheFontFloorHolds() {
        val project = MemeProject(
            mode = MemeMode.IMAGE,
            overlays = listOf(overlay(text = "line one\nline two", size = MemeRules.MIN_SIZE)),
        )
        val plan = MemeExportRules.exportPlan(project, 608, 1080)
        val item = plan.single()
        assertEquals(listOf("LINE ONE", "LINE TWO"), item.lines)
        // 12 px local would be 12 px at 1080 — above the floor; force it:
        val tiny = MemeExportRules.exportPlan(
            project.copy(overlays = listOf(overlay(size = 5))),
            608, 60,
        )
        assertEquals(10f, tiny.single().fontSizePx, "web floor: px = max(10, size × h/1080)")
    }

    @Test
    fun imageLayersCarryAssetGeometryIntoPlanAndEnvelope() {
        val layer = overlay(kind = MemeOverlayKind.IMAGE, size = 192).copy(
            id = "L1", assetId = "a2", x = 0.25f, y = 0.75f,
            scale = 2f, rotationDeg = 15f, text = "",
        )
        val project = MemeProject(mode = MemeMode.VIDEO, overlays = listOf(layer, overlay()))
        val plan = MemeExportRules.exportPlan(project, 608, 1080)
        assertEquals(2, plan.size, "blank text never drops an image layer")
        val item = plan.first { it.image }
        assertEquals("a2", item.assetId)
        assertEquals(0f, item.outlinePx)
        // 192 px × scale 2 × the 1080-height reference — the layer's target height.
        assertEquals(384f, item.fontSizePx, 0.001f)
        assertEquals(152f, item.centerX, 0.001f)
        assertEquals(810f, item.centerY, 0.001f)
        assertEquals(15f, item.rotationDeg)
        assertTrue(item.lines.isEmpty())
        val envelope = MemeExportRules.exportEnvelope(project, 1080, 1920)
        assertTrue(envelope.contains("\"image\":true"), envelope)
        assertTrue(envelope.contains("\"asset\":\"a2\""), envelope)

        // An image overlay that lost its asset reference is junk and drops.
        val orphan = MemeExportRules.exportPlan(
            project.copy(overlays = listOf(layer.copy(assetId = null), overlay())),
            608, 1080,
        )
        assertEquals(1, orphan.size, "orphan image overlay without an asset drops")
    }

    @Test
    fun timedPlanDropsOutOfWindowOverlaysAndRidesFx() {
        val windowed = overlay(text = "late").copy(id = "w1", startMs = 1000, endMs = 2000)
        val popping = overlay(text = "pop").copy(id = "p1", fx = MemeOverlayFx.POP, startMs = 500)
        val steady = overlay(text = "gm").copy(id = "s1")
        val layer = overlay(kind = MemeOverlayKind.IMAGE, size = 192)
            .copy(id = "L1", assetId = "a1", text = "", startMs = 0, endMs = 3000)
        val project = MemeProject(mode = MemeMode.VIDEO, overlays = listOf(windowed, popping, steady, layer))

        // t=0: neither windowed overlay has started; steady + layer paint.
        val t0 = MemeExportRules.paintPlanAt(project, 608, 1080, 0)
        assertEquals(setOf("s1", "L1"), t0.map { it.item.id }.toSet())
        assertEquals(1f, t0.first { it.item.id == "s1" }.fx.scale, 0f, "no-fx items ride the identity transform")

        // t=600: pop is 100 ms into its entry (below 1); windowed still hidden.
        val t600 = MemeExportRules.paintPlanAt(project, 608, 1080, 600)
        assertEquals(setOf("p1", "s1", "L1"), t600.map { it.item.id }.toSet())
        assertTrue(t600.first { it.item.id == "p1" }.fx.scale < 1f, "early pop entry sits below 1")

        // t=800: pop is 300 ms into its entry (easeOutBack ≠ 1); windowed still hidden.
        val t800 = MemeExportRules.paintPlanAt(project, 608, 1080, 800)
        assertEquals(setOf("p1", "s1", "L1"), t800.map { it.item.id }.toSet())
        assertTrue(kotlin.math.abs(t800.first { it.item.id == "p1" }.fx.scale - 1f) > 0.001f)

        // t=1500: everything visible; the pop entry has settled to identity.
        val t1500 = MemeExportRules.paintPlanAt(project, 608, 1080, 1500)
        assertEquals(setOf("w1", "p1", "s1", "L1"), t1500.map { it.item.id }.toSet())
        assertEquals(1f, t1500.first { it.item.id == "p1" }.fx.scale, 0.001f, "entry resolved after 380 ms")

        // t=2000: half-open end — the windowed overlay drops.
        val t2000 = MemeExportRules.paintPlanAt(project, 608, 1080, 2000)
        assertFalse(t2000.any { it.item.id == "w1" }, "end is exclusive")

        // Poster (null time): everything paints, untransformed.
        val poster = MemeExportRules.paintPlanAt(project, 608, 1080, null)
        assertEquals(setOf("w1", "p1", "s1", "L1"), poster.map { it.item.id }.toSet())
        assertTrue(poster.all { it.fx == space.bitos.core.studio.MemeFxRules.IDENTITY })
    }

    @Test
    fun drawingPlanScalesIntoCanvasPixelsAndRidesTheEnvelope() {
        val project = MemeProject(
            mode = MemeMode.IMAGE,
            drawStrokes = listOf(
                MemeStroke(id = "d1", colorIndex = 0, widthNorm = 0.01f, points = listOf(0f, 0f, 1f, 1f)),
            ),
        )
        val plan = MemeExportRules.drawingPlan(project, 608, 1080)
        val stroke = plan.single()
        assertEquals(0xFFFFFFFF, stroke.color)
        assertEquals(10.8f, stroke.widthPx, 0.001f, "width = norm × canvas height")
        assertEquals(listOf(0f, 0f, 608f, 1080f), stroke.pointsPx)
        val envelope = MemeExportRules.exportEnvelope(project, 1080, 1920)
        assertTrue(envelope.contains("\"strokes\""), envelope)
        // The envelope canvas is the long-edge-capped 608×1080, so the
        // width rides as norm × 1080.
        assertTrue(envelope.contains("\"w\":10.8"), envelope)
    }

    @Test
    fun blankOverlaysDropAndEnvelopeShapeHolds() {
        val project = MemeProject(
            mode = MemeMode.IMAGE,
            overlays = listOf(overlay(text = "   "), overlay(text = "gm")),
        )
        assertEquals(1, MemeExportRules.exportPlan(project, 608, 1080).size)
        val envelope = MemeExportRules.exportEnvelope(project, sourceWidth = 1080, sourceHeight = 1920)
        assertTrue(envelope.contains("\"width\":608"), envelope)
        assertTrue(envelope.contains("\"height\":1080"), envelope)
        assertTrue(envelope.contains("\"text\":\"GM\""), envelope)
        assertTrue(envelope.contains("\"fontSize\":97"), envelope)
        assertTrue(envelope.contains("\"color\":4294967295"), envelope)
        assertEquals(
            emptyList<MemeExportRules.MemeExportItem>(),
            MemeExportRules.exportPlan(project, 0, 0),
        )
    }

    /**
     * MST-077 timed envelope: rows outside their visibility window drop,
     * survivors carry the fx transform, and `atMs = null` keeps the
     * static envelope byte-shape (no fx keys — old consumers unchanged).
     */
    @Test
    fun timedEnvelopeDropsInvisibleRowsAndCarriesFx() {
        val withFx = overlay("gm").copy(
            fx = MemeOverlayFx.POP,
            startMs = 500,
            endMs = 1_500,
        )
        val project = MemeProject(mode = MemeMode.GIF, overlays = listOf(withFx))

        fun rowsAt(atMs: Long?) = MemeExportRules.exportEnvelope(project, 1080, 1080, atMs)
            .let { wire ->
                Json.parseToJsonElement(wire).jsonObject["items"]!!.jsonArray
            }

        // Outside the window: dropped entirely.
        assertEquals(0, rowsAt(0L).size, "before start → invisible")
        assertEquals(0, rowsAt(2_000L).size, "after end → invisible")

        // Inside: the row carries fx (pop scales through the entry —
        // atMs 700 is t=200 into the 380 ms entry, past the easeOutBack
        // midpoint where it overshoots above 1).
        val mid = rowsAt(700L).single().jsonObject
        val scale = (mid["fxScale"] as kotlinx.serialization.json.JsonPrimitive).content.toFloat()
        assertTrue(scale > 1f, "pop entry scales up (got $scale)")
        assertEquals(1.0, (mid["fxAlpha"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble(), 1e-4)

        // Static envelope keeps its byte-shape: no fx keys at all.
        val static = MemeExportRules.exportEnvelope(project, 1080, 1080)
            .let { Json.parseToJsonElement(it).jsonObject["items"]!!.jsonArray.single().jsonObject }
        assertFalse(static.containsKey("fxScale"))
        assertFalse(static.containsKey("fxAlpha"))
    }
}
