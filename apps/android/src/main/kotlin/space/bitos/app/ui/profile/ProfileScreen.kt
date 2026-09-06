package space.bitos.app.ui.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.identity.IdentityViewModel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.SubdirectoryArrowRight
import space.bitos.app.ui.components.AppMenuEntry
import space.bitos.app.ui.components.AppMenuItem
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import kotlinx.coroutines.withContext

/**
 * Profile surface with the account flow (ID-004): browse-first by default —
 * identity creation/import is always explicit and visibly confirms the
 * derived npub before anything is stored or replaced.
 *
 * Signed-in layout is legacy-Flutter `profile_view` parity: cover with
 * floating glass controls, hex avatar hero, centered identity block,
 * "Edit profile" pill + ⋯ actions menu, completion card, stats, collapsible
 * about with info chips, and a pinned Notes · Replies · Bitz · Reposts rail.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    identityViewModel: IdentityViewModel,
    settingsStore: space.bitos.app.data.settings.SettingsStore,
    feedRepository: space.bitos.app.data.feed.FeedRepository,
    relayManager: space.bitos.app.data.relay.RelayManager,
    notePublisher: space.bitos.app.data.publish.NotePublisher,
    notifications: space.bitos.app.data.feed.NotificationRepository,
    algorithmStore: space.bitos.app.data.feed.AlgorithmStore,
    homeViewModel: space.bitos.app.ui.feed.HomeViewModel,
    privacyPrefs: space.bitos.app.data.settings.PrivacyPrefsStore,
    /** APP-014: opens the zap wallet (local sent ledger + received). */
    onOpenZaps: () -> Unit = {},
    /** Web ProfileBitzGrid parity: opens the author-scoped reels player
     *  at the tapped tile (one shared player, context-aware data). */
    onOpenBitzPlayer: (pubkey: String, noteId: String) -> Unit = { _, _ -> },
    /** Profile-mention tap in a card body → the mentioned user's page. */
    onOpenMentionProfile: (String) -> Unit = {},
    profileLookup: space.bitos.app.data.feed.ProfileLookupStore,
    /** Own-profile content source: a dedicated author-scoped REQ so the
     *  You tabs stop depending on whichever Home timeline window happens
     *  to be loaded (global head or Following-filtered). */
    ownAuthorRepository: space.bitos.app.data.feed.AuthorRepository,
) {
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) {
        space.bitos.app.ui.settings.SettingsScreen(
            identityViewModel,
            store = settingsStore,
            feedRepository = feedRepository,
            relayManager = relayManager,
            notePublisher = notePublisher,
            notifications = notifications,
            algorithmStore = algorithmStore,
            homeViewModel = homeViewModel,
            privacyPrefs = privacyPrefs,
            profileLookup = profileLookup,
            onBack = { showSettings = false },
        )
        return
    }
    // Signed-out identity entry reuses the shared onboarding flow (method →
    // import/backup → verify) — never a second, divergent import form; the
    // flow drives its own backup/verify gates.
    var showOnboarding by remember { mutableStateOf(false) }
    if (showOnboarding) {
        space.bitos.app.ui.onboarding.OnboardingScreen(
            identityViewModel = identityViewModel,
            onDone = { showOnboarding = false },
        )
        return
    }
    val state by identityViewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var showEdit by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showFollowing by remember { mutableStateOf(false) }
    var showFollowersInfo by remember { mutableStateOf(false) }
    var npubCopied by remember { mutableStateOf(false) }
    val profileEditState by identityViewModel.profileEditState.collectAsStateWithLifecycle()
    val account = state.account

    if (account != null) {
        // ── Own profile page (legacy Flutter profile_view parity) ──────
        val feedState by homeViewModel.state.collectAsStateWithLifecycle()
        val localActions by homeViewModel.localActions.collectAsStateWithLifecycle()
        val zapState by homeViewModel.zapState.collectAsStateWithLifecycle()
        val identityState by identityViewModel.state.collectAsStateWithLifecycle()
        val publisherState by notePublisher.state.collectAsStateWithLifecycle()
        // APP-018 compact mode: note-card density follows the appearance
        // setting on this surface too (same card as the home feed).
        val settingsSnapshot by settingsStore.snapshot.collectAsStateWithLifecycle()
        val profile = feedState.profiles[account.pubkeyHex]
        var tab by remember(account.pubkeyHex) { mutableStateOf(0) }
        // Author-scoped own content (dedicated REQ, paginated); reposts stay
        // on the feed window's best-effort kind-6 projection.
        androidx.compose.runtime.LaunchedEffect(account.pubkeyHex) {
            // Recover account heads that may have been sent before a relay
            // socket opened. The contact-list head is the canonical source
            // for the Following stat and connections sheet.
            feedRepository.refreshProfileAndFollowing(account.pubkeyHex)
            ownAuthorRepository.open(account.pubkeyHex)
        }
        val ownAuthorState by ownAuthorRepository.state.collectAsStateWithLifecycle()
        val own = ownAuthorState.notes
        val tabNotes = own.filter { it.replyTo == null }
        val tabReplies = own.filter { it.replyTo != null }
        val tabBitz = own.filter { it.video != null || it.mediaUrls.isNotEmpty() }
        val tabReposts = feedState.notes.filter { it.repostedBy == account.pubkeyHex }
        // Web full-page profile parity: a Zaps tab fed by the same merged
        // ledger as the zap wallet (local sent + verified received 9735).
        val notificationsState by notifications.state.collectAsStateWithLifecycle()
        val receivedZaps = notificationsState.items.filter { it.kind == space.bitos.core.model.NotificationKind.ZAP }
        val zapSummary = remember(receivedZaps, account.pubkeyHex) {
            space.bitos.core.model.AuthorZaps.summary(
                pubkey = account.pubkeyHex,
                receivedSats = receivedZaps.map { (it.amountMsat ?: 0) / 1000 },
                receivedFrom = receivedZaps.map { it.authorPubkey },
                sentRecords = homeViewModel.sentZapRecords(),
            )
        }
        val zapEntries = remember(receivedZaps, zapSummary) {
            space.bitos.core.model.SentZapLedger.ledger(
                sent = homeViewModel.sentZapRecords().filter { it.recipientPubkey == account.pubkeyHex },
                receivedSats = receivedZaps.map { (it.amountMsat ?: 0) / 1000 },
                receivedFrom = receivedZaps.map { it.authorPubkey },
                receivedAt = receivedZaps.map { it.createdAt },
                receivedNote = receivedZaps.map { it.targetEventId },
            ).filter { it.peerPubkey == account.pubkeyHex || it.direction == space.bitos.core.model.SentZapLedger.Direction.RECEIVED }
        }
        val tabs = listOf("Notes", "Replies", "Bitz", "Reposts", "Zaps")
        val content = listOf(tabNotes, tabReplies, tabBitz, tabReposts)[tab.coerceAtMost(3)]
        // Shared-card interaction targets (home-feed parity):
        // thread sheet, note zap, link confirm, note-ref open.
        var threadTarget by remember { mutableStateOf<space.bitos.core.feed.FeedNote?>(null) }
        var zapNoteTarget by remember { mutableStateOf<space.bitos.core.feed.FeedNote?>(null) }
        var externalLink by remember { mutableStateOf<String?>(null) }
        var noteRefTarget by remember { mutableStateOf<String?>(null) }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(BitOSColors.background),
        ) {
            // ── Cover + avatar hero (zero-gap, legacy _ProfileHeader) ────
            item(key = "hero") {
                Column {
                    Box(Modifier.fillMaxWidth()) {
                        // Banner (or brand gradient fallback) + hex pattern + scrims.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                        ) {
                            val bannerUrl = profile?.banner.orEmpty()
                            val bannerBitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, bannerUrl) {
                                if (bannerUrl.isNotEmpty()) {
                                    value = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        runCatching { android.graphics.BitmapFactory.decodeStream(java.net.URL(bannerUrl).openStream()) }.getOrNull()
                                    }
                                }
                            }
                            if (bannerBitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bannerBitmap!!.asImageBitmap(),
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
                                            Brush.linearGradient(
                                                listOf(Color(0xFFF9A84B), Color(0xFFD4790F)),
                                            ),
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
                        // Glass controls row.
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.weight(1f))
                            GlassPill(icon = AppIcons.Camera, label = "Edit cover", onClick = { showEdit = true })
                            GlassIconButton(icon = AppIcons.Share, label = "Share profile") {
                                clipboard.setText(AnnotatedString("https://njump.me/" + account.npub))
                            }
                            GlassIconButton(icon = Icons.Outlined.Settings, label = "Settings") { showSettings = true }
                        }
                    }
                    // Avatar band — keeps the hex locked onto the banner edge.
                    // Legacy _ProfileAvatarHero: hex drop shadow (no tint over
                    // the picture — the photo must render untouched).
                    // requiredSize: the 64dp band is layout reservation only —
                    // without it Compose coerces the hex to the band height
                    // (92→64, visibly squashed); the avatar intentionally
                    // overflows it by 46 like the legacy Positioned(top: -46).
                    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.TopCenter) {
                        Box(
                            Modifier
                                .offset(y = (-46).dp)
                                .requiredSize(92.dp)
                                .shadow(8.dp, space.bitos.app.ui.components.HexShape()),
                        ) {
                            space.bitos.app.ui.components.PubkeyAvatar(
                                pubkey = account.pubkeyHex,
                                size = 92,
                                pictureUrl = profile?.picture,
                                label = profile?.bestDisplayName,
                                hasLightning = !profile?.lud16.isNullOrBlank(),
                            )
                        }
                    }
                }
            }

            // ── Identity block (centered, legacy _ProfileInfo) ────────────
            item(key = "identity") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            profile?.bestDisplayName?.takeIf { it.isNotBlank() } ?: "Anonymous",
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
                    profile?.name?.takeIf { it.isNotBlank() }?.let {
                        Text("@$it", fontSize = 14.sp, color = BitOSColors.primary)
                    }
                    // npub chip (copy → check, resets after 1.8 s).
                    androidx.compose.runtime.LaunchedEffect(npubCopied) {
                        if (npubCopied) {
                            kotlinx.coroutines.delay(1_800)
                            npubCopied = false
                        }
                    }
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(BitOSColors.surfaceOverlay.copy(alpha = 0.5f))
                            .clickable {
                                clipboard.setText(AnnotatedString(account.npub))
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
                        Text(
                            settingsStore.shortNpub(account.npub),
                            fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = BitOSColors.textSecondary,
                        )
                    }
                    // ⚡ Lightning chip (when lud16 published).
                    if (!profile?.lud16.isNullOrBlank()) {
                        ProfileChip(icon = AppIcons.Zap, text = "Lightning", fg = BitOSColors.zap)
                    }
                }
            }

            // ── Actions: Edit profile pill + ⋯ menu (legacy parity) ───────
            item(key = "actions") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { showEdit = true },
                        shape = RoundedCornerShape(99.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BitOSColors.primary),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(AppIcons.Pen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Edit profile", fontSize = 14.sp, fontWeight = FontWeight.W700)
                    }
                    // ⋯ more menu (copy link · npub · QR · lightning · wallet).
                    Box {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .border(1.dp, BitOSColors.border.copy(alpha = 0.3f), CircleShape)
                                .clickable { showMoreMenu = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(AppIcons.More, contentDescription = "More profile actions", tint = BitOSColors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                        val menuEntries = buildList {
                            add(AppMenuEntry.Item(space.bitos.app.ui.components.AppMenuItem("settings", "Settings", Icons.Outlined.Settings)))
                            add(AppMenuEntry.Item(space.bitos.app.ui.components.AppMenuItem("zap-wallet", "Zap wallet", AppIcons.Zap)))
                            add(AppMenuEntry.Divider)
                            add(AppMenuEntry.Item(space.bitos.app.ui.components.AppMenuItem("copy-link", "Copy profile link", Icons.Outlined.Link)))
                            add(AppMenuEntry.Item(space.bitos.app.ui.components.AppMenuItem("copy-npub", "Copy npub", AppIcons.Copy)))
                            add(AppMenuEntry.Item(space.bitos.app.ui.components.AppMenuItem("show-qr", "Show profile QR", Icons.Outlined.QrCode2)))
                            if (!profile?.lud16.isNullOrBlank()) {
                                add(AppMenuEntry.Divider)
                                add(AppMenuEntry.Item(space.bitos.app.ui.components.AppMenuItem("copy-lightning", "Copy lightning address", AppIcons.Zap)))
                            }
                        }
                        space.bitos.app.ui.components.AppMenuDropdown(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            entries = menuEntries,
                            onSelect = { id ->
                                when (id) {
                                    "settings" -> showSettings = true
                                    "zap-wallet" -> onOpenZaps()
                                    "copy-link" -> clipboard.setText(AnnotatedString("https://njump.me/" + account.npub))
                                    "copy-npub" -> clipboard.setText(AnnotatedString(account.npub))
                                    "show-qr" -> showQr = true
                                    "copy-lightning" -> profile?.lud16?.let { clipboard.setText(AnnotatedString(it)) }
                                }
                            }
                        )
                    }
                }
            }

            item(key = "completion") {
                ProfileCompletionCard(
                    missing = profileCompletionFields(profile),
                    onFinish = { showEdit = true },
                )
            }

            // ── Stats row (evenly spaced) ─────────────────────────────────
            item(key = "stats") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatPill("Posts", formatCount(tabNotes.size))
                    StatPill("Following", formatCount(feedState.following.size), onClick = {
                        // You stays composed after its first visit, so tapping
                        // Following is also the explicit recovery action for a
                        // missed/timed-out one-shot contact-list request.
                        feedRepository.refreshProfileAndFollowing(account.pubkeyHex)
                        showFollowing = true
                    })
                    StatPill("Followers", formatCount(feedState.followers.size), onClick = { showFollowersInfo = true })
                    // Web stats-row parity: truthful sats figure from the
                    // merged ledger (tapping opens the zap wallet).
                    StatPill("Sats zapped", space.bitos.core.model.ZapFormat.sats(zapSummary.totalSats), onClick = onOpenZaps)
                }
            }

            // ── About + info chips (legacy _AboutSection) ─────────────────
            item(key = "about") { AboutSection(profile) }

            // ── Pinned tab rail (Notes · Replies · Bitz · Reposts) ────────
            stickyHeader(key = "tabs") {
                // UX: no surface block behind the rail — it blends with the
                // page background (opaque so pinned content stays masked).
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

            // ── Tab content (cards · strips · 3-col grid · empty states) ──
            if (tab == 4) {
                // Zaps tab (web "Zaps" parity): merged ledger entries.
                if (zapEntries.isEmpty()) {
                    item(key = "zaps-empty") { ZapTabEmptyState() }
                } else {
                    item(key = "zaps-list") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            zapEntries.take(50).forEach { entry ->
                                space.bitos.app.ui.zap.ZapLedgerRow(
                                    entry = entry,
                                    profile = feedState.profiles[entry.peerPubkey],
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            } else if (content.isEmpty() && tab != 3 && ownAuthorState.isLoading) {
                // Own author REQ still in flight — distinguish loading from empty.
                item(key = "own-loading") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.5.dp,
                                color = BitOSColors.textSecondary,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Loading from relays…",
                                fontSize = 12.sp,
                                color = BitOSColors.textSecondary,
                            )
                        }
                    }
                }
            } else if (content.isEmpty()) {
                item(key = "empty") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TabEmptyState(tab)
                        // Own tabs come from a dedicated REQ; an empty result
                        // can be a relay timeout, so offer a re-issue.
                        if (tab != 3 && !ownAuthorState.isLoading) {
                            androidx.compose.material3.TextButton(onClick = { ownAuthorRepository.retryFirstPage() }) {
                                Text("Retry", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            }
                        }
                    }
                }
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
                                                onOpenBitzPlayer(account.pubkeyHex, note.id)
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
                        if (tab == 1) ReplyContextStrip()
                        if (tab == 3) RepostHeader(note, feedState.profiles)
                        // Same card as the home feed: rich body, media,
                        // polls and the full action row — no fork.
                        space.bitos.app.ui.components.FeedNoteCard(
                            note = note,
                            compact = settingsSnapshot.compactMode,
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
                            onAuthor = { }, // own page: nowhere to navigate
                            isMuted = homeViewModel.isMuted(note.pubkey),
                            onMuteToggle = { homeViewModel.toggleMute(note.pubkey) },
                            onReport = { reason -> homeViewModel.report(note, reason) },
                            pollTally = feedState.pollTallies[note.id],
                            canVotePoll = identityState.account != null,
                            onLoadPollVotes = { homeViewModel.loadPollVotes(note.id) },
                            onVotePoll = { optionIndex -> homeViewModel.votePoll(note, optionIndex) },
                            onOpenExternalLink = { externalLink = it },
                            onOpenNoteRef = { noteRefTarget = it },
                            onOpenMentionProfile = onOpenMentionProfile,
                            rawEventJson = { homeViewModel.rawEventJson(note.id) },
                        )
                    }
                }
            }
            // Own tabs paginated by the dedicated author REQ: crossing the
            // rendered cap while more pages exist pulls the next page.
            if (tab != 4 && content.size > 50 && ownAuthorState.canLoadMore && !ownAuthorState.isLoadingMore) {
                item(key = "own-load-more") {
                    LaunchedEffect(content.size) { ownAuthorRepository.loadMoreNotes() }
                }
            }
            item(key = "bottom-space") { Spacer(Modifier.height(32.dp)) }
        }

        // ── Shared-card overlays (home-feed parity) ─────────────────────
        threadTarget?.let { target ->
            androidx.compose.material3.ModalBottomSheet(onDismissRequest = { threadTarget = null }) {
                space.bitos.app.ui.feed.CommentThreadSheet(
                    note = target,
                    viewModel = homeViewModel,
                    identityViewModel = identityViewModel,
                    publisherState = publisherState,
                    onDismiss = { threadTarget = null },
                )
            }
        }
        zapNoteTarget?.let { target ->
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { homeViewModel.dismissZap(); zapNoteTarget = null },
            ) {
                space.bitos.app.ui.feed.ZapContent(
                    note = target,
                    lud16 = feedState.profiles[target.pubkey]?.lud16,
                    state = zapState,
                    profileName = feedState.profiles[target.pubkey]?.bestDisplayName,
                    hasIdentity = identityState.account != null,
                    zapCount = feedState.zapCounts[target.id] ?: 0,
                    paidRequestIds = feedState.zapRequestIds[target.id] ?: emptySet(),
                    onPaid = { sats, memo -> homeViewModel.onZapPaid(target, sats, memo) },
                    onAmountSelected = homeViewModel::selectZapAmount,
                    onZap = { sats, comment, anonymous ->
                        homeViewModel.selectZapAmount(sats)
                        homeViewModel.zap(target, comment, anonymous)
                    },
                    onClose = { homeViewModel.dismissZap(); zapNoteTarget = null },
                    profilePictureUrl = feedState.profiles[target.pubkey]?.picture,
                )
            }
        }
        externalLink?.let { url ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { externalLink = null },
                title = { Text("Open external link?") },
                text = {
                    Text(
                        url,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        externalLink = null
                    }) { Text("Open", color = BitOSColors.primary) }
                },
                dismissButton = {
                    TextButton(onClick = { externalLink = null }) { Text("Cancel", color = BitOSColors.textSecondary) }
                },
            )
        }
        noteRefTarget?.let { raw ->
            androidx.compose.runtime.LaunchedEffect(raw) {
                // In-place note-ref open (home parity): fetch then thread.
                repeat(20) {
                    val fetched = homeViewModel.refNote(raw)
                    if (fetched != null) {
                        threadTarget = fetched
                        return@LaunchedEffect
                    }
                    kotlinx.coroutines.delay(150)
                }
                noteRefTarget = null
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .background(BitOSColors.background)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier.padding(BitOSSpacing.screen),
                verticalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("You", style = MaterialTheme.typography.headlineMedium)
                    androidx.compose.material3.IconButton(onClick = { showSettings = true }) {
                        androidx.compose.material3.Icon(
                            androidx.compose.ui.res.painterResource(space.bitos.app.R.drawable.solar_settings_linear),
                            contentDescription = "Settings",
                            tint = BitOSColors.textSecondary,
                        )
                    }
                }
                BrowseOnlyPanel(onAddIdentity = { showOnboarding = true })
            }
        }
    }

    if (showQr && state.account != null) {
        AlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text("Your identity QR") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    space.bitos.app.ui.components.BrandQrCode(value = "https://njump.me/" + state.account!!.npub, sizeDp = 224)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Scan with any Nostr app to follow " + settingsStore.shortNpub(state.account!!.npub),
                        fontSize = 12.sp, color = BitOSColors.textSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString("https://njump.me/" + state.account!!.npub)); showQr = false }) {
                    Text("Copy profile link", color = BitOSColors.primary)
                }
            },
            dismissButton = { TextButton(onClick = { showQr = false }) { Text("Close") } },
        )
    }

    if (showFollowing && account != null) {
        ConnectionsBottomSheet(
            title = "Following",
            pubkeys = homeViewModel.state.value.following,
            profiles = homeViewModel.state.value.profiles,
            onDismiss = { showFollowing = false },
            onOpenProfile = { pubkey ->
                showFollowing = false
                onOpenMentionProfile(pubkey)
            },
        )
    }

    if (showFollowersInfo) {
        ConnectionsBottomSheet(
            title = "Followers",
            emptyText = "No followers on your connected relays yet.",
            footnote = "Derived from contact lists on your connected relays; other relays may know more.",
            pubkeys = homeViewModel.state.value.followers,
            profiles = homeViewModel.state.value.profiles,
            onDismiss = { showFollowersInfo = false },
            onOpenProfile = { pubkey ->
                showFollowersInfo = false
                onOpenMentionProfile(pubkey)
            },
        )
    }

    if (showEdit && state.account != null) {
        val editProfile = homeViewModel.state.value.profiles[state.account!!.pubkeyHex]
        // Legacy Routes.PROFILE_EDIT parity: a full page, not a sheet.
        Column(Modifier.fillMaxSize().background(BitOSColors.background)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.IconButton(onClick = { showEdit = false }) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = BitOSColors.textPrimary,
                    )
                }
                Text("Edit profile", fontSize = 17.sp, fontWeight = FontWeight.W700, color = BitOSColors.textPrimary)
            }
            ProfileEditContent(
                showHeader = false,
                initialNip05 = editProfile?.nip05.orEmpty(),
                initialLud16 = editProfile?.lud16.orEmpty(),
                initialName = editProfile?.name.orEmpty(),
                initialDisplayName = editProfile?.displayName.orEmpty(),
                initialAbout = editProfile?.about.orEmpty(),
                initialPicture = editProfile?.picture.orEmpty(),
                initialWebsite = editProfile?.website.orEmpty(),
                initialBanner = editProfile?.banner.orEmpty(),
                pubkey = state.account!!.pubkeyHex,
                error = profileEditState.error,
                busy = profileEditState.busy,
                onUploadImage = { target, bytes ->
                    val spec = space.bitos.core.model.ProfileMediaSpec
                    val (w, h) = if (target == "avatar") spec.AVATAR_SIZE to spec.AVATAR_SIZE else spec.BANNER_WIDTH to spec.BANNER_HEIGHT
                    val prepped = ProfileImagePrep.cropScale(bytes, w, h)
                        ?: return@ProfileEditContent null
                    val signer = identityViewModel.createSigner() ?: return@ProfileEditContent null
                    runCatching {
                        space.bitos.app.data.media.BlossomUploader().upload(
                            prepped, "image/jpeg", signer, space.bitos.app.data.media.DefaultBlossomServer.url,
                        ).url
                    }.getOrNull()
                },
                onPublish = { name, displayName, about, nip05, lud16, picture, banner, website ->
                    identityViewModel.publishProfile(name, displayName, about, nip05, lud16, picture, banner, website)
                },
                onClose = { showEdit = false; identityViewModel.clearProfileEditError() },
            )
        }
    }
}

