package space.bitos.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors

/**
 * Icon-only dismiss affordance for bottom-sheet headers (user decision
 * 2026-08-29): sheets close with ✕, never a labelled Close button.
 */
@Composable
fun SheetCloseIcon(
    onClose: () -> Unit,
    tint: Color = BitOSColors.textSecondary,
) {
    IconButton(
        onClick = onClose,
        modifier = Modifier.size(36.dp).semantics { contentDescription = "Close" },
    ) {
        Icon(AppIcons.Close, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}
