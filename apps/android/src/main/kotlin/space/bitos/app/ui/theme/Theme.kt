package space.bitos.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * BitOS design tokens. Ported 1:1 from the Flutter/web product tokens
 * (app_colors.dart / app_spacing.dart) so native surfaces are visually the
 * same product: bitcoin-orange primary on near-black surfaces, semantic
 * social-action colors.
 */
@Immutable
object BitOSColors {
    val background = Color(0xFF0A0A0F)
    val surface = Color(0xFF12121A)
    val surfaceElevated = Color(0xFF1A1A26)
    val surfaceOverlay = Color(0xFF22222E)

    val primary = Color(0xFFF7931A)
    val primaryContainer = Color(0x33F7931A)
    val accent = Color(0xFF06B6D4)

    val textPrimary = Color(0xFFF8F8FF)
    val textSecondary = Color(0xFF9CA3AF)
    val textTertiary = Color(0xFF6B7280)

    val success = Color(0xFF10B981)
    val warning = Color(0xFFF59E0B)
    val error = Color(0xFFEF4444)

    val like = Color(0xFFEC4899)
    val repost = Color(0xFF10B981)
    val zap = Color(0xFFF59E0B)
    val reply = Color(0xFF3B82F6)
    val bookmark = Color(0xFFF7931A)

    val border = Color(0xFF2A2A3A)
    val divider = Color(0xFF1F1F2E)
}

@Immutable
object BitOSSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val base = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val screen = xl
    val avatarGap = md
}

private val BitOSColorScheme = darkColorScheme(
    primary = BitOSColors.primary,
    onPrimary = Color(0xFF1A1000),
    primaryContainer = BitOSColors.surfaceElevated,
    onPrimaryContainer = BitOSColors.primary,
    secondary = BitOSColors.accent,
    onSecondary = Color(0xFF00232B),
    background = BitOSColors.background,
    onBackground = BitOSColors.textPrimary,
    surface = BitOSColors.surface,
    onSurface = BitOSColors.textPrimary,
    surfaceVariant = BitOSColors.surfaceElevated,
    onSurfaceVariant = BitOSColors.textSecondary,
    outline = BitOSColors.border,
    error = BitOSColors.error,
)

private val BitOSTypography = Typography(
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.W700, fontSize = 20.sp),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.W600),
    bodyMedium = Typography().bodyMedium.copy(lineHeight = 20.sp),
    bodySmall = Typography().bodySmall.copy(lineHeight = 16.sp),
    labelMedium = Typography().labelMedium.copy(fontWeight = FontWeight.W500),
    labelSmall = Typography().labelSmall.copy(fontWeight = FontWeight.W500, fontSize = 10.sp, letterSpacing = 0.3.sp),
)

private val BitOSShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun BitOSTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BitOSColorScheme,
        typography = BitOSTypography,
        shapes = BitOSShapes,
        content = content,
    )
}