/** Compact social counter parity: 1234 -> "1.2K", 1_200_000 -> "1.2M". */
private fun formatCount(count: Int): String = when {
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
    else -> count.toString()
}

/**
 * Browse-first explainer (ID-004): nothing is created silently; the
 * Add identity action reuses the shared onboarding flow verbatim.
 */
@Composable
private fun BrowseOnlyPanel(onAddIdentity: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = BitOSColors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(BitOSSpacing.base), verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
            Text("Browsing without an identity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
            Text(
                "Watch and explore anonymously. Add an identity to create a new key or import one you already have — nothing is created silently.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
            )
            Button(onClick = onAddIdentity) {
                androidx.compose.material3.Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text("Add identity")
            }
        }
    }
}

// ── Own-profile helpers (legacy parity) ────────────────────────────────

@Composable
internal fun StatPill(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(
        modifier = if (onClick == null) Modifier else Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "$label connections", onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.W700, color = BitOSColors.textPrimary)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 12.sp, color = BitOSColors.textSecondary)
    }
}

/** Canonical connection rows (Following / Followers sheets) from verified kind-3 projections. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionsBottomSheet(
    title: String,
    pubkeys: Set<String>,
    profiles: Map<String, space.bitos.core.model.ProfileMetadata>,
    onDismiss: () -> Unit,
    onOpenProfile: (String) -> Unit,
    emptyText: String = "You are not following anyone yet.",
    footnote: String? = null,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BitOSColors.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(title, modifier = Modifier.fillMaxWidth(), fontSize = 18.sp, fontWeight = FontWeight.W800, color = BitOSColors.textPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            if (pubkeys.isEmpty()) {
                Text(emptyText, color = BitOSColors.textSecondary, modifier = Modifier.padding(vertical = 20.dp))
            } else {
                pubkeys.sorted().take(100).forEach { pubkey ->
                    val profile = profiles[pubkey]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                onClickLabel = "Open " + (profile?.bestDisplayName ?: "profile"),
                            ) { onOpenProfile(pubkey) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        space.bitos.app.ui.components.PubkeyAvatar(pubkey = pubkey, size = 42, pictureUrl = profile?.picture, label = profile?.bestDisplayName)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(profile?.bestDisplayName?.takeIf { it.isNotBlank() } ?: space.bitos.app.ui.components.shortPubkey(pubkey), fontWeight = FontWeight.W700, color = BitOSColors.textPrimary)
                            profile?.name?.takeIf { it.isNotBlank() }?.let { Text("@$it", fontSize = 12.sp, color = BitOSColors.textSecondary) }
                        }
                    }
                }
            }
            footnote?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 11.sp, color = BitOSColors.textTertiary)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/// Zaps-tab empty state (web parity: honest zero, nothing fake).
@Composable
private fun ZapTabEmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("⚡", fontSize = 34.sp, color = BitOSColors.zap)
        Spacer(Modifier.height(12.dp))
        Text("No zaps yet", fontSize = 14.sp, color = BitOSColors.textSecondary)
    }
}

/// Slim “replying to …” affordance above each reply card.
/// Shared with the author profile page.
@Composable
internal fun ReplyContextStrip() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.SubdirectoryArrowRight,
            contentDescription = null,
            tint = BitOSColors.textSecondary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text("Replying to", fontSize = 11.sp, fontWeight = FontWeight.W600, color = BitOSColors.textSecondary)
    }
}

/// “🔁 name reposted” header above each embedded original note.
@Composable
private fun RepostHeader(note: space.bitos.core.feed.FeedNote, profiles: Map<String, space.bitos.core.model.ProfileMetadata>) {
    val who = profiles[note.pubkey]?.bestDisplayName ?: "Anonymous"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Repost, contentDescription = null, tint = BitOSColors.textSecondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            "$who reposted",
            fontSize = 11.sp, fontWeight = FontWeight.W600, color = BitOSColors.textSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/// Shared empty state for the timeline tabs (icon 48 @50% + message).
@Composable
private fun TabEmptyState(tab: Int) {
    val (icon, message) = when (tab) {
        1 -> AppIcons.Comment to "No replies yet"
        2 -> Icons.Outlined.PhotoLibrary to "No bitz yet"
        3 -> AppIcons.Repost to "No reposts yet"
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

/// One Bitz grid tile — poster/image cover-cropped square; video tiles get
/// the black/30 scrim + white play affordance (legacy _MediaTile).
/// Shared with the author profile page.
@Composable
internal fun BitzGridTile(note: space.bitos.core.feed.FeedNote, modifier: Modifier = Modifier) {
    val images = note.mediaUrls.filterNot { it.hasVideoExtension() }
    val model = images.firstOrNull() ?: note.video?.posterUrl ?: note.video?.url
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(0.dp))) {
        if (model != null) {
            coil.compose.AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(BitOSColors.surfaceElevated, BitOSColors.surfaceOverlay))))
        }
        if (note.video != null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))
            Box(
                Modifier
                    .size(32.dp)
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.40f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/// 16:9 video preview with play affordance, used inside note cards.
/// Shared with the author profile sheet.
@Composable
internal fun VideoTile(posterUrl: String?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(180.dp)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        if (posterUrl != null) {
            coil.compose.AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(BitOSColors.surfaceElevated, BitOSColors.surfaceOverlay))))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))
        Box(
            Modifier
                .size(32.dp)
                .align(Alignment.Center)
                .background(Color.Black.copy(alpha = 0.40f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

// ── Legacy glass controls (`_GlassIconButton`/`_GlassPillButton` parity) ──

@Composable
private fun GlassIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.30f))
            .clickable(onClickLabel = label) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun GlassPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(Color.Black.copy(alpha = 0.30f))
            .clickable(onClickLabel = label) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.W600, color = Color.White)
    }
}

@Composable
private fun ProfileChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, fg: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(fg.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.W700, color = fg)
    }
}

/** Decorative native hex tile, matching the old app fallback cover. Shared
 * with the author profile page. */
