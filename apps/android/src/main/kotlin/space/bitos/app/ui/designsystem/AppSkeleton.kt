package space.bitos.app.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.bitos.app.ui.components.HexShape
import space.bitos.app.ui.theme.BitOSRadius
import space.bitos.app.ui.theme.bitOSColors

/**
 * Skeleton block (§2.5 "Skeletons for every list/grid"): surface→overlay
 * shimmer sweep driven by [rememberShimmerPhase]. Stack freely — the
 * convenience rows below cover the common list/grid cases.
 */
@Composable
fun AppSkeleton(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = BitOSRadius.sm,
    height: Dp? = null,
) {
    val colors = bitOSColors()
    val phase = rememberShimmerPhase()
    val sweep = 1600f
    val brush = Brush.linearGradient(
        colors = listOf(colors.surfaceElevated, colors.surfaceOverlay, colors.surfaceElevated),
        start = Offset(phase * sweep - sweep / 2, 0f),
        end = Offset(phase * sweep - sweep / 2 + sweep / 3, 320f),
    )
    Box(
        modifier = modifier
            .then(if (height != null) Modifier.height(height) else Modifier)
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush),
    )
}

/** Two-line list-row skeleton with a circular leading glyph. */
@Composable
fun AppSkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppSkeleton(modifier = Modifier.size(40.dp), cornerRadius = 999.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            AppSkeleton(modifier = Modifier.fillMaxWidth(0.42f), height = 12.dp)
            AppSkeleton(modifier = Modifier.fillMaxWidth(0.68f), height = 10.dp)
        }
    }
}

/** Grid tile skeleton for explore/mosaic loading states. */
@Composable
fun AppSkeletonTile(modifier: Modifier = Modifier) {
    AppSkeleton(
        modifier = modifier.fillMaxWidth().height(220.dp),
        cornerRadius = BitOSRadius.md,
    )
}

/**
 * Matched empty state (§2.5): hex icon plate + title + one-line message +
 * a single primary CTA — the "explain why and offer one primary next
 * action" rule from ux-ui-flows §3.
 */
@Composable
fun AppEmptyState(
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    icon: @Composable () -> Unit = { DefaultEmptyIcon() },
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = bitOSColors().textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, shape = CircleShape) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun DefaultEmptyIcon() {
    val colors = bitOSColors()
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(HexShape())
            .background(colors.primary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "⬡",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.primaryDark,
        )
    }
}
