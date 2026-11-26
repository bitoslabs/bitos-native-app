package space.bitos.core.studio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

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

/**
 * Manual fine-tune over the look preset (EUI-02 reference taxonomy; v1
 * prototype `create-edit` FX triple still bri/con/sat). Composes ON TOP of
 * a look preset (pixel: look first, then the adjust chain) into the same
 * 4×5 matrix the rasterizers already burn in — preview and export stay
 * single-source.
 *
 * Only controls the one-matrix pipeline can render EXACTLY are modeled:
 * Tone = exposure (the v1 brightness field — a pure scale that keeps both
 * endpoints) · lightness (lerp to white/black, a distinct axis) ·
 * contrast;
 * Colour = saturation · warmth (R/B split) · tint (green↔magenta) · fade
 * (black-point lift). Highlights/shadows/sharpness/grain/vignette need
 * tone-curve or spatial rendering and stay OUT until a renderer supports
 * them (the spec's "available only when the renderer supports it" rule —
 * an honest absent control beats a lying slider).
 */
data class MemeAdjust(
    val brightness: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    /** Toward-white (+) / toward-black (−) shift; 0 = unchanged. */
    val lightness: Float = 0f,
    /** Warm (+R/−B) ↔ cool (−R/+B) axis; 0 = unchanged. */
    val warmth: Float = 0f,
    /** Magenta (+) ↔ green (−) axis; 0 = unchanged. */
    val tint: Float = 0f,
    /** Black-point lift / matte; 0 = unchanged. */
    val fade: Float = 0f,
) {
    val isDefault: Boolean
        get() = brightness == 1f && contrast == 1f && saturation == 1f &&
            lightness == 0f && warmth == 0f && tint == 0f && fade == 0f

    companion object {
        /** Prototype slider bounds: bri/con/exp 40–160 %, sat 0–200 %. */
        const val MIN_BRIGHTNESS = 0.4f
        const val MAX_BRIGHTNESS = 1.6f
        const val MIN_CONTRAST = 0.4f
        const val MAX_CONTRAST = 1.6f
        const val MIN_SATURATION = 0f
        const val MAX_SATURATION = 2f

        /** Signed axes (lightness/warmth/tint) and fade bounds. */
        const val MIN_SIGNED = -1f
        const val MAX_SIGNED = 1f
        const val MIN_FADE = 0f
        const val MAX_FADE = 1f

        /** Channel offset at the signed-axis full scale (≈ 12.5 % of 255). */
        internal const val AXIS_STEP = 32f

        /** Fade at full: scale 0.75 with the black point lifted to 45. */
        internal const val FADE_SCALE = 0.25f
        internal const val FADE_LIFT = 45f

        fun clamp(
            brightness: Float,
            contrast: Float,
            saturation: Float,
            lightness: Float = 0f,
            warmth: Float = 0f,
            tint: Float = 0f,
            fade: Float = 0f,
        ): MemeAdjust = MemeAdjust(
            if (brightness.isNaN()) 1f else brightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS),
            if (contrast.isNaN()) 1f else contrast.coerceIn(MIN_CONTRAST, MAX_CONTRAST),
            if (saturation.isNaN()) 1f else saturation.coerceIn(MIN_SATURATION, MAX_SATURATION),
            if (lightness.isNaN()) 0f else lightness.coerceIn(MIN_SIGNED, MAX_SIGNED),
            if (warmth.isNaN()) 0f else warmth.coerceIn(MIN_SIGNED, MAX_SIGNED),
            if (tint.isNaN()) 0f else tint.coerceIn(MIN_SIGNED, MAX_SIGNED),
            if (fade.isNaN()) 0f else fade.coerceIn(MIN_FADE, MAX_FADE),
        )

        /**
         * Lenient reader for the canonical all-keys adjust JSON
         * ([BusinessCoreBridge.memeAdjustOf] shape): missing/junk keys stay
         * neutral, values clamp, junk JSON degrades to all-neutral.
         */
        fun fromCanonical(json: String): MemeAdjust = try {
            val obj = Json.parseToJsonElement(json).jsonObject
            fun valueOf(key: String, default: Float): Float =
                (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull()
                    ?: default
            clamp(
                brightness = valueOf("bri", 1f),
                contrast = valueOf("con", 1f),
                saturation = valueOf("sat", 1f),
                lightness = valueOf("lig", 0f),
                warmth = valueOf("warm", 0f),
                tint = valueOf("tint", 0f),
                fade = valueOf("fade", 0f),
            )
        } catch (_: Exception) {
            MemeAdjust()
        }
    }
}

/**
 * The Adjust-section catalogue both platforms render from (`MemeTools`
 * pattern — labels, grouping, bounds and readouts live here, never
 * hand-rolled per platform; DESIGN_SYSTEM §0). One entry per matrix axis;
 * [EXPOSURE] reads the v1 brightness field (same pure-scale math).
 */
