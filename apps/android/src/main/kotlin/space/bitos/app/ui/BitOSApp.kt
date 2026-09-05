@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package space.bitos.app.ui
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import space.bitos.app.ui.theme.AppIcons
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import space.bitos.app.R
import space.bitos.app.ui.create.CreateScreen
import space.bitos.app.ui.discover.DiscoverScreen
import space.bitos.app.ui.feed.FeedScreen
import space.bitos.app.ui.feed.HomeViewModel
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.PlayArrow
import space.bitos.app.ui.inbox.ChatsScreen
import space.bitos.app.ui.inbox.InboxScreen
import space.bitos.app.ui.profile.ProfileScreen
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSTheme

/** Five-slot product shell (prototype parity: Home · Bitz · ＋ · Activity · You).
 * The center ＋ opens the Create sheet (New note / New Bitz) — it replaces
 * the removed Home FAB, which conflicted with the tab bar. Chats lives
 * INSIDE Activity as a chip (Activity | Chats · unread). Discover stays
 * reachable from the Home header search and the More hub; Settings pushes
 * from You. */
private enum class TopLevelDestination(val label: String, @DrawableRes val iconRes: Int) {
    HOME("Home", R.drawable.solar_nav_home),
    BITZ("Bitz", R.drawable.solar_nav_bitz),
    DISCOVER("Discover", R.drawable.solar_nav_discover),
    ACTIVITY("Activity", R.drawable.solar_nav_activity),
    YOU("You", R.drawable.solar_nav_you),
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun BitOSApp(
    homeViewModel: HomeViewModel,
    identityViewModel: space.bitos.app.identity.IdentityViewModel,
    notePublisher: space.bitos.app.data.publish.NotePublisher,
    composerDraftStore: space.bitos.app.data.publish.ComposerDraftStore,
    sentZapsStore: space.bitos.app.data.zap.SentZapsStore,
    dmRepository: space.bitos.app.data.dm.DmRepository,
    storiesRepository: space.bitos.app.data.stories.StoriesRepository,
    mediaPublishViewModel: space.bitos.app.ui.feed.MediaPublishViewModel,
    notifications: space.bitos.app.data.feed.NotificationRepository,
    searchRepository: space.bitos.app.data.feed.SearchRepository,
    authorRepository: space.bitos.app.data.feed.AuthorRepository,
    ownAuthorRepository: space.bitos.app.data.feed.AuthorRepository,
    settingsStore: space.bitos.app.data.settings.SettingsStore,
    feedRepository: space.bitos.app.data.feed.FeedRepository,
    relayManager: space.bitos.app.data.relay.RelayManager,
    algorithmStore: space.bitos.app.data.feed.AlgorithmStore,
    privacyPrefs: space.bitos.app.data.settings.PrivacyPrefsStore,
    profileLookup: space.bitos.app.data.feed.ProfileLookupStore,
    /** T16: inbound nostr:/lightning: URI pending routing (consumed once). */
    pendingDeepLink: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    // Fast access (user decision 2026-08-28): the native system splash hands
    // off straight into the six-tab shell — the branded BootSplashScreen is
    // disabled at app entry (component retained in the library, APP-022).
    // Spec §4: first launch gates on the identity onboarding flow (welcome →
    // add identity → import/backup → npub confirmation); Browse now exits
    // into the feed as guest.
    val context = androidx.compose.ui.platform.LocalContext.current
    var showOnboarding by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(!space.bitos.app.ui.onboarding.OnboardingPrefs.hasOnboarded(context))
    }

    // APP-018/APP-023 functional settings: theme (light/dark/system),
    // accent and font size apply app-wide; the snapshot is collected above
    // the theme so a change recomposes the whole shell.
    val settingsSnapshot by settingsStore.snapshot.collectAsStateWithLifecycle()
    val darkTheme = when (settingsSnapshot.themeMode) {
        space.bitos.core.settings.ThemeModeSetting.LIGHT -> false
        space.bitos.core.settings.ThemeModeSetting.SYSTEM ->
            androidx.compose.foundation.isSystemInDarkTheme()
        space.bitos.core.settings.ThemeModeSetting.DARK -> true
    }

