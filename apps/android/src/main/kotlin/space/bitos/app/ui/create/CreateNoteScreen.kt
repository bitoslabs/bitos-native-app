package space.bitos.app.ui.create

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.bitos.app.data.feed.DefaultRelays
import space.bitos.app.data.media.BlossomUploader
import space.bitos.app.data.media.DefaultBlossomServer
import space.bitos.app.data.publish.NotePublisher
import space.bitos.app.data.publish.PublishResult
import space.bitos.app.data.publish.PublishUiState
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.components.PowCard
import space.bitos.app.ui.components.PowOutcome
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.feed.HomeViewModel
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.publish.ComposerRules

/**
 * APP-008 note composer PAGE (legacy Flutter `CreateView` parity — a full
 * screen, not a sheet): author header, mention-aware field with @-autocomplete,
 * ≤4 image grid (gallery picks + URLs), content-warning field, upload
 * status, toolbar (image/URL/CW/hashtag/emoji/PoW) with the 4,000/16,000
 * character counter, and the published success state. All rules come from
 * shared `ComposerRules`; uploads run hash-verified through Blossom before
 * anything is signed.
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun CreateNoteScreen(
    identityViewModel: IdentityViewModel,
    notePublisher: NotePublisher,
    homeViewModel: HomeViewModel,
    draftStore: space.bitos.app.data.publish.ComposerDraftStore? = null,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val identity by identityViewModel.state.collectAsStateWithLifecycle()
    val feedState by homeViewModel.state.collectAsStateWithLifecycle()
    val publishState by notePublisher.state.collectAsStateWithLifecycle()

    // APP-008 draft persistence: restore once, autosave every change,
    // clear on publish/new post; discard-confirm on close.
    val restored = remember { draftStore?.load() }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    var field by remember { mutableStateOf(TextFieldValue(restored?.text ?: "")) }
    val trackedMentions = remember { mutableStateListOf<Pair<String, String>>().also { list -> restored?.trackedMentions?.forEach { (n, u) -> list += n to u } } }
    val remoteImageUrls = remember { mutableStateListOf<String>().also { list -> restored?.remoteUrls?.let(list::addAll) } }
    val pickedKeys = remember { mutableStateListOf<String>() } // uri#mime
    var contentWarningOn by remember { mutableStateOf(restored?.contentWarningReason != null) }
    var contentWarningReason by remember { mutableStateOf(restored?.contentWarningReason ?: "") }
    var uploadStatus by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var published by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var powOutcome by remember { mutableStateOf<PowOutcome?>(null) }
    var powTarget by remember { mutableStateOf(restored?.powTarget ?: 0) }
    var showEmoji by remember { mutableStateOf(false) }
    var showPow by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showPoll by remember { mutableStateOf(false) }
    val uploader = remember { BlossomUploader() }

    val counter = remember(field.text) { ComposerRules.counterState(field.text.length) }
    val mediaCount = remoteImageUrls.size + pickedKeys.size
    val canAddImage = mediaCount < ComposerRules.MAX_IMAGES
    val mentionQuery = ComposerRules.mentionQueryAt(field.text, field.selection.end)
    val suggestions = remember(mentionQuery, feedState.profiles) {
        ComposerRules.mentionSuggestions(mentionQuery ?: "", feedState.profiles.values)
    }

    val canPublish = !busy && !published &&
        (field.text.isNotBlank() || remoteImageUrls.isNotEmpty() || pickedKeys.isNotEmpty()) &&
        field.text.length <= ComposerRules.HARD_LIMIT

    fun insert(transform: (String, Int) -> Pair<String, Int>) {
        val (next, cursor) = transform(field.text, field.selection.end)
        field = TextFieldValue(next, TextRange(cursor))
    }

    val draft = space.bitos.core.publish.ComposerDraft(
        text = field.text,
        remoteUrls = remoteImageUrls.toList(),
        contentWarningReason = contentWarningReason.takeIf { contentWarningOn },
        trackedMentions = trackedMentions.toList(),
        powTarget = powTarget,
    )
    LaunchedEffect(draft) { draftStore?.save(draft) }

    fun pickMention(suggestion: ComposerRules.MentionSuggestion) {
        val cursor = field.selection.end
        val start = field.text.lastIndexOf('@', cursor - 1)
        if (start >= 0) {
            val next = field.text.replaceRange(start, cursor, "@${suggestion.name} ")
            field = TextFieldValue(next, TextRange(start + suggestion.name.length + 2))
            trackedMentions += suggestion.name to suggestion.npub
        }
    }

    val galleryPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let { pickedKeys += "${it}|${context.contentResolver.getType(it) ?: "image/png"}" } }

    /** Upload-before-sign (legacy parity): local picks become public URLs. */
    fun publish() {
        if (!canPublish) return
        busy = true
        errorMessage = ""
        uploadStatus = ""
        scope.launch {
            try {
                val signer = identityViewModel.createSigner()
                    ?: throw IllegalStateException("Importing needs an identity (Profile tab).")
                val uploadedUrls = mutableListOf<String>()
                pickedKeys.forEachIndexed { index, key ->
                    val (uriString, mime) = key.substringBeforeLast('|') to key.substringAfterLast('|', "image/png")
                    uploadStatus = if (pickedKeys.size == 1) "Uploading media…" else "Uploading media ${index + 1}/${pickedKeys.size}…"
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(android.net.Uri.parse(uriString))?.use { it.readBytes() }
                    } ?: throw BlossomUploader.UploadFailure("could not read the picked file")
                    val media = uploader.upload(bytes, mime, signer, DefaultBlossomServer.url)
                    uploadedUrls += media.url
                }
                uploadStatus = "Publishing…"
                val rewritten = ComposerRules.rewriteMentions(field.text, trackedMentions)
                val content = ComposerRules.composeContent(rewritten, remoteImageUrls + uploadedUrls)
                val tags = ComposerRules.deriveTags(content, contentWarningReason.takeIf { contentWarningOn })
                val outcome = powOutcome
                if (outcome != null) {
                    notePublisher.publishPowNoteWith(
                        content, tags, outcome.nonce, outcome.targetDifficulty, outcome.createdAtSeconds,
                        { identityViewModel.createSigner() }, DefaultRelays.writeUrls,
                    )
                } else {
                    notePublisher.publishNoteWith(
                        content, tags,
                        { identityViewModel.createSigner() }, DefaultRelays.writeUrls,
                    )
                }
                published = true
                draftStore?.clear()
            } catch (failure: Exception) {
                errorMessage = failure.message ?: "Publish failed."
            } finally {
                busy = false
                uploadStatus = ""
            }
        }
    }

    Scaffold(
        containerColor = BitOSColors.background,
        topBar = {
            TopAppBar(
                title = { Text("New note", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!published && !draft.isEmpty) showDiscardConfirm = true else onClose()
                    }) {
                        Icon(AppIcons.Close, contentDescription = "Close", tint = BitOSColors.textSecondary)
                    }
                },
                actions = {
                    TextButton(onClick = { publish() }, enabled = canPublish) {
                        if (busy) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        } else {
                            Text("Publish", color = if (canPublish) BitOSColors.primary else BitOSColors.textTertiary)
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (published) {
            PublishedState(
                onBack = onClose,
                onNew = {
                    notePublisher.dismiss()
                    field = TextFieldValue("")
                    trackedMentions.clear()
                    remoteImageUrls.clear()
                    pickedKeys.clear()
                    contentWarningOn = false
                    contentWarningReason = ""
                    powOutcome = null
                    published = false
                    draftStore?.clear()
                },
            )
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.md),
                verticalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
            ) {
                // Author header (web Composer parity).
                val profile = feedState.profiles[identity.account?.pubkeyHex]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PubkeyAvatar(
                        pubkey = identity.account?.pubkeyHex ?: "",
                        size = 40,
                        label = profile?.bestDisplayName,
                    )
                    Spacer(Modifier.width(BitOSSpacing.md))
                    Column {
                        Text(
                            profile?.bestDisplayName ?: "You",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.W700,
                            color = BitOSColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("Now", style = MaterialTheme.typography.labelSmall, color = BitOSColors.textTertiary)
                    }
                }
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    placeholder = { Text("What's happening?") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Mention autocomplete (≤6, web `candidates` parity).
                if (suggestions.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = BitOSColors.surfaceElevated,
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
                // Media grid: remote URLs + local picks, removable.
                if (mediaCount > 0) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                        items(remoteImageUrls.toList()) { url ->
                            MediaThumb(label = url.substringAfterLast('/').take(14)) { remoteImageUrls.remove(url) }
                        }
                        items(pickedKeys.toList()) { key ->
                            MediaThumb(label = "image") { pickedKeys.remove(key) }
                        }
                    }
                }
                if (contentWarningOn) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0x1AF5A623),
                        modifier = Modifier.border(1.dp, Color(0x4DF5A623), RoundedCornerShape(10.dp)),
                    ) {
                        Column(Modifier.padding(BitOSSpacing.sm)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.ReportSpam, contentDescription = null, tint = Color(0xFFF5A623), modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Content warning",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.W600,
                                    color = Color(0xFFF5A623),
                                )
                            }
                            OutlinedTextField(
                                value = contentWarningReason,
                                onValueChange = { contentWarningReason = it.take(120) },
                                placeholder = { Text("Why is this sensitive? (optional)") },
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (errorMessage.isNotEmpty()) {
                    Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = BitOSColors.error)
                }
                (publishState.result)?.let { result ->
                    if (result != PublishResult.PUBLISHED) {
                        Text(
                            when (result) {
                                PublishResult.SIGNING_REFUSED -> "Signing refused — add an identity first."
                                PublishResult.REJECTED -> "Relays rejected the note."
                                PublishResult.TIMEOUT -> "No relay receipt before timeout."
                                else -> "Publish failed."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = BitOSColors.error,
                        )
                    }
                }
            }
            if (uploadStatus.isNotEmpty()) {
                Surface(color = BitOSColors.primary.copy(alpha = 0.08f)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = BitOSSpacing.base, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp), color = BitOSColors.primary)
                        Spacer(Modifier.width(BitOSSpacing.sm))
                        Text(uploadStatus, style = MaterialTheme.typography.bodySmall, color = BitOSColors.primary, fontWeight = FontWeight.W600)
                    }
                }
            }
            ComposerToolbar(
                canAddImage = canAddImage,
                mediaCount = mediaCount,
                contentWarningOn = contentWarningOn,
                powAvailable = pickedKeys.isEmpty(),
                powActive = powOutcome != null || powTarget > 0,
                powBadge = powOutcome?.targetDifficulty?.toString() ?: powTarget.takeIf { it > 0 }?.toString(),
                counter = counter,
                onPickImage = { if (canAddImage) galleryPicker.launch("image/*") },
                onAddUrl = { showUrlDialog = true },
                onToggleCw = { contentWarningOn = !contentWarningOn; if (!contentWarningOn) contentWarningReason = "" },
                onHashtag = { insert(ComposerRules::insertHashtag) },
                onEmoji = { showEmoji = true },
                onPow = { showPow = true },
                onPoll = { showPoll = true },
            )
        }
    }

    BackHandler(enabled = !published && !draft.isEmpty) {
        showDiscardConfirm = true
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Discard draft?") },
            text = { Text("Your draft is saved on this device. Discard it and close the composer?") },
            confirmButton = {
                Button(onClick = {
                    draftStore?.clear()
                    showDiscardConfirm = false
                    onClose()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { showDiscardConfirm = false }) { Text("Keep editing") } },
        )
    }

    if (showEmoji) {
        ModalBottomSheet(onDismissRequest = { showEmoji = false }) {
            Column(Modifier.padding(bottom = BitOSSpacing.xl)) {
                Text("Insert emoji", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = BitOSSpacing.screen))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(8),
                    modifier = Modifier.padding(horizontal = BitOSSpacing.base).height(220.dp),
                ) {
                    items(ComposerRules.COMPOSER_EMOJIS) { emoji ->
                        IconButton(onClick = {
                            insert { text, cursor -> ComposerRules.insertEmoji(text, cursor, emoji) }
                            showEmoji = false
                        }) {
                            Text(emoji, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
        }
    }

    if (showPow) {
        ModalBottomSheet(onDismissRequest = { showPow = false }) {
            PowCard(
                target = powTarget,
                onTargetChange = { powTarget = it },
                content = ComposerRules.composeContent(
                    ComposerRules.rewriteMentions(field.text, trackedMentions),
                    remoteImageUrls,
                ),
                pubkeyHex = identity.account?.pubkeyHex ?: "",
                // Template tags must byte-match the published event.
                baseTags = ComposerRules.deriveTags(
                    field.text,
                    contentWarningReason.takeIf { contentWarningOn },
                ),
                onMined = { outcome ->
                    powOutcome = outcome
                    showPow = false
                },
                modifier = Modifier.padding(horizontal = BitOSSpacing.screen).padding(bottom = BitOSSpacing.xl),
            )
        }
    }

    if (showPoll) {
        PollComposerSheet(
            publishing = busy,
            onPost = { question, options ->
                val pollTags = space.bitos.core.model.PollContract.pollTags(question, options)
                if (pollTags != null) {
                    val tags = pollTags + space.bitos.core.publish.ComposerRules.deriveTags(question.trim())
                        .filter { it.firstOrNull() == "t" }
                    busy = true
                    scope.launch {
                        try {
                            notePublisher.publishNoteWith(
                                question.trim(), tags,
                                { identityViewModel.createSigner() }, DefaultRelays.writeUrls,
                            )
                            published = true
                        } finally {
                            busy = false
                        }
                    }
                }
                showPoll = false
            },
            onDismiss = { showPoll = false },
        )
    }

    if (showUrlDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Add image URL") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("https://example.com/image.jpg") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = url.trim()
                        if ((trimmed.startsWith("https://") || trimmed.startsWith("http://")) && canAddImage) {
                            remoteImageUrls += trimmed
                        }
                        showUrlDialog = false
                    },
                ) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { showUrlDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MediaThumb(label: String, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = BitOSColors.surfaceElevated,
        modifier = Modifier.size(96.dp),
    ) {
        Box {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Photo, contentDescription = null, tint = BitOSColors.textTertiary)
                Text(label, style = MaterialTheme.typography.labelSmall, color = BitOSColors.textTertiary)
            }
            Surface(
                shape = CircleShape,
                color = Color(0x99000000),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .clickable(onClickLabel = "Remove attachment") { onRemove() },
            ) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White, modifier = Modifier.padding(4.dp))
            }
        }
    }
}

