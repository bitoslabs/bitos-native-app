package space.bitos.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.bitos.core.design.DesignTokens

/**
 * BitOS design system — Compose side of the shared token contract
 * (`space.bitos.core.design.DesignTokens`, DESIGN_SYSTEM.md §2).
 *
 * Every value here resolves from the shared contract so dark and light
 * ship together and stay in parity with the SwiftUI layer: token values
 * are owned once, per-mode *text* roles (links/errors/action colors) come
 * darkened for light surfaces, and the WCAG floors are enforced by
 * `DesignTokensTest` in the shared module.
 */
@Immutable
data class BitOSPalette(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceOverlay: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textLink: Color,
    val border: Color,
    val divider: Color,
    val primary: Color,
    val primaryLight: Color,
    val primaryDark: Color,
    val onPrimary: Color,
    val accent: Color,
    val accentText: Color,
    val success: Color,
    val successText: Color,
    val warning: Color,
    val warningText: Color,
    val error: Color,
    val errorText: Color,
    val info: Color,
    val infoText: Color,
    val like: Color,
    val repost: Color,
    val zap: Color,
    val reply: Color,
    val bookmark: Color,
) {
    /** Legacy compat surface (old `BitOSColors.primaryContainer`): brand at 20%. */
    val primaryContainer: Color get() = primary.copy(alpha = 0.2f)
}

private fun palette(tokens: DesignTokens.Palette) = BitOSPalette(
    background = Color(tokens.background),
    surface = Color(tokens.surface),
    surfaceElevated = Color(tokens.surfaceElevated),
    surfaceOverlay = Color(tokens.surfaceOverlay),
    textPrimary = Color(tokens.textPrimary),
    textSecondary = Color(tokens.textSecondary),
    textTertiary = Color(tokens.textTertiary),
    textLink = Color(tokens.textLink),
    border = Color(tokens.border),
    divider = Color(tokens.divider),
    primary = Color(tokens.primary),
    primaryLight = Color(tokens.primaryLight),
    primaryDark = Color(tokens.primaryDark),
    onPrimary = Color(tokens.onPrimary),
    accent = Color(tokens.accent),
    accentText = Color(tokens.accentText),
    success = Color(tokens.success),
    successText = Color(tokens.successText),
    warning = Color(tokens.warning),
    warningText = Color(tokens.warningText),
    error = Color(tokens.error),
    errorText = Color(tokens.errorText),
    info = Color(tokens.info),
    infoText = Color(tokens.infoText),
    like = Color(tokens.like),
    repost = Color(tokens.repost),
    zap = Color(tokens.zap),
    reply = Color(tokens.reply),
    bookmark = Color(tokens.bookmark),
)

/** Dark palette — product default (OLED media accuracy). */
val BitOSDarkColors: BitOSPalette = palette(DesignTokens.DARK)

/** Light palette — APP-023; text roles darkened to hold AA on white. */
val BitOSLightColors: BitOSPalette = palette(DesignTokens.LIGHT)

/**
 * Social/action colors for the active mode. Material's scheme carries the
 * structural roles; this local carries the roles the scheme has no slot
 * for (like/repost/zap/reply/bookmark, per-mode semantic text).
 */
val LocalBitOSColors = staticCompositionLocalOf { BitOSDarkColors }

/**
 * Active palette (APP-023 appearance adapter): resolves the current
 * [LocalBitOSColors] so every screen that reads `BitOSColors.*` follows
 * the persisted theme (light/dark/system) and accent choice live. Reads
 * must run in composition — same rule as Material's `MaterialTheme.colors`;
 * non-composable helpers take a `BitOSPalette` or call [bitOSColors] at
 * their call site.
 */
val BitOSColors: BitOSPalette
    @Composable get() = LocalBitOSColors.current

@Composable
fun bitOSColors(): BitOSPalette = LocalBitOSColors.current

@Immutable
object BitOSSpacing {
    val xs = DesignTokens.Spacing.XS.dp
    val sm = DesignTokens.Spacing.SM.dp
    val md = DesignTokens.Spacing.MD.dp
    val base = DesignTokens.Spacing.BASE.dp
    val lg = DesignTokens.Spacing.LG.dp
    val xl = DesignTokens.Spacing.XL.dp
    val xxl = DesignTokens.Spacing.XXL.dp
    val screen = DesignTokens.Spacing.SCREEN.dp
    val card = DesignTokens.Spacing.CARD.dp
    val avatarGap = DesignTokens.Spacing.AVATAR_GAP.dp
}

@Immutable
object BitOSRadius {
    val xs = DesignTokens.Radius.XS.dp
    val sm = DesignTokens.Radius.SM.dp
    val md = DesignTokens.Radius.MD.dp
    val lg = DesignTokens.Radius.LG.dp
    val xl = DesignTokens.Radius.XL.dp
    val pill = DesignTokens.Radius.PILL.dp
}

/** Avatar diameters (DESIGN_SYSTEM §2.3). */
@Immutable
object BitOSAvatarSize {
    val xs = DesignTokens.AvatarSize.XS.dp
    val sm = DesignTokens.AvatarSize.SM.dp
    val md = DesignTokens.AvatarSize.MD.dp
    val lg = DesignTokens.AvatarSize.LG.dp
    val xl = DesignTokens.AvatarSize.XL.dp
    val xxl = DesignTokens.AvatarSize.XXL.dp
}