    BitOSTheme(
        darkTheme = darkTheme,
        accentColorHex = settingsSnapshot.accentColorHex,
    ) {
        // System chrome follows the active theme: light mode gets dark
        // icons on light bars, dark mode keeps the OLED bars (APP-023).
        val themedView = androidx.compose.ui.platform.LocalView.current
        val themedColors = space.bitos.app.ui.theme.BitOSColors
        if (!themedView.isInEditMode) {
            androidx.compose.runtime.SideEffect {
                val window = (themedView.context as? android.app.Activity)?.window ?: return@SideEffect
                val controller = androidx.core.view.WindowCompat.getInsetsController(window, themedView)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
                @Suppress("DEPRECATION")
                window.statusBarColor = themedColors.background.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = themedColors.background.toArgb()
            }
        }
        if (showOnboarding) {
            space.bitos.app.ui.onboarding.OnboardingScreen(
                identityViewModel = identityViewModel,
                onDone = {
                    space.bitos.app.ui.onboarding.OnboardingPrefs.markOnboarded(context)
                    showOnboarding = false
                },
            )
            return@BitOSTheme
        }
        var destination by rememberSaveable { mutableStateOf(TopLevelDestination.HOME) }
        // Preserve each tab's saveable UI state (notably Home list and Bitz
        // pager positions) while still disposing hidden media players.
        val tabStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        // APP-017: the More hub opens from the feed apps-grid action.
        var showMore by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        var hubSettingsSection by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
        // APP-003/APP-004: re-tap on the active Home/Bitz tab scrolls the
        // feed to top; a re-tap while already at top refreshes it.
        var feedRetapTick by remember { mutableStateOf(0) }
        // APP-018 privacy: sensitive-media default gates every cover
        // (settingsSnapshot is collected above the theme).
        val sensitiveShowByDefault =
            settingsSnapshot.sensitiveMedia == space.bitos.core.settings.SensitiveMediaSetting.SHOW
        // APP-008: the composer is a full page (legacy CreateView parity).
        var showCreateNote by remember { mutableStateOf(false) }
        // Prototype `openCreateSheet`: the center ＋ picker (New note /
        // New Bitz) — replaces the removed Home FAB.
        var showCreateSheet by remember { mutableStateOf(false) }
        // APP-007 remix: the composer opens seeded with attribution tags.
        var composerSeedTags by remember { mutableStateOf<List<List<String>>>(emptyList()) }
        fun openSeededComposer(tags: List<List<String>>) {
            composerSeedTags = tags
            showCreateNote = true
        }
        // APP-014: zap wallet overlay.
        var showZaps by remember { mutableStateOf(false) }
        // APP-020: static pages overlay (about/privacy/terms).
        var showStatic by remember { mutableStateOf<String?>(null) }
        // APP-015: the Saved (bookmarks) page opens from the More hub.
        var showBookmarks by remember { mutableStateOf(false) }
        // T4 capture entry: the Create hub (record Bitz / import media).
        var showCreateHub by remember { mutableStateOf(false) }
        // T16 deep links: author sheet target + lightning invoice viewer.
        var deepLinkAuthor by remember { mutableStateOf<String?>(null) }
        var deepLinkInvoice by remember { mutableStateOf<String?>(null) }
        // UX-010: in-app full profile page target. Every "View full
        // profile" affordance routes here — never an external link.
        var authorPageTarget by remember { mutableStateOf<String?>(null) }
        // Web `/bitz?author=<npub>#bitz=<id>` parity: profile Bitz-grid
        // tiles open the shared reels player scoped to that author.
        var authorBitzTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
        // APP-011: chat-header ⚡ chip target (author zap pipeline).
        var chatZapTarget by remember { mutableStateOf<String?>(null) }
        // Shared-card callbacks hosted here: external-link confirm and
        // in-place note-ref open (same flow as the home feed).
        var externalLinkTarget by remember { mutableStateOf<String?>(null) }
        var noteRefTarget by remember { mutableStateOf<String?>(null) }
        var authorThreadTarget by remember {
            mutableStateOf<space.bitos.core.feed.FeedNote?>(null)
        }
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        LaunchedEffect(pendingDeepLink) {
            val uri = pendingDeepLink ?: return@LaunchedEffect
            when (val target = space.bitos.core.nostr.DeepLinks.classify(uri)) {
                is space.bitos.core.nostr.DeepLinks.Target.Author -> {
                    authorRepository.open(target.pubkeyHex)
                    deepLinkAuthor = target.pubkeyHex
                    onDeepLinkConsumed()
                }
                is space.bitos.core.nostr.DeepLinks.Target.Note -> {
                    // Discover's ref-search fetches the head and its result
                    // card opens the thread sheet.
                    destination = TopLevelDestination.DISCOVER
                    searchRepository.search(target.reference)
                    onDeepLinkConsumed()
                }
                is space.bitos.core.nostr.DeepLinks.Target.Lightning -> {
                    deepLinkInvoice = target.invoiceUri
                    onDeepLinkConsumed()
                }
                null -> onDeepLinkConsumed()
            }
        }
        val identity by identityViewModel.state.collectAsStateWithLifecycle()
        // The shell owns badge counts, not the inbox/DM/feed payloads. These
        // distinct scalar projections keep relay bursts from invalidating
        // the entire Scaffold and active destination.
        val unreadCount by remember(notifications) {
            notifications.state
                .map { state -> state.items.count { it.id !in state.readIds } }
                .distinctUntilChanged()
        }.collectAsStateWithLifecycle(
            initialValue = notifications.state.value.let { state ->
                state.items.count { it.id !in state.readIds }
            },
        )
        val dmUnreadCount by remember(dmRepository) {
            dmRepository.state
                .map { state -> state.unreadCount + state.requestCount }
                .distinctUntilChanged()
        }.collectAsStateWithLifecycle(
            initialValue = dmRepository.state.value.let { it.unreadCount + it.requestCount },
        )
        // Shell-level account wiring: the Activity badge needs the inbox
        // subscription alive from app start, not only while the tab is open.
        androidx.compose.runtime.LaunchedEffect(identity.account?.pubkeyHex) {
            notifications.setAccount(identity.account?.pubkeyHex)
            dmRepository.setAccount(identity.account?.pubkeyHex)
        }
        // APP-018 functional setting: font size applies app-wide as a text
        // scale multiplier over the system font scale.
        val fontMultiplier = when (settingsSnapshot.fontSize) {
            space.bitos.core.settings.FontSizeSetting.SMALL -> 0.9f
            space.bitos.core.settings.FontSizeSetting.LARGE -> 1.15f
            space.bitos.core.settings.FontSizeSetting.EXTRA_LARGE -> 1.3f
            else -> 1f
        }
        val density = androidx.compose.ui.platform.LocalDensity.current
        val shellHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        val scaledDensity = androidx.compose.ui.unit.Density(density.density, density.fontScale * fontMultiplier)
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides scaledDensity,
        ) {
        Scaffold(
            containerColor = BitOSColors.background,
            bottomBar = {
                // APP-008: the composer is a full page (legacy CreateView
                // parity) — the tab bar never renders under it. The Create
                // hub (camera · meme studio · mass production) is equally
                // immersive, so the bar hides there too.
                if (!showCreateNote && !showCreateHub) {
                // Prototype tabdock parity: the combined unread badge covers
                // BOTH inbox surfaces — notifications + chats now that Chats
                // lives inside Activity.
                val activityBadgeCount = unreadCount + dmUnreadCount
                Box {
                NavigationBar(containerColor = BitOSColors.surface) {
                    // Five slots: Home · Bitz · ＋ · Activity · You. The null
                    // entry renders the center Create button.
                    val slots: List<TopLevelDestination?> = listOf(
                        TopLevelDestination.HOME,
                        TopLevelDestination.BITZ,
                        null,
                        TopLevelDestination.ACTIVITY,
                        TopLevelDestination.YOU,
                    )
                    slots.forEach { item ->
                        if (item == null) {
                            // Center slot: transparent width keeper — the
                            // visible hex floats ABOVE the bar (prototype
                            // `tab-create` margin-top:-18px overflow).
                            NavigationBarItem(
                                selected = false,
                                onClick = { showCreateSheet = true },
                                icon = { Spacer(Modifier.size(46.dp)) },
                                label = { Text("") },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unselectedIconColor = BitOSColors.textSecondary,
                                    unselectedTextColor = BitOSColors.textSecondary,
                                ),
                            )
                            return@forEach
                        }
                        val badge = when {
                            item == TopLevelDestination.ACTIVITY && activityBadgeCount > 0 ->
                                if (activityBadgeCount > 9) "9+" else activityBadgeCount.toString()
                            else -> null
                        }
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = {
                                if (destination == item &&
                                    (item == TopLevelDestination.HOME || item == TopLevelDestination.BITZ)
                                ) {
                                    feedRetapTick++
                                } else {
                                    destination = item
                                }
                            },
                            // Long-press Home/Bitz = force refresh (user
                            // decision 2026-08-28 — no header refresh button).
                            modifier = if (item == TopLevelDestination.HOME || item == TopLevelDestination.BITZ) {
                                Modifier
                                    .pointerInput(item) {
                                        detectTapGestures(
                                            onLongPress = { homeViewModel.refresh() },
                                        )
                                    }
                            } else {
                                Modifier
                            },
                            icon = {
                                androidx.compose.material3.BadgedBox(badge = {
                                    if (badge != null) {
                                        androidx.compose.material3.Badge {
                                            Text(badge, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }) {
                                    Icon(painterResource(item.iconRes), contentDescription = item.label)
                                }
                            },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = BitOSColors.primary,
                                selectedTextColor = BitOSColors.primary,
                                indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unselectedIconColor = BitOSColors.textSecondary,
                                unselectedTextColor = BitOSColors.textSecondary,
                            ),
                        )
                    }
                }
                // Center ＋: the AVATAR hex idiom (RingHexAvatar parity) —
                // a primary hex ring around a surface inner hex, ＋ glyph in
                // primary. The transparent slot below keeps widths equal.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .size(46.dp)
                        .clip(space.bitos.app.ui.components.HexShape())
                        .background(BitOSColors.primary)
                        .clickable(onClickLabel = "Create") { showCreateSheet = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(2.dp)
                            .clip(space.bitos.app.ui.components.HexShape())
                            .background(BitOSColors.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            AppIcons.Add,
                            contentDescription = null,
                            tint = BitOSColors.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopStart,
            ) {
                // APP-014 zap wallet: full screen over the shell.
                if (showStatic != null) {
                    space.bitos.app.ui.static.StaticPagesScreen(
                        initialPage = showStatic!!,
                        onClose = { showStatic = null },
                    )
                } else if (showZaps) {
                    space.bitos.app.ui.zap.ZapsScreen(
                        identityViewModel = identityViewModel,
                        homeViewModel = homeViewModel,
                        notifications = notifications,
                        sentZaps = sentZapsStore,
                        onClose = { showZaps = false },
                    )
                } else if (showCreateNote) {
                    space.bitos.app.ui.create.CreateNoteScreen(
                        identityViewModel = identityViewModel,
                        notePublisher = notePublisher,
                        homeViewModel = homeViewModel,
                        draftStore = composerDraftStore,
                        baseTags = composerSeedTags,
                        onClose = { showCreateNote = false; composerSeedTags = emptyList() },
                    )
                } else if (showBookmarks) {
                    space.bitos.app.ui.bookmarks.BookmarksScreen(
                        viewModel = homeViewModel,
                        identityViewModel = identityViewModel,
                        notePublisher = notePublisher,
                        onClose = { showBookmarks = false },
                    )
                } else if (showCreateHub) {
                    // T4: the capture pipeline (record → trim → publish and
                    // library import) is reachable from the Bitz header and
                    // the Home app-bar camera icon.
                    space.bitos.app.ui.create.CreateScreen(
                        mediaPublishViewModel = mediaPublishViewModel,
                        onClose = { showCreateHub = false },
                    )
                } else if (showMore) {
                    space.bitos.app.ui.more.MoreScreen(
                        identityViewModel = identityViewModel,
                        homeViewModel = homeViewModel,
                        settingsStore = settingsStore,
                        relayManager = relayManager,
                        onOpenProfile = { showMore = false; destination = TopLevelDestination.YOU },
                        onOpenDiscover = { showMore = false; destination = TopLevelDestination.DISCOVER },
                        onOpenSettings = { showMore = false; hubSettingsSection = "about" },
                        onOpenStaticAbout = { showStatic = "about" },
                        onOpenStaticPrivacy = { showStatic = "privacy" },
                        onOpenStaticTerms = { showStatic = "terms" },
                        onOpenLightning = { showMore = false; hubSettingsSection = "lightning" },
                        onOpenSaved = { showMore = false; showBookmarks = true },
                        onOpenZaps = { showMore = false; showZaps = true },
                        onClose = { showMore = false },
                    )
                } else if (hubSettingsSection != null) {
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
                        initialSection = hubSettingsSection,
                        onBack = { hubSettingsSection = null; showMore = true },
                    )
                } else if (authorBitzTarget != null) {
                    // Web `/bitz?author=<npub>#bitz=<id>` parity: the shared
                    // reels player scoped to one author — the same surface as
                    // the Bitz tab, data scoped to the profile's grid.
                    val (bitzAuthor, bitzNote) = authorBitzTarget!!
                    androidx.activity.compose.BackHandler { authorBitzTarget = null }
                    space.bitos.app.ui.bitz.BitzScreen(
                        viewModel = homeViewModel,
                        identityViewModel = identityViewModel,
                        notePublisher = notePublisher,
                        authorRepository = authorRepository,
                        settingsStore = settingsStore,
                        searchRepository = searchRepository,
                        sensitiveShowByDefault = sensitiveShowByDefault,
                        authorPubkey = bitzAuthor,
                        initialNoteId = bitzNote,
                        onExitAuthorMode = { authorBitzTarget = null },
                        onOpenComposer = { showCreateNote = true },
                        onOpenCreate = { showCreateHub = true },
                        onOpenRemixComposer = { openSeededComposer(it) },
                        onOpenAuthorProfile = { authorPageTarget = it },
                    )
                } else if (authorPageTarget != null) {
                    // UX-010: full-page author profile ("You"-page parity).
                    space.bitos.app.ui.profile.AuthorProfileScreen(
                        authorPubkey = authorPageTarget!!,
                        authorRepository = authorRepository,
                        homeViewModel = homeViewModel,
                        identityViewModel = identityViewModel,
                        notePublisher = notePublisher,
                        settingsStore = settingsStore,
                        onClose = { authorRepository.close(); authorPageTarget = null },
                        onOpenAuthor = { authorPageTarget = it; authorRepository.open(it) },
                        onOpenExternalLink = { externalLinkTarget = it },
                        onOpenNoteRef = { noteRefTarget = it },
                        onOpenBitzPlayer = { author, note -> authorBitzTarget = author to note },
                    )
                } else {
                    // Overlay destinations are mutually exclusive with the
                    // tab content. Rendering both was the source of the
                    // More/Settings stacked-layout bug.
                    tabStateHolder.SaveableStateProvider(destination.name) {
                        when (destination) {
                            TopLevelDestination.HOME -> FeedScreen(homeViewModel, identityViewModel, notePublisher, mediaPublishViewModel, authorRepository, settingsStore, videoOnly = false, relayManager = relayManager, onOpenProfile = { destination = TopLevelDestination.YOU }, onOpenDiscover = { destination = TopLevelDestination.DISCOVER }, onOpenHub = { showMore = true }, onOpenCreate = { showCreateHub = true }, onOpenComposer = { showCreateNote = true }, onOpenAuthorProfile = { authorPageTarget = it }, retapTick = feedRetapTick, sensitiveShowByDefault = sensitiveShowByDefault, storiesRepository = storiesRepository)
                            TopLevelDestination.BITZ -> space.bitos.app.ui.bitz.BitzScreen(
                                viewModel = homeViewModel,
                                identityViewModel = identityViewModel,
                                notePublisher = notePublisher,
                                authorRepository = authorRepository,
                                settingsStore = settingsStore,
                                searchRepository = searchRepository,
                                retapTick = feedRetapTick,
                                sensitiveShowByDefault = sensitiveShowByDefault,
                                onOpenComposer = { showCreateNote = true },
                                onOpenCreate = { showCreateHub = true },
                                onOpenRemixComposer = { openSeededComposer(it) },
                                onOpenAuthorProfile = { authorPageTarget = it },
                            )
                            TopLevelDestination.DISCOVER -> space.bitos.app.ui.discover.DiscoverScreen(
                                searchRepository,
                                homeViewModel,
                                identityViewModel,
                                notePublisher,
                                authorRepository = authorRepository,
                                onOpenAuthorProfile = { authorPageTarget = it },
                                sensitiveShowByDefault = sensitiveShowByDefault,
                                defaultZapSats = settingsSnapshot.defaultZapAmount,
                                onOpenExternalLink = { externalLinkTarget = it },
                                // Discover mosaic → the shared reels player scoped to
                                // the tile's author (same surface as profile grids).
                                onOpenBitzPlayer = { author, note -> authorBitzTarget = author to note },
                            )
                            // APP-011: profiles feed names/avatars; the zap
                            // chip routes to the author zap pipeline.
                            // APP-012: the Activity surface hosts BOTH inbox
                            // chips (prototype parity): notifications and —
                            // since the tab merge — Chats (NIP-17 DMs).
                            TopLevelDestination.ACTIVITY -> space.bitos.app.ui.inbox.InboxScreen(
                                identityViewModel,
                                notifications,
                                homeViewModel,
                                notePublisher,
                                authorRepository,
                                sensitiveShowByDefault = sensitiveShowByDefault,
                                onOpenAuthorProfile = { authorPageTarget = it },
                                dmUnreadCount = dmUnreadCount,
                                chats = {
                                    ChatDestination(
                                        homeViewModel = homeViewModel,
                                        identityViewModel = identityViewModel,
                                        dmRepository = dmRepository,
                                        onZapPeer = { peer ->
                                            homeViewModel.selectZapAmount(settingsSnapshot.defaultZapAmount.toLong())
                                            chatZapTarget = peer
                                        },
                                        onOpenProfile = { peer -> authorPageTarget = peer },
                                    )
                                },
                            )
                            TopLevelDestination.YOU -> space.bitos.app.ui.profile.ProfileScreen(identityViewModel, settingsStore, feedRepository, relayManager, notePublisher, notifications, algorithmStore, homeViewModel, privacyPrefs, profileLookup = profileLookup, ownAuthorRepository = ownAuthorRepository, onOpenZaps = { showZaps = true }, onOpenBitzPlayer = { author, note -> authorBitzTarget = author to note }, onOpenMentionProfile = { authorPageTarget = it })
                        }
                    }
                }
            }
        }
    }

        // Center ＋ picker (prototype `openCreateSheet` parity): the two
        // native creation entries. Story compose / quick MEM arrive with
        // their surfaces; the sheet grows then.
        if (showCreateSheet) {
            androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showCreateSheet = false }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                    Text(
                        "Create",
                        style = MaterialTheme.typography.labelMedium,
                        color = BitOSColors.textTertiary,
                    )
                    Spacer(Modifier.height(10.dp))
                    CreateSheetRow(
                        icon = AppIcons.Pen,
                        title = "New note",
                        subtitle = "text · poll · GIF · PoW",
                        onClick = { showCreateSheet = false; showCreateNote = true },
                    )
                    Spacer(Modifier.height(8.dp))
                    CreateSheetRow(
                        icon = AppIcons.Camera,
                        title = "New Bitz",
                        subtitle = "camera → editor → publish",
                        onClick = { showCreateSheet = false; showCreateHub = true },
                    )
                }
            }
        }

