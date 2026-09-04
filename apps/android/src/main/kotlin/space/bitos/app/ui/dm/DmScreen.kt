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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import space.bitos.app.data.dm.DmConversationState
import space.bitos.app.data.dm.DmRepository
import space.bitos.core.identity.NostrKeyCodec
import space.bitos.core.model.DmMessage
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.components.BitosPlainTextField
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.components.formatTimeAgo
import space.bitos.app.ui.components.shortPubkey
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing

/**
 * APP-011 DMs (mock `app-06-inbox-activity-messages` parity): message list
 * with generic NIP-17 previews, unread dots and message requests; secure
 * chat with the encryption banner, delivery ticks, ⚡ zap chip and
 * new-chat by npub. Conversation state derives from the shared
 * `DmPresentation` rules; unwrapping and persistence stay in the repo.
 */
@Composable
fun DmScreen(
    identityViewModel: IdentityViewModel,
    dmRepository: DmRepository,
    /** Opens the profile zap sheet for the chat peer. */
    onZapPeer: (String) -> Unit = {},
    /** Opens the full in-app profile for the chat peer. */
    onOpenProfile: (String) -> Unit = {},
    /** Live kind-0 metadata: pubkey → (display name, picture URL). */
    profiles: Map<String, Pair<String?, String?>> = emptyMap(),
) {
    val identity by identityViewModel.state.collectAsStateWithLifecycle()
    val state by dmRepository.state.collectAsStateWithLifecycle()

    LaunchedEffect(identity.account?.pubkeyHex) {
        dmRepository.setAccount(identity.account?.pubkeyHex)
    }

    val openPeer = state.openPeerPubkey
    if (openPeer != null) {
        ChatScreen(
            dmRepository = dmRepository,
            peerPubkey = openPeer,
            profile = profiles[openPeer],
            onBack = { dmRepository.openConversation(null) },
            onZapPeer = onZapPeer,
            onOpenProfile = onOpenProfile,
        )
    } else {
        ConversationList(
            state = state,
            profiles = profiles,
            onOpen = { dmRepository.openConversation(it) },
        )
    }
}

// ── Conversation list ─────────────────────────────────────────────

@Composable
private fun ConversationList(
    state: space.bitos.app.data.dm.DmUiState,
    profiles: Map<String, Pair<String?, String?>>,
    onOpen: (String) -> Unit,
) {
    var newChatOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(BitOSColors.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // Matches the Inbox header inside the shared Activity tab
                // (same gutter, same vertical rhythm on both chips).
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Messages", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { newChatOpen = true }) {
                Icon(AppIcons.Chat, contentDescription = "New message", tint = BitOSColors.textSecondary)
            }
        }

        when {
            !state.hasAccount -> ListPlaceholder("Chats need an identity (You tab).")
            state.conversations.isEmpty() && state.requests.isEmpty() -> ListPlaceholder(
                if (state.loaded) {
                    "No conversations yet.\nNew message (chat icon) starts one by npub."
                } else {
                    "Connecting…"
                },
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                // NIP-17 privacy banner (mock `state-banner info`).
                item { InfoBanner("NIP-17 secure messages — previews stay generic; content decrypts only inside the app.") }
                items(state.conversations, key = { it.conversation.peerPubkey }) { conversation ->
                    ConversationRow(conversation, profiles) { onOpen(conversation.conversation.peerPubkey) }
                }
                if (state.requests.isNotEmpty()) {
                    item { RequestsHeader(count = state.requests.size) }
                    items(state.requests, key = { "req-" + it.conversation.peerPubkey }) { request ->
                        ConversationRow(request, profiles) { onOpen(request.conversation.peerPubkey) }
                    }
                }
            }
        }
    }

    if (newChatOpen) {
        NewChatDialog(
            onDismiss = { newChatOpen = false },
            onStart = { pubkey ->
                newChatOpen = false
                onOpen(pubkey)
            },
        )
    }
}

/** Resolves npub or raw hex into a pubkey hex; null when invalid. */
internal fun resolvePeerInput(input: String): String? {
    val trimmed = input.trim()
    return when {
        trimmed.startsWith("npub1") -> NostrKeyCodec.parseNpub(trimmed)
        trimmed.matches(HEX64) -> trimmed
        else -> null
    }
}

private val HEX64 = Regex("^[0-9a-f]{64}$")

