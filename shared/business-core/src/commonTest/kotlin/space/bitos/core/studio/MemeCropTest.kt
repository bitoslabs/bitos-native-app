package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EUI-02 reference-spec crop treatment (`mobile-video-image-editor-reference
 * -spec.md` §3): ratio + rotation + flip + pan/zoom is one non-destructive
 * `MemeCrop` that composes into a deterministic `Placement` — the frame the
 * media covers — plus its wire/command ride. Cover math must be identical
 * in the stage previews and every raster/GIF burn.
 */
class MemeCropTest {

    @Test
    fun identityCropIsTheUntouchedMediaFrame() {
        val place = MemeCanvas.placement(1080, 1920, null, null)
        assertEquals(1080, place.frameWidth)
        assertEquals(1920, place.frameHeight)
        assertEquals(0f, place.drawX)
        assertEquals(0f, place.drawY)
        assertEquals(1080f, place.drawWidth)
        assertEquals(1920f, place.drawHeight)
        assertFalse(place.cover)

        // An all-default crop with no ratio frames the media identically —
        // but as the COVER treatment (picking the sheet means crop-to-fill).
        val defaulted = MemeCanvas.placement(1080, 1920, null, MemeCrop())
        assertEquals(place.frameWidth, defaulted.frameWidth)
        assertEquals(place.frameHeight, defaulted.frameHeight)
        assertEquals(0f, defaulted.drawX)
        assertTrue(defaulted.cover)
    }

    @Test
    fun coverCenterCropsTheLongerSideAway() {
        // Portrait 1080×1920 into 1:1: the frame is 1080×1080 and the media
        // covers it with the top/bottom cropped away (pan 0 = centered).
        val place = MemeCanvas.placement(1080, 1920, "1:1", MemeCrop())
        assertTrue(place.cover)
        assertEquals(1080, place.frameWidth)
        assertEquals(1080, place.frameHeight)
        assertEquals(1080f, place.drawWidth)
        assertEquals(1920f, place.drawHeight)
        assertEquals(0f, place.drawX)
        assertEquals((1080f - 1920f) / 2f, place.drawY)
    }

    @Test
    fun rotationSwapsTheFrameNotTheQuality() {
        // A landscape source rotated a quarter turn outputs portrait.
        val place = MemeCanvas.placement(1920, 1080, "source", MemeCrop(rotationDeg = 90))
        assertTrue(place.cover)
        assertEquals(1080, place.frameWidth)
        assertEquals(1920, place.frameHeight)
        assertEquals(90, place.rotationDeg)
        // The media draws at its rotated size, exactly filling the frame.
        assertEquals(1080f, place.drawWidth)
        assertEquals(1920f, place.drawHeight)

        // Rotation + a ratio: the ratio applies to the ROTATED media.
        val squared = MemeCanvas.placement(1920, 1080, "9:16", MemeCrop(rotationDeg = 90))
        assertEquals(1080, squared.frameWidth)
        assertEquals(1920, squared.frameHeight)
    }

    @Test
    fun zoomWidensThePanRangeAndPanClampsInsideIt() {
        // zoom 1 has no slack: pan cannot move the media at all.
        val tight = MemeCanvas.placement(1080, 1920, "1:1", MemeCrop())
        assertEquals(0f, tight.drawX)

        // zoom 2 doubles the media; pan ±1 reaches the edges, 0 stays centered.
        val zoomed = MemeCanvas.placement(
            1080, 1920, "1:1",
            MemeCrop(zoom = 2f, panX = 1f, panY = -1f),
        )
        assertEquals(2160f, zoomed.drawWidth)
        // baseX = (1080 − 2160)/2 = −540; half-overflow = 540; pan +1 → 0.
        assertEquals(0f, zoomed.drawX)
        // panY −1 → drawY = (1080 − 3840)/2 + (−1380) = −1380 − 1380… clamp:
        // half-overflowY = (3840 − 1080)/2 = 1380; baseY = −1380; −1 → −2760… the
        // edge alignment means drawY + drawH = frameH → drawY = 1080 − 3840 = −2760.
        assertEquals(-2760f, zoomed.drawY)
    }

    @Test
    fun clampBoundsMatchTheSheet() {
        val clamped = MemeCrop.clamp(
            rotationDeg = 63, flipH = false,
            zoom = 9f, panX = -9f, panY = Float.NaN,
        )
        assertEquals(MemeCrop(rotationDeg = 90, zoom = 3f, panX = -1f), clamped)
        assertTrue(MemeCrop().isDefault)
        assertFalse(MemeCrop(flipH = true).isDefault)
        assertFalse(MemeCrop(panY = 0.01f).isDefault)
        // Junk rotations degrade to the nearest quarter turn (0 here).
        assertEquals(MemeCrop(), MemeCrop.clamp(rotationDeg = 7, flipH = false, zoom = 1f, panX = 0f, panY = 0f))
    }

