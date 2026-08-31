package space.bitos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Inline state banner (docs/ui `state-banner`): tone-tinted background and
 * border plus a leading icon — state is never communicated by color alone.
 * Colors are the mode-independent semantic tokens (identical in both
 * DesignTokens palettes) so the enum stays a plain static catalog.
 */
enum class StateBannerTone(val color: Color, val icon: ImageVector) {
    INFO(Color(0xFF3B82F6), Icons.Rounded.Info),
    WARN(Color(0xFFF59E0B), Icons.Rounded.Warning),
    ERROR(Color(0xFFEF4444), Icons.Rounded.Cancel),
    OK(Color(0xFF10B981), Icons.Rounded.CheckCircle),
}

@Composable
fun StateBanner(
    tone: StateBannerTone,
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(tone.color.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .border(1.dp, tone.color.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            icon ?: tone.icon,
            contentDescription = null,
            tint = tone.color,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text,
            color = tone.color,
            fontSize = 12.sp,
            fontWeight = FontWeight.W600,
            lineHeight = 16.sp,
        )
    }
}
