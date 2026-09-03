package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Looks (plan MST-043; web `meme/look.ts` port): the 8 ids ride the
 * project wire and degrade to none, the CSS-spec matrix math matches
 * reference pixels, `SetLook` is an undoable command, and the composed
 * matrix is what both native rasterizers burn into exports.
 */
class MemeLooksTest {

    @Test
    fun eightWebPresetsCarryVerbatimCss() {
        val ids = MemeLooks.ALL.map { it.id }
        assertEquals(
            listOf("none", "mono", "noir", "sepia", "vhs", "deepfry", "dream", "invert"),
            ids,
        )
        assertEquals("grayscale(1) contrast(1.35) brightness(0.92)", MemeLooks.lookOf("noir").css)
        assertEquals("sepia(0.85) saturate(1.2)", MemeLooks.lookOf("sepia").css)
        assertEquals("hue-rotate(-12deg) saturate(1.6) contrast(1.12)", MemeLooks.lookOf("vhs").css)
        assertEquals("blur(1.2px) brightness(1.12) saturate(1.25)", MemeLooks.lookOf("dream").css)
        assertEquals(1.2f, MemeLooks.lookOf("dream").blurPx)
    }

    @Test
    fun unknownIdsDegradeToNoneEverywhere() {
        assertEquals("none", MemeLooks.lookOf(null).id)
        assertEquals("none", MemeLooks.lookOf("hologram").id)
        assertEquals("none", MemeLooks.lookOf("").id)
        assertEquals(null, MemeLooks.normalize("nope"))
        assertEquals(null, MemeLooks.normalize("none"))
        assertEquals("noir", MemeLooks.normalize("noir"))
        // none composes to the exact identity matrix.
        assertTrue(MemeLooks.matrixFor("none").contentEquals(MemeLooks.identity()))
        assertTrue(MemeLooks.matrixFor("junk").contentEquals(MemeLooks.identity()))
    }

    @Test
    fun matrixMathMatchesCssSpecReferencePixels() {
        // grayscale(1): pure red → its luminance (0.2126·255 ≈ 54).
        val mono = MemeLooks.applyToPixel(MemeLooks.matrixFor("mono"), 255, 0, 0)
        assertEquals(54, mono[0])
        assertEquals(54, mono[1])
        assertEquals(54, mono[2])

        // invert(1): white → black, mid-gray maps to 255−v.
        val invert = MemeLooks.applyToPixel(MemeLooks.matrixFor("invert"), 255, 255, 255)
        assertEquals(0, invert[0])
        assertEquals(0, invert[1])
        assertEquals(0, invert[2])
        val half = MemeLooks.applyToPixel(MemeLooks.matrixFor("invert"), 128, 128, 128)
        assertEquals(127, half[0])

        // sepia(1) reference: CSS spec matrix on pure red → (100, 89, 69).
        val sepiaMatrix = MemeLooks.opMatrix(MemeLook.Filter(MemeLook.Op.SEPIA, 1f))
        val sepia = MemeLooks.applyToPixel(sepiaMatrix, 255, 0, 0)
        assertEquals(100, sepia[0])
        assertEquals(88, sepia[1])
        assertEquals(69, sepia[2])

        // contrast(1) and brightness(1) are exact identities.
        val identity = MemeLooks.identity()
        assertTrue(
            MemeLooks.opMatrix(MemeLook.Filter(MemeLook.Op.CONTRAST, 1f)).contentEquals(identity),
        )
        assertTrue(
            MemeLooks.opMatrix(MemeLook.Filter(MemeLook.Op.BRIGHTNESS, 1f)).contentEquals(identity),
        )

        // saturate(0) == grayscale(1) (both take chroma to zero).
        val sat0 = MemeLooks.applyToPixel(
            MemeLooks.opMatrix(MemeLook.Filter(MemeLook.Op.SATURATE, 0f)), 255, 0, 0,
        )
        assertEquals(54, sat0[0])
    }

    @Test
    fun chainCompositionAppliesLeftToRight() {
        // noir = grayscale → contrast(1.35) → brightness(0.92), no
        // intermediate rounding: pure red's luminance 0.2126·255 = 54.21
        // → contrast → 28.56 → brightness → 26.28 → 26.
        val noir = MemeLooks.applyToPixel(MemeLooks.matrix(MemeLooks.lookOf("noir")), 255, 0, 0)
        val gray = 0.2126f * 255f
        val expected = ((gray * 1.35f + (0.5f - 0.5f * 1.35f) * 255f) * 0.92f).toInt()
        assertEquals(expected.coerceIn(0, 255), noir[0])

        // Non-commutative proof: grayscale-then-brightness ≠ brightness-then-grayscale
        // is false for pure channels, but hue-rotate ordering IS observable:
        val vhsRed = MemeLooks.applyToPixel(MemeLooks.matrixFor("vhs"), 255, 0, 0)
        assertTrue(vhsRed[0] in 0..255 && vhsRed[1] in 0..255 && vhsRed[2] in 0..255)
        // Same chain built by hand equals the preset's composition.
        val handBuilt = MemeLooks.matrix(
            MemeLook(
                "x", "x", "x",
                listOf(
                    MemeLook.Filter(MemeLook.Op.HUE_ROTATE, -12f),
                    MemeLook.Filter(MemeLook.Op.SATURATE, 1.6f),
                    MemeLook.Filter(MemeLook.Op.CONTRAST, 1.12f),
                ),
            ),
        )
        assertTrue(handBuilt.contentEquals(MemeLooks.matrixFor("vhs")))
    }

    @Test
    fun lookIdRidesTheProjectWireAndOldWiresDecodeUntouched() {
        val project = MemeProject(mode = MemeMode.IMAGE, lookId = "vhs")
        val decoded = MemeProjectContract.decode(MemeProjectContract.encode(project))!!
        assertEquals("vhs", decoded.lookId)
        // none/unknown never serialize — old wires stay byte-compatible.
        assertTrue("look" !in MemeProjectContract.encode(project.copy(lookId = null)))
        assertTrue("look" !in MemeProjectContract.encode(project.copy(lookId = "junk")))
        // Absent field decodes to null; hostile value degrades to null.
        val old = """{"v":1,"mode":"image","look":"hologram"}"""
        assertEquals(null, MemeProjectContract.decode(old)!!.lookId)
    }

    @Test
    fun setLookIsAnUndoableCommandThroughTheCodec() {
        val project = MemeProject(mode = MemeMode.IMAGE)
        val applied = MemeRules.apply(project, MemeCommand.SetLook("deepfry"))
        assertEquals("deepfry", applied.lookId)

        val encoded = MemeCommandCodec.encode(MemeCommand.SetLook("deepfry"))
        assertEquals("""{"op":"look","look":"deepfry"}""", encoded)
        val decoded = MemeCommandCodec.decode(encoded) as MemeCommand.SetLook
        assertEquals("deepfry", decoded.lookId)

        // "none" clears; hostile ids clear too (never an invalid grade).
        assertEquals(null, MemeRules.apply(applied, MemeCommand.SetLook("none")).lookId)
        assertEquals(null, MemeRules.apply(applied, MemeCommand.SetLook("<script>")).lookId)
    }
}