        // External-link confirm for shared cards on non-feed surfaces: the
        // browser only opens on an explicit Open.
        externalLinkTarget?.let { url ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { externalLinkTarget = null },
                title = { androidx.compose.material3.Text("Open external link?") },
                text = {
                    androidx.compose.material3.Text(
                        url,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url),
                                ),
                            )
                        }
                        externalLinkTarget = null
                    }) { androidx.compose.material3.Text("Open") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { externalLinkTarget = null }) {
                        androidx.compose.material3.Text("Cancel")
                    }
                },
            )
        }

        // In-place note-ref open: fetch the head note, then hand it to the
        // shared thread sheet.
        noteRefTarget?.let { raw ->
            androidx.compose.runtime.LaunchedEffect(raw) {
                var fetched: space.bitos.core.feed.FeedNote? = null
                var attempts = 0
                while (fetched == null && attempts < 20) {
                    fetched = homeViewModel.refNote(raw)
                    if (fetched == null) kotlinx.coroutines.delay(150)
                    attempts++
                }
                if (fetched != null) authorThreadTarget = fetched else noteRefTarget = null
            }
            authorThreadTarget?.let { target ->
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { authorThreadTarget = null; noteRefTarget = null },
                ) {
                    space.bitos.app.ui.feed.CommentThreadSheet(
                        note = target,
                        viewModel = homeViewModel,
                        identityViewModel = identityViewModel,
                        publisherState = notePublisher.state.collectAsStateWithLifecycle().value,
                        onDismiss = { authorThreadTarget = null; noteRefTarget = null },
                    )
                }
            }
        }

        // ── APP-011 chat zap chip (author zap pipeline, mock parity) ────
        val zapPeer = chatZapTarget
        if (zapPeer != null) {
            val feedProfiles by homeViewModel.feedProfiles.collectAsStateWithLifecycle()
            val peerProfile = feedProfiles[zapPeer]
            androidx.compose.material3.ModalBottomSheet(onDismissRequest = {
                homeViewModel.dismissZap()
                chatZapTarget = null
            }) {
                space.bitos.app.ui.feed.ZapContent(
                    note = null,
                    recipientPubkey = zapPeer,
                    state = homeViewModel.zapState.collectAsStateWithLifecycle().value,
                    lud16 = peerProfile?.lud16,
                    profileName = peerProfile?.bestDisplayName,
                    hasIdentity = identity.account != null,
                    onPaid = { sats, memo -> homeViewModel.onAuthorZapPaid(zapPeer, sats, memo) },
                    onAmountSelected = homeViewModel::selectZapAmount,
                    onZap = { sats, comment, anonymous ->
                        homeViewModel.selectZapAmount(sats)
                        homeViewModel.zapAuthor(zapPeer, peerProfile?.lud16, comment, anonymous)
                    },
                    onClose = {
                        homeViewModel.dismissZap()
                        chatZapTarget = null
                    },
                )
            }
        }

        // ── T16 deep-link surfaces (overlay everything) ────────────────
        deepLinkAuthor?.let { authorPubkey ->
            val authorState by authorRepository.state.collectAsStateWithLifecycle()
            space.bitos.app.ui.profile.AuthorProfileSheetHost(
                authorPubkey = authorPubkey,
                state = authorState,
                feedState = homeViewModel.state.value,
                homeViewModel = homeViewModel,
                identityViewModel = identityViewModel,
                notePublisher = notePublisher,
                onOpen = authorRepository::open,
                onClose = { authorRepository.close(); deepLinkAuthor = null },
                onOpenFullProfile = { authorRepository.close(); deepLinkAuthor = null; authorPageTarget = it },
                onLoadMore = authorRepository::loadMoreNotes,
            )
        }

        deepLinkInvoice?.let { invoice ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { deepLinkInvoice = null },
                title = { Text("Lightning invoice") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        space.bitos.app.ui.components.BrandQrCode(value = invoice, sizeDp = 208)
                        Text(
                            "Scan with any Lightning wallet to pay. BitOS never sees the payment.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BitOSColors.textSecondary,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(invoice))
                        deepLinkInvoice = null
                    }) { Text("Copy invoice", color = BitOSColors.primary) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { deepLinkInvoice = null }) { Text("Close", color = BitOSColors.textSecondary) }
                },
            )
        }
    }
}

