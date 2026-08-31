package space.bitos.core.design

import kotlin.math.pow

/**
 * Versioned design-token contract (DESIGN_SYSTEM.md §2, APP-022/023).
 *
 * The canonical *values* for both appearance modes live here once; the
 * SwiftUI layer (`apps/ios/BitOS/DesignSystem/BitOSTheme.swift`) and the
 * Compose layer (`apps/android/.../ui/theme/Theme.kt`) mirror them into
 * platform types. Parity is enforced by `DesignTokensTest` (contrast
 * floors, completeness) plus the native adapter-contract tests.
 *
 * Rules that make this more than a constant dump:
 *
 *  • two complete palettes (dark default, light secondary) with per-mode
 *    *text* variants for semantic colors — the dark accent fails AA as
 *    text on light surfaces, so light mode gets darkened text roles;
 *  • a WCAG 2.1 contrast engine (`relativeLuminance`/`contrastRatio`) the
 *    tests use to lock every text/surface pair to §2.6 floors;
 *  • the hex identity geometry (web `.hex-clip` parity) as fractions so
 *    iOS, Android and web draw the identical flat-top hexagon;
 *  • motion/touch-target/avatar scales both platforms must honor.
 *
 * The schema is versioned: adding a token bumps [SCHEMA_VERSION]; unknown
 * values must never crash an adapter (same rule as SettingsContract).
 */
object DesignTokens {
    const val SCHEMA_VERSION = 1

    /** Appearance modes the palette resolves for. */
    enum class Mode { DARK, LIGHT }

    /**
     * One complete set of semantic colors. Values are ARGB `Long`s with
     * full alpha (0xFF______). "Text" suffixed roles are guaranteed to
     * pass AA on `surface`/`background` in their mode (see tests).
     */
    data class Palette(
        val background: Long,
        val surface: Long,
        val surfaceElevated: Long,
        val surfaceOverlay: Long,
        val textPrimary: Long,
        val textSecondary: Long,
        val textTertiary: Long,
        val textLink: Long,
        val border: Long,
        val divider: Long,
        val primary: Long,
        val primaryLight: Long,
        val primaryDark: Long,
        val onPrimary: Long,
        val accent: Long,
        val accentText: Long,
        val success: Long,
        val successText: Long,
        val warning: Long,
        val warningText: Long,
        val error: Long,
        val errorText: Long,
        val info: Long,
        val infoText: Long,
        val like: Long,
        val repost: Long,
        val zap: Long,
        val reply: Long,
        val bookmark: Long,
    ) {
        /** Every token value, for opacity/completeness checks without reflection. */
        fun values(): List<Long> = listOf(
            background, surface, surfaceElevated, surfaceOverlay,
            textPrimary, textSecondary, textTertiary, textLink, border, divider,
            primary, primaryLight, primaryDark, onPrimary, accent, accentText,
            success, successText, warning, warningText, error, errorText,
            info, infoText, like, repost, zap, reply, bookmark,
        )
    }

    /** Dark theme (default — OLED media accuracy, DESIGN_SYSTEM §2.1). */
    val DARK = Palette(
        background = 0xFF0A0A0F,
        surface = 0xFF12121A,
        surfaceElevated = 0xFF1A1A26,
        surfaceOverlay = 0xFF22222E,
        textPrimary = 0xFFF8F8FF,
        textSecondary = 0xFF9CA3AF,
        textTertiary = 0xFF6B7280,
        textLink = 0xFFF7931A,
        border = 0xFF2A2A3A,
        divider = 0xFF1F1F2E,
        primary = 0xFFF7931A,
        primaryLight = 0xFFF9A84B,
        primaryDark = 0xFFD4790F,
        onPrimary = 0xFF1A1000,
        accent = 0xFF06B6D4,
        accentText = 0xFF06B6D4,
        success = 0xFF10B981,
        successText = 0xFF10B981,
        warning = 0xFFF59E0B,
        warningText = 0xFFF59E0B,
        error = 0xFFEF4444,
        errorText = 0xFFEF4444,
        info = 0xFF3B82F6,
        infoText = 0xFF3B82F6,
        like = 0xFFEC4899,
        repost = 0xFF10B981,
        zap = 0xFFF59E0B,
        reply = 0xFF3B82F6,
        bookmark = 0xFFF7931A,
    )

    /**
     * Light theme. Surfaces follow DESIGN_SYSTEM §2.1 with three reviewed
     * deviations, each locked by tests:
     *  • textSecondary 0x4B5563 and tertiary 0x717684 (spec's 0x6B7280 /
     *    0x9CA3AF miss the AA / 3:1 floors on near-white surfaces);
     *  • semantic/social *text-and-icon* roles darkened (700-range) so
     *    links, errors and action counts stay readable on white.
     */
    val LIGHT = Palette(
        background = 0xFFFAFAFC,
        surface = 0xFFFFFFFF,
        surfaceElevated = 0xFFF5F5F7,
        surfaceOverlay = 0xFFEEEEF0,
        textPrimary = 0xFF111827,
        textSecondary = 0xFF4B5563,
        textTertiary = 0xFF717684,
        textLink = 0xFFB45309,
        border = 0xFFE5E7EB,
        divider = 0xFFF3F4F6,
        primary = 0xFFF7931A,
        primaryLight = 0xFFF9A84B,
        primaryDark = 0xFFD4790F,
        onPrimary = 0xFF1A1000,
        accent = 0xFF06B6D4,
        accentText = 0xFF0E7490,
        success = 0xFF10B981,
        successText = 0xFF047857,
        warning = 0xFFF59E0B,
        warningText = 0xFFB45309,
        error = 0xFFEF4444,
        errorText = 0xFFB91C1C,
        info = 0xFF3B82F6,
        infoText = 0xFF1D4ED8,
        like = 0xFFBE185D,
        repost = 0xFF047857,
        zap = 0xFFB45309,
        reply = 0xFF1D4ED8,
        bookmark = 0xFFB45309,
    )

