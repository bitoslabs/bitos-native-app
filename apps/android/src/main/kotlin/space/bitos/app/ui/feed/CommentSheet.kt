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
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    actions: LocalActions,
    onLoadComments: (String) -> Unit,
    onReply: (String, FeedNote, List<String>, space.bitos.app.ui.components.PowOutcome?) -> Unit,
    onLike: (FeedNote) -> Unit,
    onRepost: (FeedNote) -> Unit,
    onBookmark: (String) -> Unit,
    onZap: (FeedNote) -> Unit,
    /** UX-010: author avatar/name taps open the profile sheet. */
    onOpenAuthor: (String) -> Unit = {},
    /** Own-note deletion (NIP-09 kind-5) — hosts gate to own notes. */
    onDelete: (FeedNote) -> Unit = {},
    /** NIP-22 comment publish for non-kind-1 roots (web `feed.comment`). */
    onComment: (String, FeedNote, FeedNote?, List<String>) -> Unit = { _, _, _, _ -> },
    onClose: () -> Unit,
) {
    val identity by identityViewModel.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    // NIP-22 mode (ADR-003): non-kind-1 roots publish kind-1111 comments
    // instead of kind-1 replies; PoW rides only the kind-1 path.
    val commentMode = note.kind != space.bitos.core.model.NostrKinds.SHORT_TEXT_NOTE
    // Own-note deletion: rows hide locally once the kind-5 is dispatched.
    val deletedIds = remember { mutableStateOf(setOf<String>()) }
    var deleteConfirm by remember { mutableStateOf<FeedNote?>(null) }
    // Rich comment bodies: media tile tap → lightbox; link tap → confirm.
    var lightboxUrl by remember { mutableStateOf<String?>(null) }
    var externalLink by remember { mutableStateOf<String?>(null) }
    val comments = feedState.comments[note.id].orEmpty().filter { it.id !in deletedIds.value }
    // APP-009 X-style threading (shared ThreadAssembly): top-level +
    // flattened descendants behind depth indents.
    val thread = feedState.threads[note.id].orEmpty().filter { it.id !in deletedIds.value }
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
    var showEmoji by remember { mutableStateOf(false) }
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
            Text("Comments", style = MaterialTheme.typography.headlineMedium)
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

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            // Legacy parity: the root post scrolls with its replies.
            item(key = "root-${note.id}") {
                RootCard(
                    note = note,
                    profile = feedState.profiles[note.pubkey],
                    replyCount = comments.size,
                    tally = tally,
                    isLiked = note.id in actions.liked,
                    isBookmarked = note.id in feedState.bookmarkedIds || note.id in actions.bookmarked,
                    resolveMentionName = { hex -> feedState.profiles[hex]?.bestDisplayName },
                    onOpenMentionProfile = onOpenAuthor,
                    onOpenExternalLink = { externalLink = it },
                    onOpenMedia = { lightboxUrl = it },
                    onLike = { onLike(note) },
                    onRepost = { onRepost(note) },
                    onBookmark = { onBookmark(note.id) },
                    onZap = { onZap(note) },
                    onOpenAuthor = { onOpenAuthor(note.pubkey) },
                    canDelete = note.pubkey == identity.account?.pubkeyHex,
                    onDelete = { deleteConfirm = note },
                )
            }
            items(thread, key = { it.id }) { item ->
                noteById[item.id]?.let { reply ->
                    ReplyRow(
                        reply = reply,
                        profile = feedState.profiles[reply.pubkey],
                        depth = item.depth,
                        orphan = item.orphan,
                        tally = feedState.tallies[reply.id],
                        isLiked = reply.id in actions.liked,
                        resolveMentionName = { hex -> feedState.profiles[hex]?.bestDisplayName },
                        onOpenMentionProfile = onOpenAuthor,
                        onOpenExternalLink = { externalLink = it },
                        onOpenMedia = { lightboxUrl = it },
                        onLike = { onLike(reply) },
                        onZap = { onZap(reply) },
                        onOpenAuthor = { onOpenAuthor(reply.pubkey) },
                        canDelete = reply.pubkey == identity.account?.pubkeyHex,
                        onDelete = { deleteConfirm = reply },
                        canReply = item.depth == 0,
                        onReplyTo = { replyTarget = reply },
                    )
                }
            }
            if (comments.isEmpty()) {
                item(key = "replies-empty") {
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
                powAvailable = !commentMode,
                canAdd = attachments.size < space.bitos.core.publish.ComposerRules.MAX_IMAGES,
                onClearTarget = { replyTarget = null },
                onRemoveAttachment = { attachments.removeAt(it) },
                onPickGallery = { galleryPicker.launch("image/*") },
                onGif = { showGif = true },
                onUrl = { showUrlDialog = true },
                onPow = { showPow = true },
                onHashtag = {
                    // No cursor tracking in the pill field — insert at the end.
                    text = space.bitos.core.publish.ComposerRules.insertHashtag(text, text.length).first
                },
                onEmoji = { showEmoji = true },
                onSend = {
                    if (!sending && (text.isNotBlank() || attachments.isNotEmpty())) {
                        awaitingReply = true
                        if (commentMode) {
                            onComment(text, note, replyTarget, attachments.toList())
                        } else {
                            onReply(text, effectiveTarget, attachments.toList(), powOutcome)
                        }
                    }
                },
            )
        }
    }

    // Media tile lightbox (feed-card parity): zoomable fullscreen viewer.
    lightboxUrl?.let { url ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { lightboxUrl = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            space.bitos.app.ui.components.MediaLightbox(url = url, onDismiss = { lightboxUrl = null })
        }
    }

    // External links in comment bodies: confirm first (never unattended —
    // the browser only opens on an explicit Open).
    externalLink?.let { url ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { externalLink = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = BitOSSpacing.screen).padding(bottom = BitOSSpacing.lg)) {
                Text("Open external link?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W700)
                Spacer(Modifier.height(BitOSSpacing.sm))
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = BitOSColors.textSecondary,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text("This link leaves BitOS.", style = MaterialTheme.typography.labelSmall, color = BitOSColors.textTertiary)
                Spacer(Modifier.height(BitOSSpacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                    androidx.compose.material3.Button(
                        onClick = {
                            runCatching {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                            }
                            externalLink = null
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = BitOSColors.primary,
                            contentColor = androidx.compose.ui.graphics.Color(0xFF0A0A0F),
                        ),
                    ) { Text("Open", fontWeight = androidx.compose.ui.text.font.FontWeight.W600) }
                    androidx.compose.material3.OutlinedButton(onClick = { externalLink = null }) {
                        Text("Cancel", color = BitOSColors.primary)
                    }
                }
            }
        }
    }

    // Emoji insert (composer toolbar parity): the shared quick-emoji set,
    // appended at the end of the reply (the pill field has no cursor tracking).
    if (showEmoji) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showEmoji = false }) {
            Column(Modifier.padding(bottom = BitOSSpacing.xl)) {
                Text(
                    "Insert emoji",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = BitOSSpacing.screen),
                )
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(8),
                    modifier = Modifier.padding(horizontal = BitOSSpacing.base).height(220.dp),
                ) {
                    gridItems(space.bitos.core.publish.ComposerRules.COMPOSER_EMOJIS) { emoji ->
                        androidx.compose.material3.IconButton(onClick = {
                            text = space.bitos.core.publish.ComposerRules
                                .insertEmoji(text, text.length, emoji).first
                            showEmoji = false
                        }) {
                            Text(emoji, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
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

    // Own-note delete confirmation (kind-5, NIP-09).
    deleteConfirm?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text("Delete this note?") },
            text = { Text("The deletion publishes to your relays and cannot be undone.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onDelete(target)
                    if (target.id == note.id) onClose() else deletedIds.value = deletedIds.value + target.id
                    deleteConfirm = null
                }) { Text("Delete", color = BitOSColors.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteConfirm = null }) { Text("Cancel") }
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
    /** NIP-22 comment mode: PoW rides only the kind-1 reply path. */
    powAvailable: Boolean = true,
    canAdd: Boolean,
    onClearTarget: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onPickGallery: () -> Unit,
    onGif: () -> Unit,
    onUrl: () -> Unit,
    onPow: () -> Unit,
    /** Text-insert helpers (composer toolbar parity). */
    onHashtag: () -> Unit = {},
    onEmoji: () -> Unit = {},
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
        // Options row — the same Solar tokens as the composer toolbar:
        // gallery · GIF · URL · PoW · hashtag · emoji (legacy reply order,
        // PoW only on the kind-1 reply path).
        Row(
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OptionButton(space.bitos.app.ui.theme.SolarFeedIcon.Gallery, "Attach from gallery", enabled = canAdd, onClick = onPickGallery)
            OptionButton(space.bitos.app.ui.theme.SolarFeedIcon.Film, "Add GIF", enabled = canAdd, onClick = onGif)
            OptionButton(space.bitos.app.ui.theme.SolarFeedIcon.LinkCircle, "Add media URL", enabled = canAdd, onClick = onUrl)
            if (powAvailable) {
                OptionButton(
                    space.bitos.app.ui.theme.SolarFeedIcon.ShieldCheck,
                    "Proof of work",
                    text = powLabel,
                    active = powActive,
                    onClick = onPow,
                )
            }
            OptionButton(space.bitos.app.ui.theme.SolarFeedIcon.Hashtag, "Insert hashtag", onClick = onHashtag)
            OptionButton(space.bitos.app.ui.theme.SolarFeedIcon.Emoji, "Insert emoji", onClick = onEmoji)
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
                        // Solar "plain" (paper plane) — legacy send parity.
                        space.bitos.app.ui.theme.SolarFeedIconImage(
                            space.bitos.app.ui.theme.SolarFeedIcon.Send,
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
    icon: space.bitos.app.ui.theme.SolarFeedIcon,
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
        space.bitos.app.ui.theme.SolarFeedIconImage(
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
 * APP-009 root card: author row + body + the legacy interactive action row
 * (like · replies · zap · repost · bookmark — Solar icons, live tallies)
 * + raw-note ⋯.
 */
@Composable
private fun RootCard(
    note: FeedNote,
    profile: space.bitos.core.model.ProfileMetadata?,
    replyCount: Int,
    tally: space.bitos.core.feed.NoteTally?,
    isLiked: Boolean,
    isBookmarked: Boolean,
    /** Rich body (NIP-27 tokens): mentions/links tappable, media as tiles. */
    resolveMentionName: (String) -> String? = { null },
    onOpenMentionProfile: (String) -> Unit = {},
    onOpenExternalLink: (String) -> Unit = {},
    onOpenMedia: (String) -> Unit = {},
    onLike: () -> Unit,
    onRepost: () -> Unit,
    onBookmark: () -> Unit,
    onZap: () -> Unit,
    onOpenAuthor: () -> Unit = {},
    /** Own notes only (NIP-09 kind-5 delete). */
    canDelete: Boolean = false,
    onDelete: () -> Unit = {},
) {
    var showRaw by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    if (showRaw) {
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        val npub = remember(note.pubkey) {
            space.bitos.core.identity.NostrKeyCodec.npub(note.pubkey)
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRaw = false },
            title = { Text("Raw note") },
            text = {
                Column {
                    Text(
                        "id: ${note.id}\nauthor: ${note.pubkey}\nkind: ${note.kind}\nat: ${note.createdAt}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    // Web PostCard menu parity: diagnostic copies.
                    androidx.compose.material3.TextButton(
                        onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(note.id)) },
                    ) { Text("Copy note ID", color = BitOSColors.primary) }
                    if (npub != null) {
                        androidx.compose.material3.TextButton(
                            onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(npub)) },
                        ) { Text("Copy author npub", color = BitOSColors.primary) }
                    }
                    androidx.compose.material3.TextButton(
                        onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(note.content)) },
                    ) { Text("Copy note text", color = BitOSColors.primary) }
                    // APP-009 ⋯ parity (mockup app-10): share + web link.
                    androidx.compose.material3.TextButton(
                        onClick = {
                            val text = npub?.let { space.bitos.core.feed.NoteShare.text(note.content, it) } ?: note.content
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(android.content.Intent.createChooser(send, null))
                        },
                    ) { Text("Share", color = BitOSColors.primary) }
                    androidx.compose.material3.TextButton(
                        onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString("https://njump.me/${note.id}")) },
                    ) { Text("Copy link", color = BitOSColors.primary) }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showRaw = false }) { Text("Close") }
            },
        )
    }
    Surface(shape = RoundedCornerShape(14.dp), color = BitOSColors.surface) {
        Column(Modifier.padding(BitOSSpacing.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Open author profile") { onOpenAuthor() },
            ) {
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
            // Rich body: NIP-27 entities tappable; bare media links render
            // as tiles below and disappear from the text (feed-card parity).
            space.bitos.app.ui.components.RichText(
                tokens = remember(note.content) { space.bitos.core.nostr.Nip27.tokenize(note.content) },
                hiddenMediaUrls = remember(note.mediaUrls) { note.mediaUrls.toSet() },
                resolveMentionName = resolveMentionName,
                onOpenProfile = onOpenMentionProfile,
                onOpenExternalLink = onOpenExternalLink,
            )
            if (note.mediaUrls.isNotEmpty()) {
                space.bitos.app.ui.components.MediaRow(urls = note.mediaUrls, onOpen = onOpenMedia)
            }
            Spacer(Modifier.height(BitOSSpacing.md))
            // Legacy _ThreadActionRow parity: Solar icon + bold count.
            Row(verticalAlignment = Alignment.CenterVertically) {
                CommentAction(
                    icon = if (isLiked) space.bitos.app.ui.theme.SolarFeedIcon.HeartFilled else space.bitos.app.ui.theme.SolarFeedIcon.Heart,
                    label = "${tally?.reactions ?: 0}",
                    tint = if (isLiked) BitOSColors.like else BitOSColors.textSecondary,
                    onClick = onLike,
                )
                CommentAction(
                    icon = space.bitos.app.ui.theme.SolarFeedIcon.Comment,
                    label = "$replyCount",
                    tint = BitOSColors.reply,
                    onClick = {},
                )
                val zapSats = ((tally?.zapMillisats ?: 0L) / 1000L)
                CommentAction(
                    icon = space.bitos.app.ui.theme.SolarFeedIcon.Zap,
                    label = listOfNotNull("${tally?.zaps ?: 0}", if (zapSats > 0) space.bitos.core.model.ZapFormat.sats(zapSats) else null)
                        .joinToString(" · "),
                    tint = BitOSColors.zap,
                    onClick = onZap,
                )
                CommentAction(
                    icon = space.bitos.app.ui.theme.SolarFeedIcon.Repost,
                    label = "${tally?.reposts ?: 0}",
                    tint = BitOSColors.repost,
                    onClick = onRepost,
                )
                CommentAction(
                    icon = if (isBookmarked) space.bitos.app.ui.theme.SolarFeedIcon.BookmarkFilled else space.bitos.app.ui.theme.SolarFeedIcon.Bookmark,
                    label = "",
                    tint = if (isBookmarked) BitOSColors.bookmark else BitOSColors.textSecondary,
                    onClick = onBookmark,
                )
                Spacer(Modifier.weight(1f))
                if (canDelete) {
                    androidx.compose.material3.TextButton(onClick = onDelete) {
                        Icon(
                            space.bitos.app.ui.theme.AppIcons.Delete,
                            contentDescription = "Delete this note",
                            tint = BitOSColors.error,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                androidx.compose.material3.IconButton(onClick = { showRaw = true }) {
                    Icon(
                        space.bitos.app.ui.theme.AppIcons.More,
                        contentDescription = "Raw note details",
                        tint = BitOSColors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * Legacy `_CommentActionButton` parity: 13 dp Solar icon + 11 sp bold label,
 * ghost chrome (no container); an optional count rides as a " · N" trailing.
 */
@Composable
private fun CommentAction(
    icon: space.bitos.app.ui.theme.SolarFeedIcon,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Comment action") { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        space.bitos.app.ui.theme.SolarFeedIconImage(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        if (label.isNotEmpty() || trailing != null) {
            Spacer(Modifier.width(3.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.W700),
                color = tint,
            )
            if (trailing != null) {
                Text(
                    " · $trailing",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.W600),
                    color = tint,
                )
            }
        }
    }
}

/**
 * One comment row (legacy `_CommentRow` parity): flat chrome — avatar 28
 * (22 for sub-replies), bold author + time, body, ghost Like · Zap · Reply
 * with Solar icons and " · count" trailings. Descendants sit behind a
 * conversation rail, never in per-row cards.
 */
@Composable
private fun ReplyRow(
    reply: FeedNote,
    profile: space.bitos.core.model.ProfileMetadata?,
    depth: Int,
    orphan: Boolean,
    tally: space.bitos.core.feed.NoteTally? = null,
    isLiked: Boolean = false,
    /** Rich body (NIP-27 tokens): mentions/links tappable, media as tiles. */
    resolveMentionName: (String) -> String? = { null },
    onOpenMentionProfile: (String) -> Unit = {},
    onOpenExternalLink: (String) -> Unit = {},
    onOpenMedia: (String) -> Unit = {},
    onLike: () -> Unit = {},
    onZap: () -> Unit = {},
    onOpenAuthor: () -> Unit = {},
    /** Own notes only (NIP-09 kind-5 delete). */
    canDelete: Boolean = false,
    onDelete: () -> Unit = {},
    /** Only root comments may receive one web-parity nested reply. */
    canReply: Boolean = true,
    onReplyTo: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp),
    ) {
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
        PubkeyAvatar(
            pubkey = reply.pubkey,
            size = if (depth > 0) 22 else 28,
            modifier = Modifier.clickable(onClickLabel = "Open author profile") { onOpenAuthor() },
        )
        Spacer(Modifier.width(BitOSSpacing.sm))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile?.bestDisplayName ?: shortPubkey(reply.pubkey),
                    style = if (depth > 0) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.W700,
                    color = BitOSColors.textPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClickLabel = "Open author profile") { onOpenAuthor() }
                        .weight(1f, fill = false),
                )
                Spacer(Modifier.width(BitOSSpacing.xs))
                Text(
                    formatTimeAgo(reply.createdAt, System.currentTimeMillis() / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textTertiary,
                )
            }
            Spacer(Modifier.height(2.dp))
            // Rich body: NIP-27 entities tappable; bare media links render
            // as tiles below (feed-card parity).
            space.bitos.app.ui.components.RichText(
                tokens = remember(reply.content) { space.bitos.core.nostr.Nip27.tokenize(reply.content) },
                hiddenMediaUrls = remember(reply.mediaUrls) { reply.mediaUrls.toSet() },
                resolveMentionName = resolveMentionName,
                onOpenProfile = onOpenMentionProfile,
                onOpenExternalLink = onOpenExternalLink,
            )
            if (reply.mediaUrls.isNotEmpty()) {
                space.bitos.app.ui.components.MediaRow(urls = reply.mediaUrls, onOpen = onOpenMedia)
            }
            if (orphan) {
                Text(
                    "Reply above unavailable",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textTertiary,
                )
            }
            Spacer(Modifier.height(2.dp))
            // Legacy parity: web comment action row — Like (+count) · Zap
            // (+sats) · Reply (retargets the reply bar).
            Row(verticalAlignment = Alignment.CenterVertically) {
                val likeCount = tally?.reactions ?: 0
                CommentAction(
                    icon = if (isLiked) space.bitos.app.ui.theme.SolarFeedIcon.HeartFilled else space.bitos.app.ui.theme.SolarFeedIcon.Heart,
                    label = if (isLiked) "Unlike" else "Like",
                    trailing = if (likeCount > 0) "$likeCount" else null,
                    tint = if (isLiked) BitOSColors.like else BitOSColors.textSecondary,
                    onClick = onLike,
                )
                val sats = (tally?.zapMillisats ?: 0L) / 1000
                CommentAction(
                    icon = space.bitos.app.ui.theme.SolarFeedIcon.Zap,
                    label = "Zap",
                    trailing = if (sats > 0) space.bitos.core.model.ZapFormat.sats(sats) else null,
                    tint = BitOSColors.zap,
                    onClick = onZap,
                )
                if (canReply) {
                    CommentAction(
                        icon = space.bitos.app.ui.theme.SolarFeedIcon.Comment,
                        label = "Reply",
                        tint = BitOSColors.textSecondary,
                        onClick = onReplyTo,
                    )
                }
                if (canDelete) {
                    androidx.compose.material3.TextButton(
                        onClick = onDelete,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text("Delete", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.W700, color = BitOSColors.error)
                    }
                }
            }
        }
    }
}

/**
 * Full comment-thread host for surfaces that do not own their own zap
 * chrome (Discover, Inbox, Bookmarks): renders the thread sheet with the
 * interactive per-comment actions and stacks the zap sheet above it when a
 * comment's Zap is tapped. FeedScreen/BitzScreen wire the same actions to
 * their existing zap sheets directly.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CommentThreadSheet(
    note: FeedNote,
    viewModel: HomeViewModel,
    identityViewModel: IdentityViewModel,
    publisherState: PublishUiState,
    onDismiss: () -> Unit,
    /** UX-010: author taps open the profile sheet (host-owned). */
    onOpenAuthor: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions by viewModel.localActions.collectAsStateWithLifecycle()
    val zapState by viewModel.zapState.collectAsStateWithLifecycle()
    val identityState by identityViewModel.state.collectAsStateWithLifecycle()
    var zapTarget by remember { mutableStateOf<FeedNote?>(null) }

    CommentContent(
        note = note,
        feedState = state,
        identityViewModel = identityViewModel,
        publisherState = publisherState,
        actions = actions,
        onLoadComments = viewModel::loadComments,
        onReply = { text, target, attachments, pow -> viewModel.reply(text, target, attachments, pow) },
        onLike = viewModel::toggleLike,
        onRepost = viewModel::repost,
        onBookmark = viewModel::toggleBookmark,
        onZap = { reply ->
            viewModel.loadZaps(reply.id)
            zapTarget = reply
        },
        onOpenAuthor = onOpenAuthor,
        onDelete = viewModel::deleteNote,
        onComment = viewModel::comment,
        onClose = onDismiss,
    )

    zapTarget?.let { zapNote ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { viewModel.dismissZap(); zapTarget = null }) {
            ZapContent(
                note = zapNote,
                lud16 = state.profiles[zapNote.pubkey]?.lud16,
                state = zapState,
                profileName = state.profiles[zapNote.pubkey]?.bestDisplayName,
                hasIdentity = identityState.account != null,
                zapCount = state.zapCounts[zapNote.id] ?: 0,
                paidRequestIds = state.zapRequestIds[zapNote.id] ?: emptySet(),
                onPaid = { sats, memo -> viewModel.onZapPaid(zapNote, sats, memo) },
                onAmountSelected = viewModel::selectZapAmount,
                onZap = { sats, comment, anonymous ->
                    viewModel.selectZapAmount(sats)
                    viewModel.zap(zapNote, comment, anonymous)
                },
                onClose = { viewModel.dismissZap(); zapTarget = null },
                profilePictureUrl = state.profiles[zapNote.pubkey]?.picture,
            )
        }
    }
}
