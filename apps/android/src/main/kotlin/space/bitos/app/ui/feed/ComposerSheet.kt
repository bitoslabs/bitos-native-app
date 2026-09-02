package space.bitos.app.ui.feed

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.ui.components.PowCard
import space.bitos.app.ui.components.PowOutcome
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.data.publish.PublishResult
import space.bitos.app.data.publish.PublishUiState
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.publish.ComposerRules
import space.bitos.core.publish.NoteComposer

/**
 * Quick note composer sheet (PUB-001 note path): identity-gated publish with
 * live relay receipt surface. Signer refusal and relay rejections are shown,
 * not swallowed. Mentions ride the shared `ComposerRules` pipeline (query
 * detection at the caret, ≤6 candidates, `@Name` tracked then rewritten to
 * `nostr:npub…` on publish) — the same contract as the full CreateNoteScreen,
 * in sheet form.
 */
@Composable
fun ComposerContent(
    identityViewModel: IdentityViewModel,
    publisherState: PublishUiState,
    /** Known profiles for @-autocomplete (FeedScreen passes its store). */
    profiles: Map<String, space.bitos.core.model.ProfileMetadata> = emptyMap(),
    onPublish: (String, PowOutcome?) -> Unit,
    onDismiss: () -> Unit,
) {
    val identity by identityViewModel.state.collectAsStateWithLifecycle()
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val trackedMentions = remember { mutableStateListOf<Pair<String, String>>() }
    var powTarget by remember { mutableStateOf(0) }
    var powOutcome by remember { mutableStateOf<PowOutcome?>(null) }
    var fieldFocused by remember { mutableStateOf(false) }

    val counter = remember(field.text) { ComposerRules.counterState(field.text.length) }
    // @-autocomplete gating (CreateNoteScreen parity): candidates surface
    // only while the field is focused and the caret sits in a trailing
    // @query — a bare `@` resolves with the empty query matching every
    // known profile (cap 6); no query at all means no panel.
    val mentionQuery = ComposerRules.mentionQueryAt(field.text, field.selection.end)
    val suggestions = remember(mentionQuery, fieldFocused, profiles) {
        if (fieldFocused && mentionQuery != null) {
            ComposerRules.mentionSuggestions(mentionQuery, profiles.values)
        } else {
            emptyList()
        }
    }

    fun pickMention(suggestion: ComposerRules.MentionSuggestion) {
        val cursor = field.selection.end
        val start = field.text.lastIndexOf('@', cursor - 1)
        if (start >= 0) {
            val next = field.text.replaceRange(start, cursor, "@${suggestion.name} ")
            field = TextFieldValue(next, androidx.compose.ui.text.TextRange(start + suggestion.name.length + 2))
            trackedMentions += suggestion.name to suggestion.npub
        }
    }

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
                value = field,
                onValueChange = { if (it.text.length <= NoteComposer.MAX_NOTE_LENGTH) field = it },
                placeholder = { Text("Say something in ₿…") },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            // Mention autocomplete (≤6, web `candidates` parity).
            if (suggestions.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = BitOSColors.surfaceElevated,
                    modifier = Modifier.border(1.dp, BitOSColors.border.copy(alpha = 0.2f), RoundedCornerShape(14.dp)),
                ) {
                    Column {
                        suggestions.forEach { suggestion ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(onClickLabel = "Mention ${suggestion.name}") { pickMention(suggestion) }
                                    .padding(horizontal = BitOSSpacing.md, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PubkeyAvatar(pubkey = suggestion.pubkeyHex, size = 28)
                                Spacer(Modifier.width(BitOSSpacing.sm))
                                Column {
                                    Text(
                                        suggestion.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.W700,
                                        maxLines = 1,
                                    )
                                    Text(
                                        suggestion.npub.take(12) + "…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = BitOSColors.textTertiary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    counter.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        counter.over -> BitOSColors.error
                        counter.near -> BitOSColors.warning
                        else -> BitOSColors.textTertiary
                    },
                )
                Button(
                    onClick = {
                        // NIP-27 publish rewrite: tracked `@Name` →
                        // `nostr:npub…` so relays resolve the mention.
                        val rewritten = ComposerRules.rewriteMentions(field.text, trackedMentions)
                        onPublish(rewritten, powOutcome)
                    },
                    enabled = field.text.isNotBlank() && !counter.over,
                ) { Text("Publish") }
            }
            // APP-008: optional NIP-13 proof-of-work. Content matches the
            // rewritten publish text so the mined template stays valid.
            identity.account?.let { account ->
                PowCard(
                    target = powTarget,
                    onTargetChange = { powTarget = it },
                    content = ComposerRules.rewriteMentions(field.text, trackedMentions),
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
            space.bitos.app.ui.components.SheetCloseIcon(onClose = onClose)
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
