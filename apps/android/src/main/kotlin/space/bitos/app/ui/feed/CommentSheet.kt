package space.bitos.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CommentContent(
    note: FeedNote,
    feedState: space.bitos.app.data.feed.FeedUiState,
    identityViewModel: IdentityViewModel,
    publisherState: PublishUiState,
    onLoadComments: (String) -> Unit,
    onReply: (String, FeedNote, List<String>, space.bitos.app.ui.components.PowOutcome?) -> Unit,
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

    // APP-009 reply bar (legacy `_ThreadReplyBar` parity): sub-reply
    // targeting, URL/GIF/gallery attachments, PoW, pill input + send.
    var replyTarget by remember { mutableStateOf<FeedNote?>(null) }
    val attachments = remember { mutableStateListOf<String>() }
    var uploadStatus by remember { mutableStateOf("") }
    var showGif by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showPow by remember { mutableStateOf(false) }
    var powTarget by remember { mutableStateOf(0) }
    var powOutcome by remember { mutableStateOf<space.bitos.app.ui.components.PowOutcome?>(null) }
    var awaitingReply by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val uploader = remember { space.bitos.app.data.media.BlossomUploader() }
    val effectiveTarget = replyTarget ?: note
    val sending = publisherState.inFlightId != null && awaitingReply

    // Clear the bar only on a successful ACK (legacy clears on success).
    androidx.compose.runtime.LaunchedEffect(publisherState) {
        if (awaitingReply && publisherState.result != null) {
            if (publisherState.result == PublishResult.PUBLISHED) {
                text = ""
                attachments.clear()
                powOutcome = null
                replyTarget = null
            }
            awaitingReply = false
        }
    }

    val galleryPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let { picked ->
            // Legacy order: upload BEFORE the URL joins the reply (nothing
            // references media that was never uploaded).
            scope.launch {
                try {
                    val signer = identityViewModel.createSigner()
                        ?: throw IllegalStateException("Replying needs an identity (Profile tab).")
                    uploadStatus = "Uploading media…"
                    val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        context.contentResolver.openInputStream(picked)?.use { it.readBytes() }
                    } ?: throw space.bitos.app.data.media.BlossomUploader.UploadFailure("could not read the picked file")
                    val media = uploader.upload(
                        bytes,
                        context.contentResolver.getType(picked) ?: "image/png",
                        signer,
                        space.bitos.app.data.media.DefaultBlossomServer.url,
                    )
                    if (attachments.size < space.bitos.core.publish.ComposerRules.MAX_IMAGES) attachments += media.url
                } catch (failure: Exception) {
                    uploadStatus = failure.message ?: "Upload failed."
                } finally {
                    uploadStatus = ""
                }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(note.id) { onLoadComments(note.id) }

    // Bounded height so the thread fills the sheet and the composer stays
    // pinned above the keyboard (ModalBottomSheet bounds content to screen).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .imePadding()
            .padding(horizontal = BitOSSpacing.screen)
            .padding(bottom = BitOSSpacing.lg),
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
            space.bitos.app.ui.components.SheetCloseIcon(onClose = onClose)
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
                .weight(1f),
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
                        onReplyTo = { replyTarget = reply },
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
            ReplyBar(
                targetName = feedState.profiles[effectiveTarget.pubkey]?.bestDisplayName
                    ?: shortPubkey(effectiveTarget.pubkey),
                isSubReply = replyTarget != null,
                text = text,
                onText = { if (it.length <= NoteComposer.MAX_NOTE_LENGTH) text = it },
                attachments = attachments,
                uploadStatus = uploadStatus,
                sending = sending,
                powActive = powOutcome != null || powTarget > 0,
                powLabel = (powOutcome?.targetDifficulty ?: powTarget).takeIf { it > 0 }?.let { "$it bits" },
                canAdd = attachments.size < space.bitos.core.publish.ComposerRules.MAX_IMAGES,
                onClearTarget = { replyTarget = null },
                onRemoveAttachment = { attachments.removeAt(it) },
                onPickGallery = { galleryPicker.launch("image/*") },
                onGif = { showGif = true },
                onUrl = { showUrlDialog = true },
                onPow = { showPow = true },
                onSend = {
                    if (!sending && (text.isNotBlank() || attachments.isNotEmpty())) {
                        awaitingReply = true
                        onReply(text, effectiveTarget, attachments.toList(), powOutcome)
                    }
                },
            )
        }
    }

    if (showGif) {
        space.bitos.app.ui.create.GifPickerSheet(
            onPick = { gif ->
                if (attachments.size < space.bitos.core.publish.ComposerRules.MAX_IMAGES) attachments += gif.url
            },
            onDismiss = { showGif = false },
        )
    }

    if (showPow) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showPow = false }) {
            space.bitos.app.ui.components.PowCard(
                target = powTarget,
                onTargetChange = { powTarget = it },
                content = space.bitos.core.publish.ComposerRules.composeContent(text, attachments),
                pubkeyHex = identity.account?.pubkeyHex ?: "",
                // Template tags must byte-match the published reply.
                baseTags = space.bitos.core.publish.NoteComposer.replyTags(
                    rootEventId = effectiveTarget.threadRootId ?: effectiveTarget.id,
                    targetEventId = effectiveTarget.id,
                    targetPubkey = effectiveTarget.pubkey,
                    targetPTags = effectiveTarget.mentions,
                    content = space.bitos.core.publish.ComposerRules.composeContent(text, attachments),
                ).orEmpty(),
                onMined = { outcome ->
                    powOutcome = outcome
                    showPow = false
                },
                modifier = Modifier.padding(horizontal = BitOSSpacing.screen).padding(bottom = BitOSSpacing.xl),
            )
        }
    }

    if (showUrlDialog) {
        var url by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Add media URL") },
            text = {
                space.bitos.app.ui.components.BitosTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = "https://…",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = url.trim()
                        if ((trimmed.startsWith("https://") || trimmed.startsWith("http://")) &&
                            attachments.size < space.bitos.core.publish.ComposerRules.MAX_IMAGES
                        ) {
                            attachments += trimmed
                        }
                        showUrlDialog = false
                    },
                ) { Text("Add") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showUrlDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * APP-009 reply bar (legacy `_ThreadReplyBar` parity): target chip,
 * attachment previews, options row (gallery · GIF · URL · PoW), pill
 * input + circular send with spinner.
 */
@Composable
private fun ReplyBar(
    targetName: String,
    isSubReply: Boolean,
    text: String,
    onText: (String) -> Unit,
    attachments: List<String>,
    uploadStatus: String,
    sending: Boolean,
    powActive: Boolean,
    powLabel: String?,
    canAdd: Boolean,
    onClearTarget: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onPickGallery: () -> Unit,
    onGif: () -> Unit,
    onUrl: () -> Unit,
    onPow: () -> Unit,
    onSend: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs)) {
        if (isSubReply) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Reply to $targetName",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.IconButton(onClick = onClearTarget) {
                    Icon(
                        space.bitos.app.ui.theme.AppIcons.Close,
                        contentDescription = "Cancel reply target",
                        tint = BitOSColors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (uploadStatus.isNotEmpty()) {
            Text(
                uploadStatus,
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.primary,
                fontWeight = FontWeight.W600,
            )
        }
        space.bitos.app.ui.components.AttachmentPreviewRow(urls = attachments, onRemove = onRemoveAttachment)
        // Options row: gallery · GIF · media URL · PoW (legacy order).
        Row(
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OptionButton(space.bitos.app.ui.theme.AppIcons.Photo, "Attach from gallery", enabled = canAdd, onClick = onPickGallery)
            OptionButton(space.bitos.app.ui.theme.AppIcons.Gif, "Add GIF", enabled = canAdd, onClick = onGif)
            OptionButton(space.bitos.app.ui.theme.AppIcons.Globe, "Add media URL", enabled = canAdd, onClick = onUrl)
            OptionButton(
                space.bitos.app.ui.theme.AppIcons.QrCode,
                "Proof of work",
                text = powLabel,
                active = powActive,
                onClick = onPow,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = BitOSColors.surfaceElevated.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f),
            ) {
                space.bitos.app.ui.components.BitosPlainTextField(
                    value = text,
                    onValueChange = onText,
                    placeholder = "Write a reply…",
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Spacer(Modifier.width(BitOSSpacing.sm))
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = if (sending || (text.isBlank() && attachments.isEmpty())) BitOSColors.surfaceElevated else BitOSColors.primaryContainer,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(
                        onClickLabel = "Send reply",
                        enabled = !sending && (text.isNotBlank() || attachments.isNotEmpty()),
                    ) { onSend() },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (sending) {
                        androidx.compose.material3.CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                            color = BitOSColors.primary,
                        )
                    } else {
                        Icon(
                            space.bitos.app.ui.theme.AppIcons.Send,
                            contentDescription = null,
                            tint = if (text.isBlank() && attachments.isEmpty()) BitOSColors.textTertiary else BitOSColors.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    text: String? = null,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .size(width = if (text != null) 72.dp else 40.dp, height = 40.dp)
            .clickable(enabled = enabled, onClickLabel = label) { onClick() },
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> BitOSColors.textTertiary.copy(alpha = 0.4f)
                active -> BitOSColors.primary
                else -> BitOSColors.textSecondary
            },
            modifier = Modifier.size(20.dp),
        )
        if (text != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.W700,
                color = if (active) BitOSColors.primary else BitOSColors.textSecondary,
            )
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
    onReplyTo: () -> Unit = {},
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
                // APP-009: retarget the reply bar at this card (legacy
                // `controller.replyTo` parity).
                androidx.compose.material3.TextButton(
                    onClick = onReplyTo,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Icon(
                        space.bitos.app.ui.theme.AppIcons.Comment,
                        contentDescription = null,
                        tint = BitOSColors.reply,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Reply", style = MaterialTheme.typography.labelSmall, color = BitOSColors.reply, fontWeight = FontWeight.W600)
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
