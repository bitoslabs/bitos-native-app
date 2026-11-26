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
// ── Crop treatment (EUI-02 reference spec §3) ───────────────────────

/**
 * One non-destructive crop treatment: ratio rides `canvasRatio` (the
 * existing key), and [MemeCrop] carries the direct-manipulation axes.
 * ALL-DEFAULT = untouched media (and a pinned ratio with no crop keeps
 * the v1 letterbox treatment — old projects never silently re-frame).
 * Rotation is CLOCKWISE degrees; flip mirrors left-right in the OUTPUT
 * frame; pan is normalized (−1 = left/top edge aligned, +1 = right/
 * bottom) with slack only where zoom overflows the frame.
 */
data class MemeCrop(
    val rotationDeg: Int = 0,
    val flipH: Boolean = false,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    val isDefault: Boolean get() = this == MemeCrop()

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 3f
        const val MIN_PAN = -1f
        const val MAX_PAN = 1f

        /** Junk degrades to the nearest quarter turn. */
        private fun nearestQuarter(deg: Int): Int {
            val r = ((deg % 360) + 360) % 360
            return when {
                r in 45 until 135 -> 90
                r in 135 until 225 -> 180
                r in 225 until 315 -> 270
                else -> 0
            }
        }

        fun clamp(
            rotationDeg: Int,
            flipH: Boolean,
            zoom: Float,
            panX: Float,
            panY: Float,
        ): MemeCrop = MemeCrop(
            nearestQuarter(rotationDeg),
            flipH,
            if (zoom.isNaN()) 1f else zoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
            if (panX.isNaN()) 0f else panX.coerceIn(MIN_PAN, MAX_PAN),
            if (panY.isNaN()) 0f else panY.coerceIn(MIN_PAN, MAX_PAN),
        )
    }
}

/**
 * Where the media goes inside the output frame — the ONE placement the
 * stage previews and every raster/GIF burn draws. `cover = false` is
 * the v1 letterbox treatment (media contained, centered); renderers
 * keep their existing code path for it.
 */
data class Placement(
    val frameWidth: Int,
    val frameHeight: Int,
    /** Post-rotation media size × zoom, in frame px. */
    val drawWidth: Float,
    val drawHeight: Float,
    /** Top-left of the rotated media rect in frame px. */
    val drawX: Float,
    val drawY: Float,
    val rotationDeg: Int,
    val flipH: Boolean,
    val cover: Boolean,
)

object MemeCanvas {

    /** `source` = the media's own aspect (default; wire stays implicit). */
    const val RATIO_SOURCE = "source"

    /** Ratio presets (id → w:h). Order is the pickers' display order. */
    val RATIOS: List<Pair<String, String>> = listOf(
        RATIO_SOURCE to "Source",
        "1:1" to "Square 1:1",
        "4:5" to "Portrait 4:5",
        "3:4" to "Portrait 3:4",
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

    /**
     * The placement for a source frame under a ratio + crop — the ONE
     * geometry the stage previews and every raster/GIF burn draws.
     *
     * Tri-state: [MemeCrop] null = legacy letterbox when a ratio is pinned
     * (v1 projects never silently re-frame), untouched media otherwise;
     * non-null = the COVER treatment (even untouched — picking a ratio in
     * the crop sheet means "crop to fill"): the frame takes the ratio at
     * the rotated source's window scale, the media draws at zoom× its
     * rotated size centered plus the clamped pan, and everything outside
     * the frame crops away.
     */
    fun placement(
        mediaWidth: Int,
        mediaHeight: Int,
        ratioId: String?,
        crop: MemeCrop?,
    ): Placement {
        val rotation = crop?.rotationDeg ?: 0
        val rotated = when (rotation) {
            90, 270 -> mediaHeight to mediaWidth
            else -> mediaWidth to mediaHeight
        }
        val hasRatio = ratioId != null && isValidRatio(ratioId) && ratioId != RATIO_SOURCE
        if (crop == null) {
            if (!hasRatio) {
                return Placement(
                    frameWidth = mediaWidth,
                    frameHeight = mediaHeight,
                    drawWidth = mediaWidth.toFloat(),
                    drawHeight = mediaHeight.toFloat(),
                    drawX = 0f,
                    drawY = 0f,
                    rotationDeg = 0,
                    flipH = false,
                    cover = false,
                )
            }
            // v1 letterbox: media scaled to fit the pinned canvas, centered.
            val (fw, fh) = fitInRatio(mediaWidth, mediaHeight, ratioId!!)
            val scale = minOf(
                fw.toFloat() / mediaWidth,
                fh.toFloat() / mediaHeight,
            )
            val drawW = mediaWidth * scale
            val drawH = mediaHeight * scale
            return Placement(
                frameWidth = fw,
                frameHeight = fh,
                drawWidth = drawW,
                drawHeight = drawH,
                drawX = (fw - drawW) / 2f,
                drawY = (fh - drawH) / 2f,
                rotationDeg = 0,
                flipH = false,
                cover = false,
            )
        }
        val terms = if (hasRatio) ratioTerms(ratioId!!) else null
        val (rw, rh) = terms ?: rotated
        // The frame is the ratio window INSIDE the rotated media (no
        // upscaling — the visible window stays at source resolution).
        val windowScale = minOf(
            rotated.first.toFloat() / rw,
            rotated.second.toFloat() / rh,
        )
        val frameW = (rw * windowScale).toInt().coerceAtLeast(1)
        val frameH = (rh * windowScale).toInt().coerceAtLeast(1)
        val drawW = rotated.first * crop.zoom
        val drawH = rotated.second * crop.zoom
        val baseX = (frameW - drawW) / 2f
        val baseY = (frameH - drawH) / 2f
        val slackX = maxOf(0f, -baseX)
        val slackY = maxOf(0f, -baseY)
        return Placement(
            frameWidth = frameW,
            frameHeight = frameH,
            drawWidth = drawW,
            drawHeight = drawH,
            drawX = baseX + crop.panX * slackX,
            drawY = baseY + crop.panY * slackY,
            rotationDeg = rotation,
            flipH = crop.flipH,
            cover = true,
        )
    }
}