@Composable
internal fun DefaultCoverHexPattern() {
    Canvas(Modifier.fillMaxSize()) {
        val tile = 120.dp.toPx()
        val hexHeight = 104.dp.toPx()
        var x = -tile / 2
        while (x < size.width + tile) {
            var y = -hexHeight / 2
            while (y < size.height + hexHeight) {
                val path = Path().apply {
                    moveTo(x + tile * .25f, y)
                    lineTo(x + tile * .75f, y)
                    lineTo(x + tile, y + hexHeight * .5f)
                    lineTo(x + tile * .75f, y + hexHeight)
                    lineTo(x + tile * .25f, y + hexHeight)
                    lineTo(x, y + hexHeight * .5f)
                    close()
                }
                drawPath(path, Color.White.copy(alpha = .08f))
                y += hexHeight
            }
            x += tile
        }
    }
}

private fun profileCompletionFields(profile: space.bitos.core.model.ProfileMetadata?): List<String> = buildList {
    if (profile?.displayName.isNullOrBlank() && profile?.name.isNullOrBlank()) add("Display name")
    if (profile?.about.isNullOrBlank()) add("Bio")
    if (profile?.picture.isNullOrBlank()) add("Profile picture")
    if (profile?.banner.isNullOrBlank()) add("Cover photo")
    if (profile?.nip05.isNullOrBlank()) add("Verified NIP-05")
    if (profile?.lud16.isNullOrBlank()) add("Lightning address")
    if (profile?.website.isNullOrBlank()) add("Website")
}

