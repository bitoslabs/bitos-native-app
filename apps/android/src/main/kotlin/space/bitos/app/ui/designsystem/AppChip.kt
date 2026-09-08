package space.bitos.app.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import space.bitos.app.ui.theme.BitOSRadius
import space.bitos.app.ui.theme.bitOSColors

/**
 * Filter/tag chip (mockup `chip` family): 32 dp tall, surface + border;
 * `active` inverts to text-on-primary; optional remove ✕ for editable
 * tag rows; optional brand tint for zap/amount chips; optional `tint`
 * for semantic text colors (e.g. accent hashtags).
 */
@Composable
fun AppChip(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    brand: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val colors = bitOSColors()
    val container = when {
        active -> colors.textPrimary
        brand -> colors.primary.copy(alpha = 0.14f)
        else -> colors.surface
    }
    val content = when {
        tint != null -> tint
        active -> colors.background
        brand -> colors.primaryDark
        else -> colors.textSecondary
    }
    val border = when {
        active -> Color.Transparent
        brand -> colors.primary.copy(alpha = 0.4f)
        else -> colors.border
    }
    val shape = CircleShape

    Row(
        modifier = modifier
            .height(32.dp)
            .clip(shape)
            .background(container, shape)
            .then(if (border != Color.Transparent) Modifier.border(1.dp, border, shape) else Modifier)
            .then(if (onClick != null && enabled) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(start = 12.dp, end = if (onRemove != null) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
        )
        if (onRemove != null) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Remove $text",
                tint = content,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove)
                    .padding(4.dp),
            )
        }
    }
}