@Composable
private fun ComposerToolbar(
    canAddImage: Boolean,
    mediaCount: Int,
    contentWarningOn: Boolean,
    powAvailable: Boolean,
    powActive: Boolean,
    powBadge: String?,
    counter: ComposerRules.CounterState,
    onPickImage: () -> Unit,
    onAddUrl: () -> Unit,
    onToggleCw: () -> Unit,
    onHashtag: () -> Unit,
    onEmoji: () -> Unit,
    onPow: () -> Unit,
    onPoll: () -> Unit,
) {
    Surface(color = BitOSColors.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.sm, vertical = BitOSSpacing.sm)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolbarButton(AppIcons.Photo, "Attach image", enabled = canAddImage, active = mediaCount > 0, badge = mediaCount.takeIf { it > 0 }?.toString(), onClick = onPickImage)
            ToolbarButton(AppIcons.Globe, "Add image URL", enabled = canAddImage, onClick = onAddUrl)
            ToolbarButton(AppIcons.Mute, "Content warning", active = contentWarningOn, onClick = onToggleCw)
            ToolbarButton(Icons.Rounded.Tag, "Insert hashtag", onClick = onHashtag)
            ToolbarButton(AppIcons.Chat, "Insert emoji", onClick = onEmoji)
            ToolbarButton(AppIcons.Poll, "Create poll", onClick = onPoll)
            ToolbarButton(AppIcons.QrCode, "Proof-of-work", enabled = powAvailable, active = powActive, badge = powBadge, onClick = onPow)
            Spacer(Modifier.width(BitOSSpacing.sm))
            CharCounter(counter)
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> BitOSColors.textTertiary.copy(alpha = 0.4f)
        active -> BitOSColors.primary
        else -> BitOSColors.textSecondary
    }
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Box(Modifier.size(36.dp).background(BitOSColors.primary.copy(alpha = 0.15f), CircleShape))
        }
        Box(
            Modifier
                .size(44.dp)
                .clickable(enabled = enabled, onClickLabel = label) { onClick() }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
            if (badge != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = BitOSColors.primary,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = Color(0xFF0A0A0F),
                        modifier = Modifier.padding(horizontal = 3.dp),
                    )
                }
            }
        }
    }
}

