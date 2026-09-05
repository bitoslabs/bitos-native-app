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
