package space.bitos.app.ui.profile

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.data.feed.AuthorRepository
import space.bitos.app.data.feed.FeedUiState
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.components.AppMenuDropdown
import space.bitos.app.ui.components.AppMenuEntry
import space.bitos.app.ui.components.AppMenuItem
import space.bitos.app.ui.components.HexShape
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.components.formatCount
import space.bitos.app.ui.feed.HomeViewModel
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.core.feed.FeedNote

/**
 * Full-page profile for any author pubkey (UX-010): "You"-page visual parity
 * — header bar, full-bleed cover with hex-pattern fallback, hex avatar hero,
 * identity block, Zap + Follow actions, stats, collapsible about, and a
 * pinned Notes · Replies · Bitz tab rail. Presented as an in-app route from
 * every "View full profile" affordance; never an external link.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AuthorProfileScreen(
    authorPubkey: String,
    authorRepository: AuthorRepository,
    homeViewModel: HomeViewModel,
    identityViewModel: IdentityViewModel,
    notePublisher: space.bitos.app.data.publish.NotePublisher,
    onClose: () -> Unit,
    /** Author tap on a note card → swap this page to that author. */
    onOpenAuthor: (String) -> Unit = {},
    /** External-link tap → confirm sheet (never opens a browser unattended). */
    onOpenExternalLink: (String) -> Unit = {},
    /** note1/nevent1/naddr1 tap → thread host (owned by the caller). */
    onOpenNoteRef: (String) -> Unit = {},
    /** Web ProfileBitzGrid parity: opens the author-scoped reels player
     *  at the tapped tile (one shared player, context-aware data). */
    onOpenBitzPlayer: (pubkey: String, noteId: String) -> Unit = { _, _ -> },
) {
    BackHandler(onBack = onClose)
    LaunchedEffect(authorPubkey) { authorRepository.open(authorPubkey) }

    val authorState by authorRepository.state.collectAsStateWithLifecycle()
    val feedState by homeViewModel.state.collectAsStateWithLifecycle()
    val zapState by homeViewModel.zapState.collectAsStateWithLifecycle()
    val identityState by identityViewModel.state.collectAsStateWithLifecycle()
    val publisherState by notePublisher.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    val profile = authorState.profile
    val isFollowing = feedState.following.contains(authorPubkey)
    val hasLightning = !profile?.lud16.isNullOrBlank()

    var tab by remember(authorPubkey) { mutableStateOf(0) }
    var showZap by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var npubCopied by remember { mutableStateOf(false) }
    // X-style: tapping a note/grid tile opens its thread.
    var threadTarget by remember { mutableStateOf<FeedNote?>(null) }
    // Note zap from a profile card.
    var zapNoteTarget by remember { mutableStateOf<FeedNote?>(null) }
    // Report user (kind-1984, p-tag only — web ProfileActionMenu parity).
    var showReportDialog by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("") }
    val localActions by homeViewModel.localActions.collectAsStateWithLifecycle()

    val notes = authorState.notes
    val tabNotes = notes.filter { it.replyTo == null }
    val tabReplies = notes.filter { it.replyTo != null }
    val tabBitz = notes.filter { it.video != null || it.mediaUrls.isNotEmpty() }
    // Web full-page profile parity: the Zaps tab shows what THIS viewer
    // verifiably zapped this author (local ledger — signed by us). Relays
    // cannot truthfully enumerate everyone else's zaps to an author, so
    // we never render an unverifiable total.
    val sentToAuthor = remember(authorPubkey, feedState) {
        homeViewModel.sentZapRecords().filter { it.recipientPubkey == authorPubkey }
    }
    val tabs = listOf("Notes", "Replies", "Bitz", "Zaps")
    val content = listOf(tabNotes, tabReplies, tabBitz)[tab.coerceAtMost(2)]

    Column(
        Modifier
            .fillMaxSize()
            .background(BitOSColors.background),
    ) {
        // ── Header bar: back · name · ⋯ (copy link · npub · lightning) ──
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = BitOSColors.textPrimary,
                )
            }
            Text(
                profile?.bestDisplayName ?: space.bitos.app.ui.components.shortPubkey(authorPubkey),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W700,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(
                        AppIcons.More,
                        contentDescription = "More profile actions",
                        tint = BitOSColors.textPrimary,
                    )
                }
                val npub = remember(authorPubkey) {
                    space.bitos.core.identity.NostrKeyCodec.npub(authorPubkey)
                }
                // Web ProfileActionMenu parity: copy affordances, then the
                // moderation group (report is destructive-toned on web).
                val menuEntries = buildList {
                    add(AppMenuEntry.Item(AppMenuItem("copy-link", "Copy profile link", Icons.Outlined.Link)))
                    add(AppMenuEntry.Item(AppMenuItem("copy-npub", if (npubCopied) "npub copied" else "Copy npub", AppIcons.Copy)))
                    if (hasLightning) {
                        add(AppMenuEntry.Item(AppMenuItem("copy-lightning", "Copy lightning address", AppIcons.Zap)))
                    }
                    if (identityState.account?.pubkeyHex != authorPubkey) {
                        add(AppMenuEntry.Divider)
                        add(
                            AppMenuEntry.Item(
                                AppMenuItem(
                                    "mute",
                                    if (homeViewModel.isMuted(authorPubkey)) "Unmute author" else "Mute author",
                                    Icons.AutoMirrored.Rounded.VolumeOff,
                                )
                            )
                        )
                        add(AppMenuEntry.Item(AppMenuItem("report", "Report user…", Icons.Outlined.Flag, destructive = true)))
                    }
                }
                AppMenuDropdown(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                    entries = menuEntries,
                    onSelect = { id ->
                        when (id) {
                            "copy-link" -> npub?.let { clipboard.setText(AnnotatedString("https://njump.me/$it")) }
                            "copy-npub" -> npub?.let {
                                clipboard.setText(AnnotatedString(it))
                                npubCopied = true
                            }
                            "copy-lightning" -> profile?.lud16?.let { clipboard.setText(AnnotatedString(it)) }
                            "mute" -> homeViewModel.toggleMute(authorPubkey)
                            "report" -> showReportDialog = true
                        }
                    }
                )
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            // ── Cover + avatar hero (zero-gap, You-page parity) ─────────
            item(key = "hero") {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                    ) {
                        val bannerUrl = profile?.banner.orEmpty()
                        if (bannerUrl.isNotEmpty()) {
                            coil.compose.AsyncImage(
                                model = bannerUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            DefaultCoverHexPattern()
                        } else {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        // Web: linear-gradient(115deg, primary-400 → primary-700).
                                        Brush.linearGradient(listOf(Color(0xFFF9A84B), Color(0xFFD4790F))),
                                    ),
                            ) { DefaultCoverHexPattern() }
                        }
                        // Web hero scrim: from-black/25 via-transparent to-black/35.
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.25f),
                                        0.45f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = 0.35f),
                                    ),
                                ),
                        )
                    }
                    // Avatar band — hex plate forms the mock's border-4
                    // float over the cover edge; requiredSize keeps the
                    // 92dp hex intact over the 64dp reservation.
                    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.TopCenter) {
                        Box(
                            Modifier
                                .offset(y = (-46).dp)
                                .requiredSize(100.dp)
                                .shadow(8.dp, HexShape())
                                .clip(HexShape())
                                .background(BitOSColors.background),
                            contentAlignment = Alignment.Center,
                        ) {
                            PubkeyAvatar(
                                pubkey = authorPubkey,
                                size = 92,
                                pictureUrl = profile?.picture,
                                label = profile?.bestDisplayName,
                                hasLightning = hasLightning,
                            )
                        }
                    }
                }
            }

            // ── Identity block (centered, You-page parity) ──────────────
            item(key = "identity") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            profile?.bestDisplayName?.takeIf { it.isNotBlank() }
                                ?: space.bitos.app.ui.components.shortPubkey(authorPubkey),
                            fontSize = 24.sp, fontWeight = FontWeight.W800, color = BitOSColors.textPrimary,
                        )
                        if (!profile?.nip05.isNullOrBlank()) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = "Verified",
                                tint = BitOSColors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    profile?.nip05?.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 13.sp, color = BitOSColors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(BitOSColors.surfaceOverlay.copy(alpha = 0.5f))
                            .clickable {
                                val npub = space.bitos.core.identity.NostrKeyCodec.npub(authorPubkey) ?: return@clickable
                                clipboard.setText(AnnotatedString(npub))
                                npubCopied = true
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (npubCopied) AppIcons.Check else AppIcons.Copy,
                            contentDescription = null,
                            tint = if (npubCopied) BitOSColors.success else BitOSColors.textSecondary,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        val npub = remember(authorPubkey) { space.bitos.core.identity.NostrKeyCodec.npub(authorPubkey) }
                        Text(
                            npub?.take(20)?.plus("…") ?: space.bitos.app.ui.components.shortPubkey(authorPubkey),
                            fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = BitOSColors.textSecondary,
                        )
                    }
                }
            }

            // ── Actions: Zap + Follow (UX-010 mock parity) ──────────────
            item(key = "actions") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { showZap = true },
                        enabled = hasLightning,
                        shape = RoundedCornerShape(99.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (hasLightning) BitOSColors.primary else BitOSColors.surfaceOverlay,
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(AppIcons.Zap, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (hasLightning) "Zap" else "No lightning address", fontSize = 14.sp, fontWeight = FontWeight.W700)
                    }
                    if (isFollowing) {
                        OutlinedButton(
                            onClick = { homeViewModel.toggleFollow(authorPubkey) },
                            shape = RoundedCornerShape(99.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Following ✓", fontSize = 14.sp, fontWeight = FontWeight.W700)
                        }
                    } else {
                        Button(
                            onClick = { homeViewModel.toggleFollow(authorPubkey) },
                            shape = RoundedCornerShape(99.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BitOSColors.textPrimary),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Follow", fontSize = 14.sp, fontWeight = FontWeight.W700, color = BitOSColors.background)
                        }
                    }
                }
            }

            // ── Stats row (truthful counts from the author REQ window) ──
            item(key = "stats") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatPill("Posts", formatCount(tabNotes.size.toLong()))
                    StatPill("Replies", formatCount(tabReplies.size.toLong()))
                    StatPill("Bitz", formatCount(tabBitz.size.toLong()))
                    // Web stats parity: sats THIS viewer zapped the author
                    // (locally verified ledger — the only truthful figure).
                    StatPill(
                        "Zapped by you",
                        space.bitos.core.model.ZapFormat.sats(sentToAuthor.sumOf { it.amountSats }),
                        onClick = { showZap = true },
                    )
                }
            }

            // ── About + info chips (shared AboutSection) ────────────────
            item(key = "about") { AboutSection(profile) }

            // ── Pinned tab rail (Notes · Replies · Bitz) ─────────────────
            stickyHeader(key = "tabs") {
                Column(Modifier.fillMaxWidth().background(BitOSColors.background)) {
                    Row(Modifier.fillMaxWidth().height(48.dp)) {
                        tabs.forEachIndexed { index, label ->
                            val selected = tab == index
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable { tab = index }
                                    .padding(top = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    label,
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.W800 else FontWeight.W500,
                                    color = if (selected) BitOSColors.primary else BitOSColors.textSecondary,
                                )
                                Spacer(Modifier.height(8.dp))
                                Box(
                                    Modifier
                                        .width(if (selected) 28.dp else 0.dp)
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(99.dp))
                                        .background(if (selected) BitOSColors.primary else Color.Transparent),
                                )
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(BitOSColors.border.copy(alpha = 0.15f)))
                }
            }

            // ── Tab content (cards · 3-col grid · loading · empty) ──────
            if (tab == 3) {
                // Zaps tab (web parity): this viewer's verified zaps to the
                // author, from the same shared ledger as the wallet.
                if (sentToAuthor.isEmpty()) {
                    item(key = "zaps-empty") { AuthorZapsEmptyState() }
                } else {
                    item(key = "zaps-list") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            sentToAuthor.take(50).forEach { record ->
                                space.bitos.app.ui.zap.ZapLedgerRow(
                                    entry = space.bitos.core.model.SentZapLedger.LedgerEntry(
                                        direction = space.bitos.core.model.SentZapLedger.Direction.SENT,
                                        sats = record.amountSats,
                                        peerPubkey = record.recipientPubkey,
                                        createdAt = record.createdAt,
                                        memo = record.memo,
                                        targetNoteId = record.targetNoteId,
                                    ),
                                    profile = feedState.profiles[record.recipientPubkey],
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            } else if (authorState.isLoading && notes.isEmpty()) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BitOSColors.primary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    }
                }
            } else if (content.isEmpty()) {
                item(key = "empty") { AuthorTabEmptyState(tab) }
            } else if (tab == 2) {
                item(key = "bitz-grid") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                        content.take(60).chunked(3).forEach { rowNotes ->
                            Row(Modifier.fillMaxWidth()) {
                                rowNotes.forEach { note ->
                                    BitzGridTile(
                                        note,
                                        Modifier
                                            .weight(1f)
                                            .padding(1.dp)
                                            .clickable(onClickLabel = "Play bitz") {
                                                onOpenBitzPlayer(authorPubkey, note.id)
                                            },
                                    )
                                }
                                repeat(3 - rowNotes.size) { Spacer(Modifier.weight(1f).padding(1.dp)) }
                            }
                        }
                    }
                }
            } else {
                items(content.take(50), key = { "note-${it.id}" }) { note ->
                    Column {
                        if (tab == 1) space.bitos.app.ui.profile.ReplyContextStrip()
                        // Same card as the home feed — rich body, media,
                        // polls, full actions and the ⋯ menu. No fork.
                        space.bitos.app.ui.components.FeedNoteCard(
                            note = note,
                            profile = feedState.profiles[note.pubkey],
                            bookmarked = note.id in feedState.bookmarkedIds || note.id in localActions.bookmarked,
                            liked = note.id in localActions.liked,
                            resolveMentionName = { hex -> feedState.profiles[hex]?.bestDisplayName },
                            onLike = { homeViewModel.toggleLike(note) },
                            onBookmark = { homeViewModel.toggleBookmark(note.id) },
                            onComment = { threadTarget = note },
                            onRepost = { homeViewModel.repost(note) },
                            onZap = {
                                homeViewModel.loadZaps(note.id)
                                zapNoteTarget = note
                            },
                            onAuthor = { onOpenAuthor(note.pubkey) },
                            isMuted = homeViewModel.isMuted(note.pubkey),
                            onMuteToggle = { homeViewModel.toggleMute(note.pubkey) },
                            onReport = { reason -> homeViewModel.report(note, reason) },
                            pollTally = feedState.pollTallies[note.id],
                            canVotePoll = identityState.account != null,
                            onLoadPollVotes = { homeViewModel.loadPollVotes(note.id) },
                            onVotePoll = { optionIndex -> homeViewModel.votePoll(note, optionIndex) },
                            onOpenAttachment = { onOpenExternalLink(it) },
                            onOpenExternalLink = { onOpenExternalLink(it) },
                            onOpenNoteRef = { onOpenNoteRef(it) },
                        )
                    }
                }
            }
            // Pages of five arrive on demand — this sentinel asks for the
            // next older page when it composes.
            if (authorState.canLoadMore && notes.isNotEmpty()) {
                item(key = "load-more") {
                    LaunchedEffect(notes.size) { authorRepository.loadMoreNotes() }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (authorState.isLoadingMore) {
                            CircularProgressIndicator(color = BitOSColors.primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Loading more…", fontSize = 13.sp, color = BitOSColors.textSecondary)
                        }
                    }
                }
            }
            item(key = "bottom-space") { Spacer(Modifier.height(32.dp)) }
        }
    }

    // X-style note detail: the tapped note's thread.
    val thread = threadTarget
    if (thread != null) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { threadTarget = null }) {
            space.bitos.app.ui.feed.CommentThreadSheet(
                note = thread,
                viewModel = homeViewModel,
                identityViewModel = identityViewModel,
                publisherState = publisherState,
                onDismiss = { threadTarget = null },
            )
        }
    }

    // Note zap from a profile card (web PostCard zap parity).
    val zapNote = zapNoteTarget
    if (zapNote != null) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { homeViewModel.dismissZap(); zapNoteTarget = null },
        ) {
            space.bitos.app.ui.feed.ZapContent(
                note = zapNote,
                lud16 = profile?.lud16,
                state = zapState,
                profileName = profile?.bestDisplayName,
                hasIdentity = identityState.account != null,
                zapCount = feedState.zapCounts[zapNote.id] ?: 0,
                paidRequestIds = feedState.zapRequestIds[zapNote.id] ?: emptySet(),
                onPaid = { sats, memo -> homeViewModel.onZapPaid(zapNote, sats, memo) },
                onAmountSelected = homeViewModel::selectZapAmount,
                onZap = { sats, comment, anonymous ->
                    homeViewModel.selectZapAmount(sats)
                    homeViewModel.zap(zapNote, comment, anonymous)
                },
                onClose = { homeViewModel.dismissZap(); zapNoteTarget = null },
                profilePictureUrl = profile?.picture,
            )
        }
    }

    // Report user (kind-1984, p-tag only).
    if (showReportDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showReportDialog = false; reportReason = "" },
            title = { Text("Report this user") },
            text = {
                Column {
                    Text("The report is published as a kind-1984 event.", fontSize = 12.sp, color = BitOSColors.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    space.bitos.app.ui.components.BitosTextField(
                        value = reportReason,
                        onValueChange = { reportReason = it.take(140) },
                        placeholder = "Reason (spam, harassment…)",
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val reason = reportReason.trim()
                    showReportDialog = false
                    reportReason = ""
                    if (reason.isNotEmpty()) {
                        notePublisher.publishReport(
                            targetEventId = null,
                            targetPubkey = authorPubkey,
                            reason = reason,
                            signerProvider = { identityViewModel.createSigner() },
                            writeRelays = space.bitos.app.data.feed.DefaultRelays.writeUrls,
                        )
                    }
                }) { Text("Report", color = BitOSColors.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showReportDialog = false; reportReason = "" }) {
                    Text("Cancel", color = BitOSColors.textSecondary)
                }
            },
        )
    }

    // Profile zap (NIP-57 p-tag only, no target note).
    if (showZap) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { homeViewModel.dismissZap(); showZap = false },
        ) {
            space.bitos.app.ui.feed.ZapContent(
                recipientPubkey = authorPubkey,
                lud16 = profile?.lud16,
                state = zapState,
                profileName = profile?.bestDisplayName,
                hasIdentity = identityState.account != null,
                onPaid = { sats, memo -> homeViewModel.onAuthorZapPaid(authorPubkey, sats, memo) },
                onAmountSelected = homeViewModel::selectZapAmount,
                onZap = { sats, comment, anonymous ->
                    homeViewModel.selectZapAmount(sats)
                    homeViewModel.zapAuthor(authorPubkey, profile?.lud16, comment, anonymous)
                },
                onClose = { homeViewModel.dismissZap(); showZap = false },
                profilePictureUrl = profile?.picture,
            )
        }
    }
}

/** Zaps-tab empty state: honest zero — nothing fake. */
@Composable
private fun AuthorZapsEmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("⚡", fontSize = 34.sp, color = BitOSColors.zap)
        Spacer(Modifier.height(12.dp))
        Text("You haven't zapped this author yet", fontSize = 14.sp, color = BitOSColors.textSecondary)
    }
}

/** Shared empty state for the author tabs (icon 48 @50% + message). */
@Composable
private fun AuthorTabEmptyState(tab: Int) {    val (icon, message) = when (tab) {
        1 -> AppIcons.Comment to "No replies yet"
        2 -> AppIcons.Photo to "No bitz yet"
        else -> AppIcons.Pen to "No posts yet"
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = BitOSColors.textSecondary.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, fontSize = 14.sp, color = BitOSColors.textSecondary)
    }
}
