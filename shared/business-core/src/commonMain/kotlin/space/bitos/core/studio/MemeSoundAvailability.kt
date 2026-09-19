package space.bitos.core.studio

/**
 * EUI-05 (reference spec §4): the honest sound-row state machine + loop
 * copy — one shared rule so neither platform can render a false
 * download/add state or an unexplained offline row.
 *
 *  • **Added** only when the row IS the current soundtrack.
 *  • **Downloading** is never represented as an active soundtrack.
 *  • A row whose load failed explains its connection need instead of a
 *    dead button; **Add** stays offered (a retry is always one tap away).
 *  • The loop copy states the real duration relationship (the spec's
 *    "Play once / Loop to fill" offer appears only when the track is
 *    SHORTER than the video).
 */
object MemeSoundAvailability {

    enum class RowState(val actionLabel: String, val hint: String?) {
        AVAILABLE("Add", null),
        NEEDS_CONNECTION("Add", "Needs a connection to load"),
        DOWNLOADING("Downloading…", "Not your soundtrack until it loads"),
        ADDED("Added", null),
    }

    /**
     * The row state for a catalogue entry. Precedence: Added (it IS the
     * soundtrack) → Downloading (a retry in flight outranks a past
     * failure) → NeedsConnection (a load failed) → Available.
     */
    fun rowState(added: Boolean, downloading: Boolean, loadFailed: Boolean): RowState = when {
        added -> RowState.ADDED
        downloading -> RowState.DOWNLOADING
        loadFailed -> RowState.NEEDS_CONNECTION
        else -> RowState.AVAILABLE
    }

    /**
     * The soundtrack row's loop explanation: empty while the video length
     * is unknown (no claim beats a wrong one); one line when the track
     * covers the whole video (loop is moot); the shorter-track endings
     * name both durations so the choice is informed.
     */
    fun loopCopy(trackMs: Long, videoMs: Long, loop: Boolean): String {
        if (videoMs <= 0) return ""
        if (trackMs >= videoMs) return "Covers the whole video (${seconds(videoMs)})"
        val endings = if (loop) "repeats to fill" else "plays once, then silence"
        return "Track ${seconds(trackMs)} < video ${seconds(videoMs)} — $endings"
    }

    private fun seconds(ms: Long): String {
        val whole = ms / 1000
        val tenths = (ms % 1000) / 100
        return if (tenths == 0L) "$whole s" else "$whole.$tenths s"
    }
}
