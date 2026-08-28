package space.bitos.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import space.bitos.app.ui.theme.AppIcons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.bitos.app.ui.theme.BitOSColors

/**
 * AppMenu system (unified feature spec §4, APP-022): the anchored popover
 * menu plus the bottom-sheet variant for long lists. Android clamping and
 * anchoring are native DropdownMenu behavior; iOS implements the same
 * entry/visual contract with its tested `AppMenuLayout` (screen-clamped
 * showAt parity with the legacy clients).
 */

data class AppMenuItem(
    val id: String,
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val checked: Boolean = false,
)

sealed interface AppMenuEntry {
    data class Item(val item: AppMenuItem) : AppMenuEntry
    data object Divider : AppMenuEntry
}

private fun labelColor(item: AppMenuItem): Color =
    if (item.destructive) BitOSColors.error else BitOSColors.textPrimary

private fun iconColor(item: AppMenuItem): Color =
    if (item.destructive) BitOSColors.error else BitOSColors.textSecondary

/** Anchored popover menu. Wrap the trigger composable in the same Box. */
@Composable
fun AppMenuDropdown(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    entries: List<AppMenuEntry>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        containerColor = BitOSColors.surfaceOverlay,
        tonalElevation = 0.dp,
        shadowElevation = 16.dp,
    ) {
        entries.forEach { entry ->
            when (entry) {
                AppMenuEntry.Divider -> HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    thickness = 1.dp,
                    color = BitOSColors.divider,
                )
                is AppMenuEntry.Item -> {
                    val item = entry.item
                    DropdownMenuItem(
                        text = {
                            Text(
                                item.label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.W600,
                                color = labelColor(item),
                            )
                        },
                        leadingIcon = item.icon?.let { icon ->
                            {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    tint = iconColor(item),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                        trailingIcon = if (item.checked) {
                            {
                                Icon(
                                    AppIcons.Check,
                                    contentDescription = null,
                                    tint = BitOSColors.primary,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        } else {
                            null
                        },
                        onClick = {
                            onSelect(item.id)
                            onDismissRequest()
                        },
                    )
                }
            }
        }
    }
}

/** Bottom-sheet menu for long lists (Bitz comments host parity). */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun AppBottomSheetMenu(
    onDismissRequest: () -> Unit,
    entries: List<AppMenuEntry>,
    onSelect: (String) -> Unit,
    title: String? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = BitOSColors.surface,
    ) {
        if (title != null) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.W600,
                color = BitOSColors.textSecondary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        entries.forEach { entry ->
            when (entry) {
                AppMenuEntry.Divider -> HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    thickness = 1.dp,
                    color = BitOSColors.divider,
                )
                is AppMenuEntry.Item -> AppMenuItemRow(entry.item) { onSelect(entry.item.id) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AppMenuItemRow(item: AppMenuItem, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClickLabel = item.label) { onClick() }
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = item.label },
    ) {
        if (item.icon != null) {
            Icon(item.icon, contentDescription = null, tint = iconColor(item), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            item.label,
            fontSize = 13.sp,
            fontWeight = FontWeight.W600,
            color = labelColor(item),
        )
        Spacer(Modifier.weight(1f))
        if (item.checked) {
            Icon(AppIcons.Check, contentDescription = null, tint = BitOSColors.primary, modifier = Modifier.size(14.dp))
        }
    }
}