@Composable
private fun NewChatDialog(onDismiss: () -> Unit, onStart: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    val resolved = remember(value) { resolvePeerInput(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New message") },
        text = {
            Column {
                Text(
                    "Address by npub or public key (hex).",
                    style = MaterialTheme.typography.bodySmall,
                    color = BitOSColors.textSecondary,
                )
                Spacer(Modifier.size(8.dp))
                BitosPlainTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = "npub1…",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (value.isNotBlank() && resolved == null) {
                    Text(
                        "Not a valid npub or hex pubkey.",
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { resolved?.let(onStart) }, enabled = resolved != null) { Text("Chat") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ListPlaceholder(message: String) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = BitOSColors.textSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun InfoBanner(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = BitOSColors.surfaceElevated,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.Inbox,
                contentDescription = null,
                tint = BitOSColors.textTertiary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = BitOSColors.textSecondary)
        }
    }
}

@Composable
private fun ConversationRow(
    state: DmConversationState,
    profiles: Map<String, Pair<String?, String?>>,
    onOpen: () -> Unit,
) {
    val conversation = state.conversation
    val profile = profiles[conversation.peerPubkey]
    val nowSec = remember { System.currentTimeMillis() / 1000 }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open chat") { onOpen() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            PubkeyAvatar(pubkey = conversation.peerPubkey, size = 44, pictureUrl = profile?.second)
            if (state.unreadCount > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(10.dp)
                        .background(BitOSColors.primary, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(BitOSSpacing.md))
        val last = conversation.lastMessage
        Column(Modifier.weight(1f)) {
            Text(
                profile?.first ?: shortPubkey(conversation.peerPubkey),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.W700,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (last != null) "${state.previewLine} · ${formatTimeAgo(last.createdAt, nowSec)}" else "No messages",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.unreadCount > 0) BitOSColors.textPrimary else BitOSColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (last != null) {
            Text(
                formatTimeAgo(last.createdAt, nowSec),
                style = MaterialTheme.typography.labelSmall,
                color = BitOSColors.textTertiary,
            )
        }
    }
}

@Composable
private fun RequestsHeader(count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = BitOSSpacing.screen,
                end = BitOSSpacing.screen,
                top = BitOSSpacing.md,
                bottom = BitOSSpacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = BitOSColors.surfaceElevated) {
            Icon(
                AppIcons.People,
                contentDescription = null,
                tint = BitOSColors.textSecondary,
                modifier = Modifier
                    .padding(10.dp)
                    .size(20.dp),
            )
        }
        Spacer(Modifier.width(BitOSSpacing.md))
        Column {
            Text("Message requests", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W700)
            Text(
                "$count waiting — accepting never reveals you read them",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
            )
        }
    }
}

// ── Chat screen ───────────────────────────────────────────────────

@Composable
private fun ChatScreen(
    dmRepository: DmRepository,
    peerPubkey: String,
    profile: Pair<String?, String?>?,
    onBack: () -> Unit,
    onZapPeer: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val state by dmRepository.state.collectAsStateWithLifecycle()
    val conversation = (
        state.conversations.firstOrNull { it.conversation.peerPubkey == peerPubkey }
            ?: state.requests.firstOrNull { it.conversation.peerPubkey == peerPubkey }
        )?.conversation
    var text by remember(peerPubkey) { mutableStateOf("") }
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Keep relative timestamps honest while the chat is open.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            nowSec = System.currentTimeMillis() / 1000
        }
    }

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
        // Header: back · avatar+name (→ profile) · NIP-17 line · zap chip.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.xs, vertical = BitOSSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(AppIcons.Back, contentDescription = "Back to messages", tint = BitOSColors.textPrimary, modifier = Modifier.size(22.dp))
            }
            PubkeyAvatar(
                pubkey = peerPubkey,
                size = 36,
                pictureUrl = profile?.second,
                modifier = Modifier.clickable(onClickLabel = "Open profile") { onOpenProfile(peerPubkey) },
            )
            Spacer(Modifier.width(BitOSSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    profile?.first ?: shortPubkey(peerPubkey),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.W700,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "End-to-end encrypted · NIP-17",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.success,
                )
            }
            // Mock `chip chip-orange ⚡ Zap` → the profile zap sheet.
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color(0x26F7931A),
                modifier = Modifier.clickable(onClickLabel = "Zap") { onZapPeer(peerPubkey) },
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Zap, contentDescription = null, tint = BitOSColors.zap, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Zap", style = MaterialTheme.typography.labelMedium, color = BitOSColors.zap, fontWeight = FontWeight.W700)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            // Encryption banner (mock first chip row).
            item { EncryptionBanner() }
            items(conversation?.messages ?: emptyList(), key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    isMine = message.authorPubkey != peerPubkey,
                    delivered = state.deliveryByRumorId[message.id],
                    nowSec = nowSec,
                )
            }
        }

        // Input bar: rounded field + accent send (mock composer row).
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            BitosPlainTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = "Message ${profile?.first ?: shortPubkey(peerPubkey)}",
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
private fun EncryptionBanner() {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = BitOSColors.surfaceElevated,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BitOSSpacing.xs),
    ) {
        Text(
            "Messages decrypt only in this app — never in a push notification",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textSecondary,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MessageBubble(
    message: DmMessage,
    isMine: Boolean,
    delivered: Boolean?,
    nowSec: Long,
) {
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
                    color = if (isMine) Color(0xFF0A0A0F) else BitOSColors.textPrimary,
                )
                // Delivery ticks: sending… → delivered (relay OK receipt).
                val suffix = if (isMine) {
                    when (delivered) {
                        true -> " · delivered"
                        false -> " · sending…"
                        null -> ""
                    }
                } else {
                    ""
                }
                Text(
                    formatTimeAgo(message.createdAt, nowSec) + suffix,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMine) Color(0x990A0A0F) else BitOSColors.textTertiary,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}
