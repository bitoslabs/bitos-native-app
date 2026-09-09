package space.bitos.core.studio

/**
 * Canvas rules (image/GIF modes): a project may pin its canvas to a ratio
 * preset and a background color instead of always taking the source
 * media's frame. Deterministic core for both natives — the wire carries
 * one additive `canvas` object (old readers ignore it); the stage
 * letterboxes media onto the canvas and the raster/video renderers fill
 * the background. VIDEO mode keeps the source frame in V1 (a re-frame is
 * a re-encode, not a crop) — the sheets say so instead of pretending.
 */
object MemeCanvas {

    /** `source` = the media's own aspect (default; wire stays implicit). */
    const val RATIO_SOURCE = "source"

    /** Ratio presets (id → w:h). Order is the pickers' display order. */
    val RATIOS: List<Pair<String, String>> = listOf(
        RATIO_SOURCE to "Source",
        "1:1" to "Square 1:1",
        "4:5" to "Portrait 4:5",
        "9:16" to "Story 9:16",
        "16:9" to "Wide 16:9",
    )

    private val ratioRegex = Regex("^\\d{1,2}:\\d{1,2}$")
    private val hexRegex = Regex("^#[0-9a-fA-F]{6}$")

    /** True for [RATIO_SOURCE] or a `w:h` preset with sane terms (1..99). */
    fun isValidRatio(id: String): Boolean {
        if (id == RATIO_SOURCE) return true
        if (!ratioRegex.matches(id)) return false
        val (w, h) = id.split(":").map { it.toInt() }
        return w in 1..99 && h in 1..99
    }

    /** True for a 6-digit hex color (`#rrggbb`, the wire form). */
    fun isValidBackground(hex: String): Boolean = hexRegex.matches(hex)

    // ── Blank-GIF timing (plan D3, additive canvas wire keys) ─────────

    /** Blank-GIF loop lengths the sheet offers (ms). */
    val BLANK_GIF_MS_choices = listOf(1_000L, 2_000L, 3_000L)

    /** Blank-GIF frame rates; 10 is the default. */
    val BLANK_GIF_FPS = setOf(10, 15)

    /** Hostile-input bounds (decode clamps to these, not the choices). */
    const val MIN_BLANK_GIF_MS = 500L
    const val MAX_BLANK_GIF_MS = 10_000L
    const val DEFAULT_BLANK_GIF_MS = 2_000L
    const val DEFAULT_BLANK_GIF_FPS = 10

    fun isValidBlankGifMs(ms: Long): Boolean = ms in MIN_BLANK_GIF_MS..MAX_BLANK_GIF_MS

    fun clampBlankGifMs(ms: Long): Long = ms.coerceIn(MIN_BLANK_GIF_MS, MAX_BLANK_GIF_MS)

    fun clampBlankGifFps(fps: Int): Int =
        if (fps in BLANK_GIF_FPS) fps else DEFAULT_BLANK_GIF_FPS

    /** Derived frame count for a blank loop (≥1, capped by the GIF asset cap). */
    fun blankGifFrameCount(secMs: Long, fps: Int): Int =
        ((clampBlankGifMs(secMs) * clampBlankGifFps(fps)) / 1000L).toInt()
            .coerceAtLeast(1)
            .coerceAtMost(60)

    /** Per-frame delay for a loop rate (never under the wire delay floor). */
    fun loopDelayMs(fps: Int): Int =
        (1000 / clampBlankGifFps(fps)).coerceAtLeast(MemeProjectContract.MIN_FRAME_DELAY_MS)

    // ── Video→GIF sampling bounds (plan D4, MST-081/082) ────────────

    const val VIDEO_GIF_FPS = 10
    const val VIDEO_GIF_MAX_SPAN_MS = 10_000L
    private const val VIDEO_GIF_MAX_FRAMES = 150

    /** True when the timeline is longer than the sampled span (say so). */
    fun videoGifSpanTrimmed(durationMs: Long): Boolean = durationMs > VIDEO_GIF_MAX_SPAN_MS

    fun videoGifFrameCount(durationMs: Long): Int {
        val span = minOf(durationMs.coerceAtLeast(1L), VIDEO_GIF_MAX_SPAN_MS)
        return ((span * VIDEO_GIF_FPS) / 1000L).toInt().coerceIn(1, VIDEO_GIF_MAX_FRAMES)
    }

    fun videoGifDelayMs(): Int = loopDelayMs(VIDEO_GIF_FPS)

    /** Parses `w:h` → (w, h); `source` → null (take the media's). */
    fun ratioTerms(id: String): Pair<Int, Int>? {
        if (!isValidRatio(id) || id == RATIO_SOURCE) return null
        val (w, h) = id.split(":").map { it.toInt() }
        return w to h
    }

    /** Fits a (w,h) box into the canvas ratio — the letterboxed media rect. */
    fun fitInRatio(
        mediaWidth: Int,
        mediaHeight: Int,
        ratioId: String,
    ): Pair<Int, Int> {
        val terms = ratioTerms(ratioId) ?: return mediaWidth to mediaHeight
        val (rw, rh) = terms
        val scale = minOf(
            mediaWidth.toFloat() / rw,
            mediaHeight.toFloat() / rh,
        )
        val w = (rw * scale).toInt().coerceAtLeast(1)
        val h = (rh * scale).toInt().coerceAtLeast(1)
        return w to h
    }
}
