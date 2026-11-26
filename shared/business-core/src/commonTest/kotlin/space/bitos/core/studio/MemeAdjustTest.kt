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

    // ── EUI-02: the reference-editor Tone/Colour taxonomy ───────────────

    @Test
    fun extendedControlsComposeReferencePixels() {
        // Exposure IS the v1 brightness field: a pure scale (black stays
        // black) — 100 → 150.
        val exposure = MemeLooks.adjustedMatrixFor(null, MemeAdjust(brightness = 1.5f))
        assertEquals(150, MemeLooks.applyToPixel(exposure, 100, 100, 100)[0])

        // Lightness lerps toward white for + and black for −: a distinct
        // axis from exposure (which preserves both endpoints).
        val lighter = MemeLooks.adjustedMatrixFor(null, MemeAdjust(lightness = 1f))
        assertEquals(255, MemeLooks.applyToPixel(lighter, 100, 100, 100)[0])
        val darker = MemeLooks.adjustedMatrixFor(null, MemeAdjust(lightness = -1f))
        assertEquals(0, MemeLooks.applyToPixel(darker, 100, 100, 100)[0])
        val halfLight = MemeLooks.adjustedMatrixFor(null, MemeAdjust(lightness = 0.5f))
        assertEquals(177, MemeLooks.applyToPixel(halfLight, 100, 100, 100)[0]) // 100·0.5 + 127.5

        // Warmth shifts R/B apart around the pixel value (64-step at full).
        val warm = MemeLooks.adjustedMatrixFor(null, MemeAdjust(warmth = 1f))
        assertEquals(160, MemeLooks.applyToPixel(warm, 128, 128, 128)[0])
        assertEquals(128, MemeLooks.applyToPixel(warm, 128, 128, 128)[1])
        assertEquals(96, MemeLooks.applyToPixel(warm, 128, 128, 128)[2])
        val cool = MemeLooks.adjustedMatrixFor(null, MemeAdjust(warmth = -1f))
        assertEquals(96, MemeLooks.applyToPixel(cool, 128, 128, 128)[0])
        assertEquals(160, MemeLooks.applyToPixel(cool, 128, 128, 128)[2])

        // Tint is the green↔magenta axis: + removes green.
        val magenta = MemeLooks.adjustedMatrixFor(null, MemeAdjust(tint = 1f))
        assertEquals(96, MemeLooks.applyToPixel(magenta, 128, 128, 128)[1])
        val green = MemeLooks.adjustedMatrixFor(null, MemeAdjust(tint = -1f))
        assertEquals(160, MemeLooks.applyToPixel(green, 128, 128, 128)[1])

        // Fade lifts the black point and softens white (0.75 scale + 45).
        val faded = MemeLooks.adjustedMatrixFor(null, MemeAdjust(fade = 1f))
        assertEquals(45, MemeLooks.applyToPixel(faded, 0, 0, 0)[0])
        assertEquals(236, MemeLooks.applyToPixel(faded, 255, 255, 255)[0])
    }

    @Test
    fun extendedClampBoundsAndDefaults() {
        val clamped = MemeAdjust.clamp(
            brightness = 9f, contrast = -3f, saturation = 5f,
            lightness = 7f, warmth = -9f, tint = Float.NaN, fade = 4f,
        )
        assertEquals(
            MemeAdjust(
                brightness = 1.6f, contrast = 0.4f, saturation = 2f,
                lightness = 1f, warmth = -1f, tint = 0f, fade = 1f,
            ),
            clamped,
            "multipliers cap at the prototype bounds, signed axes at ±1, NaN degrades to neutral",
        )

        assertTrue(!MemeAdjust(lightness = 0.01f).isDefault)
        assertTrue(!MemeAdjust(warmth = -0.01f).isDefault)
        assertTrue(!MemeAdjust(tint = 0.01f).isDefault)
        assertTrue(!MemeAdjust(fade = 0.01f).isDefault)
        assertTrue(MemeAdjust(lightness = 0f, warmth = 0f, tint = 0f, fade = 0f).isDefault)
    }

    @Test
    fun extendedFieldsRideTheWireAdditively() {
        val full = MemeAdjust(
            brightness = 1.2f, contrast = 0.9f, saturation = 1.4f,
            lightness = 0.2f, warmth = -0.3f, tint = 0.4f, fade = 0.5f,
        )
        val decoded = MemeProjectContract.decode(
            MemeProjectContract.encode(MemeProject(mode = MemeMode.IMAGE, adjust = full)),
        )!!
        assertEquals(full, decoded.adjust)

        // A v1 wire written before the extension decodes with neutral new
        // fields — drafts and remixes survive the upgrade.
        val legacy = MemeProjectContract.decode(
            """{"v":1,"mode":"image","adjust":{"bri":1.2,"con":0.9,"sat":1.4}}""",
        )!!
        assertEquals(MemeAdjust(1.2f, 0.9f, 1.4f), legacy.adjust)

        // All-neutral never serializes, exactly like the v1 triple.
        assertTrue(
            "adjust" !in MemeProjectContract.encode(
                MemeProject(mode = MemeMode.IMAGE, adjust = MemeAdjust(fade = 0f)),
            ),
        )
    }

    @Test
    fun extendedCommandRoundTripsAndOldCommandsStillDecode() {
        val full = MemeCommand.SetAdjust(
            MemeAdjust(brightness = 1.2f, lightness = 0.2f, warmth = -0.3f, tint = 0.4f, fade = 0.5f),
        )
        val decoded = MemeCommandCodec.decode(MemeCommandCodec.encode(full)) as MemeCommand.SetAdjust
        assertEquals(full.adjust, decoded.adjust)

        // A v1 command (triple only) still decodes; new fields stay neutral.
        val legacy = MemeCommandCodec.decode("""{"op":"adjust","bri":1.4,"con":0.8}""") as MemeCommand.SetAdjust
        assertEquals(MemeAdjust(1.4f, 0.8f), legacy.adjust)

        // An extended-only value is visible on its own (the row is kept)…
        val warmthOnly = MemeRules.apply(
            MemeProject(mode = MemeMode.IMAGE),
            MemeCommand.SetAdjust(MemeAdjust(warmth = 0.5f)),
        )
        assertEquals(MemeAdjust(warmth = 0.5f), warmthOnly.adjust)
        // …and returning a field to neutral clears the row.
        val cleared = MemeRules.apply(
            MemeProject(mode = MemeMode.IMAGE, adjust = MemeAdjust(fade = 0.5f)),
            MemeCommand.SetAdjust(MemeAdjust(fade = 0f)),
        )
        assertNull(cleared.adjust)
    }

    @Test
    fun projectSeamsExposeCanonicalAdjustAndComposedMatrix() {
        val bridge = space.bitos.core.bridge.BusinessCoreBridge()
        val project = MemeProjectContract.encode(
            MemeProject(
                mode = MemeMode.IMAGE,
                lookId = "vhs",
                adjust = MemeAdjust(brightness = 1.2f, warmth = 0.5f),
            ),
        )
        // Canonical adjust JSON: every key present, defaults filled, so the
        // native side never parses wire fields or clamps values itself.
        val canonical = bridge.memeAdjustOf(project)
        assertTrue(canonical.contains("\"bri\":1.2"), canonical)
        assertTrue(canonical.contains("\"warm\":0.5"), canonical)
        assertTrue(canonical.contains("\"sat\":1.0"), canonical)
        assertTrue(canonical.contains("\"fade\":0.0"), canonical)
        // The matrix seam equals the shared composition for those values.
        val envelope = bridge.memeAdjustMatrix("vhs", bridge.memeAdjustOf(project))
        val floats = envelope.substringAfter("\"matrix\":[").substringBefore("]")
            .split(",").map { it.toFloat() }
        assertTrue(
            floats.toFloatArray().contentEquals(
                MemeLooks.adjustedMatrixFor("vhs", MemeAdjust(brightness = 1.2f, warmth = 0.5f)),
            ),
        )

        // The live-preview seam takes the canonical adjust JSON directly
        // (lenient keys, clamped values) and composes identically.
        val viaCanonical = bridge.memeAdjustMatrix("vhs", """{"bri":1.5,"junk":"x"}""")
        val canonicalFloats = viaCanonical.substringAfter("\"matrix\":[").substringBefore("]")
            .split(",").map { it.toFloat() }
        assertTrue(
            canonicalFloats.toFloatArray().contentEquals(
                MemeLooks.adjustedMatrixFor("vhs", MemeAdjust(brightness = 1.5f)),
            ),
        )
        // Junk JSON degrades to the neutral adjust = plain look matrix.
        assertEquals(
            bridge.memeLookMatrix("vhs"),
            bridge.memeAdjustMatrix("vhs", "not json"),
        )
    }

    @Test
    fun controlCatalogCoversEveryAxisOnceAndMatchesTheClamp() {
        // Each axis maps to exactly one control; with()/value() round-trip.
        MemeAdjustControl.entries.forEach { control ->
            assertEquals(0.5f, control.value(control.with(MemeAdjust(), 0.5f)), control.name)
            assertEquals(control.neutral, control.value(MemeAdjust()), control.name)
        }
        // A probe with one perturbed axis flags exactly its control.
        val probe = MemeAdjust(brightness = 1.3f, lightness = 0.2f)
        assertEquals(
            listOf(MemeAdjustControl.EXPOSURE, MemeAdjustControl.LIGHTNESS),
            MemeAdjustControl.entries.filter { it.isNonDefault(probe) },
        )
        // Slider bounds agree with the clamp for every axis.
        MemeAdjustControl.entries.forEach { control ->
            val clamped = MemeAdjust.fromCanonical(
                """{"${control.wireKey}":1e9}""",
            )
            assertEquals(
                control.text(control.range.endInclusive),
                control.text(control.value(clamped)),
                control.name,
            )
        }
        // Signed axes read "+30"/"−30"-style; multipliers read "120%".
        assertEquals("+30", MemeAdjustControl.WARMTH.text(0.3f))
        assertEquals("-30", MemeAdjustControl.WARMTH.text(-0.3f))
        assertEquals("120%", MemeAdjustControl.EXPOSURE.text(1.2f))
        // The seam JSON carries the same set the enum declares.
        val catalog = space.bitos.core.bridge.BusinessCoreBridge().memeAdjustControls()
        MemeAdjustControl.entries.forEach { control ->
            assertTrue(catalog.contains("\"${control.name.lowercase()}\""), catalog)
        }
        assertTrue(catalog.contains("\"sections\""), catalog)
    }
}
