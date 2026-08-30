package space.bitos.app.ui.feed

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import space.bitos.app.data.feed.DefaultRelays
import space.bitos.app.data.feed.FeedTimeline
import space.bitos.app.data.feed.FeedUiState
import space.bitos.app.data.feed.RelayHealth
import space.bitos.app.data.publish.NotePublisher
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.player.VideoPlayerPool
import space.bitos.app.ui.components.AppMenuDropdown
import space.bitos.app.ui.components.AppMenuEntry
import space.bitos.app.ui.components.AppMenuItem
import space.bitos.app.ui.components.MediaLightbox
import space.bitos.app.ui.components.MediaRow
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.components.AnimatedLikeIcon
import space.bitos.app.ui.components.RichText
import space.bitos.app.ui.components.SensitiveCover
import space.bitos.app.ui.components.formatTimeAgo
import space.bitos.app.ui.components.shortPubkey
import space.bitos.app.ui.theme.AppIcons
import space.bitos.core.feed.FeedFilter
import space.bitos.core.feed.BitzTimelinePolicy
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.app.ui.theme.SolarFeedIcon
import space.bitos.app.ui.theme.SolarFeedIconImage
import space.bitos.core.feed.FeedNote

/**
 * A visible row owns its relative-time clock. It ticks each second only for
 * the first minute, then wakes on minute boundaries; relay state is never
 * republished just to advance a label.
 */
@Composable
private fun rememberRelativeTimeNow(createdAtSeconds: Long): Long {
    var nowSeconds by remember(createdAtSeconds) {
        mutableLongStateOf(System.currentTimeMillis() / 1_000)
    }
    LaunchedEffect(createdAtSeconds) {
        while (true) {
            val age = (nowSeconds - createdAtSeconds).coerceAtLeast(0)
            val delaySeconds = if (age < 60) 1L else (60 - (age % 60)).coerceAtLeast(1L)
            delay(delaySeconds * 1_000)
            nowSeconds = System.currentTimeMillis() / 1_000
        }
    }
    return nowSeconds
}