enum class MemeAdjustControl(
    val section: Section,
    val label: String,
    /** Canonical JSON key in `MemeAdjust.fromCanonical`/`memeAdjustOf`. */
    val wireKey: String,
    /** Semantic icon token both platforms resolve (visual parity pass). */
    val icon: String,
    private val get: (MemeAdjust) -> Float,
) {
    EXPOSURE(Section.TONE, "Exposure", "bri", "exposure", { it.brightness }),
    LIGHTNESS(Section.TONE, "Lightness", "lig", "lightness", { it.lightness }),
    CONTRAST(Section.TONE, "Contrast", "con", "contrast", { it.contrast }),
    SATURATION(Section.COLOUR, "Saturation", "sat", "saturation", { it.saturation }),
    WARMTH(Section.COLOUR, "Warmth", "warm", "warmth", { it.warmth }),
    TINT(Section.COLOUR, "Tint", "tint", "tint", { it.tint }),
    FADE(Section.COLOUR, "Fade", "fade", "fade", { it.fade });

    enum class Section(val label: String) { TONE("Tone"), COLOUR("Colour") }

    /** Current value of this control in [a]. */
    fun value(a: MemeAdjust): Float = get(a)

    /** [a] with this control set to [v] (data-class copy, one per axis). */
    fun with(a: MemeAdjust, v: Float): MemeAdjust = when (this) {
        EXPOSURE -> a.copy(brightness = v)
        LIGHTNESS -> a.copy(lightness = v)
        CONTRAST -> a.copy(contrast = v)
        SATURATION -> a.copy(saturation = v)
        WARMTH -> a.copy(warmth = v)
        TINT -> a.copy(tint = v)
        FADE -> a.copy(fade = v)
    }

    /** Slider bounds — exactly the shared clamp bounds. */
    val range: ClosedFloatingPointRange<Float>
        get() = when (this) {
            EXPOSURE -> MemeAdjust.MIN_BRIGHTNESS..MemeAdjust.MAX_BRIGHTNESS
            LIGHTNESS, WARMTH, TINT -> MemeAdjust.MIN_SIGNED..MemeAdjust.MAX_SIGNED
            CONTRAST -> MemeAdjust.MIN_CONTRAST..MemeAdjust.MAX_CONTRAST
            SATURATION -> MemeAdjust.MIN_SATURATION..MemeAdjust.MAX_SATURATION
            FADE -> MemeAdjust.MIN_FADE..MemeAdjust.MAX_FADE
        }

    /** The untouched value for this control. */
    val neutral: Float
        get() = if (this == EXPOSURE || this == CONTRAST || this == SATURATION) 1f else 0f

    fun isNonDefault(a: MemeAdjust): Boolean = value(a) != neutral

    /** Slider readout: "120%" for multipliers, "+30"/"-30" for signed axes. */
    fun text(v: Float): String {
        val pct = (v * 100).toInt()
        return when {
            neutral == 0f && pct > 0 -> "+$pct"
            neutral == 0f -> "$pct"
            else -> "$pct%"
        }
    }

    companion object {
        /**
         * The JSON native UIs decode to render the Adjust section:
         * `{"sections":[{"id","label"}],
         *   "controls":[{"id","section","label","min","max","neutral",
         *   "signed"}]}`. IDs are the enum names lowercased (stable tokens).
         */
        fun catalogJson(): String = buildJsonObject {
            put("sections", buildJsonArray {
                Section.entries.forEach { section ->
                    add(buildJsonObject {
                        put("id", section.name.lowercase())
                        put("label", section.label)
                    })
                }
            })
            put("controls", buildJsonArray {
                entries.forEach { control ->
                    add(buildJsonObject {
                        put("id", control.name.lowercase())
                        put("section", control.section.name.lowercase())
                        put("label", control.label)
                        put("min", control.range.start)
                        put("max", control.range.endInclusive)
                        put("neutral", control.neutral)
                        put("signed", control.neutral == 0f)
                        put("icon", control.icon)
                    })
                }
            })
        }.toString()
    }
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

    /**
     * Look preset + manual adjust composed into ONE 4×5 matrix: the pixel
     * takes the look chain first, then the v1 prototype order brightness →
     * contrast → saturation extended with the EUI-02 axes — lightness
     * (before contrast) and warmth → tint → fade (after saturation).
     * Neutral fields contribute exact identity matrices, so v1 triples
     * compose to the identical v1 matrix. Default adjust = the plain look
     * matrix.
     */
    fun adjustedMatrixFor(lookId: String?, adjust: MemeAdjust?): FloatArray {
        val look = lookOf(lookId)
        if (adjust == null || adjust.isDefault) return matrix(look)
        var composed = matrix(look)
        listOf(
            opMatrix(MemeLook.Filter(MemeLook.Op.BRIGHTNESS, adjust.brightness)),
            lightnessMatrix(adjust.lightness),
            opMatrix(MemeLook.Filter(MemeLook.Op.CONTRAST, adjust.contrast)),
            opMatrix(MemeLook.Filter(MemeLook.Op.SATURATE, adjust.saturation)),
            warmthMatrix(adjust.warmth),
            tintMatrix(adjust.tint),
            fadeMatrix(adjust.fade),
        ).forEach { op ->
            composed = multiply(op, composed)
        }
        return composed
    }

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

    /** Toward-white (+k) lerp `v·(1−k)+255k`; toward-black (−k) `v·(1+k)`. */
    private fun lightnessMatrix(k: Float): FloatArray =
        if (k >= 0f) scaleTranslate(1f - k, 255f * k) else scaleTranslate(1f + k, 0f)

    /** Warm (+k): R gains, B loses; cool (−k) mirrored. G untouched. */
    private fun warmthMatrix(k: Float): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, MemeAdjust.AXIS_STEP * k,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, -MemeAdjust.AXIS_STEP * k,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Magenta (+k): G loses; green (−k): G gains. R/B untouched. */
    private fun tintMatrix(k: Float): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, -MemeAdjust.AXIS_STEP * k,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Fade: black point lifts to [MemeAdjust.FADE_LIFT]·k, scale softens. */
    private fun fadeMatrix(k: Float): FloatArray =
        scaleTranslate(
            1f - MemeAdjust.FADE_SCALE * k,
            MemeAdjust.FADE_LIFT * k,
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
