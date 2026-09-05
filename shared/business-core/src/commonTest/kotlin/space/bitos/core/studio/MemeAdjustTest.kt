package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Manual adjust (prototype `create-edit` FX panel sliders): the triple
 * composes ON TOP of a look preset into the one 4×5 matrix both
 * rasterizers burn in, rides the project wire as an additive key, and
 * lands as an undoable `SetAdjust` command with slider-burst coalescing.
 */
class MemeAdjustTest {

    @Test
    fun defaultAdjustReducesToThePlainLookMatrix() {
        val lookOnly = MemeLooks.matrixFor("vhs")
        assertTrue(
            MemeLooks.adjustedMatrixFor("vhs", MemeAdjust()).contentEquals(lookOnly),
        )
        assertTrue(
            MemeLooks.adjustedMatrixFor("vhs", null).contentEquals(lookOnly),
        )
    }

    @Test
    fun adjustOnlyMatchesCssSpecReferencePixels() {
        // brightness(1.5): every channel × 1.5 — 100 → 150.
        val bright = MemeLooks.adjustedMatrixFor(null, MemeAdjust(brightness = 1.5f))
        val px = MemeLooks.applyToPixel(bright, 100, 100, 100)
        assertEquals(150, px[0])

        // contrast(1.5): v' = (v − 0.5)·1.5 + 0.5 in 0..1 — 255 → 255,
        // 128 → (128−127.5)·1.5 + 127.5 = 128.25 → 128, 0 → −63.75 → 0.
        val contrast = MemeLooks.adjustedMatrixFor(null, MemeAdjust(contrast = 1.5f))
        assertEquals(255, MemeLooks.applyToPixel(contrast, 255, 255, 255)[0])
        assertEquals(128, MemeLooks.applyToPixel(contrast, 128, 128, 128)[0])
        assertEquals(0, MemeLooks.applyToPixel(contrast, 0, 0, 0)[0])

        // saturate(0): chroma to zero — pure red → luminance 54.
        val sat0 = MemeLooks.adjustedMatrixFor(null, MemeAdjust(saturation = 0f))
        assertEquals(54, MemeLooks.applyToPixel(sat0, 255, 0, 0)[0])
    }

    @Test
    fun lookFirstThenAdjustOrderIsObservable() {
        // brightness(0.5) AFTER invert(1): white → invert black → ×0.5 = 0.
        val adjustAfter = MemeLooks.applyToPixel(
            MemeLooks.adjustedMatrixFor("invert", MemeAdjust(brightness = 0.5f)),
            255, 255, 255,
        )
        assertEquals(0, adjustAfter[0])
        // …while gray 100 → invert 155 → 77 proves the chain ran look→adjust.
        val gray = MemeLooks.applyToPixel(
            MemeLooks.adjustedMatrixFor("invert", MemeAdjust(brightness = 0.5f)),
            100, 100, 100,
        )
        assertEquals(((255 - 100) * 0.5f).toInt(), gray[0])

        // Hand-built equality: look chain ++ [bri, con, sat] in that order.
        val handBuilt = MemeLooks.matrix(
            MemeLook(
                "x", "x", "x",
                MemeLooks.lookOf("noir").filters +
                    listOf(
                        MemeLook.Filter(MemeLook.Op.BRIGHTNESS, 1.2f),
                        MemeLook.Filter(MemeLook.Op.CONTRAST, 0.9f),
                        MemeLook.Filter(MemeLook.Op.SATURATE, 1.4f),
                    ),
            ),
        )
        assertTrue(
            handBuilt.contentEquals(
                MemeLooks.adjustedMatrixFor(
                    "noir",
                    MemeAdjust(brightness = 1.2f, contrast = 0.9f, saturation = 1.4f),
                ),
            ),
        )
    }

    @Test
    fun clampBoundsMatchThePrototypeSliders() {
        // bri/con 0.4–1.6, sat 0–2; NaN degrades to 1.
        val clamped = MemeAdjust.clamp(brightness = 9f, contrast = -3f, saturation = 5f)
        assertEquals(MemeAdjust(1.6f, 0.4f, 2f), clamped)
        assertEquals(
            MemeAdjust(1f, 1f, 1f),
            MemeAdjust.clamp(brightness = Float.NaN, contrast = Float.NaN, saturation = Float.NaN),
        )
        assertTrue(MemeAdjust().isDefault)
        assertTrue(!MemeAdjust(brightness = 1.01f).isDefault)
    }

