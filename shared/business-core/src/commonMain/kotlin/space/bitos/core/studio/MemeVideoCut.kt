package space.bitos.core.studio

/**
 * Video clip cut policy (MST-030/MST-034 revision): a long clip is never
 * REJECTED outright — the system CUTS it to the allowed window and tells
 * the creator why ("over the size limit — trimmed to …"). Pure and
 * common-tested so Android (Media3 clipping) and iOS (AVComposition trim)
 * cut identically.
 *
 *  • [cutForDuration]: applies the caller-selected profile cap. Legacy
 *    callers retain the one-minute default; Studio's Full profile supplies
 *    the project safety cap instead.
 *  • [nextCutForSize]: the export ladder — when a rendered meme exceeds
 *    the upload bound, the duration shrinks by the size ratio (bitrate ≈
 *    duration for one transcode), never below [MIN_KEEP_MS], retried at
 *    most [MAX_CUT_ATTEMPTS] times before surfacing the failure.
 */
object MemeVideoCutRules {

    /** Plan's clip cap (MST-030). */
    const val MAX_CLIP_MS = 60_000L

    /** One publishable meme is at most one minute, across every clip. */
    const val MAX_TIMELINE_MS = MAX_CLIP_MS

    /**
     * Creator-facing timeline choices. They are product limits, not Nostr
     * protocol limits: NIP-71's kind 21/22 distinction is presentation.
     */
    enum class PublishProfile(val id: String, val label: String, val maxTimelineMs: Long) {
        /** Preserve the selected source timeline (up to the project safety cap). */
        FULL("full", "Full", MemeProjectContract.MAX_DURATION_MS),
        SHORT_10("short-10", "10 s", 10_000L),
        SHORT_60("short-60", "60 s", 60_000L),
        LONG_3_MIN("long-3m", "3 min", 180_000L),
        LONG_5_MIN("long-5m", "5 min", 300_000L),
    }

    /** Kind 22 is reserved for short profiles; long profiles use kind 21. */
    fun isShortForm(profile: PublishProfile): Boolean = profile == PublishProfile.SHORT_10 ||
        profile == PublishProfile.SHORT_60

    /** A durable retry derives the same intent from its rendered duration. */
    fun isShortFormDuration(durationMs: Long): Boolean = durationMs in 1..MAX_CLIP_MS

    /** Studio source-size bound (256 MB): the single cross-platform cap
     *  for picked/imported video sources — the Create hub's import-media
     *  gate (both platforms) and Android's timeline source budget read
     *  it. Common-tested so a bump is deliberate, never drift. */
    const val MAX_SOURCE_BYTES = 256L * 1024 * 1024

    /** Never cut below this — a meme needs a beat to land. */
    const val MIN_KEEP_MS = 5_000L

    /** Export re-cut attempts before the size failure surfaces. */
    const val MAX_CUT_ATTEMPTS = 3

    data class Cut(
        val startMs: Long,
        val endMs: Long,
        val cut: Boolean,
        /** Creator-facing reason (null = nothing was cut). */
        val message: String?,
    )

    /** Pick-time cap: over-long clips keep their first [maxClipMs]. */
    fun cutForDuration(durationMs: Long, maxClipMs: Long = MAX_CLIP_MS): Cut {
        if (durationMs <= 0) return Cut(0, 0, cut = false, message = null)
        if (durationMs <= maxClipMs) {
            return Cut(0, durationMs, cut = false, message = null)
        }
        return Cut(
            startMs = 0,
            endMs = maxClipMs,
            cut = true,
            message = "over the size limit — trimmed to first ${durationLabel(maxClipMs)}",
        )
    }

    /**
     * Export ladder step: null when the export already fits or the clip
     * cannot shrink further (the caller surfaces the size failure then).
     */
    fun nextCutForSize(currentMs: Long, sizeBytes: Long, maxBytes: Long): Cut? {
        if (sizeBytes in 1..maxBytes) return null
        if (currentMs <= MIN_KEEP_MS) return null
        val scaled = (currentMs * maxBytes / sizeBytes.coerceAtLeast(1))
            .coerceIn(MIN_KEEP_MS, currentMs - 1)
        return Cut(
            startMs = 0,
            endMs = scaled,
            cut = true,
            message = "over the size limit — trimmed to ${durationLabel(scaled)}",
        )
    }

    /** "60 s" / "34.5 s" — no locale machinery, deterministic output. */
    fun durationLabel(ms: Long): String {
        val whole = ms / 1000
        val tenths = (ms % 1000) / 100
        return if (tenths == 0L) "$whole s" else "$whole.$tenths s"
    }
}