/**
 * Home surface (FED-002): full-screen vertical pager; only the settled page
 * plays. Text notes render as full-screen cards, video notes (kind 22 or
 * legacy mp4 links) render through the three-slot player pool.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: HomeViewModel,
    identityViewModel: IdentityViewModel,
    notePublisher: NotePublisher,
    mediaPublishViewModel: MediaPublishViewModel,
    authorRepository: space.bitos.app.data.feed.AuthorRepository,
    settingsStore: space.bitos.app.data.settings.SettingsStore,
    videoOnly: Boolean = false,
    sensitiveShowByDefault: Boolean = false,
    onOpenProfile: () -> Unit = {},
    onOpenDiscover: () -> Unit = {},
    onOpenHub: () -> Unit = {},
    /** Opens the Create hub (record/import) — iOS app-bar camera parity. */
    onOpenCreate: () -> Unit = {},
    /** APP-008: opens the full-page composer (legacy CreateView parity). */
    onOpenComposer: () -> Unit = {},
    /** APP-003/APP-004: bumped when the user re-taps the ACTIVE shell tab
     * (Home/Bitz) — scrolls to top, or refreshes when already at top. */
    retapTick: Int = 0,
    /** APP-006: stories bar + viewer. */
    storiesRepository: space.bitos.app.data.stories.StoriesRepository? = null,
    /** UX-010: opens the in-app full profile page for a pubkey. */
    onOpenAuthorProfile: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions by viewModel.localActions.collectAsStateWithLifecycle()
    val publishState by notePublisher.state.collectAsStateWithLifecycle()
    var showComposer by rememberSaveable { mutableStateOf(false) }
    var showImportMedia by rememberSaveable { mutableStateOf(false) }
    val mediaState by mediaPublishViewModel.state.collectAsStateWithLifecycle()
    var showCommentsFor by androidx.compose.runtime.remember { mutableStateOf<FeedNote?>(null) }
    var zapTarget by androidx.compose.runtime.remember { mutableStateOf<FeedNote?>(null) }
    /** External-link confirm sheet (never opens the browser unattended). */
    var externalLink by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    /** In-place note-ref open (note1/nevent1/naddr1 → thread sheet). */
    var refOpenTarget by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    var authorTarget by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val authorState by authorRepository.state.collectAsStateWithLifecycle()
    val zapState by viewModel.zapState.collectAsStateWithLifecycle()
    val identityState by identityViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // APP-006: stories bar + viewer.
    val storiesState by (storiesRepository?.state
        ?: kotlinx.coroutines.flow.MutableStateFlow(space.bitos.app.data.stories.StoriesUiState())
    ).collectAsStateWithLifecycle()
    var storyViewerTarget by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<space.bitos.core.model.StoryAuthor?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(state.accountPubkey, state.following) {
        storiesRepository?.setAccount(state.accountPubkey, state.following, context)
    }
    // APP-018 functional settings: autoplay policy + playback rate drive the
    // pool live (closure reads the current snapshot on every reconciliation).
    val settingsSnapshot by settingsStore.snapshot.collectAsStateWithLifecycle()
    val currentSettings = androidx.compose.runtime.rememberUpdatedState(settingsSnapshot)
    val feedHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val pool = androidx.compose.runtime.remember {
        VideoPlayerPool(
            context = context,
            canAutoplay = { autoplayAllowed(context, currentSettings.value.mediaAutoPlay) },
            rateProvider = { currentSettings.value.videoPlaybackRate.rate.toFloat() },
        )
    }
    val playerBindings by pool.playerBindings.collectAsStateWithLifecycle()
    // Shell split (user decision): Home tab = text notes; Bitz tab = reels.
    val feedNotes = remember(state.notes, videoOnly) {
        if (videoOnly) state.notes.filter { it.video != null } else state.notes.filter { it.video == null }
    }
    val pagerState = rememberPagerState(pageCount = { feedNotes.size })
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val revealPendingAtTop = {
        scope.launch {
            if (videoOnly && pagerState.currentPage != 0) {
                pagerState.animateScrollToPage(0)
            } else if (!videoOnly && (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)) {
                listState.animateScrollToItem(0)
            }
            viewModel.revealPendingNotes()
        }
        Unit
    }

    DisposableEffect(Unit) {
        onDispose { pool.releaseAll() }
    }
    // Re-reconcile the pool only when the settled page's identity or its
    // immediate neighbors change — live arrivals appended to the tail do
    // not affect the three active slots.
    val settledNoteId = feedNotes.getOrNull(pagerState.settledPage)?.id
    val prevNoteId = feedNotes.getOrNull(pagerState.settledPage - 1)?.id
    val nextNoteId = feedNotes.getOrNull(pagerState.settledPage + 1)?.id
    LaunchedEffect(settledNoteId, prevNoteId, nextNoteId) {
        pool.update(pagerState.settledPage, feedNotes)
    }
    // APP-004: arrivals are held while the user is scrolled into the ACTIVE
    // surface (list first row / pager page 0 = top); at top auto-reveals.
    LaunchedEffect(
        videoOnly,
        pagerState.settledPage,
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
    ) {
        viewModel.holdNewNotes(
            if (videoOnly) {
                pagerState.settledPage != 0
            } else {
                listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0
            },
        )
    }
    // APP-004 pagination: near the end of the active surface, fetch one
    // older page (the repository guards in-flight + exhausted requests).
    LaunchedEffect(videoOnly, pagerState.settledPage, feedNotes.size, state.isLoadingOlder, state.noMoreOlder) {
        if (videoOnly && feedNotes.isNotEmpty() && !state.noMoreOlder && !state.isLoadingOlder &&
            pagerState.settledPage >= feedNotes.size - BitzTimelinePolicy.PREFETCH_BUFFER_THRESHOLD
        ) {
            viewModel.loadOlder()
        }
    }
    val listNearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >=
                info.totalItemsCount - BitzTimelinePolicy.PREFETCH_BUFFER_THRESHOLD
        }
    }
    LaunchedEffect(listNearEnd, state.isLoadingOlder, state.noMoreOlder) {
        if (!videoOnly && listNearEnd && !state.noMoreOlder && !state.isLoadingOlder) viewModel.loadOlder()
    }
    // APP-003/APP-004: re-tap on the active shell tab scrolls to top; a
    // re-tap while already at top refreshes (X/Instagram pattern).
    LaunchedEffect(retapTick) {
        if (retapTick == 0) return@LaunchedEffect
        if (videoOnly) {
            if (pagerState.currentPage != 0) {
                scope.launch { pagerState.animateScrollToPage(0) }
            } else {
                viewModel.refresh()
            }
        } else {
            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                scope.launch { listState.animateScrollToItem(0) }
            } else {
                viewModel.refresh()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BitOSColors.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            FeedHeader(
                state = state,
                onTimeline = viewModel::selectTimeline,
                onFilter = viewModel::selectFilter,
                onOpenDiscover = onOpenDiscover,
                onOpenHub = onOpenHub,
                onOpenCreate = onOpenCreate,
            )
            // Float within the feed area, below (not over) the tabs. This
            // keeps the control visible while preserving tab hit targets.
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    if (state.accountPubkey == null) {
                        GuestBanner(onGetStarted = onOpenProfile)
                    }
                    when {
                        state.isLoading && feedNotes.isEmpty() -> FeedLoading()
                        state.timeline == FeedTimeline.FOLLOWING && state.accountPubkey == null && feedNotes.isEmpty() -> FollowingPlaceholder()
                        state.timeline == FeedTimeline.FOLLOWING && !state.followingResolved && feedNotes.isEmpty() -> FeedLoading()
                        feedNotes.isEmpty() -> FeedEmpty(
                            relayHealth = state.relayHealth,
                            filterActive = state.filter != FeedFilter.ALL &&
                                (if (state.timeline == FeedTimeline.FOLLOWING) state.followingCount else state.forYouCount) > 0,
                            onRetry = viewModel::retryNow,
                            onShowAll = { viewModel.selectFilter(FeedFilter.ALL) },
                        )
                        else -> if (videoOnly) VerticalPager(state = pagerState) { page ->
                            FeedPage(
                                note = feedNotes[page], state = state, actions = actions, pool = pool,
                                player = playerBindings[feedNotes[page].id],
                                onLike = viewModel::toggleLike, onBookmark = viewModel::toggleBookmark,
                                onComment = { showCommentsFor = it }, onRepost = viewModel::repost,
                                onFollow = viewModel::toggleFollow,
                                onZap = { viewModel.loadZaps(it.id); viewModel.selectZapAmount(settingsSnapshot.defaultZapAmount.toLong()); zapTarget = it },
                                onAuthor = { authorTarget = it }, isMuted = viewModel.isMuted(feedNotes[page].pubkey),
                                onMuteToggle = { viewModel.toggleMute(feedNotes[page].pubkey) },
                                onReport = { reason -> viewModel.report(feedNotes[page], reason) },
                            )
                        } else NotesList(
                            notes = feedNotes, state = state, actions = actions, viewModel = viewModel,
                            compact = settingsSnapshot.compactMode,
                            listState = listState, onComment = { showCommentsFor = it }, onZap = { viewModel.selectZapAmount(settingsSnapshot.defaultZapAmount.toLong()); zapTarget = it },
                            onAuthor = { authorTarget = it }, onLike = { note ->
                                if (settingsSnapshot.hapticEnabled) {
                                    feedHaptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                }
                                viewModel.toggleLike(note)
                            },
                            onBookmark = viewModel::toggleBookmark, onRepost = viewModel::repost,
                            onOpenExternalLink = { externalLink = it },
                            onOpenNoteRef = { raw ->
                                // In-place note-ref open (web parity): fetch
                                // the head, then show the thread sheet.
                                refOpenTarget = raw
                                viewModel.openNoteReference(raw)
                            },
                            sensitiveShowByDefault = sensitiveShowByDefault,
                            mediaPreview = settingsSnapshot.mediaPreview,
                        )
                    }
                }
                // APP-006: stories bar (top, scrolls with content via the header).
        if (!videoOnly && storiesState.authors.isNotEmpty()) {
            space.bitos.app.ui.stories.StoriesBar(
                authors = storiesState.authors,
                seenIds = storiesState.seenIds,
                onOpenViewer = { storyViewerTarget = it },
                modifier = Modifier.padding(top = 48.dp),
            )
        }

        // APP-006: story viewer full-screen overlay.
        storyViewerTarget?.let { storyAuthor ->
            space.bitos.app.ui.stories.StoryViewer(
                author = storyAuthor,
                onSeen = { slideId -> storiesRepository?.markSeen(slideId, context) },
                onClose = { storyViewerTarget = null },
            )
        }

        androidx.compose.animation.AnimatedVisibility(
                    visible = state.pendingNotes.isNotEmpty(),
                    enter = androidx.compose.animation.slideInVertically(initialOffsetY = { -it }) + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { -it }) + androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = BitOSSpacing.xs),
                ) {
                    NewNotesPill(
                        pending = state.pendingNotes,
                        authorName = { state.profiles[it]?.bestDisplayName },
                        hasLightning = { !state.profiles[it]?.lud16.isNullOrBlank() },
                        onReveal = revealPendingAtTop,
                    )
                }
            }
        }

        // APP-004: New-note extended FAB (spec §3.4).
        androidx.compose.material3.ExtendedFloatingActionButton(
            onClick = onOpenComposer,
            icon = {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(space.bitos.app.R.drawable.solar_pen_linear),
                    contentDescription = null,
                )
            },
            text = { Text("New note") },
            containerColor = BitOSColors.primary,
            contentColor = Color(0xFF0A0A0F),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp),
        )
    }

    authorTarget?.let { authorPubkey ->
        space.bitos.app.ui.profile.AuthorProfileSheetHost(
            authorPubkey = authorPubkey,
            state = authorState,
            feedState = state,
            homeViewModel = viewModel,
            identityViewModel = identityViewModel,
            notePublisher = notePublisher,
            onOpen = authorRepository::open,
            onClose = { authorRepository.close(); authorTarget = null },
            // UX-010: the full profile is an in-app page, never a browser link.
            onOpenFullProfile = { authorRepository.close(); authorTarget = null; onOpenAuthorProfile(it) },
            onLoadMore = authorRepository::loadMoreNotes,
        )
    }

    zapTarget?.let { target ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { viewModel.dismissZap(); zapTarget = null }) {
            ZapContent(
                note = target,
                lud16 = state.profiles[target.pubkey]?.lud16,
                state = zapState,
                profileName = state.profiles[target.pubkey]?.bestDisplayName,
                hasIdentity = identityViewModel.state.value.account != null,
                zapCount = state.zapCounts[target.id] ?: 0,
                paidRequestIds = state.zapRequestIds[target.id] ?: emptySet(),
                onPaid = { sats, memo -> viewModel.onZapPaid(target, sats, memo) },
                onAmountSelected = viewModel::selectZapAmount,
                onZap = { sats, comment, anonymous ->
                    viewModel.selectZapAmount(sats)
                    viewModel.zap(target, comment, anonymous)
                },
                onClose = { viewModel.dismissZap(); zapTarget = null },
                profilePictureUrl = state.profiles[target.pubkey]?.picture,
            )
        }
    }

    showCommentsFor?.let { target ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showCommentsFor = null }) {
            CommentContent(
                note = target,
                feedState = state,
                identityViewModel = identityViewModel,
                publisherState = publishState,
                actions = actions,
                onLoadComments = viewModel::loadComments,
                onReply = { text, note, attachments, pow -> viewModel.reply(text, note, attachments, pow) },
                onLike = viewModel::toggleLike,
                onRepost = viewModel::repost,
                onBookmark = viewModel::toggleBookmark,
                // Per-comment zap: opens the zap sheet above this one.
                onZap = { reply ->
                    viewModel.loadZaps(reply.id)
                    viewModel.selectZapAmount(settingsSnapshot.defaultZapAmount.toLong())
                    zapTarget = reply
                },
                // UX-010: author taps inside the thread open the profile sheet.
                onOpenAuthor = { authorTarget = it },
                onClose = { showCommentsFor = null },
            )
        }
    }

    // In-place note-ref open: poll the bounded side-store until the head
    // arrives (3 s), then show the thread sheet.
    LaunchedEffect(refOpenTarget) {
        val raw = refOpenTarget ?: return@LaunchedEffect
        repeat(20) {
            val fetched = viewModel.refNote(raw)
            if (fetched != null) {
                refOpenTarget = null
                showCommentsFor = fetched
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(150)
        }
        refOpenTarget = null
    }

    // External-link confirm: the browser only opens on an explicit Open.
    externalLink?.let { url ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { externalLink = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = BitOSSpacing.base).padding(bottom = BitOSSpacing.lg)) {
                Text("Open external link?", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.W700)
                Spacer(Modifier.height(BitOSSpacing.sm))
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = BitOSColors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
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
                            contentColor = Color(0xFF0A0A0F),
                        ),
                    ) { Text("Open", fontWeight = androidx.compose.ui.text.font.FontWeight.W600) }
                    androidx.compose.material3.OutlinedButton(onClick = { externalLink = null }) {
                        Text("Cancel", color = BitOSColors.primary)
                    }
                }
            }
        }
    }

    if (showComposer) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showComposer = false }) {
            ComposerContent(
                identityViewModel = identityViewModel,
                publisherState = publishState,
                onPublish = { text, pow ->
                    if (pow != null) {
                        notePublisher.publishPowWith(
                            text,
                            pow.nonce,
                            pow.targetDifficulty,
                            pow.createdAtSeconds,
                            { identityViewModel.createSigner() },
                            DefaultRelays.writeUrls,
                        )
                    } else {
                        notePublisher.publishWith(text, { identityViewModel.createSigner() }, DefaultRelays.writeUrls)
                    }
                },
                onDismiss = {
                    notePublisher.dismiss()
                    showComposer = false
                },
            )
        }
    }

    // APP-019 quick entry: gallery import → hash-verified upload → kind-22.
    if (showImportMedia) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showImportMedia = false }) {
            ImportMediaContent(
                state = mediaState,
                onPublish = { mediaPublishViewModel.publish(it) },
                onPick = { uri, _ -> mediaPublishViewModel.mediaPicked(uri) },
                onCancel = {
                    mediaPublishViewModel.cancel()
                    showImportMedia = false
                },
            )
        }
    }
}

