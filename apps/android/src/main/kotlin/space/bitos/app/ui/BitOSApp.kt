@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package space.bitos.app.ui
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
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

/** Six-tab product shell (user decision 2026-08-28, legacy-app parity):
 * Home · Bitz · Discover · Chats · Activity · You. Studio/Create entry
 * points stay on the Home FAB, the Bitz header and the future You hub;
 * Settings pushes from You. */
private enum class TopLevelDestination(val label: String, @DrawableRes val iconRes: Int) {
    HOME("Home", R.drawable.solar_nav_home),
    BITZ("Bitz", R.drawable.solar_nav_bitz),
    DISCOVER("Discover", R.drawable.solar_nav_discover),
    CHATS("Chats", R.drawable.solar_nav_chats),
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
    // APP-002: first launch gates on the onboarding carousel.
    val context = androidx.compose.ui.platform.LocalContext.current
    var showOnboarding by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(!space.bitos.app.ui.onboarding.OnboardingPrefs.hasOnboarded(context))
    }

    BitOSTheme {
        if (showOnboarding) {
            space.bitos.app.ui.onboarding.OnboardingScreen(
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
        // APP-018 privacy: sensitive-media default gates every cover.
        val settingsSnapshot by settingsStore.snapshot.collectAsStateWithLifecycle()
        val sensitiveShowByDefault =
            settingsSnapshot.sensitiveMedia == space.bitos.core.settings.SensitiveMediaSetting.SHOW
        // APP-008: the composer is a full page (legacy CreateView parity).
        var showCreateNote by remember { mutableStateOf(false) }
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
        val authorState by authorRepository.state.collectAsStateWithLifecycle()
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
        val notificationsState by notifications.state.collectAsStateWithLifecycle()
        // Shell-level account wiring: the Activity badge needs the inbox
        // subscription alive from app start, not only while the tab is open.
        androidx.compose.runtime.LaunchedEffect(identity.account?.pubkeyHex) {
            notifications.setAccount(identity.account?.pubkeyHex)
        }
        val unreadCount = notificationsState.items.count { it.id !in notificationsState.readIds }
        // APP-018 functional setting: font size applies app-wide as a text
        // scale multiplier over the system font scale.
        val fontMultiplier = when (settingsSnapshot.fontSize) {
            space.bitos.core.settings.FontSizeSetting.SMALL -> 0.9f
            space.bitos.core.settings.FontSizeSetting.LARGE -> 1.15f
            space.bitos.core.settings.FontSizeSetting.EXTRA_LARGE -> 1.3f
            else -> 1f
        }
        val density = androidx.compose.ui.platform.LocalDensity.current
        val scaledDensity = androidx.compose.ui.unit.Density(density.density, density.fontScale * fontMultiplier)
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides scaledDensity,
        ) {
        Scaffold(
            containerColor = BitOSColors.background,
            bottomBar = {
                NavigationBar(containerColor = BitOSColors.surface) {
                    TopLevelDestination.entries.filterNot { it == TopLevelDestination.DISCOVER }.forEach { item ->
                        val badge = if (item == TopLevelDestination.ACTIVITY && unreadCount > 0) {
                            if (unreadCount > 9) "9+" else unreadCount.toString()
                        } else {
                            null
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
                }
                else {
                    // Overlay destinations are mutually exclusive with the
                    // tab content. Rendering both was the source of the
                    // More/Settings stacked-layout bug.
                    tabStateHolder.SaveableStateProvider(destination.name) {
                        when (destination) {
                            TopLevelDestination.HOME -> FeedScreen(homeViewModel, identityViewModel, notePublisher, mediaPublishViewModel, authorRepository, settingsStore, videoOnly = false, onOpenProfile = { destination = TopLevelDestination.YOU }, onOpenDiscover = { destination = TopLevelDestination.DISCOVER }, onOpenHub = { showMore = true }, onOpenCreate = { showCreateHub = true }, onOpenComposer = { showCreateNote = true }, retapTick = feedRetapTick, sensitiveShowByDefault = sensitiveShowByDefault, storiesRepository = storiesRepository)
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
                            )
                            TopLevelDestination.DISCOVER -> space.bitos.app.ui.discover.DiscoverScreen(
                                searchRepository,
                                homeViewModel,
                                identityViewModel,
                                notePublisher,
                            )
                            TopLevelDestination.CHATS -> space.bitos.app.ui.dm.DmScreen(identityViewModel, dmRepository)
                            TopLevelDestination.ACTIVITY -> space.bitos.app.ui.inbox.InboxScreen(
                                identityViewModel,
                                notifications,
                                homeViewModel,
                                notePublisher,
                                authorRepository,
                                sensitiveShowByDefault = sensitiveShowByDefault,
                            )
                            TopLevelDestination.YOU -> space.bitos.app.ui.profile.ProfileScreen(identityViewModel, settingsStore, feedRepository, relayManager, notePublisher, notifications, algorithmStore, homeViewModel, privacyPrefs, profileLookup = profileLookup, onOpenZaps = { showZaps = true })
                        }
                    }
                }
            }
        }
    }

        // ── T16 deep-link surfaces (overlay everything) ────────────────
        deepLinkAuthor?.let { authorPubkey ->
            androidx.compose.material3.ModalBottomSheet(onDismissRequest = { authorRepository.close(); deepLinkAuthor = null }) {
                space.bitos.app.ui.profile.AuthorProfileContent(
                    authorPubkey = authorPubkey,
                    state = authorState,
                    feedState = homeViewModel.state.value,
                    onOpen = authorRepository::open,
                    onFollow = homeViewModel::toggleFollow,
                    onClose = { authorRepository.close(); deepLinkAuthor = null },
                )
            }
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
