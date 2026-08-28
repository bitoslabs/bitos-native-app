package space.bitos.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.Edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.components.PubkeyAvatar
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.res.painterResource
import space.bitos.app.R
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing

/**
 * Profile surface with the account flow (ID-004): browse-first by default —
 * identity creation/import is always explicit and visibly confirms the
 * derived npub before anything is stored or replaced.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    identityViewModel: IdentityViewModel,
    settingsStore: space.bitos.app.data.settings.SettingsStore,
    feedRepository: space.bitos.app.data.feed.FeedRepository,
) {
    var showSettings by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (showSettings) {
        space.bitos.app.ui.settings.SettingsScreen(
            identityViewModel,
            store = settingsStore,
            feedRepository = feedRepository,
            onBack = { showSettings = false },
        )
        return
    }
    val state by identityViewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    var showEdit by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val profileEditState by identityViewModel.profileEditState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BitOSColors.background)
            .verticalScroll(rememberScrollState())
            .padding(BitOSSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("You", style = MaterialTheme.typography.headlineMedium)
            androidx.compose.material3.IconButton(onClick = { showSettings = true }) {
                androidx.compose.material3.Icon(
                    painterResource(R.drawable.solar_settings_linear),
                    contentDescription = "Settings",
                    tint = BitOSColors.textSecondary,
                )
            }
        }

        if (state.account != null) {
            AccountPanel(
                npub = state.account!!.npub,
                onCopy = { clipboard.setText(AnnotatedString(state.account!!.npub)) },
                onRemove = identityViewModel::removeAccount,
            )
            // Edit profile: opens the kind-0 publish flow.
            androidx.compose.material3.OutlinedButton(
                onClick = { showEdit = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.Icon(
                    painterResource(R.drawable.solar_pen_linear),
                    contentDescription = null,
                )
                Spacer(Modifier.width(BitOSSpacing.sm))
                Text("Edit profile")
            }
        } else {
            BrowseOnlyPanel(
                onCreate = identityViewModel::createKeyPreview,
                onImport = { /* import field revealed below */ },
            )
            ImportPanel(onSubmit = identityViewModel::importNsecPreview, error = state.importError)
        }
    }

    if (showEdit) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showEdit = false }) {
            ProfileEditContent(
                initialNip05 = "",
                initialLud16 = "",
                error = profileEditState.error,
                busy = profileEditState.busy,
                onPublish = { name, displayName, about, nip05, lud16 ->
                    identityViewModel.publishProfile(name, displayName, about, nip05, lud16)
                },
                onClose = { showEdit = false; identityViewModel.clearProfileEditError() },
            )
        }
    }

    state.preview?.let { preview ->
        ConfirmIdentityDialog(
            npub = preview.npub,
            replacesExisting = preview.replacesExisting,
            busy = state.busy,
            onConfirm = identityViewModel::confirmPreview,
            onDismiss = identityViewModel::cancelPreview,
        )
    }
}

@Composable
private fun AccountPanel(npub: String, onCopy: () -> Unit, onRemove: () -> Unit) {
    var confirmRemove by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = BitOSColors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PubkeyAvatar(pubkey = npub, size = 48)
                Spacer(Modifier.width(BitOSSpacing.md))
                Column {
                    Text("Local identity active", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
                    Text("Secret sealed in Android Keystore", style = MaterialTheme.typography.bodySmall, color = BitOSColors.textSecondary)
                }
            }
            Text(
                npub,
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
                maxLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                OutlinedButton(onClick = onCopy) { Text("Copy npub") }
                OutlinedButton(onClick = { confirmRemove = true }) { Text("Remove") }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove identity?") },
            text = { Text("The sealed secret is deleted from this device. Without a backup you lose the account. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("Remove", color = BitOSColors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun BrowseOnlyPanel(onCreate: () -> Unit, onImport: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = BitOSColors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
            Text("Browsing without an identity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
            Text(
                "You can watch and explore anonymously. Actions that need a signature offer key creation or import below — nothing is created silently.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                Button(onClick = onCreate) {
                    Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("Create identity")
                }
                OutlinedButton(onClick = onImport) {
                    Icon(Icons.Outlined.PersonAddAlt, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("Import nsec")
                }
            }
        }
    }
}

@Composable
private fun ImportPanel(onSubmit: (String) -> Unit, error: String?) {
    var input by remember { mutableStateOf("") }
    Surface(shape = RoundedCornerShape(16.dp), color = BitOSColors.surfaceElevated, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
            Text("Import a secret key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("nsec1…") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it, color = BitOSColors.error) } },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { onSubmit(input) }, enabled = input.isNotBlank()) {
                Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text("Review key")
            }
            Text(
                "The key stays on this device, sealed in the Android Keystore. Never share an nsec.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textTertiary,
            )
        }
    }
}

@Composable
private fun ConfirmIdentityDialog(
    npub: String,
    replacesExisting: Boolean,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (replacesExisting) "Replace identity?" else "Confirm your identity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                Text(
                    if (replacesExisting) {
                        "This REPLACES the identity currently stored on this device. Its secret is overwritten."
                    } else {
                        "This is the public identity derived from your key. Verify it before continuing."
                    },
                )
                Surface(shape = RoundedCornerShape(10.dp), color = BitOSColors.surfaceElevated) {
                    Text(
                        npub,
                        style = MaterialTheme.typography.bodySmall,
                        color = BitOSColors.primary,
                        modifier = Modifier.padding(BitOSSpacing.sm),
                    )
                }
                Text(
                    "Backup warning: if this is a new key, write the secret down now — it cannot be recovered from this device.",
                    style = MaterialTheme.typography.bodySmall,
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