@Composable
private fun FeedPage(
    note: FeedNote,
    state: FeedUiState,
    actions: LocalActions,
    pool: VideoPlayerPool,
    player: androidx.media3.exoplayer.ExoPlayer?,
    onLike: (space.bitos.core.feed.FeedNote) -> Unit,
    onBookmark: (String) -> Unit,
    onComment: (space.bitos.core.feed.FeedNote) -> Unit,
    onRepost: (space.bitos.core.feed.FeedNote) -> Unit,
    onFollow: (String) -> Unit,
    onZap: (space.bitos.core.feed.FeedNote) -> Unit,
    onAuthor: (String) -> Unit,
    isMuted: Boolean,
    onMuteToggle: () -> Unit,
    onReport: (String) -> Unit,
) {
    if (note.video != null) {
        VideoNotePage(note, state, actions, pool, player, onLike, onBookmark, onComment, onRepost, onFollow, onZap, onAuthor, isMuted, onMuteToggle, onReport)
    } else {
        TextNotePage(note, state, actions, onLike, onBookmark, onComment, onRepost, onFollow, onZap, onAuthor)
    }
}

/** APP-018: persisted autoplay policy → can the visible video start? */
private fun autoplayAllowed(
    context: android.content.Context,
    policy: space.bitos.core.settings.MediaAutoPlaySetting,
): Boolean = when (policy) {
    space.bitos.core.settings.MediaAutoPlaySetting.ALWAYS -> true
    space.bitos.core.settings.MediaAutoPlaySetting.NEVER -> false
    space.bitos.core.settings.MediaAutoPlaySetting.WIFI -> runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)
}

