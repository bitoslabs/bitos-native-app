package space.bitos.core.studio

/**
 * Meme "looks" — one-tap color grades applied to the SOURCE MEDIA, never
 * the captions (web `meme/look.ts` port, plan MST-043). Web renders one
 * CSS filter chain for both preview and export; native can't run CSS, so
 * each look ALSO carries a parsed op list and the engine composes it into
 * ONE 4×5 color matrix (row-major R/G/B/A rows, 5th column = translate,
 * Android `ColorMatrix` / iOS `CIColorMatrix` layout — the composite is
 * applied once, identical math on both platforms).
 *
 * Ids (never raw CSS) ride the project wire; unknown ids degrade to
 * `none`. CSS-filter `blur(px)` (dream) is carried as [MemeLook.blurPx]
 * for platforms that support it — V1 rasterizers apply the color matrix
 * only (documented caveat; the softness is cosmetic, the grade survives).
 */
data class MemeLook(
    val id: String,
    val label: String,
    /** Web-parity CSS chain (the pinned interop string, display only). */
    val css: String,
    val filters: List<Filter>,
    /** Optional pre-blur in px before the color matrix (0 = none). */
    val blurPx: Float = 0f,
) {
    enum class Op { GRAYSCALE, SEPIA, HUE_ROTATE, SATURATE, CONTRAST, BRIGHTNESS, INVERT }

    data class Filter(val op: Op, val amount: Float)
}

object MemeLooks {

    const val NONE = "none"

    /** The 8 web presets (look.ts), verbatim values. */
    val ALL: List<MemeLook> = listOf(
        MemeLook(NONE, "None", "none", emptyList()),
        look("mono", "B&W", "grayscale(1)", MemeLook.Filter(MemeLook.Op.GRAYSCALE, 1f)),
        look(
            "noir", "Noir", "grayscale(1) contrast(1.35) brightness(0.92)",
            MemeLook.Filter(MemeLook.Op.GRAYSCALE, 1f),
            MemeLook.Filter(MemeLook.Op.CONTRAST, 1.35f),
            MemeLook.Filter(MemeLook.Op.BRIGHTNESS, 0.92f),
        ),
        look(
            "sepia", "Sepia", "sepia(0.85) saturate(1.2)",
            MemeLook.Filter(MemeLook.Op.SEPIA, 0.85f),
            MemeLook.Filter(MemeLook.Op.SATURATE, 1.2f),
        ),
        look(
            "vhs", "VHS", "hue-rotate(-12deg) saturate(1.6) contrast(1.12)",
            MemeLook.Filter(MemeLook.Op.HUE_ROTATE, -12f),
            MemeLook.Filter(MemeLook.Op.SATURATE, 1.6f),
            MemeLook.Filter(MemeLook.Op.CONTRAST, 1.12f),
        ),
        look(
            "deepfry", "Deep fry", "saturate(3.2) contrast(1.6)",
            MemeLook.Filter(MemeLook.Op.SATURATE, 3.2f),
            MemeLook.Filter(MemeLook.Op.CONTRAST, 1.6f),
        ),
        look(
            "dream", "Dream", "blur(1.2px) brightness(1.12) saturate(1.25)",
            MemeLook.Filter(MemeLook.Op.BRIGHTNESS, 1.12f),
            MemeLook.Filter(MemeLook.Op.SATURATE, 1.25f),
            blurPx = 1.2f,
        ),
        look("invert", "Invert", "invert(1)", MemeLook.Filter(MemeLook.Op.INVERT, 1f)),
    )

    private fun look(id: String, label: String, css: String, vararg filters: MemeLook.Filter, blurPx: Float = 0f) =
        MemeLook(id, label, css, filters.toList(), blurPx)

    /** Tolerant reader for wire/draft data — unknown ⇒ [NONE] (never throws). */
    fun lookOf(raw: String?): MemeLook =
        ALL.firstOrNull { it.id == raw } ?: ALL.first()

    /** Normalized wire value: unknown/null/none → null (missing = none). */
    fun normalize(raw: String?): String? {
        val look = lookOf(raw)
        return if (look.id == NONE) null else look.id
    }