/**
 * Chats is the only top-level destination that renders feed profile
 * enrichment. Collect that projection inside this destination so profile
 * arrivals recompose Chats, never the shell/navigation tree.
 */
/** One Create-sheet row (prototype list-row parity): icon plate + labels. */
@Composable
private fun CreateSheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = BitOSColors.surfaceElevated,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .clickable(onClickLabel = title) { onClick() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(BitOSColors.primaryContainer, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = BitOSColors.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = BitOSColors.textPrimary)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = BitOSColors.textTertiary)
            }
        }
    }
}

@Composable
private fun ChatDestination(
    homeViewModel: HomeViewModel,
    identityViewModel: space.bitos.app.identity.IdentityViewModel,
    dmRepository: space.bitos.app.data.dm.DmRepository,
    onZapPeer: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val feedProfiles by homeViewModel.feedProfiles.collectAsStateWithLifecycle()
    val chatProfiles = remember(feedProfiles) {
        feedProfiles.mapValues { (_, profile) -> profile.bestDisplayName to profile.picture }
    }
    space.bitos.app.ui.dm.DmScreen(
        identityViewModel = identityViewModel,
        dmRepository = dmRepository,
        onZapPeer = onZapPeer,
        onOpenProfile = onOpenProfile,
        profiles = chatProfiles,
    )
}
