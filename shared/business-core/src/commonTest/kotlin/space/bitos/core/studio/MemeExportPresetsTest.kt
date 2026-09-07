package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Export quality/size presets (MST-036): the bitrate table, the MB
 * estimate and the manual publish gate are pinned so Android and iOS
 * consume identical numbers. AUTO must equal the pre-MST-036 export.
 */
class MemeExportPresetsTest {

    @Test
    fun bitrateTableIsPinnedForAllNinePresets() {
        assertEquals(6_000_000, MemeExportPresets.bitrate(MemeExportPresets.Resolution.P1080, MemeExportPresets.Quality.HIGH))
        assertEquals(4_000_000, MemeExportPresets.bitrate(MemeExportPresets.Resolution.P1080, MemeExportPresets.Quality.MEDIUM))
        assertEquals(2_500_000, MemeExportPresets.bitrate(MemeExportPresets.Resolution.P1080, MemeExportPresets.Quality.LOW))
        assertEquals(4_000_000, MemeExportPresets.bitrate(MemeExportPresets.Resolution.P720, MemeExportPresets.Quality.HIGH))
        assertEquals(2_500_000, MemeExportPresets.bitrate(MemeExportPresets.Resolution.P720, MemeExportPresets.Quality.MEDIUM))
        assertEquals(1_500_000, MemeExportPresets.bitrate(MemeExportPresets.Resolution.P720, MemeExportPresets.Quality.LOW))
        assertEquals(2_000_000, MemeExportPresets.bitrate(MemeExportPresets.Resolution.P480, MemeExportPresets.Quality.HIGH))
        assertEquals(1_200_000, MemeExportPresets.bitrate(MemeExportPresets.Resolution.P480, MemeExportPresets.Quality.MEDIUM))
        assertEquals(800_000, MemeExportPresets.bitrate(MemeExportPresets.Resolution.P480, MemeExportPresets.Quality.LOW))
    }

    @Test
    fun autoIsExactlyTheLegacyExport() {
        val auto = MemeExportPresets.AUTO
        assertEquals(6_000_000, auto.videoBitrateBps)
        assertEquals(1080, auto.longEdge)
        // Legacy callers of outputSize keep the same canvas.
        assertEquals(
            MemeExportRules.outputSize(1080, 1920),
            MemeExportPresets.outputSize(auto, 1080, 1920),
        )
    }

    @Test
    fun estimatesAreExactIntegerMath() {
        // 720p/Medium, 30 s: (2.5 Mbps + 128 kbps) × 30 s × 1.03.
        val preset = MemeExportPresets.Preset(
            MemeExportPresets.Resolution.P720,
            MemeExportPresets.Quality.MEDIUM,
        )
        assertEquals(10_150_650L, MemeExportPresets.estimateBytes(preset, 30_000))
        // AUTO (6 Mbps tier), 60 s.
        assertEquals(47_338_800L, MemeExportPresets.estimateBytes(MemeExportPresets.AUTO, 60_000))
        // Degenerate durations estimate zero, never negative.
        assertEquals(0L, MemeExportPresets.estimateBytes(MemeExportPresets.AUTO, 0))
    }

    @Test
    fun manualPublishGateBlocksOverheadBoundEstimates() {
        // Gate = Blossom 64 MiB with 10% headroom (57.6 MiB).
        assertEquals(60_397_977L, MemeExportPresets.PUBLISH_GATE_BYTES)
        // A minute at AUTO fits.
        assertTrue(MemeExportPresets.publishFits(MemeExportPresets.AUTO, 60_000))
        // 90 s at AUTO estimates ~71 MB — blocked for manual picks.
        assertFalse(MemeExportPresets.publishFits(MemeExportPresets.AUTO, 90_000))
        // A lower preset rescues the same duration (manual means re-pick).
        val lower = MemeExportPresets.Preset(
            MemeExportPresets.Resolution.P720,
            MemeExportPresets.Quality.MEDIUM,
        )
        assertTrue(MemeExportPresets.publishFits(lower, 90_000))
    }