// ---------------------------------------------------------------------
// Video page
// ---------------------------------------------------------------------

@Composable
private fun VideoNotePage(
    note: FeedNote,
    state: FeedUiState,
    actions: LocalActions,
    pool: VideoPlayerPool,
    player: androidx.media3.exoplayer.ExoPlayer?,
    onLike: (space.bitos.core.feed.FeedNote) -> Unit,
    onBookmark: (String) -> Unit,
    onComment: (space.bitos.core.feed.FeedNote) -> Unit,
    onRepost: (space.bitos.core.feed.FeedNote) -> Unit,
    onFollow: (String) -> Unit,
    onZap: (space.bitos.core.feed.FeedNote) -> Unit,
    onAuthor: (String) -> Unit,
    isMuted: Boolean,
    onMuteToggle: () -> Unit,
    onReport: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PosterImage(
            url = note.video!!.posterUrl,
            contentScale = ContentScale.Fit,
            backgroundColor = Color.Black,
            modifier = Modifier.fillMaxSize(),
        )
        // Preserve the creator's frame. The black page background becomes
        // letterbox/pillarbox space instead of cropping the original video.
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { view -> view.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        // Tap layer: pause/play the settled slot only.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(onClickLabel = "Pause or resume playback") { pool.togglePlay(note.id) },
        )
        CaptionOverlay(note, state, onFollow, onAuthor, Modifier.align(Alignment.BottomStart).fillMaxWidth())
        VideoActionRail(
            note = note,
            isLiked = note.id in actions.liked,
            isBookmarked = note.id in state.bookmarkedIds || note.id in actions.bookmarked,
            onLike = onLike,
            onBookmark = onBookmark,
            onComment = onComment,
            onRepost = onRepost,
            onFollow = onFollow,
            onZap = onZap,
            onAuthor = onAuthor,
            isMuted = isMuted,
            onMuteToggle = onMuteToggle,
            onReport = onReport,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun CaptionOverlay(note: FeedNote, state: FeedUiState, onFollow: (String) -> Unit, onAuthor: (String) -> Unit, modifier: Modifier) {
    val profile = state.profiles[note.pubkey]
    Column(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to Color.Transparent,
                    1f to Color(0xCC000000),
                ),
            )
            .padding(horizontal = BitOSSpacing.base, vertical = BitOSSpacing.lg),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClickLabel = "View author profile") { onAuthor(note.pubkey) },
        ) {
            PubkeyAvatar(pubkey = note.pubkey, size = 36, pictureUrl = profile?.picture, label = profile?.bestDisplayName, hasLightning = !profile?.lud16.isNullOrBlank())
            Spacer(Modifier.width(BitOSSpacing.sm))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile?.bestDisplayName ?: shortPubkey(note.pubkey), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!profile?.nip05.isNullOrBlank()) Icon(Icons.Rounded.CheckCircle, contentDescription = "NIP-05 identity claim", tint = BitOSColors.primary, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                }
                Text(
                    formatTimeAgo(note.createdAt, rememberRelativeTimeNow(note.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xB3F8F8FF),
                )
            }
            Spacer(Modifier.width(BitOSSpacing.sm))
            FollowChip(
                isFollowing = note.pubkey in state.following,
                onToggle = { onFollow(note.pubkey) },
            )
        }

        Spacer(Modifier.height(BitOSSpacing.sm))
        note.repostedBy?.let { _ ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SolarFeedIconImage(SolarFeedIcon.Repost, contentDescription = null, tint = BitOSColors.repost, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    "Reposted",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.repost,
                )
            }
            Spacer(Modifier.height(2.dp))
        }
        Text(
            note.content,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (note.hashtags.isNotEmpty()) {
            Spacer(Modifier.height(BitOSSpacing.xs))
            Text(
                note.hashtags.take(4).joinToString(" ") { "#$it" },
                style = MaterialTheme.typography.labelMedium,
                color = BitOSColors.accent,
            )
        }
    }
}

@Composable
private fun VideoActionRail(
    note: FeedNote,
    isLiked: Boolean,
    isBookmarked: Boolean,
    onLike: (space.bitos.core.feed.FeedNote) -> Unit,
    onBookmark: (String) -> Unit,
    onComment: (space.bitos.core.feed.FeedNote) -> Unit,
    onRepost: (space.bitos.core.feed.FeedNote) -> Unit,
    onFollow: (String) -> Unit,
    onZap: (space.bitos.core.feed.FeedNote) -> Unit,
    onAuthor: (String) -> Unit,
    isMuted: Boolean,
    onMuteToggle: () -> Unit,
    onReport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(end = BitOSSpacing.base),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // User decision 2026-08-29: [like · comment · repost · zap · bookmark].
        RailButton(
            icon = if (isLiked) SolarFeedIcon.HeartFilled else SolarFeedIcon.Heart,
            label = if (isLiked) "Unlike" else "Like",
            tint = if (isLiked) BitOSColors.like else Color.White,
        ) { onLike(note) }
        RailButton(
            icon = SolarFeedIcon.Comment,
            label = "Replies",
            tint = Color.White,
        ) { onComment(note) }
        RailButton(
            icon = SolarFeedIcon.Repost,
            label = "Repost",
            tint = Color.White,
        ) { onRepost(note) }
        RailButton(
            icon = SolarFeedIcon.Zap,
            label = "Zap",
            tint = BitOSColors.zap,
        ) { onZap(note) }
        RailButton(
            icon = if (isBookmarked) SolarFeedIcon.BookmarkFilled else SolarFeedIcon.Bookmark,
            label = if (isBookmarked) "Remove bookmark" else "Bookmark",
            tint = if (isBookmarked) BitOSColors.bookmark else Color.White,
        ) { onBookmark(note.id) }
        MoreMenuButton(
            note = note,
            isSaved = isBookmarked,
            isMuted = isMuted,
            onSaveToggle = { onBookmark(note.id) },
            onMuteToggle = onMuteToggle,
            onReport = onReport,
        )
    }
}

