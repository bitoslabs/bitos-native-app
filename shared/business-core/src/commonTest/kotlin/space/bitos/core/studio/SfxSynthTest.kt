package space.bitos.core.studio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Synth SFX (plan MST-041 / §3.6; web `sfx.ts` port): the 31-recipe
 * catalog is complete and bucketed, the PCM renderer is deterministic
 * with the WebAudio envelope shape (12 ms attack, exponential decay),
 * WAV bytes are well-formed, and cues ride the project wire + command
 * stack with the hostile clamps.
 */
class SfxSynthTest {

    @Test
    fun catalogHasAllThirtyOneRecipesAcrossFiveBuckets() {
        assertEquals(31, SfxSynth.RECIPES.size)
        val bucketed = SfxSynth.BUCKETS.flatMap { it.sfx }.toSet()
        assertEquals(SfxSynth.RECIPES.keys, bucketed, "every recipe is bucketed exactly once")
        assertEquals(listOf("funny", "impact", "system", "money", "transitions"), SfxSynth.BUCKETS.map { it.id })
        // Verbatim spot checks against the web tables.
        assertEquals(0.9, SfxSynth.RECIPES["boom"]!!.level)
        assertEquals(3, SfxSynth.RECIPES["boom"]!!.notes.size)
        assertEquals(1.4, SfxSynth.RECIPES["sad-trombone"]!!.duration)
        assertEquals(8, SfxSynth.RECIPES["jackpot"]!!.notes.size)
        assertEquals(2637.0, SfxSynth.RECIPES["jackpot"]!!.notes.last().f)
    }

    @Test
    fun pcmIsDeterministicBoundedAndAudible() {
        val recipe = SfxSynth.recipeOf("boom")!!
        val a = SfxSynth.renderPcm(recipe)
        val b = SfxSynth.renderPcm(recipe)
        assertTrue(a.contentEquals(b), "same input → identical PCM")
        assertEquals((0.9 * SfxSynth.SAMPLE_RATE).toInt(), a.size)
        assertTrue(a.all { abs(it) <= 1f })
        val peak = a.maxOf { abs(it) }
        assertTrue(peak > 0.3f, "audible: peak $peak")
        // Starts at silence (attack), never negative-gain.
        // WebAudio envelopes start at the −80 dB floor, not true zero.
        assertTrue(abs(a[0]) < 1e-3)
        // Gain scales the render.
        val quiet = SfxSynth.renderPcm(recipe, gain = 0.1)
        assertTrue(quiet.maxOf { abs(it) } < peak)
    }

    @Test
    fun wavContainerIsWellFormed() {
        val pcm = FloatArray(100) { 0.5f }
        val wav = SfxSynth.wav(SfxSynth.pcm16Le(pcm))
        assertEquals(44 + 200, wav.size)
        assertEquals("RIFF", wav.sliceArray(0..3).decodeToString())
        assertEquals("WAVE", wav.sliceArray(8..11).decodeToString())
        assertEquals("data", wav.sliceArray(36..39).decodeToString())
        // 16-bit mono little-endian round-trip: 0.5 amplitude → 16383.
        assertEquals(16383, ((wav[45].toInt() and 0xFF) shl 8) or (wav[44].toInt() and 0xFF))
    }

    @Test
    fun cuesRideTheWireWithClamps() {
        val project = MemeProject(
            mode = MemeMode.VIDEO,
            sfxCues = listOf(
                MemeSfxCue("c1", "boom", 1_200, 0.8f),
                MemeSfxCue("c2", "custom", 3_000),
            ),
        )
        val decoded = MemeProjectContract.decode(MemeProjectContract.encode(project))!!
        assertEquals(2, decoded.sfxCues.size)
        assertEquals("boom", decoded.sfxCues[0].sfx)
        assertEquals(1_200, decoded.sfxCues[0].atMs)
        // Unknown synth ids + hostile gains degrade at the wire.
        val hostile = MemeProjectContract.encode(project)
            .replace("\"sfx\":\"boom\"", "\"sfx\":\"hologram\"")
            .replace("\"g\":0.8", "\"g\":9")
        val hostileDecoded = MemeProjectContract.decode(hostile)!!
        assertTrue(hostileDecoded.sfxCues.none { it.sfx == "hologram" })
        assertTrue(hostileDecoded.sfxCues.all { it.gain <= 1f })
        // Absent field = empty (old wires byte-compatible).
        assertTrue(MemeProjectContract.decode("""{"v":1,"mode":"image"}""")!!.sfxCues.isEmpty())
    }

