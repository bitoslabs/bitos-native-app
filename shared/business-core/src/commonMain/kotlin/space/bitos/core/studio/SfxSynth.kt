package space.bitos.core.studio

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/**
 * Synthesized meme SFX (plan MST-041 / §3.6; web `meme/sfx.ts` port).
 * Recipes are DATA (oscillator/note tables — no audio assets, no license
 * surface); [renderPcm] paints them with the exact WebAudio envelope math
 * (12 ms linear attack, exponential decay to −80 dB, linear frequency
 * ramps) so a native preview and a web render carry the same cue schedule.
 * Timbre-level parity is NOT claimed — the contract is the data + timing
 * (plan: "goldens assert cue schedule, not waveform").
 */
object SfxSynth {

    const val SAMPLE_RATE = 44_100
    const val MASTER_GAIN = 0.5
    const val ATTACK_SEC = 0.012
    const val SILENCE_FLOOR = 0.0001
    const val MAX_CUES = 16

    enum class Wave { SINE, TRIANGLE, SAWTOOTH, SQUARE }

    data class Note(
        val wave: Wave,
        /** Start delay (s). */
        val t: Double,
        /** Duration (s). */
        val d: Double,
        val f: Double,
        /** Optional linear ramp target (Hz). */
        val to: Double? = null,
        /** Peak gain 0–1 (recipe-relative). */
        val g: Double,
    )

    data class Recipe(val id: String, val duration: Double, val level: Double, val notes: List<Note>)

    data class Bucket(val id: String, val label: String, val sfx: List<String>)

    /** Human labels (web sound-catalog.ts SFX_LABELS — display copy, single
     *  source: pickers show these, the wire keeps raw ids). */
    val LABELS: Map<String, String> = mapOf(
        "boom" to "Boom", "bruh" to "Bruh", "laugh" to "Laugh",
        "crowd-laugh" to "Crowd laugh", "gasp" to "Gasp",
        "sad-trombone" to "Sad trombone", "awkward-silence" to "Awkward silence",
        "bass-hit" to "Bass hit", "whoosh" to "Whoosh", "slam" to "Slam",
        "explosion" to "Explosion", "punch" to "Punch", "anime-slash" to "Anime slash",
        "error" to "Error", "success" to "Success", "notification" to "Notification",
        "loading" to "Loading", "game-over" to "Game over",
        "coin" to "Coin", "cash" to "Cash", "jackpot" to "Jackpot",
        "lightning-zap" to "Lightning zap",
        "pop" to "Pop", "boing" to "Boing", "drumroll" to "Drumroll", "ding" to "Ding",
        "swipe" to "Swipe", "click" to "Click", "snap" to "Snap",
        "record-scratch" to "Record scratch", "reverse-whoosh" to "Reverse whoosh",
    )

    /** Display label for an id — the id itself when unknown. */
    fun labelOf(id: String): String = LABELS[id] ?: id

    /** The 5 Meme-Pack buckets (schema.ts MEME_SFX_IDS ordering). */
    val BUCKETS: List<Bucket> = listOf(
        Bucket("funny", "Funny", listOf("boom", "bruh", "laugh", "crowd-laugh", "gasp", "sad-trombone", "awkward-silence")),
        Bucket("impact", "Impact", listOf("bass-hit", "whoosh", "slam", "explosion", "punch", "anime-slash")),
        Bucket("system", "System", listOf("error", "success", "notification", "loading", "game-over")),
        Bucket("money", "Money", listOf("coin", "cash", "jackpot", "lightning-zap")),
        Bucket("transitions", "Transitions", listOf("pop", "boing", "drumroll", "ding", "swipe", "click", "snap", "record-scratch", "reverse-whoosh")),
    )

    private fun n(wave: Wave, t: Double, d: Double, f: Double, g: Double, to: Double? = null) =
        Note(wave, t, d, f, to, g)

    private fun r(id: String, duration: Double, level: Double, vararg notes: Note) =
        Recipe(id, duration, level, notes.toList())