    @Test
    fun cropRidesTheWireAdditively() {
        val full = MemeCrop(rotationDeg = 90, flipH = true, zoom = 1.5f, panX = -0.25f, panY = 0.5f)
        val decoded = MemeProjectContract.decode(
            MemeProjectContract.encode(
                MemeProject(mode = MemeMode.IMAGE, crop = full),
            ),
        )!!
        assertEquals(full, decoded.crop)

        // Absent = the v1 letterbox (v1 wires stay byte-identical); an
        // untouched cover rides as an empty object.
        assertTrue("crop" !in MemeProjectContract.encode(MemeProject(mode = MemeMode.IMAGE)))
        assertTrue(
            "\"crop\":{}" in MemeProjectContract.encode(MemeProject(mode = MemeMode.IMAGE, crop = MemeCrop())),
        )

        // A v1 wire decodes with no crop; hostile rows clamp.
        assertNull(MemeProjectContract.decode("""{"v":1,"mode":"image"}""")!!.crop)
        val hostile = MemeProjectContract.decode(
            """{"v":1,"mode":"image","crop":{"rot":63,"zoom":9,"panx":"x"}}""",
        )!!
        assertEquals(MemeCrop(rotationDeg = 90, zoom = 3f), hostile.crop)
    }

    @Test
    fun setCropIsAnUndoableCoalescedCommand() {
        val project = MemeProject(mode = MemeMode.IMAGE)
        val applied = MemeRules.apply(project, MemeCommand.SetCrop(MemeCrop(rotationDeg = 180)))
        assertEquals(MemeCrop(rotationDeg = 180), applied.crop)

        // An untouched value keeps the cover treatment; only null clears.
        assertEquals(MemeCrop(), MemeRules.apply(applied, MemeCommand.SetCrop(MemeCrop())).crop)
        assertNull(MemeRules.apply(applied, MemeCommand.SetCrop(null)).crop)

        val encoded = MemeCommandCodec.encode(MemeCommand.SetCrop(MemeCrop(zoom = 1.5f, panY = 0.25f)))
        val decoded = MemeCommandCodec.decode(encoded) as MemeCommand.SetCrop
        assertEquals(MemeCrop(zoom = 1.5f, panY = 0.25f), decoded.crop)

        // Slider/gesture bursts coalesce to one undo step.
        val first = MemeCommand.SetCrop(MemeCrop(zoom = 1.2f))
        val second = MemeCommand.SetCrop(MemeCrop(zoom = 1.6f, panX = 0.1f))
        assertEquals(second, MemeRules.coalesce(first, second))
        assertNull(MemeRules.coalesce(first, MemeCommand.SetLook("vhs")))
    }

    @Test
    fun ratioSetGainsPortraitThreeFour() {
        assertTrue(MemeCanvas.isValidRatio("3:4"))
        assertEquals(3 to 4, MemeCanvas.ratioTerms("3:4"))
        assertTrue("3:4" in MemeCanvas.RATIOS.map { it.first })
    }

    @Test
    fun legacyLetterboxProjectsKeepTheirTreatment() {
        // A pinned ratio with a NULL crop stays the v1 contain (letterbox)
        // treatment — old projects must not silently re-frame.
        val legacy = MemeCanvas.placement(1080, 1920, "1:1", null)
        assertFalse(legacy.cover)
        val (w, h) = MemeCanvas.fitInRatio(1080, 1920, "1:1")
        assertEquals(w, legacy.frameWidth)
        assertEquals(h, legacy.frameHeight)
        // The media scales to FIT the square canvas, centered.
        assertEquals(607.5f, legacy.drawWidth)
        assertEquals(1080f, legacy.drawHeight)
        assertEquals((1080f - 607.5f) / 2f, legacy.drawX)
    }

    @Test
    fun identityCropSerializesAsPresentCover() {
        // The tri-state rides the wire: `{}` = cover with defaults, absent =
        // legacy letterbox. An identity-cover project survives a round trip.
        val round = MemeProjectContract.decode(
            MemeProjectContract.encode(MemeProject(mode = MemeMode.IMAGE, crop = MemeCrop())),
        )!!
        assertEquals(MemeCrop(), round.crop)
        assertTrue(MemeCanvas.placement(1080, 1920, "1:1", round.crop).cover)
        assertTrue("\"crop\"" in MemeProjectContract.encode(MemeProject(mode = MemeMode.IMAGE, crop = MemeCrop())))
        // Clearing explicitly returns to the legacy treatment.
        assertTrue("crop" !in MemeProjectContract.encode(MemeProject(mode = MemeMode.IMAGE, crop = null)))
    }

    @Test
    fun cropSeamsExposeCanonicalValuesAndPlacement() {
        val bridge = space.bitos.core.bridge.BusinessCoreBridge()
        val project = MemeProjectContract.encode(
            MemeProject(mode = MemeMode.IMAGE, crop = MemeCrop(zoom = 1.5f, panX = 0.25f)),
        )
        assertTrue(bridge.memeCropOf(project).contains("\"zoom\":1.5"))
        assertEquals("", bridge.memeCropOf(MemeProjectContract.encode(MemeProject(mode = MemeMode.IMAGE))))
        val place = bridge.memeCropPlacement(project, 1080, 1920)
        assertTrue(place.contains("\"cover\":true"), place)
        assertTrue(place.contains("\"frameW\":1080"), place)
        // Identity placement for a cropless project.
        val plain = bridge.memeCropPlacement(
            MemeProjectContract.encode(MemeProject(mode = MemeMode.IMAGE)), 1080, 1920,
        )
        assertTrue(plain.contains("\"cover\":false"), plain)
        assertTrue(plain.contains("\"frameW\":1080"), plain)
    }
}
