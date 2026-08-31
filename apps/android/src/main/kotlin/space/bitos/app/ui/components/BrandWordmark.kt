package space.bitos.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import space.bitos.app.R
import space.bitos.app.ui.theme.LocalBitOSIsDark

/**
 * Official wordmark, theme-aware: `bitos_branding_light` (black art) on
 * the light shell, `bitos_branding_dark` (white art) on the dark one.
 *
 * BOTH variants are plain (non-qualified) drawables selected from
 * [LocalBitOSIsDark]: `drawable-night-*` resolution follows the SYSTEM
 * uiMode while the shell follows the persisted ThemeModeSetting, so a
 * forced-Light app on a dark system must not load `bitos_branding` at
 * all — it would resolve to the night (white) bucket. Tinting is not an
 * option either (the bolt's orange gradient). The system-qualified
 * `bitos_branding` pair remains for the launch chain (windowBackground /
 * v31 splash), where only the system mode is knowable.
 */
@Composable
fun BrandWordmark(
    height: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val isDark = LocalBitOSIsDark.current
    Image(
        painter = painterResource(
            if (isDark) R.drawable.bitos_branding_dark else R.drawable.bitos_branding_light
        ),
        contentDescription = contentDescription,
        modifier = modifier.height(height),
        contentScale = ContentScale.Fit,
    )
}
