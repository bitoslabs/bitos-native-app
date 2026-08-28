package space.bitos.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing

/** Identity confirmation dialog (shared: You tab + More hub add-account). */
@Composable
fun ConfirmIdentityDialog(
    npub: String,
    replacesExisting: Boolean,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (replacesExisting) "Switch identity?" else "Confirm your identity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                Text(
                    if (replacesExisting) {
                        "This account becomes the ACTIVE identity; every saved account stays sealed on this device."
                    } else {
                        "This is the public identity derived from your key. Verify it before continuing."
                    },
                )
                Surface(shape = RoundedCornerShape(10.dp), color = BitOSColors.surfaceElevated) {
                    Text(
                        npub,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = BitOSColors.primary,
                        modifier = Modifier.padding(BitOSSpacing.sm),
                    )
                }
                Text(
                    "Backup warning: if this is a new key, write the secret down now — it cannot be recovered from this device.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = BitOSColors.warning,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy) { Text(if (busy) "Storing…" else "Use this identity") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