    /** The 31 recipes — verbatim ports of the web tables. */
    val RECIPES: Map<String, Recipe> = listOf(
        r("boom", 0.9, 0.9,
            n(Wave.SINE, 0.0, 0.55, 180.0, 1.0, 38.0),
            n(Wave.SINE, 0.02, 0.5, 90.0, 0.8, 30.0),
            n(Wave.TRIANGLE, 0.0, 0.12, 220.0, 0.35, 60.0)),
        r("bruh", 0.5, 0.7,
            n(Wave.SAWTOOTH, 0.0, 0.42, 165.0, 0.5, 82.0),
            n(Wave.SINE, 0.0, 0.42, 82.0, 0.55, 55.0),
            n(Wave.SINE, 0.05, 0.1, 240.0, 0.18, 180.0)),
        r("laugh", 1.1, 0.55,
            n(Wave.TRIANGLE, 0.0, 0.09, 420.0, 0.5, 300.0),
            n(Wave.TRIANGLE, 0.14, 0.09, 400.0, 0.48, 290.0),
            n(Wave.TRIANGLE, 0.28, 0.09, 430.0, 0.5, 310.0),
            n(Wave.TRIANGLE, 0.42, 0.09, 380.0, 0.45, 270.0),
            n(Wave.TRIANGLE, 0.56, 0.12, 360.0, 0.42, 250.0),
            n(Wave.SINE, 0.7, 0.35, 300.0, 0.2, 180.0)),
        r("whoosh", 0.7, 0.6,
            n(Wave.SINE, 0.0, 0.62, 200.0, 0.5, 1600.0),
            n(Wave.TRIANGLE, 0.05, 0.5, 140.0, 0.3, 900.0),
            n(Wave.SINE, 0.5, 0.2, 1500.0, 0.25, 300.0)),
        r("pop", 0.18, 0.8,
            n(Wave.SINE, 0.0, 0.09, 520.0, 1.0, 240.0),
            n(Wave.SINE, 0.04, 0.05, 900.0, 0.3, 1400.0)),
        r("boing", 0.75, 0.7,
            n(Wave.SINE, 0.0, 0.6, 110.0, 0.7, 320.0),
            n(Wave.SINE, 0.03, 0.55, 330.0, 0.4, 110.0),
            n(Wave.SINE, 0.08, 0.45, 220.0, 0.3, 430.0)),
        r("drumroll", 1.2, 0.65,
            n(Wave.SQUARE, 0.0, 0.05, 190.0, 0.25), n(Wave.SQUARE, 0.09, 0.05, 200.0, 0.25),
            n(Wave.SQUARE, 0.18, 0.05, 210.0, 0.28), n(Wave.SQUARE, 0.26, 0.05, 220.0, 0.28),
            n(Wave.SQUARE, 0.33, 0.05, 230.0, 0.3), n(Wave.SQUARE, 0.4, 0.05, 240.0, 0.32),
            n(Wave.SQUARE, 0.46, 0.05, 255.0, 0.35), n(Wave.SQUARE, 0.51, 0.05, 270.0, 0.38),
            n(Wave.SINE, 0.58, 0.5, 160.0, 0.9, 45.0)),
        r("ding", 0.8, 0.7,
            n(Wave.SINE, 0.0, 0.7, 1244.0, 0.7),
            n(Wave.SINE, 0.0, 0.5, 1867.0, 0.25),
            n(Wave.SINE, 0.01, 0.3, 2489.0, 0.12)),
        r("sad-trombone", 1.4, 0.65,
            n(Wave.SAWTOOTH, 0.0, 0.36, 233.0, 0.4),
            n(Wave.SAWTOOTH, 0.4, 0.36, 220.0, 0.42),
            n(Wave.SAWTOOTH, 0.8, 0.55, 207.0, 0.45, 180.0),
            n(Wave.SAWTOOTH, 0.8, 0.55, 104.0, 0.3, 92.0)),
        r("crowd-laugh", 1.6, 0.55,
            n(Wave.TRIANGLE, 0.0, 0.12, 380.0, 0.4, 300.0),
            n(Wave.TRIANGLE, 0.16, 0.12, 420.0, 0.42, 330.0),
            n(Wave.TRIANGLE, 0.32, 0.12, 360.0, 0.38, 290.0),
            n(Wave.TRIANGLE, 0.5, 0.14, 400.0, 0.36, 310.0),
            n(Wave.SINE, 0.1, 1.2, 190.0, 0.14),
            n(Wave.SINE, 0.28, 1.0, 150.0, 0.1)),
        r("gasp", 0.5, 0.7,
            n(Wave.SINE, 0.0, 0.22, 300.0, 0.55, 900.0),
            n(Wave.TRIANGLE, 0.02, 0.18, 500.0, 0.25, 1200.0),
            n(Wave.SINE, 0.24, 0.2, 700.0, 0.3, 350.0)),
        r("awkward-silence", 1.1, 0.3,
            n(Wave.SINE, 0.0, 0.9, 60.0, 0.12),
            n(Wave.TRIANGLE, 0.92, 0.12, 220.0, 0.3, 180.0),
            n(Wave.SINE, 0.92, 0.12, 110.0, 0.2)),
        r("bass-hit", 0.7, 0.9,
            n(Wave.SINE, 0.0, 0.5, 120.0, 1.0, 34.0),
            n(Wave.SQUARE, 0.0, 0.04, 800.0, 0.15)),
        r("slam", 0.8, 0.9,
            n(Wave.SINE, 0.0, 0.45, 100.0, 1.0, 30.0),
            n(Wave.SAWTOOTH, 0.02, 0.12, 400.0, 0.2, 80.0),
            n(Wave.TRIANGLE, 0.1, 0.25, 90.0, 0.3, 45.0)),
        r("explosion", 1.2, 0.85,
            n(Wave.SAWTOOTH, 0.0, 0.7, 220.0, 0.5, 28.0),
            n(Wave.SAWTOOTH, 0.01, 0.6, 180.0, 0.45, 24.0),
            n(Wave.SINE, 0.0, 0.8, 70.0, 0.8, 22.0),
            n(Wave.TRIANGLE, 0.4, 0.5, 55.0, 0.25, 20.0)),
        r("punch", 0.35, 0.85,
            n(Wave.SINE, 0.0, 0.14, 150.0, 0.9, 55.0),
            n(Wave.SINE, 0.12, 0.16, 120.0, 0.8, 40.0),
            n(Wave.TRIANGLE, 0.0, 0.06, 500.0, 0.2, 200.0)),
        r("anime-slash", 0.5, 0.75,
            n(Wave.SAWTOOTH, 0.0, 0.16, 2400.0, 0.5, 600.0),
            n(Wave.SINE, 0.05, 0.3, 1800.0, 0.3, 900.0),
            n(Wave.TRIANGLE, 0.14, 0.2, 3000.0, 0.18)),
        r("error", 0.55, 0.6,
            n(Wave.SQUARE, 0.0, 0.14, 330.0, 0.3),
            n(Wave.SQUARE, 0.18, 0.3, 220.0, 0.32)),
        r("success", 0.8, 0.65,
            n(Wave.TRIANGLE, 0.0, 0.12, 523.0, 0.4),
            n(Wave.TRIANGLE, 0.12, 0.12, 659.0, 0.42),
            n(Wave.TRIANGLE, 0.24, 0.3, 784.0, 0.45),
            n(Wave.SINE, 0.24, 0.3, 1568.0, 0.15)),
        r("notification", 0.6, 0.6,
            n(Wave.SINE, 0.0, 0.22, 988.0, 0.5),
            n(Wave.SINE, 0.18, 0.35, 1319.0, 0.5),
            n(Wave.SINE, 0.0, 0.2, 1976.0, 0.12)),
        r("loading", 1.2, 0.4,
            n(Wave.SINE, 0.0, 0.5, 320.0, 0.25, 480.0),
            n(Wave.SINE, 0.5, 0.5, 480.0, 0.25, 320.0),
            n(Wave.TRIANGLE, 0.0, 1.1, 160.0, 0.12)),
        r("game-over", 1.3, 0.6,
            n(Wave.SQUARE, 0.0, 0.16, 392.0, 0.28),
            n(Wave.SQUARE, 0.18, 0.16, 370.0, 0.28),
            n(Wave.SQUARE, 0.36, 0.16, 349.0, 0.28),
            n(Wave.SQUARE, 0.54, 0.5, 330.0, 0.3, 165.0),
            n(Wave.SINE, 0.54, 0.6, 82.0, 0.3)),
        r("coin", 0.4, 0.7,
            n(Wave.SQUARE, 0.0, 0.07, 988.0, 0.3),
            n(Wave.SQUARE, 0.08, 0.25, 1319.0, 0.35),
            n(Wave.SINE, 0.08, 0.25, 2637.0, 0.12)),
        r("cash", 0.9, 0.65,
            n(Wave.TRIANGLE, 0.0, 0.2, 700.0, 0.3, 1400.0),
            n(Wave.SINE, 0.18, 0.4, 1319.0, 0.4),
            n(Wave.SINE, 0.26, 0.5, 1760.0, 0.3),
            n(Wave.TRIANGLE, 0.05, 0.3, 880.0, 0.15)),
        r("jackpot", 1.5, 0.65,
            n(Wave.SQUARE, 0.0, 0.06, 988.0, 0.22),
            n(Wave.SQUARE, 0.07, 0.06, 1047.0, 0.22),
            n(Wave.SQUARE, 0.14, 0.06, 988.0, 0.2),
            n(Wave.SQUARE, 0.21, 0.06, 1175.0, 0.22),
            n(Wave.TRIANGLE, 0.4, 0.2, 784.0, 0.35),
            n(Wave.TRIANGLE, 0.6, 0.2, 988.0, 0.38),
            n(Wave.TRIANGLE, 0.8, 0.5, 1319.0, 0.42),
            n(Wave.SINE, 0.8, 0.5, 2637.0, 0.15)),
        r("lightning-zap", 0.6, 0.8,
            n(Wave.SAWTOOTH, 0.0, 0.25, 1600.0, 0.55, 90.0),
            n(Wave.SQUARE, 0.02, 0.18, 220.0, 0.35, 60.0),
            n(Wave.SINE, 0.05, 0.4, 80.0, 0.6, 30.0)),
        r("swipe", 0.3, 0.6,
            n(Wave.SINE, 0.0, 0.24, 600.0, 0.45, 2400.0),
            n(Wave.TRIANGLE, 0.04, 0.18, 400.0, 0.25, 1600.0)),
        r("click", 0.09, 0.55,
            n(Wave.SQUARE, 0.0, 0.03, 1200.0, 0.3),
            n(Wave.SINE, 0.01, 0.06, 2400.0, 0.25, 800.0)),
        r("snap", 0.18, 0.7,
            n(Wave.SINE, 0.0, 0.05, 2000.0, 0.5, 700.0),
            n(Wave.SINE, 0.05, 0.1, 180.0, 0.55, 70.0)),
        r("record-scratch", 0.6, 0.7,
            n(Wave.SAWTOOTH, 0.0, 0.08, 900.0, 0.4, 300.0),
            n(Wave.SAWTOOTH, 0.1, 0.08, 800.0, 0.42, 250.0),
            n(Wave.SAWTOOTH, 0.22, 0.25, 200.0, 0.45, 1400.0),
            n(Wave.SINE, 0.22, 0.3, 100.0, 0.2, 400.0)),
        r("reverse-whoosh", 0.7, 0.6,
            n(Wave.SINE, 0.0, 0.55, 1800.0, 0.5, 160.0),
            n(Wave.TRIANGLE, 0.05, 0.5, 1300.0, 0.3, 120.0)),
    ).associateBy { it.id }

