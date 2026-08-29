package space.bitos.app.ui.dm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import space.bitos.app.data.dm.DmRepository
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.components.formatTimeAgo
import space.bitos.app.ui.components.shortPubkey
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.model.DmConversation
import space.bitos.core.model.DmMessage

/**
 * APP-011 DMs: conversation list → chat. Unwraps happen in the repo
 * (shared NIP-17); the UI renders grouped conversations and message
 * bubbles (sent right-aligned accent, received left-aligned surface).
 */
@Composable
fun DmScreen(
    identityViewModel: IdentityViewModel,
    dmRepository: DmRepository,
) {
    val identity by identityViewModel.state.collectAsStateWithLifecycle()
    val state by dmRepository.state.collectAsStateWithLifecycle()

    LaunchedEffect(identity.account?.pubkeyHex) {
        dmRepository.setAccount(identity.account?.pubkeyHex)
    }

    if (state.openPeerPubkey != null) {
        ChatScreen(
            dmRepository = dmRepository,
            peerPubkey = state.openPeerPubkey!!,
            onBack = { dmRepository.openConversation(null) },
        )
    } else {
        ConversationList(
            state = state,
            onOpen = { dmRepository.openConversation(it) },
        )
    }
}

// ── Conversation list ─────────────────────────────────────────────

@Composable
private fun ConversationList(
    state: space.bitos.app.data.dm.DmUiState,
    onOpen: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(BitOSColors.background)
            .padding(horizontal = BitOSSpacing.screen),
    ) {
        Text("Chats", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = BitOSSpacing.md))
        if (!state.hasAccount) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Chats need an identity (You tab).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BitOSColors.textSecondary,
                )
            }
        } else if (state.conversations.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.loaded) "No conversations yet." else "Connecting…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BitOSColors.textSecondary,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                items(state.conversations, key = { it.peerPubkey }) { conversation ->
                    ConversationRow(conversation) { onOpen(conversation.peerPubkey) }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: DmConversation, onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BitOSColors.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open chat") { onOpen() },
    ) {
        Row(Modifier.padding(BitOSSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            PubkeyAvatar(pubkey = conversation.peerPubkey, size = 44)
            Spacer(Modifier.width(BitOSSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    shortPubkey(conversation.peerPubkey),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.W700,
                    maxLines = 1,
                )
                conversation.lastMessage?.let { last ->
                    Text(
                        last.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = BitOSColors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            conversation.lastMessage?.let { last ->
                Text(
                    formatTimeAgo(last.createdAt, System.currentTimeMillis() / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textTertiary,
                )
            }
        }
    }
}

// ── Chat screen ───────────────────────────────────────────────────

@Composable
private fun ChatScreen(
    dmRepository: DmRepository,
    peerPubkey: String,
    onBack: () -> Unit,
) {
    val state by dmRepository.state.collectAsStateWithLifecycle()
    val identity by dmRepository.state.collectAsStateWithLifecycle()
    val conversation = state.conversations.firstOrNull { it.peerPubkey == peerPubkey }
    var text by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(conversation?.messages?.size) {
        if ((conversation?.messages?.size ?: 0) > 0) {
            listState.animateScrollToItem(conversation!!.messages.size - 1)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BitOSColors.background)
            .imePadding(),
    ) {
        // Header.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.TextButton(onClick = onBack) { Text("← Chats") }
            Spacer(Modifier.width(BitOSSpacing.sm))
            PubkeyAvatar(pubkey = peerPubkey, size = 32)
            Spacer(Modifier.width(BitOSSpacing.sm))
            Text(shortPubkey(peerPubkey), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W700)
        }

        // Messages.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            items(conversation?.messages ?: emptyList(), key = { it.id }) { message ->
                MessageBubble(message, isMine = message.authorPubkey != peerPubkey)
            }
        }

        // Input bar.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            space.bitos.app.ui.components.BitosPlainTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = "Write a message…",
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val content = text.trim()
                    if (content.isNotEmpty()) {
                        scope.launch { dmRepository.sendMessage(peerPubkey, content) }
                        text = ""
                    }
                },
                enabled = text.isNotBlank(),
            ) { Text("Send") }
        }
    }
}

@Composable
private fun MessageBubble(message: DmMessage, isMine: Boolean) {
    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isMine) 16.dp else 4.dp,
                bottomEnd = if (isMine) 4.dp else 16.dp,
            ),
            color = if (isMine) BitOSColors.primary else BitOSColors.surfaceElevated,
            modifier = Modifier.width(280.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isMine) androidx.compose.ui.graphics.Color(0xFF0A0A0F) else BitOSColors.textPrimary,
                )
                Text(
                    formatTimeAgo(message.createdAt, System.currentTimeMillis() / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMine) androidx.compose.ui.graphics.Color(0x990A0A0F) else BitOSColors.textTertiary,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}
