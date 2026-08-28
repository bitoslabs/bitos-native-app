package space.bitos.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.data.publish.PublishResult
import space.bitos.app.data.publish.PublishUiState
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.components.formatTimeAgo
import space.bitos.app.ui.components.shortPubkey
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.feed.FeedNote
import space.bitos.core.publish.NoteComposer

/**
 * Reply thread sheet (SOC-002): verified replies with an identity-gated
 * composer. Published replies reconcile through the feed gate like every
 * other event; the composer appends optimistically only on ACK.
 */
@Composable
fun CommentContent(
    note: FeedNote,
    feedState: space.bitos.app.data.feed.FeedUiState,
    identityViewModel: IdentityViewModel,
    publisherState: PublishUiState,
    onLoadComments: (String) -> Unit,
    onReply: (String, FeedNote) -> Unit,
    onClose: () -> Unit,
) {
    val identity by identityViewModel.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    val comments = feedState.comments[note.id].orEmpty()

    androidx.compose.runtime.LaunchedEffect(note.id) { onLoadComments(note.id) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BitOSSpacing.screen)
            .padding(bottom = BitOSSpacing.xl),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Replies", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(BitOSSpacing.sm))
            Text(
                "${comments.size}",
                style = MaterialTheme.typography.labelMedium,
                color = BitOSColors.textTertiary,
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        Spacer(Modifier.height(BitOSSpacing.md))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            items(comments, key = { it.id }) { reply ->
                ReplyRow(reply, feedState.profiles[reply.pubkey])
            }
            if (comments.isEmpty()) {
                item {
                    Text(
                        if (feedState.hasLoadedAnyEvent) "No replies yet." else "Loading replies…",
                        style = MaterialTheme.typography.bodySmall,
                        color = BitOSColors.textSecondary,
                    )
                }
            }
        }

        Spacer(Modifier.height(BitOSSpacing.md))
        when {
            publisherState.result == PublishResult.SIGNING_REFUSED ->
                Text("Signing refused — open Profile to add an identity.", color = BitOSColors.error)
            publisherState.result == PublishResult.REJECTED ->
                Text("Relays rejected the reply.", color = BitOSColors.error)
            else -> Unit
        }
        if (identity.account == null) {
            Text(
                "Replying needs an identity (Profile tab).",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= NoteComposer.MAX_NOTE_LENGTH) text = it },
                    placeholder = { Text("Write a reply…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(BitOSSpacing.sm))
                Button(
                    onClick = { onReply(text, note); text = "" },
                    enabled = text.isNotBlank(),
                ) { Text("Reply") }
            }
        }
    }
}

@Composable
private fun ReplyRow(reply: FeedNote, profile: space.bitos.core.model.ProfileMetadata?) {
    Surface(shape = RoundedCornerShape(12.dp), color = BitOSColors.surface) {
        Row(Modifier.padding(BitOSSpacing.md)) {
            PubkeyAvatar(pubkey = reply.pubkey, size = 32)
            Spacer(Modifier.width(BitOSSpacing.sm))
            Column {
                Row {
                    Text(
                        profile?.bestDisplayName ?: shortPubkey(reply.pubkey),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.W600,
                        color = BitOSColors.primary,
                    )
                    Spacer(Modifier.width(BitOSSpacing.sm))
                    Text(
                        formatTimeAgo(reply.createdAt, System.currentTimeMillis() / 1000),
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textTertiary,
                    )
                }
                Text(reply.content, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