/** Web parity ring: fills with length, amber near the limit, red over. */
@Composable
private fun CharCounter(counter: ComposerRules.CounterState) {
    if (counter.label.startsWith("0 /")) return
    val color = when {
        counter.over -> BitOSColors.error
        counter.near -> Color(0xFFF5A623)
        else -> BitOSColors.textSecondary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            counter.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.W700,
            color = color,
        )
        Spacer(Modifier.width(4.dp))
        CircularProgressIndicator(
            progress = { counter.ratio },
            strokeWidth = 2.5.dp,
            trackColor = BitOSColors.textTertiary.copy(alpha = 0.15f),
            color = color,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** APP-008 poll composer (legacy `PollComposer` parity): question ≤280,
 * 2–6 options ≤80, live counters, publish through the tags path. */
@Composable
private fun PollComposerSheet(
    publishing: Boolean,
    onPost: (String, List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(listOf("", "")) }
    val clean = options.map { it.trim() }.filter { it.isNotEmpty() }
    val canPost = question.trim().isNotEmpty() && question.trim().length <= space.bitos.core.model.PollContract.MAX_QUESTION &&
        clean.size >= space.bitos.core.model.PollContract.MIN_OPTIONS &&
        clean.all { it.length <= space.bitos.core.model.PollContract.MAX_OPTION } && !publishing

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BitOSSpacing.screen)
            .padding(bottom = BitOSSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Create poll", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(
                onClick = { onPost(question, options) },
                enabled = canPost,
            ) { Text("Post") }
        }
        OutlinedTextField(
            value = question,
            onValueChange = { question = it.take(space.bitos.core.model.PollContract.MAX_QUESTION) },
            label = { Text("Question (${question.length}/280)") },
            singleLine = false,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        options.forEachIndexed { index, value ->
            OutlinedTextField(
                value = value,
                onValueChange = { next ->
                    options = options.toMutableList().also { list ->
                        list[index] = next.take(space.bitos.core.model.PollContract.MAX_OPTION)
                    }
                },
                label = { Text("Choice ${index + 1}") },
                singleLine = true,
                trailingIcon = if (options.size > space.bitos.core.model.PollContract.MIN_OPTIONS) {
                    {
                        IconButton(onClick = { options = options.toMutableList().also { it.removeAt(index) } }) {
                            Icon(AppIcons.Close, contentDescription = "Remove choice", tint = BitOSColors.textSecondary)
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (options.size < space.bitos.core.model.PollContract.MAX_OPTIONS) {
            TextButton(onClick = { options = options + "" }) {
                Icon(AppIcons.Add, contentDescription = null, tint = BitOSColors.primary)
                Spacer(Modifier.width(4.dp))
                Text("Add choice", color = BitOSColors.primary)
            }
        }
        Text(
            "2–6 choices, published as a note with poll tags (legacy wire).",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textTertiary,
        )
    }
}

@Composable
private fun PublishedState(onBack: () -> Unit, onNew: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(BitOSSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(64.dp).background(BitOSColors.success.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Check, contentDescription = null, tint = BitOSColors.success, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(BitOSSpacing.lg))
        Text("Published", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(BitOSSpacing.sm))
        Text(
            "Your note is on its way to the relays.",
            style = MaterialTheme.typography.bodyMedium,
            color = BitOSColors.textSecondary,
        )
        Spacer(Modifier.height(BitOSSpacing.xl))
        Button(
            onClick = onBack,
            modifier = Modifier.width(220.dp),
        ) { Text("Back to feed") }
        TextButton(onClick = onNew) { Text("New post", color = BitOSColors.primary) }
    }
}