/**
 * ⋯ overflow → bottom sheet (legacy parity: web item set in the Flutter
 * sheet chrome). Icon-only trigger — no label, no dead rail Share.
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun MoreMenuButton(
    note: FeedNote,
    isSaved: Boolean,
    isMuted: Boolean,
    onSaveToggle: () -> Unit,
    onMuteToggle: () -> Unit,
    onReport: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val npub = remember(note.pubkey) {
        space.bitos.core.identity.NostrKeyCodec.npub(note.pubkey) ?: note.pubkey
    }
    var showSheet by remember { mutableStateOf(false) }
    IconButton(
        onClick = { showSheet = true },
        modifier = Modifier.size(36.dp).semantics { contentDescription = "More options" },
    ) {
        SolarFeedIconImage(SolarFeedIcon.More, contentDescription = null, tint = BitOSColors.textSecondary, modifier = Modifier.size(20.dp))
    }
    if (showSheet) {
        space.bitos.app.ui.components.AppBottomSheetMenu(
            onDismissRequest = { showSheet = false },
            title = "Post actions",
            entries = listOf(
                AppMenuEntry.Item(AppMenuItem("share", "Share", icon = AppIcons.Share)),
                AppMenuEntry.Item(
                    AppMenuItem("save", if (isSaved) "Unsave note" else "Save note", icon = AppIcons.Bookmark),
                ),
                AppMenuEntry.Item(AppMenuItem("copy-id", "Copy note ID", icon = AppIcons.Copy)),
                AppMenuEntry.Item(AppMenuItem("copy-text", "Copy note text", icon = AppIcons.Pen)),
                AppMenuEntry.Item(AppMenuItem("copy-npub", "Copy author npub", icon = AppIcons.User)),
                AppMenuEntry.Divider,
                AppMenuEntry.Item(
                    AppMenuItem("mute", if (isMuted) "Unmute author" else "Mute author", icon = AppIcons.Mute),
                ),
                AppMenuEntry.Divider,
                AppMenuEntry.Item(AppMenuItem("report-spam", "Report as spam", icon = AppIcons.ReportSpam, destructive = true)),
                AppMenuEntry.Item(AppMenuItem("report-illicit", "Report as illicit", icon = AppIcons.ReportIllicit, destructive = true)),
                AppMenuEntry.Item(AppMenuItem("report-harassment", "Report as harassment", icon = AppIcons.ReportHarassment, destructive = true)),
            ),
            onSelect = { id ->
                when (id) {
                    "share" -> {
                        val send = Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, space.bitos.core.feed.NoteShare.text(note.content, npub))
                        }
                        context.startActivity(android.content.Intent.createChooser(send, null))
                    }
                    "save" -> onSaveToggle()
                    "copy-id" -> clipboard.setText(androidx.compose.ui.text.AnnotatedString(note.id))
                    "copy-text" -> clipboard.setText(androidx.compose.ui.text.AnnotatedString(note.content))
                    "copy-npub" -> clipboard.setText(androidx.compose.ui.text.AnnotatedString(npub))
                    "mute" -> onMuteToggle()
                    "report-spam" -> onReport("spam")
                    "report-illicit" -> onReport("illicit")
                    "report-harassment" -> onReport("harassment")
                }
            },
        )
    }
}

/** Compact follow/unfollow chip for note captions. */
@Composable
private fun FollowChip(isFollowing: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isFollowing) Color.Transparent else BitOSColors.primaryContainer,
        contentColor = if (isFollowing) Color(0xB3F8F8FF) else BitOSColors.primary,
        modifier = Modifier.clickable(onClickLabel = if (isFollowing) "Unfollow author" else "Follow author") { onToggle() },
    ) {
        Text(
            if (isFollowing) "Following" else "Follow",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = BitOSSpacing.md, vertical = 4.dp),
        )
    }
}

@Composable
private fun RailButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0x33000000),
            modifier = Modifier.clickable(onClickLabel = label) { onClick() },
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.padding(10.dp).size(24.dp),
            )
        }
    }
}