    fun recipeOf(id: String): Recipe? = RECIPES[id]

    /** Total loudness of a schedule (peak sum; for gain clamping hints). */
    fun scheduleGainSum(cues: List<MemeSfxCue>): Double =
        cues.sumOf { cue -> (RECIPES[cue.sfx]?.level ?: 1.0) * cue.gain }

    /**
     * Renders one recipe at [gain] into mono float PCM (−1..1), sample
     * count = ceil(duration·rate). WebAudio envelope: linear 12 ms attack
     * from the silence floor, exponential decay to the floor at note end.
     */
    fun renderPcm(recipe: Recipe, gain: Double = 1.0): FloatArray {
        val samples = ceil(recipe.duration * SAMPLE_RATE).toInt().coerceAtLeast(1)
        val out = FloatArray(samples)
        recipe.notes.forEach { note ->
            val startSample = (note.t * SAMPLE_RATE).toInt()
            val lengthSamples = (note.d * SAMPLE_RATE).toInt().coerceAtLeast(1)
            val endSample = (samples - startSample).coerceAtMost(lengthSamples)
            val peak = (MASTER_GAIN * recipe.level * gain * note.g)
                .coerceIn(0.0, 1.0).toFloat()
            if (peak <= 0f) return@forEach
            val attackSamples = (ATTACK_SEC * SAMPLE_RATE).toInt()
            val floorRatio = SILENCE_FLOOR / peak
            var phase = 0.0
            for (i in 0 until endSample) {
                val progress = i.toDouble() / lengthSamples
                val frequency = if (note.to != null) {
                    note.f + (note.to - note.f) * progress
                } else {
                    note.f
                }
                phase += 2.0 * PI * frequency / SAMPLE_RATE
                if (phase > 2.0 * PI) phase -= 2.0 * PI * floor(phase / (2.0 * PI))
                val env = when {
                    i < attackSamples && attackSamples > 0 -> {
                        val attack = i.toDouble() / attackSamples
                        SILENCE_FLOOR + (peak - SILENCE_FLOOR) * attack
                    }
                    else -> {
                        val decay = (i - attackSamples).toDouble() /
                            (lengthSamples - attackSamples).coerceAtLeast(1)
                        // Exponential interpolation peak → floor (WebAudio
                        // exponentialRampToValueAtTime).
                        peak * exp(ln(floorRatio) * decay.coerceIn(0.0, 1.0))
                    }
                }
                val idx = startSample + i
                if (idx < samples) out[idx] += (waveform(note.wave, phase) * env).toFloat()
            }
        }
        // Guard against clipping when voices stack.
        for (i in out.indices) out[i] = out[i].coerceIn(-1f, 1f)
        return out
    }

