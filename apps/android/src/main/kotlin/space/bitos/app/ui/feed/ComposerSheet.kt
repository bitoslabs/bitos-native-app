package space.bitos.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.ui.components.PowCard
import space.bitos.app.ui.components.PowOutcome
import space.bitos.app.data.publish.PublishResult
import space.bitos.app.data.publish.PublishUiState
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.publish.NoteComposer

/**
 * Note composer sheet (PUB-001 note path): identity-gated publish with live
 * relay receipt surface. Signer refusal and relay rejections are shown, not
 * swallowed.
 */
@Composable
fun ComposerContent(
    identityViewModel: IdentityViewModel,
    publisherState: PublishUiState,
    onPublish: (String, PowOutcome?) -> Unit,
    onDismiss: () -> Unit,
) {
    val identity by identityViewModel.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    var powTarget by remember { mutableStateOf(0) }
    var powOutcome by remember { mutableStateOf<PowOutcome?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BitOSSpacing.screen)
            .padding(bottom = BitOSSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
    ) {
        Text("New note", style = MaterialTheme.typography.headlineMedium)

        if (publisherState.result != null || publisherState.inFlightId != null) {
            PublishStatus(state = publisherState, onDismiss = onDismiss)
        } else if (identity.account == null) {
            IdentityGateNotice(onClose = onDismiss)
        } else {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= NoteComposer.MAX_NOTE_LENGTH) text = it },
                placeholder = { Text("Say something in ₿…") },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${text.length}/${NoteComposer.MAX_NOTE_LENGTH}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (text.length >= NoteComposer.MAX_NOTE_LENGTH) BitOSColors.error else BitOSColors.textTertiary,
                )
                Button(
                    onClick = { onPublish(text, powOutcome) },
                    enabled = text.isNotBlank(),
                ) { Text("Publish") }
            }
            // APP-008: optional NIP-13 proof-of-work. The card keys its
            // session on content + target — any edit restarts cleanly.
            identity.account?.let { account ->
                PowCard(
                    target = powTarget,
                    onTargetChange = { powTarget = it },
                    content = text,
                    pubkeyHex = account.pubkeyHex,
                    onMined = { powOutcome = it },
                )
            }
        }
    }
}

@Composable
private fun IdentityGateNotice(onClose: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = BitOSColors.surfaceElevated) {
        Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
            Text("Signing needs an identity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
            Text(
                "Open the Profile tab to create or import a key, then come back to publish. Nothing is created silently.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
            )
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
    }
}

@Composable
private fun PublishStatus(state: PublishUiState, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = BitOSColors.surfaceElevated) {
        Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
            when (state.result) {
                null -> {
                    Text("Publishing…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
                    LinearProgressIndicator(
                        color = BitOSColors.primary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
                PublishResult.PUBLISHED -> Text(
                    "Published ✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = BitOSColors.success,
                    fontWeight = FontWeight.W600,
                )
                PublishResult.SIGNING_REFUSED -> Text("Signing refused by the signer.", color = BitOSColors.error)
                PublishResult.INVALID -> Text("Note rejected before sending.", color = BitOSColors.error)
                PublishResult.REJECTED -> Text(
                    "Relays rejected the note: " + (state.receipts.firstNotNullOfOrNull { it.message } ?: "no reason given"),
                    color = BitOSColors.error,
                )
                PublishResult.TIMEOUT -> Text("No relay acknowledged in time. The note was not confirmed.", color = BitOSColors.warning)
            }
            state.receipts.forEach { receipt ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(receipt.relay.host(), style = MaterialTheme.typography.bodySmall, color = BitOSColors.textSecondary)
                    Text(
                        when (receipt.accepted) {
                            true -> "accepted"
                            false -> "rejected"
                            null -> "waiting…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (receipt.accepted) {
                            true -> BitOSColors.success
                            false -> BitOSColors.error
                            null -> BitOSColors.textTertiary
                        },
                    )
                }
            }
            if (state.result != null) {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}
