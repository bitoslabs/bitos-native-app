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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
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
    // APP-009 X-style threading (shared ThreadAssembly): top-level +
    // flattened descendants behind depth indents.
    val thread = feedState.threads[note.id].orEmpty()
    val noteById = remember(comments) { comments.associateBy { it.id } }
    // APP-009 live deltas (shared NoteTally): reactions/reposts/zaps.
    val tally = feedState.tallies[note.id]

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
                "${thread.size}",
                style = MaterialTheme.typography.labelMedium,
                color = BitOSColors.textTertiary,
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        Spacer(Modifier.height(BitOSSpacing.md))

        RootCard(
            note = note,
            profile = feedState.profiles[note.pubkey],
            replyCount = comments.size,
            tally = tally,
        )
        Spacer(Modifier.height(BitOSSpacing.md))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            items(thread, key = { it.id }) { item ->
                noteById[item.id]?.let { reply ->
                    ReplyRow(
                        reply = reply,
                        profile = feedState.profiles[reply.pubkey],
                        depth = item.depth,
                        orphan = item.orphan,
                        tally = feedState.tallies[reply.id],
                    )
                }
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
                space.bitos.app.ui.components.BitosPlainTextField(
                    value = text,
                    onValueChange = { if (it.length <= NoteComposer.MAX_NOTE_LENGTH) text = it },
                    placeholder = "Write a reply…",
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

/**
 * APP-009 root card: author row + body + the full action row with live
 * tallies (reply count · like+z · repost · zap+sats) per spec §3.9.
 */
@Composable
private fun RootCard(
    note: FeedNote,
    profile: space.bitos.core.model.ProfileMetadata?,
    replyCount: Int,
    tally: space.bitos.core.feed.NoteTally?,
) {
    var showRaw by remember { mutableStateOf(false) }
    if (showRaw) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRaw = false },
            title = { Text("Raw note") },
            text = {
                Text(
                    "id: ${note.id}\nauthor: ${note.pubkey}\nkind: ${note.kind}\nat: ${note.createdAt}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showRaw = false }) { Text("Close") }
            },
        )
    }
    Surface(shape = RoundedCornerShape(14.dp), color = BitOSColors.surface) {
        Column(Modifier.padding(BitOSSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PubkeyAvatar(pubkey = note.pubkey, size = 40, label = profile?.bestDisplayName)
                Spacer(Modifier.width(BitOSSpacing.sm))
                Column {
                    Text(
                        profile?.bestDisplayName ?: shortPubkey(note.pubkey),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.W700,
                    )
                    Text(
                        formatTimeAgo(note.createdAt, System.currentTimeMillis() / 1000),
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textTertiary,
                    )
                }
            }
            Spacer(Modifier.height(BitOSSpacing.sm))
            Text(note.content, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(BitOSSpacing.md))
            // Live action row (APP-009 §3.9).
            Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.base)) {
                TallyAction(space.bitos.app.ui.theme.AppIcons.Comment, "$replyCount", BitOSColors.reply)
                TallyAction(space.bitos.app.ui.theme.AppIcons.Heart, tally?.reactions?.toString() ?: "0", BitOSColors.like)
                TallyAction(space.bitos.app.ui.theme.AppIcons.Repost, tally?.reposts?.toString() ?: "0", BitOSColors.repost)
                val sats = ((tally?.zapMillisats ?: 0L) / 1000L).let { if (it > 0) space.bitos.core.model.ZapFormat.sats(it) else "" }
                TallyAction(space.bitos.app.ui.theme.AppIcons.Zap, listOfNotNull("${tally?.zaps ?: 0}", sats.ifEmpty { null }).joinToString(" · "), BitOSColors.zap)
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = { showRaw = true }) {
                    Text("⋯", color = BitOSColors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun TallyAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.W600)
    }
}

@Composable
private fun ReplyRow(
    reply: FeedNote,
    profile: space.bitos.core.model.ProfileMetadata?,
    depth: Int,
    orphan: Boolean,
    tally: space.bitos.core.feed.NoteTally? = null,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (depth > 0) BitOSColors.background else BitOSColors.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp),
    ) {
        Row(Modifier.padding(BitOSSpacing.md)) {
            // APP-009: descendants carry a left border (conversation rail).
            if (depth > 0) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(44.dp)
                        .background(BitOSColors.primary.copy(alpha = 0.35f), RoundedCornerShape(1.dp)),
                )
                Spacer(Modifier.width(BitOSSpacing.sm))
            }
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
                if (orphan) {
                    Text(
                        "Reply above unavailable",
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textTertiary,
                    )
                }
                // APP-009: live per-reply deltas (reactions · zaps+sats).
                if (tally != null && (tally.reactions > 0 || tally.zaps > 0)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.base)) {
                        if (tally.reactions > 0) {
                            TallyAction(space.bitos.app.ui.theme.AppIcons.Heart, "${'$'}{tally.reactions}", BitOSColors.like)
                        }
                        if (tally.zaps > 0) {
                            val sats = tally.zapMillisats / 1000
                            val label = if (sats > 0) "${'$'}{tally.zaps} · ${'$'}{space.bitos.core.model.ZapFormat.sats(sats)}" else "${'$'}{tally.zaps}"
                            TallyAction(space.bitos.app.ui.theme.AppIcons.Zap, label, BitOSColors.zap)
                        }
                    }
                }
            }
        }
    }
}
