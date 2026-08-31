package space.bitos.app.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.bitos.app.ui.theme.BitOSRadius
import space.bitos.app.ui.theme.bitOSColors
import space.bitos.core.design.DesignTokens

enum class AppButtonVariant { PRIMARY, ON_SURFACE, GHOST, DANGER }

/**
 * Heights clear the 48 dp touch target at MD+ (§2.6); SM is a compact
 * case whose interactive minimum Material still enforces.
 */
enum class AppButtonSize(val height: Dp, val horizontalPadding: Dp) {
    SM(40.dp, 16.dp),
    MD(48.dp, 20.dp),
    LG(56.dp, 24.dp),
}

/**
 * Press tracking against the raw interaction stream (version-stable:
 * `InteractionSource.interactions` + `PressInteraction` events).
 */
@Composable
private fun rememberPressed(interaction: MutableInteractionSource): Boolean {
    val pressed = remember { mutableStateOf(false) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> pressed.value = true
                is PressInteraction.Release,
                is PressInteraction.Cancel -> pressed.value = false
            }
        }
    }
    return pressed.value
}

/**
 * The app button (DESIGN_SYSTEM §8 + mockup `btn` family): filled /
 * inverted / outlined / danger-tinted, pill by default, pressed
 * scale-down (fast, standard easing), optional leading icon, loading
 * state that swaps the icon for a spinner, and 45% disabled alpha.
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.PRIMARY,
    size: AppButtonSize = AppButtonSize.MD,
    pill: Boolean = true,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    val colors = bitOSColors()
    val scheme = MaterialTheme.colorScheme
    val (container, content, borderColor) = when (variant) {
        AppButtonVariant.PRIMARY -> Triple(colors.primary, colors.onPrimary, Color.Transparent)
        AppButtonVariant.ON_SURFACE -> Triple(colors.textPrimary, colors.background, Color.Transparent)
        AppButtonVariant.GHOST -> Triple(colors.surface, colors.textPrimary, colors.border)
        AppButtonVariant.DANGER -> Triple(
            colors.error.copy(alpha = 0.12f),
            colors.errorText,
            colors.error.copy(alpha = 0.4f),
        )
    }
    val shape = if (pill) CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(BitOSRadius.md)
    val interaction = remember { MutableInteractionSource() }
    val pressed = rememberPressed(interaction)
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = BitOSMotion.standard(BitOSMotion.fast),
        label = "pressScale",
    )
    val interactive = enabled && !loading

    Row(
        modifier = modifier
            .heightIn(min = size.height)
            .scale(scale)
            .clip(shape)
            .background(container, shape)
            .then(if (borderColor != Color.Transparent) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .alpha(if (interactive) 1f else 0.45f)
            .clickable(interactionSource = interaction, indication = null, enabled = interactive, onClick = onClick)
            .padding(horizontal = size.horizontalPadding, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(if (size == AppButtonSize.SM) 14.dp else 18.dp),
                strokeWidth = 2.dp,
                color = content,
            )
        } else {
            leadingIcon?.invoke()
        }
        Text(
            text = text,
            style = if (size == AppButtonSize.SM) {
                MaterialTheme.typography.labelMedium
            } else {
                MaterialTheme.typography.labelLarge
            },
            color = content,
        )
    }
}

/** Compact icon-only action target: 48 dp square, same variants. */
@Composable
fun AppIconButton(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.GHOST,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    val colors = bitOSColors()
    val container = when (variant) {
        AppButtonVariant.PRIMARY -> colors.primary
        AppButtonVariant.ON_SURFACE -> colors.textPrimary
        AppButtonVariant.GHOST -> colors.surface
        AppButtonVariant.DANGER -> colors.error.copy(alpha = 0.12f)
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed = rememberPressed(interaction)
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = BitOSMotion.standard(BitOSMotion.fast),
        label = "pressScale",
    )
    Box(
        modifier = modifier
            .size(DesignTokens.MIN_TOUCH_TARGET_DP.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(container, CircleShape)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon() }
}