    fun resolve(mode: Mode): Palette = when (mode) {
        Mode.DARK -> DARK
        Mode.LIGHT -> LIGHT
    }

    // ── Typography (Inter; JetBrains Mono for keys/ids/sats) ───────────

    /** `lineHeight` is a ×size multiplier; `letterSpacing` in points at 1×. */
    data class TextStyleRole(
        val size: Int,
        val weight: Int,
        val lineHeight: Double,
        val letterSpacing: Double,
    )

    object Type {
        val displayLarge = TextStyleRole(32, 700, 1.2, -0.5)
        val headlineLarge = TextStyleRole(24, 700, 1.3, -0.3)
        val headlineMedium = TextStyleRole(20, 600, 1.35, -0.2)
        val headlineSmall = TextStyleRole(18, 600, 1.4, 0.0)
        val bodyLarge = TextStyleRole(16, 400, 1.5, 0.0)
        val bodyMedium = TextStyleRole(14, 400, 1.5, 0.0)
        val bodySmall = TextStyleRole(12, 400, 1.5, 0.0)
        val labelLarge = TextStyleRole(14, 600, 1.4, 0.1)
        val labelMedium = TextStyleRole(12, 500, 1.4, 0.2)
        val labelSmall = TextStyleRole(10, 500, 1.4, 0.3)
        val menuItem = TextStyleRole(13, 600, 1.4, 0.0)
        val mono = TextStyleRole(13, 400, 1.6, 0.5)
    }

    // ── Geometry (4-pt grid) ────────────────────────────────────────────

    object Spacing {
        const val XS = 4
        const val SM = 8
        const val MD = 12
        const val BASE = 16
        const val LG = 20
        const val XL = 24
        const val XXL = 32
        const val XXXL = 48
        const val SCREEN = XL
        const val CARD = BASE
        const val SECTION_GAP = XXL
        const val LIST_ITEM = SM
        const val ICON_TEXT = SM
        const val AVATAR_GAP = MD
    }

    object Radius {
        const val XS = 4
        const val SM = 8
        const val MD = 12
        const val LG = 16
        const val XL = 20
        const val PILL = 999
    }

    /** Avatar diameters (DESIGN_SYSTEM §2.3). */
    object AvatarSize {
        const val XS = 24
        const val SM = 32
        const val MD = 40
        const val LG = 56
        const val XL = 80
        const val XXL = 120
    }

    /** Minimum touch targets (§2.6): iOS points / Android dp. */
    const val MIN_TOUCH_TARGET_PT = 44
    const val MIN_TOUCH_TARGET_DP = 48

    /**
     * Flat-top hexagon fractions — CSS `.hex-clip`
     * `polygon(25% 6.7%, 75% 6.7%, 100% 50%, 75% 93.3%, 25% 93.3%, 0% 50%)`
     * parity: a *regular* hexagon touching the left/right edges with the
     * 6.7% vertical inset, not one stretched to fill the square.
     */
    const val HEX_VERTEX_X_INSET = 0.25
    const val HEX_VERTEX_Y_INSET = 0.067

    // ── Motion (§2.4) ───────────────────────────────────────────────────

    /** Milliseconds. */
    object Duration {
        const val INSTANT = 100
        const val FAST = 200
        const val NORMAL = 300
        const val SLOW = 500
        const val EMPHASIS = 800
    }

    enum class Curve { STANDARD, ENTER, EXIT, BOUNCE, DECELERATE }

    /**
     * Like scale-bounce parameters (§2.4): 1→1.3→1 over 300 ms elastic.
     * Components own their platform animation; these keep the numbers one
     * source of truth.
     */
    const val LIKE_SCALE_PEAK = 1.3

    // ── WCAG 2.1 contrast engine (§2.6 gate) ────────────────────────────

    /** sRGB channel → linear-light (IEC 61966-2-1). */
    private fun linearChannel(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** Relative luminance of an ARGB color (alpha ignored). */
    fun relativeLuminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * linearChannel(r) + 0.7152 * linearChannel(g) + 0.0722 * linearChannel(b)
    }

    /** WCAG contrast ratio in [1, 21]. */
    fun contrastRatio(a: Long, b: Long): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** AA floor: 4.5:1 body text, 3:1 large text (≥18pt regular / 14pt bold). */
    fun meetsAA(foreground: Long, background: Long, largeText: Boolean = false): Boolean {
        val floor = if (largeText) 3.0 else 4.5
        return contrastRatio(foreground, background) >= floor
    }
}
