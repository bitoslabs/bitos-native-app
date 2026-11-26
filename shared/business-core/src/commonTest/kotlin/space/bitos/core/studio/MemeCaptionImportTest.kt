package space.bitos.core.studio

import space.bitos.core.studio.MemeCaptionImport.CaptionCue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EUI-06 (reference spec §5): timed-caption import — a bounded, tolerant,
 * DETERMINISTIC SRT/WebVTT cue parser (same bytes → same cues on both
 * platforms), plus the caption placement rule imports render from.
 * Nothing is claimed that wasn't parsed: dropped cues are counted, never
 * silently eaten, and the overlay cap truncates loudly.
 */
class MemeCaptionImportTest {

    @Test
    fun parsesPlainSrtWithTolerances() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,500
            Hello there

            2
            00:00:04,000 --> 00:00:06,000
            Second line
            continues here

        """.trimIndent()
        val result = MemeCaptionImport.parse(srt)
        assertEquals(2, result.cues.size)
        assertEquals(CaptionCue(1_000L, 3_500L, "Hello there"), result.cues[0])
        assertEquals(CaptionCue(4_000L, 6_000L, "Second line\ncontinues here"), result.cues[1])
        assertEquals(0, result.dropped)
        assertTrue(result.error == null)
    }

    @Test
    fun parsesWebVttWithHeaderAndNotes() {
        val vtt = "WEBVTT\r\n\r\nNOTE this is a comment\r\n\r\n" +
            "00:01.000 --> 00:02.000\r\nVTT cue\r\n"
        val result = MemeCaptionImport.parse(vtt)
        assertEquals(1, result.cues.size)
        // `00:01.000` is MM:SS.mmm (1 s), not an hour — VTT clips hours.
        assertEquals(CaptionCue(1_000L, 2_000L, "VTT cue"), result.cues[0])
        assertEquals(0, result.dropped)
    }

    @Test
    fun junkIsDroppedAndCountedNeverThrows() {
        // Degenerate windows, unparseable times, empty text — all dropped
        // with a count; the good cues survive.
        val mixed = """
            1
            00:00:05,000 --> 00:00:02,000
            Backwards window

            2
            garbage --> 00:00:02,000
            Bad timecode

            3
            00:00:01,000 --> 00:00:02,000
            Good cue

            4
            00:00:08,000 --> 00:00:09,000


        """.trimIndent()
        val result = MemeCaptionImport.parse(mixed)
        assertEquals(1, result.cues.size)
        assertEquals("Good cue", result.cues[0].text)
        assertEquals(3, result.dropped)
    }

    @Test
    fun capsAreBoundedAndLoud() {
        // Long text truncates to the wire bound; a huge import truncates
        // at the overlay cap and SAYS so via `truncated`.
        val long = buildString {
            repeat(80) { index ->
                val sec = index.toString().padStart(2, '0')
                append(index).append('\n')
                append("00:00:$sec,000 --> 00:00:$sec,900\n")
                append("Cue $index ").append("x".repeat(400)).append("\n\n")
            }
        }
        val result = MemeCaptionImport.parse(long)
        assertEquals(MemeProjectContract.MAX_OVERLAYS, result.cues.size)
        assertTrue(result.truncated, "the import must say it truncated")
        assertTrue(result.cues.all { it.text.length <= MemeProjectContract.MAX_TEXT_LENGTH })
        // Times are bounded by the wire duration cap.
        assertTrue(result.cues.all { it.endMs <= MemeProjectContract.MAX_DURATION_MS })
    }

    @Test
    fun emptyOrForeignFilesReportHonestErrors() {
        val empty = MemeCaptionImport.parse("")
        assertTrue(empty.cues.isEmpty() && empty.error != null)
        val foreign = MemeCaptionImport.parse("just some prose\nwithout any cue")
        assertTrue(foreign.cues.isEmpty() && foreign.error != null)
    }

    @Test
    fun captionPlacementIsDeterministicAndSafeArea() {
        // Non-overlapping cues all sit in the bottom safe area.
        val a = MemeCaptionImport.placement(overlapRow = 0)
        assertEquals(0.5f, a.x)
        assertEquals(0.86f, a.y)
        // Overlapping cues alternate up a row (the importer staggers
        // overlaps so both stay readable, deterministic).
        val b = MemeCaptionImport.placement(overlapRow = 1)
        assertEquals(0.74f, b.y)
        assertEquals(a.x, b.x)
        // The caption style preset: heavy outline, readable size.
        val style = MemeCaptionImport.STYLE
        assertEquals(3, style.outline)
        assertTrue(style.size >= 40)
    }
}