    private fun waveform(wave: Wave, phase: Double): Double = when (wave) {
        Wave.SINE -> sin(phase)
        Wave.TRIANGLE -> 2.0 / PI * asin(sin(phase))
        Wave.SAWTOOTH -> 2.0 * (phase / (2.0 * PI) - floor(phase / (2.0 * PI) + 0.5))
        Wave.SQUARE -> if (sin(phase) >= 0) 1.0 else -1.0
    }

    /** Mono float PCM → 16-bit little-endian bytes (AudioTrack input). */
    fun pcm16Le(pcm: FloatArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = (pcm[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Wraps 16-bit mono PCM in a WAV container (AVAudioPlayer input). */
    fun wav(pcm16: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val out = ByteArray(44 + pcm16.size)
        fun putInt(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
            out[offset + 2] = ((value shr 16) and 0xFF).toByte()
            out[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        fun putAscii(offset: Int, text: String) {
            text.forEachIndexed { i, c -> out[offset + i] = c.code.toByte() }
        }
        putAscii(0, "RIFF")
        putInt(4, 36 + pcm16.size)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1) // PCM
        putShort(22, 1) // mono
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2) // byte rate
        putShort(32, 2) // block align
        putShort(34, 16) // bits
        putAscii(36, "data")
        putInt(40, pcm16.size)
        pcm16.copyInto(out, 44)
        return out
    }
    /**
     * Mixes a cue schedule into one mono PCM track over the export window
     * (web `renderSfxTrack` parity: cues starting at/after the window end
     * drop; the render carries the +250 ms tail then clips to the window —
     * the video export bounds the audible result). Deterministic.
     */
    fun renderCueTrack(cues: List<MemeSfxCue>, durationMs: Long): FloatArray {
        if (durationMs <= 0) return FloatArray(0)
        val totalSamples = ceil(durationMs / 1000.0 * SAMPLE_RATE).toInt()
        val out = FloatArray(totalSamples)
        cues.sortedBy { it.atMs }.forEach { cue ->
            if (cue.atMs >= durationMs) return@forEach
            val recipe = RECIPES[cue.sfx] ?: return@forEach
            val pcm = renderPcm(recipe, cue.gain.toDouble())
            val offset = (cue.atMs / 1000.0 * SAMPLE_RATE).toInt()
            val copy = (pcm.size).coerceAtMost(totalSamples - offset)
            for (i in 0 until copy) out[offset + i] += pcm[i]
        }
        for (i in out.indices) out[i] = out[i].coerceIn(-1f, 1f)
        return out
    }

    /** True when the project would export with sound (cues inside window). */
    fun hasAudibleCues(cues: List<MemeSfxCue>, durationMs: Long): Boolean =
        durationMs > 0 && cues.any { it.atMs < durationMs && RECIPES[it.sfx] != null }

    /**
     * Cue times mapped into the OUTPUT timeline of a rate-adjusted export:
     * media time compresses by `rate` (2× plays cues twice as early).
     * `MemeProjectContract.clampSpeed` bounds the rate; 1 returns the list.
     */
    fun cuesInOutputTimeline(cues: List<MemeSfxCue>, rate: Float): List<MemeSfxCue> {
        val clamped = MemeProjectContract.clampSpeed(rate)
        if (clamped == 1f || cues.isEmpty()) return cues
        return cues.map { cue -> cue.copy(atMs = (cue.atMs / clamped).toLong()) }
    }
}

/** One scheduled cue on the project (schema-parity with the wire document:
 *  id, synth id (or `custom` + library soundId later), media-time atMs,
 *  0–1 gain; ≤ [SfxSynth.MAX_CUES]). */
data class MemeSfxCue(
    val id: String,
    val sfx: String,
    val atMs: Long,
    val gain: Float = 1f,
) {
    init {
        require(gain.isFinite()) { "gain must be finite" }
    }
}