@Composable
private fun RailButton(icon: SolarFeedIcon, label: String, tint: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            SolarFeedIconImage(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

// ---------------------------------------------------------------------
// Text page
// ---------------------------------------------------------------------

@Composable
private fun TextNotePage(
    note: FeedNote,
    state: FeedUiState,
    actions: LocalActions,
    onLike: (space.bitos.core.feed.FeedNote) -> Unit,
    onBookmark: (String) -> Unit,
    onComment: (space.bitos.core.feed.FeedNote) -> Unit,
    onRepost: (space.bitos.core.feed.FeedNote) -> Unit,
    onFollow: (String) -> Unit,
    onZap: (space.bitos.core.feed.FeedNote) -> Unit,
    onAuthor: (String) -> Unit,
) {
    val profile = state.profiles[note.pubkey]
    Box(Modifier.fillMaxSize().background(BitOSColors.background).padding(BitOSSpacing.screen)) {
        Column(Modifier.align(Alignment.TopStart).padding(top = BitOSSpacing.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PubkeyAvatar(pubkey = note.pubkey, pictureUrl = profile?.picture, label = profile?.bestDisplayName)
                Spacer(Modifier.width(BitOSSpacing.avatarGap))
                Column {
                    Text(
                        profile?.bestDisplayName ?: shortPubkey(note.pubkey),
                        style = MaterialTheme.typography.titleMedium,
                        color = BitOSColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatTimeAgo(note.createdAt, rememberRelativeTimeNow(note.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textTertiary,
                    )
                }
            }
            Spacer(Modifier.height(BitOSSpacing.base))
            note.repostedBy?.let { _ ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SolarFeedIconImage(SolarFeedIcon.Repost, contentDescription = null, tint = BitOSColors.repost, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reposted", style = MaterialTheme.typography.labelMedium, color = BitOSColors.repost)
                }
            }
            Spacer(Modifier.height(BitOSSpacing.sm))
            Text(
                note.content,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                color = BitOSColors.textPrimary,
            )
            if (note.hashtags.isNotEmpty()) {
                Spacer(Modifier.height(BitOSSpacing.sm))
                Text(
                    note.hashtags.take(6).joinToString(" ") { "#$it" },
                    style = MaterialTheme.typography.labelMedium,
                    color = BitOSColors.accent,
                )
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = BitOSSpacing.xl),
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.lg),
        ) {
            RailButton(
                icon = SolarFeedIcon.Comment,
                label = "Replies",
                tint = BitOSColors.textSecondary,
            ) { onComment(note) }
            RailButton(
                icon = SolarFeedIcon.Repost,
                label = "Repost",
                tint = BitOSColors.textSecondary,
            ) { onRepost(note) }
            RailButton(
                icon = SolarFeedIcon.Zap,
                label = "Zap",
                tint = BitOSColors.zap,
            ) { onZap(note) }
            RailButton(
                icon = if (note.id in actions.liked) SolarFeedIcon.HeartFilled else SolarFeedIcon.Heart,
                label = if (note.id in actions.liked) "Unlike" else "Like",
                tint = if (note.id in actions.liked) BitOSColors.like else BitOSColors.textSecondary,
            ) { onLike(note) }
            RailButton(
                icon = if (note.id in state.bookmarkedIds || note.id in actions.bookmarked) SolarFeedIcon.BookmarkFilled else SolarFeedIcon.Bookmark,
                label = if (note.id in state.bookmarkedIds || note.id in actions.bookmarked) "Remove bookmark" else "Bookmark",
                tint = if (note.id in state.bookmarkedIds || note.id in actions.bookmarked) BitOSColors.bookmark else BitOSColors.textSecondary,
            ) { onBookmark(note.id) }
        }
    }
}

// ---------------------------------------------------------------------
// Poster loading (minimal until the media pipeline lands)
// ---------------------------------------------------------------------

@Composable
fun PosterImage(
    url: String?,
    contentScale: ContentScale = ContentScale.Crop,
    backgroundColor: Color = BitOSColors.surface,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(if (backgroundColor == BitOSColors.surface) {
        Brush.linearGradient(listOf(BitOSColors.surface, BitOSColors.surfaceElevated))
    } else {
        Brush.linearGradient(listOf(backgroundColor, backgroundColor))
    })) {
        if (url != null) {
            coil.compose.AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---------------------------------------------------------------------
// Header / states (unchanged behavior)
// ---------------------------------------------------------------------

/** Home tab: scrolling compact NoteCard list (legacy UX parity). */
@Composable
private fun NotesList(
    notes: List<FeedNote>,
    state: FeedUiState,
    compact: Boolean = false,
    sensitiveShowByDefault: Boolean = false,
    mediaPreview: Boolean = true,
    actions: LocalActions,
    viewModel: HomeViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onComment: (FeedNote) -> Unit,
    onZap: (FeedNote) -> Unit,
    onAuthor: (String) -> Unit,
    onLike: (FeedNote) -> Unit,
    onBookmark: (String) -> Unit,
    onRepost: (FeedNote) -> Unit,
    onOpenExternalLink: (String) -> Unit = {},
    onOpenNoteRef: (String) -> Unit = {},
) {
    androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(notes, key = { _, note -> note.id }) { index, note ->
            NoteCardRow(
                note = note,
                profile = state.profiles[note.pubkey],
                bookmarked = note.id in state.bookmarkedIds || note.id in actions.bookmarked,
                liked = note.id in actions.liked,
                resolveMentionName = { hex -> state.profiles[hex]?.bestDisplayName },
                onLike = { onLike(note) },
                onBookmark = { onBookmark(note.id) },
                onComment = { onComment(note) },
                onRepost = { onRepost(note) },
                onZap = { onZap(note) },
                onAuthor = { onAuthor(note.pubkey) },
                isMuted = viewModel.isMuted(note.pubkey),
                onMuteToggle = { viewModel.toggleMute(note.pubkey) },
                onReport = { reason -> viewModel.report(note, reason) },
                onOpenExternalLink = onOpenExternalLink,
                onOpenNoteRef = onOpenNoteRef,
                sensitiveShowByDefault = sensitiveShowByDefault,
                mediaPreview = mediaPreview,
                compact = compact,
            )
            // Legacy UI parity: hairline divider between cards (not after
            // the last one).
            if (index < notes.lastIndex) {
                androidx.compose.material3.HorizontalDivider(
                    thickness = 0.5.dp,
                    color = BitOSColors.divider,
                    modifier = Modifier.padding(start = 68.dp),
                )
            }
        }
        // APP-004 pagination: footer spinner while an older page loads.
        if (state.isLoadingOlder) {
            item(key = "older-footer") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BitOSColors.primary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun NoteCardRow(
    note: FeedNote,
    profile: space.bitos.core.model.ProfileMetadata?,
    bookmarked: Boolean,
    liked: Boolean,
    resolveMentionName: (String) -> String?,
    onLike: () -> Unit,
    onBookmark: () -> Unit,
    onComment: () -> Unit,
    onRepost: () -> Unit,
    onZap: () -> Unit,
    onAuthor: () -> Unit,
    isMuted: Boolean,
    onMuteToggle: () -> Unit,
    onReport: (String) -> Unit,
    /** External-link tap → confirm sheet (owned by the screen). */
    onOpenExternalLink: (String) -> Unit = {},
    /** note1/nevent1/naddr1 tap → in-place thread open. */
    onOpenNoteRef: (String) -> Unit = {},
    sensitiveShowByDefault: Boolean = false,
    mediaPreview: Boolean = true,
    compact: Boolean = false,
) {
    var revealed by androidx.compose.runtime.remember(note.id) { androidx.compose.runtime.mutableStateOf(false) }
    // APP-005 Show more/less: font-scale-safe line clamp.
    var expanded by androidx.compose.runtime.remember(note.id) { androidx.compose.runtime.mutableStateOf(false) }
    var canExpand by androidx.compose.runtime.remember(note.id) { androidx.compose.runtime.mutableStateOf(false) }
    var lightboxUrl by androidx.compose.runtime.remember(note.id) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    lightboxUrl?.let { url ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { lightboxUrl = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            MediaLightbox(url = url, onDismiss = { lightboxUrl = null })
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BitOSSpacing.screen, vertical = if (compact) 4.dp else BitOSSpacing.md),
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else BitOSSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(onClickLabel = "Open author profile") { onAuthor() },
                contentAlignment = Alignment.Center,
            ) {
                PubkeyAvatar(
                    pubkey = note.pubkey,
                    size = if (compact) 28 else 36,
                    pictureUrl = profile?.picture,
                    label = profile?.bestDisplayName,
                    hasLightning = !profile?.lud16.isNullOrBlank(),
                )
            }
            Spacer(Modifier.width(BitOSSpacing.sm))
            Column(
                Modifier
                    .clickable(onClickLabel = "Open author profile") { onAuthor() }
                    .padding(vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile?.bestDisplayName ?: shortPubkey(note.pubkey), style = MaterialTheme.typography.titleSmall, color = BitOSColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!profile?.nip05.isNullOrBlank()) Icon(Icons.Rounded.CheckCircle, contentDescription = "NIP-05 identity claim", tint = BitOSColors.primary, modifier = Modifier.padding(start = 4.dp).size(13.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (note.repostedBy != null) {
                        SolarFeedIconImage(SolarFeedIcon.Repost, contentDescription = null, tint = BitOSColors.repost, modifier = Modifier.size(10.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        formatTimeAgo(note.createdAt, rememberRelativeTimeNow(note.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textTertiary,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            MoreMenuButton(
                note = note,
                isSaved = bookmarked,
                isMuted = isMuted,
                onSaveToggle = onBookmark,
                onMuteToggle = onMuteToggle,
                onReport = onReport,
            )
        }
        if (note.contentWarning && !revealed && !sensitiveShowByDefault) {
            SensitiveCover(onReveal = { revealed = true })
        } else {
            RichText(
                tokens = androidx.compose.runtime.remember(note.content) { space.bitos.core.nostr.Nip27.tokenize(note.content) },
                hiddenMediaUrls = remember(note.mediaUrls) { note.mediaUrls.toSet() },
                resolveMentionName = resolveMentionName,
                onOpenNoteRef = onOpenNoteRef,
                onOpenExternalLink = onOpenExternalLink,
                maxLines = if (expanded) Int.MAX_VALUE else NOTE_COLLAPSE_LINES,
                onOverflow = { canExpand = it },
                onOpenProfile = { onAuthor() },
            )
            if (canExpand || expanded) {
                androidx.compose.material3.TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(
                        if (expanded) "Show less" else "Show more",
                        style = MaterialTheme.typography.labelMedium,
                        color = BitOSColors.primary,
                    )
                }
            }
            note.poll?.let { poll -> PollOptions(poll) }
            if (mediaPreview) {
                MediaRow(urls = note.mediaUrls, onOpen = { lightboxUrl = it })
            } else {
                Text(
                    if (note.mediaUrls.size == 1) "1 attachment (previews off)" else "${note.mediaUrls.size} attachments (previews off)",
                    fontSize = 12.sp, color = BitOSColors.textTertiary,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.base)) {
            // User decision 2026-08-29: order [like · comment · repost ·
            // zap · bookmark]; APP-005 §2.4 scale-bounce + haptic on like.
            AnimatedLikeIcon(
                liked = liked,
                tint = if (liked) BitOSColors.like else BitOSColors.textSecondary,
                iconSize = 18.dp,
                onClick = onLike,
            )
            CardAction(SolarFeedIcon.Comment, "Replies", BitOSColors.reply, onComment)
            CardAction(SolarFeedIcon.Repost, "Repost", BitOSColors.repost, onRepost)
            CardAction(SolarFeedIcon.Zap, "Zap", BitOSColors.zap, onZap)
            CardAction(if (bookmarked) SolarFeedIcon.BookmarkFilled else SolarFeedIcon.Bookmark, if (bookmarked) "Remove bookmark" else "Bookmark", if (bookmarked) BitOSColors.bookmark else BitOSColors.textSecondary, onBookmark)
        }
    }
}

/** APP-005: bodies collapse beyond 8 lines (line-based, so font scaling
 * cannot break the clamp); full-screen card pages never clamp. */
private const val NOTE_COLLAPSE_LINES = 8

/** APP-008 poll display (V1): question + option rows; voting/bars land
 * with the response-format decision (legacy is compose-only). */
@Composable
private fun PollOptions(poll: space.bitos.core.model.Poll) {
    Column(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        poll.options.forEach { option ->
            Surface(shape = RoundedCornerShape(8.dp), color = BitOSColors.surface) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .border(1.5.dp, BitOSColors.textTertiary, androidx.compose.foundation.shape.CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        option.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BitOSColors.textPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(
            "Poll · ${poll.totalOptions} options",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textTertiary,
        )
    }
}

@Composable
private fun CardAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun CardAction(icon: SolarFeedIcon, label: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        SolarFeedIconImage(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * Home header (legacy Flutter parity): wordmark + filter/search actions,
 * For-you/Following underline tabs below. Compose stays on the FAB; the
 * relay-count pill was removed (legacy has no live count in the header).
 */
@Composable
private fun FeedHeader(
    state: FeedUiState,
    onTimeline: (FeedTimeline) -> Unit,
    onFilter: (FeedFilter) -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenHub: () -> Unit,
    onOpenCreate: () -> Unit = {},
) {
    var filterExpanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = BitOSSpacing.screen, end = BitOSSpacing.xs, top = BitOSSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Official wordmark, ~22dp like the web/Flutter app bar.
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(space.bitos.app.R.drawable.bitos_branding),
                contentDescription = "BitOS",
                modifier = Modifier.height(22.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
            Spacer(Modifier.weight(1f))
            // APP-004: content filter (spec §3.4: All/Original/Replies/Media/
            // Liked/Mine, single-select with checks).
            Box {
                IconButton(onClick = { filterExpanded = true }) {
                    Icon(AppIcons.Filter, contentDescription = "Filter notes", tint = if (state.filter != FeedFilter.ALL) BitOSColors.primary else BitOSColors.textSecondary)
                }
                AppMenuDropdown(
                    expanded = filterExpanded,
                    onDismissRequest = { filterExpanded = false },
                    entries = FeedFilter.entries.map { option ->
                        AppMenuEntry.Item(
                            AppMenuItem(
                                id = option.name,
                                label = option.label,
                                checked = option == state.filter,
                            ),
                        )
                    },
                    onSelect = { id ->
                        FeedFilter.entries.firstOrNull { it.name == id }?.let(onFilter)
                    },
                )
            }
            IconButton(onClick = onOpenDiscover) {
                Icon(AppIcons.Search, contentDescription = "Search", tint = BitOSColors.textSecondary)
            }
            // iOS app-bar parity: the camera icon opens the Create hub
            // (record Bitz / import media) instead of nothing.
            IconButton(onClick = onOpenCreate) {
                Icon(AppIcons.Camera, contentDescription = "Create video", tint = BitOSColors.textSecondary)
            }
            // Solar widget-linear opens the account hub; media import stays
            // in Create so the header remains focused on feed navigation.
            IconButton(onClick = onOpenHub) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(space.bitos.app.R.drawable.solar_widget_linear),
                    contentDescription = "Open hub",
                    tint = BitOSColors.textSecondary,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, BitOSColors.divider)
                .padding(horizontal = BitOSSpacing.screen),
            verticalAlignment = Alignment.Bottom,
        ) {
            TimelineTab("For You", AppIcons.Sparkles, state.timeline == FeedTimeline.FOR_YOU, 0) { onTimeline(FeedTimeline.FOR_YOU) }
            Spacer(Modifier.width(BitOSSpacing.lg))
            TimelineTab("Following", AppIcons.People, state.timeline == FeedTimeline.FOLLOWING, state.followingCount) { onTimeline(FeedTimeline.FOLLOWING) }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** APP-004: reveal pill with author avatars and a count centered in its badge. */
@Composable
private fun NewNotesPill(
    pending: List<FeedNote>,
    authorName: (String) -> String?,
    hasLightning: (String) -> Boolean,
    onReveal: () -> Unit,
) {
    val authors = pending.asSequence().map { it.pubkey }.distinct().take(4).toList()
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = BitOSColors.primary,
        shadowElevation = 6.dp,
        modifier = Modifier.clickable(onClickLabel = "Show ${pending.size} new notes") { onReveal() },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (authors.isNotEmpty()) {
                // X-style overlapping avatar stack; later entries draw on top.
                Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
                    authors.forEach { pubkey ->
                        Box(
                            Modifier
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(BitOSColors.primary)
                                .padding(1.5.dp),
                        ) {
                            PubkeyAvatar(pubkey = pubkey, size = 18, label = authorName(pubkey), hasLightning = hasLightning(pubkey))
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color(0xFF0A0A0F).copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (pending.size > 99) "99+" else pending.size.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.W800,
                    color = Color(0xFF0A0A0F),
                )
            }
            Icon(
                AppIcons.ArrowUp,
                contentDescription = null,
                tint = Color(0xFF0A0A0F),
                modifier = Modifier.size(14.dp),
            )
            Text(
                "New ${if (pending.size == 1) "note" else "notes"}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.W700,
                color = Color(0xFF0A0A0F),
            )
        }
    }
}

/** APP-004: guest banner — browsing without an identity (spec §3.4). */
@Composable
private fun GuestBanner(onGetStarted: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BitOSColors.surfaceElevated,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BitOSSpacing.screen)
            .padding(bottom = BitOSSpacing.sm),
    ) {
        Row(
            Modifier.padding(horizontal = BitOSSpacing.base, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            Text(
                "Browsing BitOS as a guest",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = onGetStarted) {
                Text("Get started", color = BitOSColors.primary, fontWeight = FontWeight.W600)
            }
        }
    }
}

/** Underline tab (legacy parity: icon + label, primary underline, hairline row border) + live note count (spec §3.4). */
@Composable
private fun TimelineTab(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, count: Int, onClick: () -> Unit) {
    val tint = if (selected) BitOSColors.primary else BitOSColors.textSecondary
    Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClickLabel = "Show $label timeline") { onClick() }
            .border(2.5.dp, if (selected) BitOSColors.primary else Color.Transparent)
            .padding(horizontal = BitOSSpacing.md, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.W700 else FontWeight.W600,
                color = tint,
            )
            if (count > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    if (count > 999) "999+" else "$count",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.W600,
                    color = tint.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun FeedLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = BitOSColors.primary, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun FeedEmpty(
    relayHealth: RelayHealth,
    filterActive: Boolean,
    onRetry: () -> Unit,
    onShowAll: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(BitOSSpacing.xxl), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
            when {
                // APP-004: relay-error state — relays configured, none
                // connected; the pool keeps reconnecting, user can force it.
                !relayHealth.isLive && relayHealth.total > 0 -> {
                    Text("Can't reach relays", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "No relay connection right now. We keep retrying automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BitOSColors.textSecondary,
                    )
                }
                // APP-004: filter-mismatch empty — notes exist under All.
                filterActive -> {
                    Text("No notes match this filter", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.material3.TextButton(onClick = onShowAll) {
                        Text("Show all notes", color = BitOSColors.primary, fontWeight = FontWeight.W600)
                    }
                }
                else -> {
                    Text("No notes yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (relayHealth.isLive) {
                            "Connected relays have not returned verified notes yet. Retrying every few seconds."
                        } else {
                            "Relays are connecting. The feed fills once a connection succeeds."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = BitOSColors.textSecondary,
                    )
                }
            }
            // Manual retry resets the APP-004 auto-retry backoff (2 s).
            androidx.compose.material3.OutlinedButton(onClick = onRetry) {
                Icon(AppIcons.Refresh, contentDescription = null, tint = BitOSColors.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Retry now", color = BitOSColors.primary)
            }
        }
    }
}

@Composable
private fun FollowingPlaceholder() {
    Box(Modifier.fillMaxSize().padding(BitOSSpacing.xxl), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
            Text("Following needs an identity", style = MaterialTheme.typography.titleMedium)
            Text(
                "Create, import or connect a Nostr identity to build a following timeline.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
            )
        }
    }
}
