package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Video cut policy (MST-030/MST-034 revision): long clips are CUT with a
 * creator-facing "over the size limit" message — never rejected — and the
 * export ladder shrinks duration by the size ratio down to a floor.
 */
class MemeVideoCutTest {

    @Test
    fun shortClipsPassThroughUntouched() {
        val keep = MemeVideoCutRules.cutForDuration(12_000)
        assertEquals(0, keep.startMs)
        assertEquals(12_000, keep.endMs)
        assertEquals(false, keep.cut)
        assertNull(keep.message)
        // Degenerate durations never cut to a bogus window.
        assertEquals(0, MemeVideoCutRules.cutForDuration(0).endMs)
    }

    @Test
    fun longClipsAreCutToTheFirstMinuteWithTheMessage() {
        assertEquals(
            MemeVideoCutRules.MAX_CLIP_MS,
            MemeVideoCutRules.MAX_TIMELINE_MS,
            "a multi-clip meme has one shared one-minute output budget",
        )
        val cut = MemeVideoCutRules.cutForDuration(184_000)
        assertTrue(cut.cut)
        assertEquals(0, cut.startMs)
        assertEquals(60_000, cut.endMs)
        assertEquals("over the size limit — trimmed to first 60 s", cut.message)
        // Exactly at the cap = not a cut.
        assertEquals(false, MemeVideoCutRules.cutForDuration(60_000).cut)
        // One frame past = cut.
        assertTrue(MemeVideoCutRules.cutForDuration(60_001).cut)
    }

    @Test
    fun sizeLadderShrinksProportionallyWithAFloor() {
        // 40 MB over a 20 MB bound → duration halves.
        val half = MemeVideoCutRules.nextCutForSize(60_000, 40 * 1024 * 1024, 20 * 1024 * 1024)
        assertEquals(30_000, half!!.endMs)
        assertTrue(half.message!!.contains("over the size limit"))
        // Already fitting → nothing to do.
        assertNull(MemeVideoCutRules.nextCutForSize(60_000, 1024, 20 * 1024 * 1024))
        // At the floor → cannot shrink, caller surfaces the failure.
        assertNull(MemeVideoCutRules.nextCutForSize(5_000, 40 * 1024 * 1024, 1024))
        // Never below the floor even with a brutal ratio.
        val floored = MemeVideoCutRules.nextCutForSize(6_000, 64 * 1024 * 1024, 1024)
        assertEquals(5_000, floored!!.endMs)
    }

    @Test
    fun durationLabelsAreDeterministic() {
        assertEquals("60 s", MemeVideoCutRules.durationLabel(60_000))
        assertEquals("34.5 s", MemeVideoCutRules.durationLabel(34_500))
        assertEquals("5 s", MemeVideoCutRules.durationLabel(5_000))
    }

    @Test
    fun sourceByteCapIsTheCrossPlatformImportBound() {
        // The studio import-media gate (Create hub, both platforms) and
        // Android's timeline source cap read THIS constant — pin it so a
        // bump is a deliberate, common-tested decision, not drift.
        assertEquals(256L * 1024 * 1024, MemeVideoCutRules.MAX_SOURCE_BYTES)
        assertEquals("256 MB", "${MemeVideoCutRules.MAX_SOURCE_BYTES / (1024 * 1024)} MB")
    }
}