/// “Complete your profile” card (legacy _ProfileCompletionCard parity):
/// sparkles tile + score subtitle, primary→cyan progress bar, missing-field
/// chips and the primary Finish pill.
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProfileCompletionCard(missing: List<String>, onFinish: () -> Unit) {
    if (missing.isEmpty()) return
    val score = ((7 - missing.size) * 100) / 7
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(BitOSColors.surfaceOverlay.copy(alpha = 0.3f))
            .border(1.dp, BitOSColors.border.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BitOSColors.zap.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Sparkles, contentDescription = null, tint = BitOSColors.zap, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Complete your profile", fontSize = 14.sp, fontWeight = FontWeight.W800)
                Text(
                    "$score% complete · ${missing.size} steps to go",
                    fontSize = 11.5.sp, color = BitOSColors.textSecondary,
                )
            }
            Button(
                onClick = onFinish,
                shape = RoundedCornerShape(99.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BitOSColors.primary),
            ) {
                Text("Finish", fontSize = 11.sp, fontWeight = FontWeight.W700)
            }
        }
        // Progress bar — primary → cyan gradient fill (web parity).
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(BitOSColors.surfaceOverlay.copy(alpha = 0.6f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(score / 100f)
                    .height(8.dp)
                    .background(
                        Brush.horizontalGradient(listOf(BitOSColors.primary, BitOSColors.accent)),
                    ),
            )
        }
        // Missing-field chips (legacy pill chips).
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            missing.forEach { field ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(BitOSColors.surfaceOverlay.copy(alpha = 0.5f))
                        .border(1.dp, BitOSColors.border.copy(alpha = 0.15f), RoundedCornerShape(99.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Rounded.Circle,
                        contentDescription = null,
                        tint = BitOSColors.textSecondary,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(field, fontSize = 11.sp, fontWeight = FontWeight.W600, color = BitOSColors.textSecondary)
                }
            }
        }
    }
}

