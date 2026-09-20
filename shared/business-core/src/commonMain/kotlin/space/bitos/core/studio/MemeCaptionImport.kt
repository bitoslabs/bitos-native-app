package space.bitos.core.studio

/**
 * EUI-06 (reference spec §5): timed-caption import + placement — one
 * bounded, tolerant, DETERMINISTIC parser both platforms share (same
 * bytes → same cues), so exported caption timing can never drift between
 * apps. Accepts SRT (comma decimals, optional index lines) and WebVTT
 * (dot decimals, `WEBVTT` header, `NOTE` blocks); CRLF/BOM tolerant.
 *
 * Honesty rules: dropped cues are COUNTED (never silently eaten), the
 * overlay cap truncates loudly ([Parsed.truncated]), per-cue text clamps
 * to the wire text bound, windows clamp to the wire duration bound, and
 * an unparseable file reports an error instead of an empty success.
 *
 * Speech-to-text generation is NOT offered: no transcription engine
 * exists in this codebase (the spec's "where available") — the sheets
 * say what they can do instead of showing a lying button.
 */
object MemeCaptionImport {

    /** One timed caption cue (media time, ms). */
    data class CaptionCue(
        val startMs: Long,
        val endMs: Long,
        val text: String,
    )

    /** The caption style preset imports land with (readable default). */
    data class CaptionStyle(
        val size: Int,
        val outline: Int,
    )

    val STYLE = CaptionStyle(size = 48, outline = 3)

    /** Bottom safe-area placement; overlapping cues stagger up a row. */
    data class Placement(val x: Float, val y: Float)

    /** Import result: the kept cues (≤ [MemeProjectContract.MAX_OVERLAYS])
     *  plus the honest accounting. */
    data class Parsed(
        val cues: List<CaptionCue>,
        /** Cues discarded (degenerate window / bad timecode / empty text). */
        val dropped: Int,
        /** True when good cues existed beyond the overlay cap. */
        val truncated: Boolean,
        /** Non-null when NOTHING usable was found (bad file / wrong type). */
        val error: String?,
    )

    // `HH:MM:SS,mmm` / `MM:SS.mmm` / `HH:MM:SS.mmm`, tolerant separators.
    private val timeRegex = Regex(
        """(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""",
    )
    fun parse(raw: String): Parsed {
        val text = raw.trimStart('\uFEFF').replace("\r\n", "\n").replace("\r", "\n")
        val blocks = text.split(Regex("\n{2,}"))
        val cues = ArrayList<CaptionCue>()
        var dropped = 0
        var truncated = false
        var sawAnyCueLine = false
        for (block in blocks) {
            val lines = block.lineSequence()
                .map { it.trimEnd() }
                .filterNot { it.isBlank() }
                .toList()
            if (lines.isEmpty()) continue
            if (lines.firstOrNull()?.startsWith("WEBVTT") == true ||
                lines.firstOrNull()?.startsWith("NOTE") == true
            ) continue
            // The timecode line is wherever the arrow is (index line first
            // in SRT, first line in VTT).
            val arrowIndex = lines.indexOfFirst { "-->" in it }
            if (arrowIndex < 0) {
                dropped += 1
                continue
            }
            sawAnyCueLine = true
            val times = parseWindow(lines[arrowIndex])
            val body = lines.drop(arrowIndex + 1)
                .joinToString("\n")
                .trim()
            if (times == null || body.isEmpty()) {
                dropped += 1
                continue
            }
            val (start, end) = times
            val clampedEnd = end.coerceAtMost(MemeProjectContract.MAX_DURATION_MS)
            val clampedStart = start.coerceIn(0L, MemeProjectContract.MAX_DURATION_MS)
            if (clampedEnd <= clampedStart) {
                dropped += 1
                continue
            }
            if (cues.size >= MemeProjectContract.MAX_OVERLAYS) {
                truncated = true
                continue
            }
            cues.add(
                CaptionCue(
                    startMs = clampedStart,
                    endMs = clampedEnd,
                    text = body.take(MemeProjectContract.MAX_TEXT_LENGTH),
                ),
            )
        }
        if (!sawAnyCueLine) {
            return Parsed(emptyList(), dropped, false, "No timed cues found in that file")
        }
        cues.sortBy { it.startMs }
        return Parsed(cues, dropped, truncated, null)
    }

    private fun parseWindow(line: String): Pair<Long, Long>? {
        val matches = timeRegex.findAll(line).toList()
        if (matches.size < 2) return null
        val start = matches[0].toMs() ?: return null
        val end = matches[1].toMs() ?: return null
        if (end <= start) return null
        return start to end
    }

    private fun MatchResult.toMs(): Long? {
        val (h, m, s, frac) = destructured
        val hours = h.ifBlank { "0" }.toLongOrNull() ?: return null
        val minutes = m.toLongOrNull() ?: return null
        val seconds = s.toLongOrNull() ?: return null
        val millis = (frac.padEnd(3, '0')).toLongOrNull() ?: return null
        if (minutes > 59 || seconds > 59) return null
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
    }

    /**
     * Where an imported cue sits: centered, bottom safe area; cues that
     * OVERLAP in time stagger up one deterministic row so both stay
     * readable ([overlapRow] 0/1 alternating).
     */
    fun placement(overlapRow: Int): Placement = Placement(
        x = 0.5f,
        y = if (overlapRow % 2 == 0) 0.86f else 0.74f,
    )
}
