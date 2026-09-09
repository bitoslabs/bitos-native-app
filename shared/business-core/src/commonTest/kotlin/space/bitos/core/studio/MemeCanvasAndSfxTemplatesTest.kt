package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Canvas wire rules (additive `canvas` key) + sound-template staging
 * (web sound-catalog/suggestions parity): round-trips preserve the
 * settings, junk drops, old wires stay byte-identical; templates are
 * additive and capped.
 */
class MemeCanvasAndSfxTemplatesTest {

    @Test
    fun canvasRoundTripsRatioAndBackground() {
        val project = MemeProject(
            mode = MemeMode.IMAGE,
            canvasRatio = "1:1",
            canvasBg = "#fde047",
        )
        val decoded = MemeProjectContract.decode(MemeProjectContract.encode(project))!!
        assertEquals("1:1", decoded.canvasRatio)
        assertEquals("#fde047", decoded.canvasBg)
    }

    @Test
    fun canvasJunkDropsAndSourceStaysImplicit() {
        val wire = """
            {"v":1,"mode":"image","assets":[],"overlays":[],
             "canvas":{"ratio":"999:1","bg":"not-a-color"}}
        """.trimIndent()
        val decoded = MemeProjectContract.decode(wire)!!
        assertNull(decoded.canvasRatio, "an out-of-range ratio drops")
        assertNull(decoded.canvasBg, "a non-hex background drops")
        // And an unset canvas writes NO key (old wires byte-identical).
        val plain = MemeProjectContract.encode(MemeProject(mode = MemeMode.IMAGE))
        assertFalse(plain.contains("\"canvas\""))
    }

    /**
     * Blank-GIF timing (plan D3, MST-079): `sec`/`fps` round-trip inside
     * the additive canvas object, hostile values clamp (not drop — the
     * cap owns them), junk drops, and old wires stay byte-identical.
     */
    @Test
    fun blankGifTimingRoundTripsClampsAndStaysAdditive() {
        val project = MemeProject(
            mode = MemeMode.GIF,
            canvasRatio = "1:1",
            canvasBg = "#22d3ee",
            canvasSec = 2_000L,
            canvasFps = 15,
        )
        val decoded = MemeProjectContract.decode(MemeProjectContract.encode(project))!!
        assertEquals(2_000L, decoded.canvasSec)
        assertEquals(15, decoded.canvasFps)

        // Hostile in-bounds clamp; out-of-bounds decode drops to null.
        val clamped = MemeProjectContract.decode(
            MemeProjectContract.encode(MemeProject(mode = MemeMode.GIF, canvasSec = 99_000L, canvasFps = 42)),
        )!!
        assertEquals(10_000L, clamped.canvasSec, "the shared cap owns the loop length")
        assertEquals(10, clamped.canvasFps, "a non-preset fps clamps to the default on write")
        val junk = MemeProjectContract.decode(
            """{"v":1,"mode":"gif","assets":[],"overlays":[],"canvas":{"sec":100,"fps":3}}""",
        )!!
        assertNull(junk.canvasSec)
        assertNull(junk.canvasFps)

        // Old canvas wires (ratio/bg only) decode with null timing.
        val legacy = MemeProjectContract.decode(
            """{"v":1,"mode":"gif","assets":[],"overlays":[],"canvas":{"ratio":"9:16"}}""",
        )!!
        assertNull(legacy.canvasSec)
        assertNull(legacy.canvasFps)
    }

    /**
     * Derived GIF timing (MST-086 single-sourcing): loop frame counts and
     * per-frame delays clamp identically everywhere they're computed, and
     * the video→GIF sampling bounds (fps · span · trimmed flag) are pure.
     */
    @Test
    fun derivedGifTimingClampsIdentically() {
        // Blank loop: 3 s × 15 fps = 45; hostile inputs clamp first.
        assertEquals(45, MemeCanvas.blankGifFrameCount(3_000, 15))
        assertEquals(60, MemeCanvas.blankGifFrameCount(10_000, 15))
        assertEquals(5, MemeCanvas.blankGifFrameCount(500, 10))
        // Delay floor holds; rates are preset-clamped before dividing.
        assertEquals(100, MemeCanvas.loopDelayMs(10))
        assertEquals(66, MemeCanvas.loopDelayMs(15))
        assertEquals(MemeCanvas.loopDelayMs(10), MemeCanvas.loopDelayMs(7))

        // Video→GIF span: capped at 10 s, flagged when trimmed.
        assertEquals(100, MemeCanvas.videoGifFrameCount(10_000))
        assertEquals(100, MemeCanvas.videoGifFrameCount(60_000))
        assertEquals(30, MemeCanvas.videoGifFrameCount(3_000))
        assertEquals(1, MemeCanvas.videoGifFrameCount(1))
        assertFalse(MemeCanvas.videoGifSpanTrimmed(10_000))
        assertTrue(MemeCanvas.videoGifSpanTrimmed(10_001))
        assertEquals(100, MemeCanvas.videoGifDelayMs())
    }

    @Test
    fun ratioFitLetterboxesTheMedia() {
        // 1920×1080 media on a 1:1 canvas → 1080×1080.
        assertEquals(1080 to 1080, MemeCanvas.fitInRatio(1920, 1080, "1:1"))
        // 1080×1920 on 16:9 → 1080×608 (height = 1080·9/16 = 607.5 → 607).
        assertEquals(1080 to 607, MemeCanvas.fitInRatio(1080, 1920, "16:9"))
        // Source passes the media through untouched.
        assertEquals(640 to 480, MemeCanvas.fitInRatio(640, 480, MemeCanvas.RATIO_SOURCE))
        assertTrue(MemeCanvas.isValidRatio("4:5"))
        assertFalse(MemeCanvas.isValidRatio("0:9"))
    }

    @Test
    fun soundTemplatesStageAdditivelyAndCap() {
        val existing = listOf(MemeSfxCue("c0", "ding", 5_000, 1f))
        val next = SfxTemplates.apply(existing, "cash-register", 1_000)
        // Existing cue survives; template cues land at their offsets.
        assertTrue(next.any { it.id == "c0" && it.atMs == 5_000L })
        assertTrue(next.any { it.sfx == "cash" && it.atMs == 1_000L })
        assertTrue(next.any { it.sfx == "jackpot" && it.atMs == 1_900L })
        // Unknown template id is a no-op.
        assertEquals(existing, SfxTemplates.apply(existing, "nope", 0))
        // The 16-cue cap holds: staging into an almost-full list truncates
        // the TAIL (new cues), never the user's existing ones.
        val full = (0 until 15).map { MemeSfxCue("c$it", "ding", it * 100L, 1f) }
        val capped = SfxTemplates.apply(full, "laugh-track", 0)
        assertEquals(SfxSynth.MAX_CUES, capped.size)
        assertTrue(capped.take(15).all { it.id.startsWith("c") })
    }

    @Test
    fun everyTemplateCueResolvesToARecipe() {
        SfxTemplates.ALL.forEach { template ->
            template.cues.forEach { cue ->
                assertTrue(
                    SfxSynth.RECIPES[cue.sfx] != null,
                    "${template.id} references unknown recipe ${cue.sfx}",
                )
            }
            assertTrue(template.cues.isNotEmpty(), "${template.id} stages nothing")
        }
        // Labels cover every recipe id (pickers never show a raw id).
        SfxSynth.RECIPES.keys.forEach { id ->
            assertTrue(SfxSynth.labelOf(id) != id, "missing human label for $id")
        }
    }
}
