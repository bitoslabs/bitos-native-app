package space.bitos.core.studio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Soundtrack PCM bed math ("use this sound" Wave B, plan §5): the editor
 * and the export mixdown place a decoded soundtrack into the SAME mono
 * 44.1 kHz PCM bed the SFX cues render into (MST-041) — one track, one
 * WAV, sample-accurate offsets, Transformer mixes it as the usual second
 * sequence. Pure so both platforms mix byte-identically.
 */
class MemeSoundMixTest {

    // ── resample ─────────────────────────────────────────────────────

    @Test
    fun resampleAtTheSameRateIsAnIdentity() {
        val pcm = floatArrayOf(0f, 0.25f, -0.5f, 1f)
        val out = MemeSoundMix.resample(pcm, fromRate = 44_100, toRate = 44_100)
        assertEquals(4, out.size)
        assertTrue(out.zip(pcm.toTypedArray()).all { (a, b) -> abs(a - b) < 1e-6f })
    }

    @Test
    fun resampleHalvesWhenTheRateHalves() {
        // 8 samples at 2·bed rate → 4 at bed rate; linear picks every
        // even source sample (positions 0, 2, 4, 6).
        val pcm = floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f)
        val out = MemeSoundMix.resample(pcm, fromRate = 88_200, toRate = 44_100)
        assertEquals(listOf(0f, 2f, 4f, 6f), out.toList())
    }

    @Test
    fun resampleUpconvertsByInterpolation() {
        val out = MemeSoundMix.resample(floatArrayOf(0f, 1f), fromRate = 44_100, toRate = 88_200)
        assertEquals(4, out.size)
        assertTrue(abs(out[1] - 0.5f) < 1e-6f, "midpoint interpolates: ${out.toList()}")
        assertEquals(1f, out[3])
    }

    @Test
    fun resampleOfEmptyOrJunkRatesStaysSafe() {
        assertEquals(0, MemeSoundMix.resample(FloatArray(0), 44_100, 44_100).size)
        // Junk rate falls back to a passthrough, never divides by zero.
        val passthrough = MemeSoundMix.resample(floatArrayOf(0.5f, 0.5f), fromRate = 0, toRate = 44_100)
        assertEquals(listOf(0.5f, 0.5f), passthrough.toList())
    }

    // ── bed placement ────────────────────────────────────────────────

    @Test
    fun bedPlacesTheTrackAtItsTimelineOffset() {
        // 10 ms of unity PCM at the bed rate, placed at 1000 ms.
        val bedRate = SfxSynth.SAMPLE_RATE
        val track = FloatArray((bedRate * 0.01f).toInt()) { 1f }
        val bed = MemeSoundMix.bedTrack(track, trackRate = bedRate, timelineDurationMs = 2_000, offsetMs = 1_000, volume = 1f)
        assertEquals((bedRate * 2f).toInt(), bed.size, "bed spans the whole timeline")
        assertTrue(bed.take((bedRate * 0.999f).toInt()).all { it == 0f }, "silence before the offset")
        assertTrue(bed.drop(bedRate).take(track.size).all { it == 1f }, "track samples at the offset")
        assertTrue(bed.drop(bedRate + track.size).all { it == 0f }, "silence after the track")
    }

    @Test
    fun bedTruncatesAtTheTimelineEndAndClampsGain() {
        val bedRate = SfxSynth.SAMPLE_RATE
        val track = FloatArray(bedRate) { 1f } // 1 s
        // Placed at 1500 ms of a 2 s timeline → only the first 500 ms fit;
        // the bed itself ends at exactly the timeline end.
        val bed = MemeSoundMix.bedTrack(track, bedRate, timelineDurationMs = 2_000, offsetMs = 1_500, volume = 0.5f)
        assertEquals((bedRate * 2f).toInt(), bed.size)
        val offsetSample = bedRate * 3 / 2
        assertTrue(bed.take(offsetSample).all { it == 0f }, "silence before the offset")
        assertTrue(
            bed.drop(offsetSample).all { abs(it - 0.5f) < 1e-6f },
            "truncated track at 0.5 gain fills to the timeline end",
        )
    }

    @Test
    fun bedHandlesDegenerateInputs() {
        assertEquals(0, MemeSoundMix.bedTrack(FloatArray(0), 44_100, 2_000, 0, 1f).size)
        assertEquals(0, MemeSoundMix.bedTrack(FloatArray(100) { 1f }, 44_100, 0, 0, 1f).size)
        // Negative offset / junk volume coerce, never crash or wrap.
        val bed = MemeSoundMix.bedTrack(FloatArray(44) { 1f }, 44_100, 1_000, -50, 9f)
        assertEquals((44_100f * 1f).toInt(), bed.size)
        assertTrue(bed.take(44).all { it == 1f }, "offset coerced to 0, gain clamped to 1")
    }

    @Test
    fun bedLoopsToFillTheTimelineWhenLoopIsSet() {
        val bedRate = SfxSynth.SAMPLE_RATE
        // 0.5 s of unity PCM, offset 250 ms, looping over a 2 s timeline.
        val track = FloatArray(bedRate / 2) { 1f }
        val bed = MemeSoundMix.bedTrack(
            track, bedRate,
            timelineDurationMs = 2_000, offsetMs = 250, volume = 1f,
            loop = true,
        )
        assertEquals((bedRate * 2f).toInt(), bed.size, "bed spans the whole timeline")
        // Silence before the offset; the track then repeats cyclically to
        // the end — no gap after the first placement.
        assertTrue(bed.take(bedRate / 4).all { it == 0f }, "silence before the offset")
        assertTrue(
            bed.drop(bedRate / 4).all { it == 1f },
            "loop repeats fill every cycle to the timeline end",
        )
    }

    @Test
    fun bedLoopRespectsGainAndDegenerateShapes() {
        val bedRate = SfxSynth.SAMPLE_RATE
        val track = FloatArray(bedRate / 2) { 1f }
        val bed = MemeSoundMix.bedTrack(track, bedRate, 2_000, 0, 0.5f, loop = true)
        assertTrue(bed.drop(bedRate / 2).take(100).all { kotlin.math.abs(it - 0.5f) < 1e-6f }, "gain kept across cycles")
        // Offset beyond the timeline: an all-silent bed, never a crash.
        val beyond = MemeSoundMix.bedTrack(track, bedRate, 1_000, 5_000, 1f, loop = true)
        assertEquals((bedRate * 1f).toInt(), beyond.size)
        assertTrue(beyond.all { it == 0f })
        // Empty track with loop stays empty (nothing to repeat).
        assertEquals(0, MemeSoundMix.bedTrack(FloatArray(0), bedRate, 1_000, 0, 1f, loop = true).size)
    }

    // ── mix (cue bed + soundtrack bed = one track) ───────────────────

    @Test
    fun mixAddsElementwiseAndClampsToUnity() {
        val a = floatArrayOf(0.25f, -0.75f, 0.9f)
        val b = floatArrayOf(0.25f, -0.75f, 0.9f)
        val mixed = MemeSoundMix.mix(a, b)
        assertEquals(0.5f, mixed[0])
        assertEquals(-1f, mixed[1])
        assertEquals(1f, mixed[2], "clamped at +1")
    }

    @Test
    fun mixPadsTheShorterBedWithSilence() {
        val mixed = MemeSoundMix.mix(floatArrayOf(0.1f, 0.2f), floatArrayOf(0.5f))
        assertEquals(listOf(0.6f, 0.2f), mixed.toList())
    }

    @Test
    fun cueTrackPlusSoundtrackBedSumLikeTheExportHears() {
        // 1 s of cues + a soundtrack at 500 ms → overlapping sum.
        val cues = SfxSynth.renderCueTrack(
            listOf(MemeSfxCue(id = "c1", sfx = "coin", atMs = 500, gain = 1f)),
            durationMs = 1_000,
        )
        val sound = MemeSoundMix.bedTrack(FloatArray(SfxSynth.SAMPLE_RATE / 2) { 0.5f }, SfxSynth.SAMPLE_RATE, 1_000, 500, 1f)
        val mixed = MemeSoundMix.mix(cues, sound)
        assertEquals(cues.size, mixed.size)
        assertTrue(
            mixed.drop(SfxSynth.SAMPLE_RATE / 2).take(100).any { it > 0.5f },
            "cue + soundtrack sum where they overlap",
        )
    }
}
