package space.bitos.app.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import space.bitos.app.ui.theme.BitOSRadius
import space.bitos.app.ui.theme.bitOSColors

/**
 * Card container (mockup `card` family): surface fill, hairline border,
 * md radius, 16 dp content padding by default (§2.3 semantic spacing).
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    elevated: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = bitOSColors()
    val shape = RoundedCornerShape(BitOSRadius.md)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (elevated) colors.surfaceElevated else colors.surface)
            .border(1.dp, colors.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
    ) { content() }
}