/// Bio (5-line clamp, Show more past 240 chars) + info chips (NIP-05,
/// website, lightning) + hairline divider (legacy _AboutSection).
/// Shared with the author profile page.
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun AboutSection(profile: space.bitos.core.model.ProfileMetadata?) {
    val bio = profile?.about?.trim().orEmpty()
    val nip05 = profile?.nip05?.trim().orEmpty()
    val website = profile?.website?.trim().orEmpty()
    val lud16 = profile?.lud16?.trim().orEmpty()
    if (bio.isEmpty() && nip05.isEmpty() && website.isEmpty() && lud16.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (bio.isNotEmpty()) {
            Text(
                bio,
                fontSize = 14.sp, color = BitOSColors.textSecondary, lineHeight = 21.sp,
                maxLines = if (expanded || bio.length <= 240) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis,
            )
            if (bio.length > 240) {
                Text(
                    if (expanded) "Show less" else "Show more",
                    fontSize = 12.sp, fontWeight = FontWeight.W700, color = BitOSColors.primary,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
        }
        if (nip05.isNotEmpty() || website.isNotEmpty() || lud16.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (nip05.isNotEmpty()) {
                    InfoChip(Icons.Rounded.CheckCircle, nip05, BitOSColors.accent)
                }
                if (website.isNotEmpty()) {
                    InfoChunkWebsite(website)
                }
                if (lud16.isNotEmpty()) {
                    InfoChip(AppIcons.Zap, lud16, BitOSColors.zap)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(BitOSColors.border.copy(alpha = 0.65f)))
    }
}

@Composable
private fun InfoChunkWebsite(website: String) {
    val uri = website.takeIf { Uri.parse(it).scheme?.lowercase() in setOf("http", "https") }?.let(Uri::parse)
    if (uri == null) {
        InfoChunkPlain(website)
        return
    }
    val context = LocalContext.current
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BitOSColors.textSecondary.copy(alpha = 0.1f))
            .clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Rounded.Language,
            contentDescription = null,
            tint = BitOSColors.textSecondary,
            modifier = Modifier.size(14.dp),
        )
        Text(website, fontSize = 11.sp, color = BitOSColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InfoChunkPlain(text: String) {
    InfoChip(null, text, BitOSColors.textSecondary)
}

/// Small rounded identity chip (radius 8, tint@10% bg, 14dp icon).
@Composable
private fun InfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        }
        Text(text, fontSize = 11.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun String.hasVideoExtension(): Boolean {
    val lower = lowercase()
    return listOf(".mp4", ".webm", ".mov", ".m4v").any { lower.endsWith(it) }
}