private fun scheme(c: BitOSPalette, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = c.primary,
        onPrimary = c.onPrimary,
        primaryContainer = c.surfaceElevated,
        onPrimaryContainer = c.primary,
        secondary = c.accent,
        onSecondary = c.onPrimary,
        background = c.background,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.surfaceElevated,
        onSurfaceVariant = c.textSecondary,
        outline = c.border,
        outlineVariant = c.divider,
        error = c.error,
        onError = c.onPrimary,
    )
} else {
    lightColorScheme(
        primary = c.primary,
        onPrimary = c.onPrimary,
        primaryContainer = c.surfaceOverlay,
        onPrimaryContainer = c.primaryDark,
        secondary = c.accent,
        onSecondary = c.onPrimary,
        background = c.background,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.surfaceElevated,
        onSurfaceVariant = c.textSecondary,
        outline = c.border,
        outlineVariant = c.divider,
        error = c.error,
        onError = c.onPrimary,
    )
}

private fun role(role: DesignTokens.TextStyleRole) = androidx.compose.ui.text.TextStyle(
    fontSize = role.size.sp,
    fontWeight = FontWeight(role.weight),
    lineHeight = (role.size * role.lineHeight).sp,
    letterSpacing = role.letterSpacing.sp,
)

private val BitOSTypography = Typography(
    displayLarge = role(DesignTokens.Type.displayLarge),
    headlineLarge = role(DesignTokens.Type.headlineLarge),
    headlineMedium = role(DesignTokens.Type.headlineMedium),
    headlineSmall = role(DesignTokens.Type.headlineSmall),
    titleLarge = role(DesignTokens.Type.headlineSmall),
    titleMedium = role(DesignTokens.Type.labelLarge),
    bodyLarge = role(DesignTokens.Type.bodyLarge),
    bodyMedium = role(DesignTokens.Type.bodyMedium),
    bodySmall = role(DesignTokens.Type.bodySmall),
    labelLarge = role(DesignTokens.Type.labelLarge),
    labelMedium = role(DesignTokens.Type.labelMedium),
    labelSmall = role(DesignTokens.Type.labelSmall),
)

private val BitOSShapes = Shapes(
    extraSmall = RoundedCornerShape(BitOSRadius.xs),
    small = RoundedCornerShape(BitOSRadius.sm),
    medium = RoundedCornerShape(BitOSRadius.md),
    large = RoundedCornerShape(BitOSRadius.lg),
    extraLarge = RoundedCornerShape(BitOSRadius.xl),
)

/**
 * App theme. `darkTheme` follows the resolved settings ThemeModeSetting
 * (default DARK — the product default; SYSTEM resolves through
 * [androidx.compose.foundation.isSystemInDarkTheme] at the app shell).
 * `accentColorHex` applies the persisted accent palette choice
 * (`#RRGGBB`, already canonicalized by the shared settings rules) over
 * the brand primary.
 */
@Composable
fun BitOSTheme(
    darkTheme: Boolean = true,
    accentColorHex: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = remember(darkTheme, accentColorHex) {
        applyAccent(if (darkTheme) BitOSDarkColors else BitOSLightColors, accentColorHex)
    }
    CompositionLocalProvider(LocalBitOSColors provides colors) {
        MaterialTheme(
            colorScheme = scheme(colors, darkTheme),
            typography = BitOSTypography,
            shapes = BitOSShapes,
            content = content,
        )
    }
}

// ── Accent derivation (pure; shared-rule inputs, deterministic output) ──

/**
 * Applies the user's accent over a base palette: brand primary and its
 * light/dark variants re-point at the chosen color. Malformed hex falls
 * back to the base palette — a corrupt store must never crash theming.
 */
internal fun applyAccent(base: BitOSPalette, accentHex: String?): BitOSPalette {
    val argb = parseAccentArgb(accentHex) ?: return base
    return base.copy(
        primary = Color(argb),
        primaryLight = Color(mixToward(argb, towardWhite = true)),
        primaryDark = Color(mixToward(argb, towardWhite = false)),
    )
}

/** `#RRGGBB` (case-insensitive) → ARGB long; null when malformed. */
internal fun parseAccentArgb(hex: String?): Long? {
    if (hex == null || !hex.startsWith("#")) return null
    val digits = hex.substring(1)
    if (digits.length != 6 || digits.any { it.isDigit().not() && it.lowercaseChar() !in 'a'..'f' }) {
        return null
    }
    return 0xFF000000L or digits.toLong(16)
}

/** Mixes an ARGB color 30% toward white or black (legacy brand tints). */
internal fun mixToward(argb: Long, towardWhite: Boolean): Long {
    val target = if (towardWhite) 0xFFL else 0x00L
    fun channel(shift: Int): Long {
        val value = (argb shr shift) and 0xFFL
        val mixed = value + ((target - value) * 30L / 100L)
        return mixed and 0xFFL
    }
    return (0xFFL shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
