package space.bitos.core.studio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Waveform peaks for the trending rail's rows ("use this sound" §3.21):
 * decode → bucket → normalize max-abs per bucket. Pure + common-tested so
 * both platforms render identical bars from identical bytes.
 */
class MemeSoundWaveformTest {

    @Test
    fun flatSilenceYieldsFlatZeroBars() {
        val peaks = MemeSoundWaveform.peaks(FloatArray(4_410) { 0f }, bars = 30)
        assertEquals(30, peaks.size)
        assertTrue(peaks.all { it == 0f })
    }

    @Test
    fun peakPerBucketIsTheMaxAbsAndNormalized() {
        // 2 buckets × 4 samples: [0.1, -0.8, 0.05, 0.05] [1.0, 0.2, 0.2, 0.2]
        val pcm = floatArrayOf(0.1f, -0.8f, 0.05f, 0.05f, 1.0f, 0.2f, 0.2f, 0.2f)
        val peaks = MemeSoundWaveform.peaks(pcm, bars = 2)
        assertEquals(0.8f, peaks[0])
        assertEquals(1f, peaks[1])
    }

    @Test
    fun sparsePcmBucketsRemainingBarsFallToZero() {
        // More buckets than samples: filled buckets keep their value, the
        // tail degrades to silence — never an index crash.
        val peaks = MemeSoundWaveform.peaks(floatArrayOf(0.5f, -0.25f), bars = 4)
        assertEquals(listOf(0.5f, 0.25f, 0f, 0f), peaks.toList())
    }

    @Test
    fun degenerateInputsYieldZeroBars() {
        assertEquals(0, MemeSoundWaveform.peaks(FloatArray(0), bars = 30).size)
        assertEquals(0, MemeSoundWaveform.peaks(FloatArray(100) { 1f }, bars = 0).size)
        assertEquals(0, MemeSoundWaveform.peaks(FloatArray(100) { 1f }, bars = -3).size)
    }

    @Test
    fun barsAreBoundedToUnityEvenWithJunkPcm() {
        val peaks = MemeSoundWaveform.peaks(floatArrayOf(42f, -420f, Float.NaN), bars = 3)
        assertTrue(peaks.all { it in 0f..1f }, "clamped/NaN-safe: ${peaks.toList()}")
    }
}
