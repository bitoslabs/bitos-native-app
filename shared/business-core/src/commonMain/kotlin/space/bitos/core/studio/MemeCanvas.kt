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
