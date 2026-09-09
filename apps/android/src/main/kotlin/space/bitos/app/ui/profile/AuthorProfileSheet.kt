package space.bitos.app.ui.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.data.feed.AuthorUiState
import space.bitos.app.data.feed.FeedUiState
import space.bitos.app.ui.components.AppMenuDropdown
import space.bitos.app.ui.components.AppMenuEntry
import space.bitos.app.ui.components.AppMenuItem
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.components.SheetCloseIcon
import space.bitos.app.ui.components.formatCount
import space.bitos.app.ui.components.formatTimeAgo
import space.bitos.app.ui.components.shortPubkey
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.app.ui.theme.SolarFeedIcon
import space.bitos.app.ui.theme.SolarFeedIconImage
import space.bitos.core.feed.FeedNote
import space.bitos.core.model.ProfileMetadata

/**
 * Author profile bottom sheet: banner hero, all profile fields (about,
 * website, lightning), Zap + follow/unfollow, copy-npub, stats, and a
 * "View full profile" action that routes to the in-app full profile page
 * (never an external link).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuthorProfileContent(
    authorPubkey: String,
    state: AuthorUiState,
    feedState: FeedUiState,
    onOpen: (String) -> Unit,
    onFollow: (String) -> Unit,
    onClose: () -> Unit,
    /** Opens the profile zap sheet (only rendered with a lud16). */
    onZap: () -> Unit = {},
    /** Routes to the in-app full profile page (UX-010). */
    onOpenFullProfile: (String) -> Unit = {},
    /** X-style: tapping a note card opens its thread. */
    onOpenNote: (FeedNote) -> Unit = {},
    /** Next older notes page (five at a time). */
    onLoadMore: () -> Unit = {},
    /** Card action row (web PostCard parity). */
    onLike: (FeedNote) -> Unit = {},
    onRepost: (FeedNote) -> Unit = {},
    onZapNote: (FeedNote) -> Unit = {},
    /** Locally liked note ids (optimistic toggles from the feed store). */
    likedIds: Set<String> = emptySet(),
    /** Moderation menu (⋯): shown only when viewing another account. */
    showModeration: Boolean = false,
    /** Current mute state for the [authorPubkey] (menu label). */
    isMuted: Boolean = false,
    onToggleMute: () -> Unit = {},
    onReport: () -> Unit = {},
) {
    LaunchedEffect(authorPubkey) { onOpen(authorPubkey) }

    val profile = state.profile
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var aboutExpanded by remember { mutableStateOf(false) }
    var npubCopied by remember { mutableStateOf(false) }
    // Web ProfileActionMenu parity: copy affordances + moderation, same
    // entries as the full profile page's ⋯ menu.
    var showMoreMenu by remember { mutableStateOf(false) }
    val npub = remember(authorPubkey) {
        space.bitos.core.identity.NostrKeyCodec.npub(authorPubkey)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.90f),
    ) {
        // ── Banner ──────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
        ) {
            val bannerUrl = profile?.banner?.takeIf { it.isNotBlank() }
            if (bannerUrl != null) {
                coil.compose.AsyncImage(
                    model = bannerUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                // Mock parity: vivid orange → amber gradient with the
                // hexagon pattern overlay (same palette as the "You" page
                // default cover).
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFF9A84B), Color(0xFFD4790F)),
                            )
                        ),
                ) {
                    DefaultCoverHexPattern()
                }
            }
            // ⋯ actions menu in the banner's top-left corner (copy link ·
            // npub · lightning, then mute/report — full-page parity).
            Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(
                        AppIcons.More,
                        contentDescription = "More profile actions",
                        tint = Color.White,
                    )
                }
                AppMenuDropdown(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                    entries = buildList {
                        npub?.let {
                            add(AppMenuEntry.Item(AppMenuItem("copy-link", "Copy profile link", AppIcons.Link)))
                            add(AppMenuEntry.Item(AppMenuItem("copy-npub", if (npubCopied) "npub copied" else "Copy npub", AppIcons.Copy)))
                        }
                        val lud16 = profile?.lud16?.takeIf { v -> v.isNotBlank() }
                        if (lud16 != null) {
                            add(AppMenuEntry.Item(AppMenuItem("copy-lightning", "Copy lightning address", AppIcons.Zap)))
                        }
                        if (showModeration) {
                            add(AppMenuEntry.Divider)
                            add(AppMenuEntry.Item(AppMenuItem("mute", if (isMuted) "Unmute author" else "Mute author", AppIcons.Mute)))
                            add(AppMenuEntry.Item(AppMenuItem("report", "Report user…", AppIcons.ReportSpam, destructive = true)))
                        }
                    },
                    onSelect = { id ->
                        when (id) {
                            "copy-link" -> npub?.let { clipboard.setText(AnnotatedString("https://njump.me/$it")) }
                            "copy-npub" -> npub?.let {
                                clipboard.setText(AnnotatedString(it))
                                npubCopied = true
                            }
                            "copy-lightning" -> profile?.lud16?.let { clipboard.setText(AnnotatedString(it)) }
                            "mute" -> onToggleMute()
                            "report" -> onReport()
                        }
                    },
                )
            }
            // Close button in top-right corner of the banner
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                SheetCloseIcon(onClose = onClose)
            }
        }

        // ── Content below banner ────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = BitOSSpacing.screen,
                    vertical = BitOSSpacing.base,
                ),
                verticalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
            ) {
                // ── Avatar + "View Profile" pill (mock parity) ─────────
                item(key = "header") {
                    // Keep the identity at the visual seam between cover and
                    // sheet content. Centering the hex makes the banner read
                    // as a profile hero instead of a generic card header;
                    // the navigation pill remains a secondary edge action.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .overlapAbove(42.dp),
                    ) {
                        // Hex avatar floating over the banner edge: a
                        // background-colored hex plate forms the border
                        // (mock border-4) — no circular plate.
                        Box(
                            modifier = Modifier
                                .size(84.dp)
                                .align(Alignment.Center)
                                .shadow(6.dp, space.bitos.app.ui.components.HexShape())
                                .clip(space.bitos.app.ui.components.HexShape())
                                .background(BitOSColors.background),
                            contentAlignment = Alignment.Center,
                        ) {
                            PubkeyAvatar(
                                pubkey = authorPubkey,
                                size = 76,
                                pictureUrl = profile?.picture,
                                label = profile?.bestDisplayName,
                                hasLightning = !profile?.lud16.isNullOrBlank(),
                            )
                        }
                        // In-app full profile route (UX-010) — white pill
                        // at the seam's trailing edge, never an external link.
                        Button(
                            onClick = { onOpenFullProfile(authorPubkey) },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BitOSColors.textPrimary,
                                contentColor = BitOSColors.background,
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .height(34.dp),
                        ) {
                            Text("View Profile", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.W700)
                        }
                    }
                }

                // ── Name block ───────────────────────────────────────
                item(key = "name") {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                profile?.bestDisplayName ?: shortPubkey(authorPubkey),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.W700,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (!profile?.nip05.isNullOrBlank()) {
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    AppIcons.CheckCircle,
                                    contentDescription = "Verified",
                                    tint = BitOSColors.accent,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        profile?.nip05?.takeIf { it.isNotBlank() }?.let { nip05 ->
                            Text(nip05, style = MaterialTheme.typography.bodySmall, color = BitOSColors.accent)
                        }
                        // Copy npub chip (moved from the header row).
                        androidx.compose.material3.TextButton(
                            onClick = {
                                val npub = space.bitos.core.identity.NostrKeyCodec.npub(authorPubkey)
                                if (npub != null) {
                                    clipboard.setText(AnnotatedString(npub))
                                    npubCopied = true
                                }
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        ) {
                            Icon(
                                imageVector = AppIcons.Copy,
                                contentDescription = null,
                                tint = if (npubCopied) BitOSColors.success else BitOSColors.textTertiary,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (npubCopied) "npub copied" else shortPubkey(authorPubkey),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (npubCopied) BitOSColors.success else BitOSColors.textTertiary,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            )
                        }
                    }
                }

                // ── About ────────────────────────────────────────────
                val about = profile?.about?.trim()?.takeIf { it.isNotBlank() }
                if (about != null) {
                    item(key = "about") {
                        Column {
                            Text(
                                about,
                                style = MaterialTheme.typography.bodyMedium,
                                color = BitOSColors.textSecondary,
                                maxLines = if (aboutExpanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (about.length > 160) {
                                TextButton(
                                    onClick = { aboutExpanded = !aboutExpanded },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 0.dp, vertical = 0.dp,
                                    ),
                                ) {
                                    Text(
                                        if (aboutExpanded) "Show less" else "Show more",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = BitOSColors.primary,
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Info chips: lightning + website ──────────────────
                val lud16 = profile?.lud16?.trim()?.takeIf { it.isNotBlank() }
                val website = profile?.website?.trim()?.takeIf { it.isNotBlank() }
                if (lud16 != null || website != null) {
                    item(key = "chips") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
                        ) {
                            if (lud16 != null) {
                                InfoChip(
                                    icon = { SolarFeedIconImage(SolarFeedIcon.Zap, contentDescription = null, tint = BitOSColors.zap, modifier = Modifier.size(13.dp)) },
                                    text = lud16,
                                    tint = BitOSColors.zap,
                                    onClick = {
                                        clipboard.setText(AnnotatedString(lud16))
                                    },
                                )
                            }
                            if (website != null) {
                                val url = if (website.startsWith("http")) website else "https://$website"
                                InfoChip(
                                    icon = { Icon(AppIcons.Globe, contentDescription = null, tint = BitOSColors.textSecondary, modifier = Modifier.size(13.dp)) },
                                    text = website,
                                    tint = BitOSColors.textSecondary,
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                // ── Stats row (truthful counts from the author REQ) ───
                item(key = "stats") {
                    val bitzCount = state.notes.count { it.video != null || it.mediaUrls.isNotEmpty() }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xl),
                    ) {
                        Column {
                            Text(
                                formatCount(state.notes.size.toLong()),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.W700,
                            )
                            Text("Notes", style = MaterialTheme.typography.labelSmall, color = BitOSColors.textSecondary)
                        }
                        Column {
                            Text(
                                formatCount(bitzCount.toLong()),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.W700,
                            )
                            Text("Bitz", style = MaterialTheme.typography.labelSmall, color = BitOSColors.textSecondary)
                        }
                        if (feedState.following.contains(authorPubkey)) {
                            Column {
                                Text("✓", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W700, color = BitOSColors.primary)
                                Text("Follows", style = MaterialTheme.typography.labelSmall, color = BitOSColors.textSecondary)
                            }
                        }
                    }
                }

                // ── Quick actions: Zap + Follow (mock parity) ──────────
                item(key = "quick-actions") {
                    val hasLightning = !profile?.lud16.isNullOrBlank()
                    val isFollowing = feedState.following.contains(authorPubkey)
                    Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                        Button(
                            onClick = onZap,
                            enabled = hasLightning,
                            shape = RoundedCornerShape(99.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hasLightning) BitOSColors.primary else BitOSColors.surfaceOverlay,
                                contentColor = if (hasLightning) Color(0xFF0A0A0F) else BitOSColors.textTertiary,
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(AppIcons.Zap, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (hasLightning) "Zap" else "No lightning address", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.W700)
                        }
                        OutlinedButton(
                            onClick = { onFollow(authorPubkey) },
                            shape = RoundedCornerShape(99.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                if (isFollowing) "Following ✓" else "Follow",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.W700,
                            )
                        }
                    }
                }

                // ── Latest note preview (mock parity) ─────────────────
                val latestNote = state.notes.firstOrNull { it.content.isNotBlank() }
                if (latestNote != null) {
                    item(key = "latest-note") {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = BitOSColors.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, BitOSColors.border.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(BitOSSpacing.md)) {
                                Text(
                                    "Latest Note",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BitOSColors.textSecondary,
                                    letterSpacing = androidx.compose.ui.unit.TextUnit(0.8f, androidx.compose.ui.unit.TextUnitType.Sp),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    latestNote.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // ── Divider ──────────────────────────────────────────
                item(key = "divider") {
                    androidx.compose.material3.HorizontalDivider(color = BitOSColors.divider)
                }

                // ── Notes ────────────────────────────────────────────
                item(key = "notes-header") {
                    when {
                        state.isLoading && state.notes.isEmpty() ->
                            Box(Modifier.fillMaxWidth().padding(BitOSSpacing.xl), contentAlignment = Alignment.Center) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    color = BitOSColors.primary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        state.notes.isEmpty() ->
                            Box(Modifier.fillMaxWidth().padding(BitOSSpacing.xl), contentAlignment = Alignment.Center) {
                                Text(
                                    "No notes yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BitOSColors.textSecondary,
                                )
                            }
                        else ->
                            Text(
                                "Notes",
                                style = MaterialTheme.typography.labelMedium,
                                color = BitOSColors.textTertiary,
                                letterSpacing = androidx.compose.ui.unit.TextUnit(0.8f, androidx.compose.ui.unit.TextUnitType.Sp),
                            )
                    }
                }

                items(state.notes, key = { it.id }) { note ->
                    AuthorNoteCard(
                        note,
                        profile,
                        onClick = { onOpenNote(note) },
                        isLiked = note.id in likedIds,
                        tally = feedState.tallies[note.id],
                        onLike = { onLike(note) },
                        onRepost = { onRepost(note) },
                        onZap = { onZapNote(note) },
                    )
                }

                // Pages of five arrive on demand — this sentinel asks for
                // the next older page when it composes.
                if (state.canLoadMore && state.notes.isNotEmpty()) {
                    item(key = "load-more") {
                        androidx.compose.runtime.LaunchedEffect(state.notes.size) { onLoadMore() }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = BitOSSpacing.sm),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.isLoadingMore) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    color = BitOSColors.primary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Loading more…", style = MaterialTheme.typography.labelMedium, color = BitOSColors.textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shared host for the author profile sheet: wraps the ModalBottomSheet,
 * stacks the profile-zap sheet above it, and routes "View full profile" to
 * the caller's in-app page. Every author entry point (Feed, Bitz, Inbox,
 * Discover, deep links) presents profiles through this host so routing and
 * zap chrome stay identical everywhere.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AuthorProfileSheetHost(
    authorPubkey: String,
    state: AuthorUiState,
    feedState: FeedUiState,
    homeViewModel: space.bitos.app.ui.feed.HomeViewModel,
    identityViewModel: space.bitos.app.identity.IdentityViewModel,
    notePublisher: space.bitos.app.data.publish.NotePublisher,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
    /** In-app full profile route (UX-010) — never an external link. */
    onOpenFullProfile: (String) -> Unit,
    /** Next older notes page (five at a time). */
    onLoadMore: () -> Unit = {},
) {
    var showZap by remember { mutableStateOf(false) }
    // X-style: the tapped note's thread opens above the profile sheet.
    var threadTarget by remember { mutableStateOf<FeedNote?>(null) }
    // Note zap from a profile card (web PostCard zap parity).
    var zapNoteTarget by remember { mutableStateOf<FeedNote?>(null) }
    // Report user (kind-1984, p-tag only — full-page ⋯ menu parity).
    var showReportDialog by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("") }
    val zapState by homeViewModel.zapState.collectAsStateWithLifecycle()
    val identityState by identityViewModel.state.collectAsStateWithLifecycle()
    val publisherState by notePublisher.state.collectAsStateWithLifecycle()
    val localActions by homeViewModel.localActions.collectAsStateWithLifecycle()
    val feedStateLive by homeViewModel.state.collectAsStateWithLifecycle()

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onClose) {
        AuthorProfileContent(
            authorPubkey = authorPubkey,
            state = state,
            feedState = feedState,
            onOpen = onOpen,
            onFollow = homeViewModel::toggleFollow,
            onZap = { showZap = true },
            onOpenFullProfile = onOpenFullProfile,
            onOpenNote = { threadTarget = it },
            onLoadMore = onLoadMore,
            onLike = homeViewModel::toggleLike,
            onRepost = homeViewModel::repost,
            onZapNote = { note ->
                homeViewModel.loadZaps(note.id)
                zapNoteTarget = note
            },
            likedIds = localActions.liked,
            onClose = onClose,
            showModeration = identityState.account?.pubkeyHex != authorPubkey,
            isMuted = homeViewModel.isMuted(authorPubkey),
            onToggleMute = { homeViewModel.toggleMute(authorPubkey) },
            onReport = { showReportDialog = true },
        )
    }

    if (showReportDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showReportDialog = false; reportReason = "" },
            title = { Text("Report this user") },
            text = {
                Column {
                    Text("The report is published as a kind-1984 event.", style = MaterialTheme.typography.bodySmall, color = BitOSColors.textSecondary)
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

    val zapNote = zapNoteTarget
    if (zapNote != null) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { homeViewModel.dismissZap(); zapNoteTarget = null },
        ) {
            space.bitos.app.ui.feed.ZapContent(
                note = zapNote,
                lud16 = state.profile?.lud16,
                state = zapState,
                profileName = state.profile?.bestDisplayName,
                hasIdentity = identityState.account != null,
                zapCount = feedStateLive.zapCounts[zapNote.id] ?: 0,
                paidRequestIds = feedStateLive.zapRequestIds[zapNote.id] ?: emptySet(),
                onPaid = { sats, memo -> homeViewModel.onZapPaid(zapNote, sats, memo) },
                onAmountSelected = homeViewModel::selectZapAmount,
                onZap = { sats, comment, anonymous ->
                    homeViewModel.selectZapAmount(sats)
                    homeViewModel.zap(zapNote, comment, anonymous)
                },
                onClose = { homeViewModel.dismissZap(); zapNoteTarget = null },
                profilePictureUrl = state.profile?.picture,
            )
        }
    }

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

    // Profile zap (NIP-57 p-tag only): stacked above the profile sheet.
    if (showZap) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { homeViewModel.dismissZap(); showZap = false },
        ) {
            space.bitos.app.ui.feed.ZapContent(
                recipientPubkey = authorPubkey,
                lud16 = state.profile?.lud16,
                state = zapState,
                profileName = state.profile?.bestDisplayName,
                hasIdentity = identityState.account != null,
                onPaid = { sats, memo -> homeViewModel.onAuthorZapPaid(authorPubkey, sats, memo) },
                onAmountSelected = homeViewModel::selectZapAmount,
                onZap = { sats, comment, anonymous ->
                    homeViewModel.selectZapAmount(sats)
                    homeViewModel.zapAuthor(authorPubkey, state.profile?.lud16, comment, anonymous)
                },
                onClose = { homeViewModel.dismissZap(); showZap = false },
                profilePictureUrl = state.profile?.picture,
            )
        }
    }
}

/**
 * Lets a header overlap the preceding banner while reserving only its visible height in the list.
 * Compose padding cannot use negative values.
 */
private fun Modifier.overlapAbove(overlap: androidx.compose.ui.unit.Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val overlapPx = overlap.roundToPx()
        layout(
            width = placeable.width,
            height = (placeable.height - overlapPx).coerceAtLeast(0),
        ) {
            placeable.placeRelative(x = 0, y = -overlapPx)
        }
    }

@Composable
private fun InfoChip(
    icon: @Composable () -> Unit,
    text: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(tint.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon()
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

/// Compact like · repost · zap row shared by profile-surface note cards
/// (web PostCard action-bar parity; comment opens the thread sheet).
@Composable
internal fun ProfileCardActions(
    isLiked: Boolean = false,
    tally: space.bitos.core.feed.NoteTally? = null,
    onLike: () -> Unit,
    onRepost: () -> Unit,
    onZap: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = if (isLiked) "Unlike" else "Like") { onLike() }
                .padding(vertical = 2.dp),
        ) {
            space.bitos.app.ui.theme.SolarFeedIconImage(
                if (isLiked) space.bitos.app.ui.theme.SolarFeedIcon.HeartFilled else space.bitos.app.ui.theme.SolarFeedIcon.Heart,
                contentDescription = null,
                tint = if (isLiked) BitOSColors.like else BitOSColors.textSecondary,
                modifier = Modifier.size(14.dp),
            )
            val likes = tally?.reactions ?: 0
            if (likes > 0) {
                Spacer(Modifier.width(4.dp))
                Text("$likes", style = MaterialTheme.typography.labelSmall, color = if (isLiked) BitOSColors.like else BitOSColors.textSecondary)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "Repost") { onRepost() }
                .padding(vertical = 2.dp),
        ) {
            space.bitos.app.ui.theme.SolarFeedIconImage(
                space.bitos.app.ui.theme.SolarFeedIcon.Repost,
                contentDescription = null,
                tint = BitOSColors.repost,
                modifier = Modifier.size(14.dp),
            )
            val reposts = tally?.reposts ?: 0
            if (reposts > 0) {
                Spacer(Modifier.width(4.dp))
                Text("$reposts", style = MaterialTheme.typography.labelSmall, color = BitOSColors.repost)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "Zap") { onZap() }
                .padding(vertical = 2.dp),
        ) {
            space.bitos.app.ui.theme.SolarFeedIconImage(
                space.bitos.app.ui.theme.SolarFeedIcon.Zap,
                contentDescription = null,
                tint = BitOSColors.zap,
                modifier = Modifier.size(14.dp),
            )
            val sats = (tally?.zapMillisats ?: 0L) / 1000L
            if (sats > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    space.bitos.core.model.ZapFormat.sats(sats),
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.zap,
                )
            }
        }
    }
}

/// X-style card: time + media badge, clamped content, inline media preview
/// (image row / 16:9 video tile); optional action row; the whole card opens
/// the note's thread.
@Composable
private fun AuthorNoteCard(
    note: FeedNote,
    profile: ProfileMetadata?,
    onClick: () -> Unit = {},
    isLiked: Boolean = false,
    tally: space.bitos.core.feed.NoteTally? = null,
    onLike: () -> Unit = {},
    onRepost: () -> Unit = {},
    onZap: () -> Unit = {},
) {
    var expanded by remember(note.id) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = BitOSColors.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open note thread") { onClick() },
    ) {
        Column(Modifier.padding(BitOSSpacing.md).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTimeAgo(note.createdAt, System.currentTimeMillis() / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                if (note.video != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(imageVector = AppIcons.PlayCircle, contentDescription = null, tint = BitOSColors.accent, modifier = Modifier.size(12.dp))
                        Text("Video", style = MaterialTheme.typography.labelSmall, color = BitOSColors.accent)
                    }
                } else if (note.mediaUrls.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(AppIcons.Photo, contentDescription = null, tint = BitOSColors.textSecondary, modifier = Modifier.size(12.dp))
                        Text("${note.mediaUrls.size}", style = MaterialTheme.typography.labelSmall, color = BitOSColors.textSecondary)
                    }
                }
            }
            if (note.content.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    note.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.content.length > 200 && !expanded) {
                    TextButton(
                        onClick = { expanded = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("Show more", style = MaterialTheme.typography.labelSmall, color = BitOSColors.primary)
                    }
                }
            }
            // Inline media: up to three square images or one 16:9 video tile.
            val images = note.mediaUrls.filterNot { it.hasVideoExtension() }
            if (note.video != null) {
                VideoTile(
                    posterUrl = note.video?.posterUrl ?: note.video?.url,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            } else if (images.isNotEmpty()) {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    images.take(3).forEach { url ->
                        coil.compose.AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(92.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
            // Action row (like · repost · zap), web PostCard parity.
            androidx.compose.material3.HorizontalDivider(
                color = BitOSColors.divider.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 6.dp),
            )
            ProfileCardActions(
                isLiked = isLiked,
                tally = tally,
                onLike = onLike,
                onRepost = onRepost,
                onZap = onZap,
            )
        }
    }
}

private fun String.hasVideoExtension(): Boolean {
    val lower = lowercase()
    return listOf(".mp4", ".webm", ".mov", ".m4v").any { lower.endsWith(it) }
}
