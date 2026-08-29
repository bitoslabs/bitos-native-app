package space.bitos.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing

/**
 * Identity confirmation gate (shared: You tab + More hub add-account),
 * ID-004. For a freshly generated key this is also the one-time backup
 * moment: the secret can be revealed and copied here — deliberately, never
 * automatically — before it is sealed.
 */
@Composable
fun ConfirmIdentityDialog(
    npub: String,
    replacesExisting: Boolean,
    isNewKey: Boolean,
    /** nsec of the pending key; non-null only for generated keys (backup reveal). */
    secretNsec: String?,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    replacesExisting -> "Switch identity?"
                    isNewKey -> "Your new identity"
                    else -> "Confirm your identity"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                Text(
                    when {
                        replacesExisting ->
                            "This account becomes the ACTIVE identity; every saved account stays sealed on this device."
                        isNewKey ->
                            "This identity was just generated on this device. Write the secret down now — it cannot be recovered later."
                        else ->
                            "This is the public identity derived from your key. Check that it matches the account you expect."
                    },
                )
                Surface(shape = RoundedCornerShape(10.dp), color = BitOSColors.surfaceElevated) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                        Text(
                            npub,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = BitOSColors.primary,
                            modifier = Modifier
                                .weight(1f)
                                .padding(BitOSSpacing.sm),
                        )
                        IconButton(onClick = { clipboard.setText(AnnotatedString(npub)) }) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = "Copy public key (npub)",
                                tint = BitOSColors.textSecondary,
                            )
                        }
                    }
                }
                if (isNewKey && secretNsec != null) {
                    BackupRevealSection(
                        secretNsec = secretNsec,
                        revealed = revealed,
                        onToggle = { revealed = !revealed },
                        onCopy = { clipboard.setText(AnnotatedString(secretNsec)) },
                    )
                } else if (!isNewKey) {
                    Text(
                        "Imported keys are already in your possession; no backup is needed here.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = BitOSColors.textTertiary,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy) {
                Text(if (busy) "Storing…" else if (isNewKey) "I saved my key" else "Use this identity")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One-time backup reveal for a generated key: explicit show + copy. */
@Composable
private fun BackupRevealSection(
    secretNsec: String,
    revealed: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(10.dp), color = BitOSColors.surfaceElevated) {
        Column(Modifier.padding(BitOSSpacing.sm), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Key,
                    contentDescription = null,
                    tint = BitOSColors.warning,
                    modifier = Modifier.padding(end = BitOSSpacing.xs),
                )
                Text(
                    "Secret key (backup)",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = BitOSColors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onToggle) {
                    Text(if (revealed) "Hide" else "Show")
                }
            }
            if (revealed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        secretNsec,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = BitOSColors.textPrimary,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = BitOSSpacing.xs),
                    )
                    IconButton(onClick = onCopy) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "Copy secret key (nsec)",
                            tint = BitOSColors.textSecondary,
                        )
                    }
                }
                Text(
                    "Write it down and store it somewhere safe. Never share it — anyone holding it controls the account.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = BitOSColors.warning,
                )
            } else {
                Text(
                    "Shown once here while creating the account; after this it stays sealed.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = BitOSColors.textTertiary,
                )
            }
        }
    }
}
