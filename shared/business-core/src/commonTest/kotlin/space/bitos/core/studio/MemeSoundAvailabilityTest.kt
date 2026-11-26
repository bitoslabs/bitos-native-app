package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * EUI-05 (reference spec §4): sound rows must never show a false state —
 * Added only when it IS the current soundtrack, Downloading never
 * represented as attached, a failed remote row explains its connection
 * need instead of a dead button, and the loop copy states the real
 * duration relationship.
 */
class MemeSoundAvailabilityTest {

    @Test
    fun rowStatesAreDistinctAndHonest() {
        // Default: a usable remote/local row.
        assertEquals(
            MemeSoundAvailability.RowState.AVAILABLE,
            MemeSoundAvailability.rowState(added = false, downloading = false, loadFailed = false),
        )
        // A failed load explains the connection need; Add stays offered.
        assertEquals(
            MemeSoundAvailability.RowState.NEEDS_CONNECTION,
            MemeSoundAvailability.rowState(added = false, downloading = false, loadFailed = true),
        )
        // Downloading outranks a past failure (a retry is in flight).
        assertEquals(
            MemeSoundAvailability.RowState.DOWNLOADING,
            MemeSoundAvailability.rowState(added = false, downloading = true, loadFailed = true),
        )
        // Added outranks everything — it IS the soundtrack.
        assertEquals(
            MemeSoundAvailability.RowState.ADDED,
            MemeSoundAvailability.rowState(added = true, downloading = false, loadFailed = false),
        )
        assertEquals(
            MemeSoundAvailability.RowState.ADDED,
            MemeSoundAvailability.rowState(added = true, downloading = true, loadFailed = false),
        )
    }

    @Test
    fun rowActionLabelsArePinned() {
        // The four catalogue verbs the spec demands stay distinguishable.
        assertEquals("Add", MemeSoundAvailability.RowState.AVAILABLE.actionLabel)
        assertEquals("Add", MemeSoundAvailability.RowState.NEEDS_CONNECTION.actionLabel)
        assertEquals("Downloading…", MemeSoundAvailability.RowState.DOWNLOADING.actionLabel)
        assertEquals("Added", MemeSoundAvailability.RowState.ADDED.actionLabel)
        // Only the two honest-trouble states carry a hint; AVAILABLE/ADDED
        // never explain what isn't wrong.
        assertEquals(null, MemeSoundAvailability.RowState.AVAILABLE.hint)
        assertEquals(null, MemeSoundAvailability.RowState.ADDED.hint)
        assertEquals(
            "Needs a connection to load",
            MemeSoundAvailability.RowState.NEEDS_CONNECTION.hint,
        )
        assertEquals(
            "Not your soundtrack until it loads",
            MemeSoundAvailability.RowState.DOWNLOADING.hint,
        )
    }

    @Test
    fun loopCopyStatesTheRealDurationRelationship() {
        // Unknown video length yet → no claim at all.
        assertEquals("", MemeSoundAvailability.loopCopy(trackMs = 5_000, videoMs = 0, loop = true))
        // Track covers the whole video → loop is moot, say so once.
        assertEquals(
            "Covers the whole video (12 s)",
            MemeSoundAvailability.loopCopy(trackMs = 14_000, videoMs = 12_000, loop = false),
        )
        assertEquals(
            "Covers the whole video (12 s)",
            MemeSoundAvailability.loopCopy(trackMs = 12_000, videoMs = 12_000, loop = true),
        )
        // Shorter track: the two honest endings.
        assertEquals(
            "Track 6 s < video 12 s — repeats to fill",
            MemeSoundAvailability.loopCopy(trackMs = 6_000, videoMs = 12_000, loop = true),
        )
        assertEquals(
            "Track 6 s < video 12 s — plays once, then silence",
            MemeSoundAvailability.loopCopy(trackMs = 6_000, videoMs = 12_000, loop = false),
        )
        // Rounded seconds, deterministic separators.
        assertEquals(
            "Track 6.5 s < video 12.4 s — repeats to fill",
            MemeSoundAvailability.loopCopy(trackMs = 6_500, videoMs = 12_400, loop = true),
        )
    }
}