    @Test
    fun sizeLabelsAreDeterministic() {
        assertEquals("≈ 45.1 MB", MemeExportPresets.sizeLabel(47_338_800L))
        assertEquals("≈ 9.7 MB", MemeExportPresets.sizeLabel(10_150_650L))
        assertEquals("≈ 0.0 MB", MemeExportPresets.sizeLabel(0L))
        // ≥ 100 MB drops the decimal.
        assertEquals("≈ 117 MB", MemeExportPresets.sizeLabel(123_000_000L))
    }

    @Test
    fun presetOutputCanvasCapsNeverUpscalesAndStaysEven() {
        val p480 = MemeExportPresets.Preset(MemeExportPresets.Resolution.P480, MemeExportPresets.Quality.HIGH)
        // 1920×1080 → 480×270.
        assertEquals(480 to 270, MemeExportPresets.outputSize(p480, 1920, 1080))
        // Small sources are never upscaled.
        val p720 = MemeExportPresets.Preset(MemeExportPresets.Resolution.P720, MemeExportPresets.Quality.HIGH)
        assertEquals(640 to 360, MemeExportPresets.outputSize(p720, 640, 360))
        // Odd landing dims even down: 1080×1921 @720p → 404×720.
        assertEquals(404 to 720, MemeExportPresets.outputSize(p720, 1080, 1921))
        // Degenerate sources keep a 9:16 default at the preset cap.
        assertEquals(480 to 853, MemeExportPresets.outputSize(p480, 0, 0))
    }

    @Test
    fun encoderPlanCapsTheLongEdgeAndCarriesTheSharedTargets() {
        // 9:16 portrait at AUTO: long edge caps at 1080 → 608×1080 (the
        // shared MST-016 web-parity canvas — oversized video sources
        // downscale, closing the doc/implementation drift).
        val auto = MemeExportPresets.encoderPlan(MemeExportPresets.AUTO, 1080, 1920)
        assertEquals(6_000_000, auto.videoBitrateBps)
        assertEquals(MemeExportPresets.AUDIO_BITRATE_BPS, auto.audioBitrateBps)
        assertEquals(608, auto.width)
        assertEquals(1080, auto.height)
        assertTrue(auto.scaleNeeded)
        // Landscape source: 1920×1080 caps to 1080×608 (long edge, either
        // orientation — passthrough only when the SOURCE long edge ≤ cap).
        val landscape = MemeExportPresets.encoderPlan(MemeExportPresets.AUTO, 1920, 1080)
        assertEquals(1080 to 608, landscape.width to landscape.height)
        assertTrue(landscape.scaleNeeded)
    }

    @Test
    fun encoderPlanPresetsScaleThePortraitCanvas() {
        val p720 = MemeExportPresets.encoderPlan(
            MemeExportPresets.Preset(MemeExportPresets.Resolution.P720, MemeExportPresets.Quality.HIGH),
            1080, 1920,
        )
        assertEquals(4_000_000, p720.videoBitrateBps)
        assertEquals(404, p720.width)
        assertEquals(720, p720.height)
        assertTrue(p720.scaleNeeded)
        // 480p: exact quarter — 270×480.
        val p480 = MemeExportPresets.encoderPlan(
            MemeExportPresets.Preset(MemeExportPresets.Resolution.P480, MemeExportPresets.Quality.LOW),
            1080, 1920,
        )
        assertEquals(800_000, p480.videoBitrateBps)
        assertEquals(270 to 480, p480.width to p480.height)
        assertEquals(0.25f, p480.scaleX)
        assertEquals(0.25f, p480.scaleY)
    }

    @Test
    fun encoderPlanNeverUpscalesAndEvensOddSources() {
        // 640×360 sits under every cap — passes through untouched.
        val small = MemeExportPresets.encoderPlan(
            MemeExportPresets.Preset(MemeExportPresets.Resolution.P1080, MemeExportPresets.Quality.LOW),
            640, 360,
        )
        assertEquals(640 to 360, small.width to small.height)
        assertFalse(small.scaleNeeded)
        // Odd upright dims even FIRST: 1081×1921 → frame 1080×1920 → AUTO 608×1080.
        val odd = MemeExportPresets.encoderPlan(MemeExportPresets.AUTO, 1081, 1921)
        assertEquals(608 to 1080, odd.width to odd.height)
    }
}