    @Test
    fun adjustRidesTheWireAsAnAdditiveKey() {
        val project = MemeProject(
            mode = MemeMode.IMAGE,
            lookId = "vhs",
            adjust = MemeAdjust(brightness = 1.2f, contrast = 0.8f, saturation = 1.5f),
        )
        val wire = MemeProjectContract.encode(project)
        assertTrue("\"adjust\"" in wire, wire)
        val decoded = MemeProjectContract.decode(wire)!!
        assertEquals(MemeAdjust(1.2f, 0.8f, 1.5f), decoded.adjust)

        // Default/absent never serializes — old wires stay byte-identical.
        assertTrue("adjust" !in MemeProjectContract.encode(project.copy(adjust = null)))
        assertTrue("adjust" !in MemeProjectContract.encode(project.copy(adjust = MemeAdjust())))
        assertNull(
            MemeProjectContract.decode(MemeProjectContract.encode(project.copy(adjust = null)))!!
                .adjust,
        )

        // Hostile rows clamp; junk decodes to default (null).
        val hostile = MemeProjectContract.decode(
            """{"v":1,"mode":"image","adjust":{"bri":42,"con":"x","sat":-9}}""",
        )!!
        assertEquals(MemeAdjust(1.6f, 1f, 0f), hostile.adjust)
        assertNull(
            MemeProjectContract.decode("""{"v":1,"mode":"image","adjust":"junk"}""")!!.adjust,
        )
        assertNull(
            MemeProjectContract.decode("""{"v":1,"mode":"image","adjust":{"bri":1,"con":1,"sat":1}}""")!!
                .adjust,
        )
    }

    @Test
    fun setAdjustIsAnUndoableCommandThroughTheCodec() {
        val project = MemeProject(mode = MemeMode.IMAGE)
        val applied = MemeRules.apply(
            project,
            MemeCommand.SetAdjust(MemeAdjust(brightness = 1.4f)),
        )
        assertEquals(MemeAdjust(brightness = 1.4f), applied.adjust)

        val encoded = MemeCommandCodec.encode(MemeCommand.SetAdjust(MemeAdjust(1.4f, 0.7f, 1.1f)))
        assertEquals("""{"op":"adjust","bri":1.4,"con":0.7,"sat":1.1}""", encoded)
        val decoded = MemeCommandCodec.decode(encoded) as MemeCommand.SetAdjust
        assertEquals(MemeAdjust(1.4f, 0.7f, 1.1f), decoded.adjust)

        // Hostile values clamp through apply; a default triple CLEARS.
        val hostile = MemeRules.apply(project, MemeCommand.SetAdjust(MemeAdjust(brightness = 99f)))
        assertEquals(MemeAdjust(brightness = 1.6f), hostile.adjust)
        assertNull(
            MemeRules.apply(hostile, MemeCommand.SetAdjust(MemeAdjust())).adjust,
        )
        assertNull(
            MemeRules.apply(hostile, MemeCommand.SetAdjust(MemeAdjust(brightness = Float.NaN, contrast = Float.NaN, saturation = Float.NaN)))
                .adjust,
            "junk degrades to default and clears",
        )
    }

    @Test
    fun adjustSliderBurstsCoalesceIntoOneUndoStep() {
        val first = MemeCommand.SetAdjust(MemeAdjust(brightness = 1.1f))
        val second = MemeCommand.SetAdjust(MemeAdjust(brightness = 1.4f, contrast = 1.2f))
        val merged = MemeRules.coalesce(first, second)
        assertEquals(second, merged, "the later full triple wins as-is")
        // Different command kinds never merge.
        assertNull(MemeRules.coalesce(first, MemeCommand.SetLook("vhs")))
    }

    @Test
    fun bridgeSeamComposesTheSameMatrix() {
        val viaSeam = space.bitos.core.bridge.BusinessCoreBridge().memeAdjustMatrix(
            lookId = "vhs",
            brightness = 1.2f,
            contrast = 1f,
            saturation = 1f,
        )
        assertTrue(viaSeam.contains("\"matrix\":["))
        assertTrue(viaSeam.contains("\"blur\":0"))
        // The matrix body equals the shared composition (parse 20 floats).
        val matrixPart = viaSeam.substringAfter("\"matrix\":[").substringBefore("]")
        val floats = matrixPart.split(",").map { it.toFloat() }
        assertEquals(20, floats.size)
        assertTrue(
            floats.toFloatArray().contentEquals(
                MemeLooks.adjustedMatrixFor("vhs", MemeAdjust(brightness = 1.2f)),
            ),
        )
        // Defaults reduce to the plain look envelope.
        assertEquals(
            space.bitos.core.bridge.BusinessCoreBridge().memeLookMatrix("vhs"),
            space.bitos.core.bridge.BusinessCoreBridge().memeAdjustMatrix("vhs", 1f, 1f, 1f),
        )
    }
}
