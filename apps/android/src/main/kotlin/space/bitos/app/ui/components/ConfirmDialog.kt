package space.bitos.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import space.bitos.app.ui.theme.BitOSColors

/**
 * Confirmation dialog for sensitive settings actions (sign-out, remove
 * account, remove key). A modal dialog — not an in-place confirm-button
 * swap — so the confirming tap can never land on the pixel that triggered
 * the request; the body states the consequence and what survives.
 * `destructive` tints the confirm action with the error color.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) BitOSColors.error else BitOSColors.primary,
                    fontWeight = FontWeight.W600,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = BitOSColors.textSecondary) }
        },
    )
}