    @Test
    fun cueCommandsApplyThroughTheStackWithCaps() {
        var project = MemeProject(mode = MemeMode.VIDEO)
        project = MemeRules.apply(project, MemeCommand.AddSfxCue(MemeSfxCue("c1", "ding", 500)))
        assertEquals(1, project.sfxCues.size)
        // Junk ids no-op; over-cap adds stop at 16.
        assertEquals(
            project,
            MemeRules.apply(project, MemeCommand.AddSfxCue(MemeSfxCue("cx", "nope", 0))),
        )
        var filled = project
        repeat(20) { index ->
            filled = MemeRules.apply(filled, MemeCommand.AddSfxCue(MemeSfxCue("c$index", "pop", 10)))
        }
        assertEquals(SfxSynth.MAX_CUES, filled.sfxCues.size)
        filled = MemeRules.apply(filled, MemeCommand.RemoveSfxCue("c1"))
        assertTrue(filled.sfxCues.none { it.id == "c1" })
        // Codec round-trip.
        val encoded = MemeCommandCodec.encode(MemeCommand.AddSfxCue(MemeSfxCue("c9", "coin", 250, 0.5f)))
        val decoded = MemeCommandCodec.decode(encoded) as MemeCommand.AddSfxCue
        assertEquals("coin", decoded.cue.sfx)
        assertEquals(250, decoded.cue.atMs)
        assertEquals(0.5f, decoded.cue.gain)
        assertNotNull(MemeCommandCodec.decode("""{"op":"cue-del","id":"c1"}"""))
    }

    @Test
    fun cueTrackMixesAtOffsetsAndDropsOutOfRangeCues() {
        val cues = listOf(
            MemeSfxCue("c1", "ding", 0),
            MemeSfxCue("c2", "pop", 1_000),
            MemeSfxCue("late", "boom", 2_000), // at the window end → drops
        )
        val track = SfxSynth.renderCueTrack(cues, durationMs = 2_000)
        assertEquals((2_000 / 1000.0 * SfxSynth.SAMPLE_RATE).toInt(), track.size)
        // c1 audible near t=0, silent between the two cues' envelopes,
        // c2 audible at 1 s, and the boom never lands.
        assertTrue(abs(track[100]) > 1e-3f, "ding onset")
        val mid = track[(1_500 / 1000.0 * SfxSynth.SAMPLE_RATE).toInt()]
        assertEquals(0f, mid, 1e-4f, "pop tail decayed by 1.5 s")
        assertTrue(abs(track[(1_050 / 1000.0 * SfxSynth.SAMPLE_RATE).toInt()]) > 1e-3f, "pop onset")
        assertTrue(!SfxSynth.hasAudibleCues(listOf(cues[2]), 2_000))
        assertTrue(SfxSynth.hasAudibleCues(cues, 2_000))
        assertEquals(0, SfxSynth.renderCueTrack(cues, 0).size)
    }

    @Test
    fun cuesMapIntoTheRateAdjustedOutputTimeline() {
        val cues = listOf(
            MemeSfxCue("c1", "coin", 1_000),
            MemeSfxCue("c2", "cash", 4_000),
        )
        // 1× returns the schedule unchanged.
        assertEquals(cues, SfxSynth.cuesInOutputTimeline(cues, 1f))
        // 2× halves media offsets (cues land twice as early in output time).
        val doubled = SfxSynth.cuesInOutputTimeline(cues, 2f)
        assertEquals(500L, doubled[0].atMs)
        assertEquals(2_000L, doubled[1].atMs)
        // Slow-mo stretches; a junk rate degrades to source speed.
        val halved = SfxSynth.cuesInOutputTimeline(cues, 0.5f)
        assertEquals(2_000L, halved[0].atMs)
        assertEquals(cues, SfxSynth.cuesInOutputTimeline(cues, Float.NaN))
    }
}