    /**
     * Composes a look's chain into one 4×5 matrix. CSS applies filters
     * left-to-right on the pixel, so the RIGHT-most filter's matrix comes
     * first in the multiplication: result = mₙ × … × m₁.
     */
    fun matrix(look: MemeLook): FloatArray {
        var composed = identity()
        look.filters.forEach { filter ->
            composed = multiply(opMatrix(filter), composed)
        }
        return composed
    }

    /** Convenience: the composed matrix for a wire id (none = identity). */
    fun matrixFor(lookId: String?): FloatArray = matrix(lookOf(lookId))

    // ── CSS filter-spec matrices (values in 0..255 space) ───────────────

    internal fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    internal fun opMatrix(filter: MemeLook.Filter): FloatArray = when (filter.op) {
        // x·M + (1−x)·I per the CSS filter-effects spec (blend with identity).
        MemeLook.Op.GRAYSCALE -> blend(filter.amount, floatArrayOf(
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))

        MemeLook.Op.SEPIA -> blend(filter.amount, floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))

        MemeLook.Op.INVERT -> blend(filter.amount, floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))

        MemeLook.Op.SATURATE -> floatArrayOf(
            0.213f + 0.787f * filter.amount, 0.715f - 0.715f * filter.amount, 0.072f - 0.072f * filter.amount, 0f, 0f,
            0.213f - 0.213f * filter.amount, 0.715f + 0.285f * filter.amount, 0.072f - 0.072f * filter.amount, 0f, 0f,
            0.213f - 0.213f * filter.amount, 0.715f - 0.715f * filter.amount, 0.072f + 0.928f * filter.amount, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )

        MemeLook.Op.HUE_ROTATE -> {
            val radians = kotlin.math.PI * filter.amount / 180f
            val c = kotlin.math.cos(radians).toFloat()
            val s = kotlin.math.sin(radians).toFloat()
            floatArrayOf(
                0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0f, 0f,
                0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0f, 0f,
                0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        }

        // v' = (v − 0.5)·c + 0.5 in 0..1 ⇒ scale c, translate (0.5 − 0.5c)·255.
        MemeLook.Op.CONTRAST -> scaleTranslate(
            filter.amount,
            (0.5f - 0.5f * filter.amount) * 255f,
        )

        MemeLook.Op.BRIGHTNESS -> scaleTranslate(filter.amount, 0f)
    }

    private fun blend(amount: Float, spec: FloatArray): FloatArray {
        val x = amount.coerceIn(0f, 1f)
        return FloatArray(20) { index -> x * spec[index] + (1f - x) * identity()[index] }
    }

    private fun scaleTranslate(scale: Float, translate: Float): FloatArray = floatArrayOf(
        scale, 0f, 0f, 0f, translate,
        0f, scale, 0f, 0f, translate,
        0f, 0f, scale, 0f, translate,
        0f, 0f, 0f, 1f, 0f,
    )

    /** 4×5 affine multiply: `a × b` (both row-major, 5th column = offset). */
    internal fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                var sum = if (col == 4) a[row * 5 + 4] else 0f
                for (k in 0 until 4) {
                    val aVal = a[row * 5 + k]
                    sum += if (col == 4) aVal * b[k * 5 + 4] else aVal * b[k * 5 + col]
                }
                out[row * 5 + col] = sum
            }
        }
        return out
    }

    /** Applies a composed matrix to one pixel — the pure test/preview path. */
    fun applyToPixel(matrix: FloatArray, r: Int, g: Int, b: Int): IntArray {
        fun channel(row: Int, c1: Int, c2: Int, c3: Int): Int =
            (matrix[row * 5] * c1 + matrix[row * 5 + 1] * c2 + matrix[row * 5 + 2] * c3 + matrix[row * 5 + 4])
                .toInt().coerceIn(0, 255)
        return intArrayOf(
            channel(0, r, g, b),
            channel(1, r, g, b),
            channel(2, r, g, b),
        )
    }
}
