package space.bitos.app.ui.feed

import android.graphics.Bitmap
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.app.ui.theme.SolarFeedIcon
import space.bitos.app.ui.theme.SolarFeedIconImage
import space.bitos.core.feed.FeedNote
import java.net.URL
import android.graphics.BitmapFactory

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
    videoOnly: Boolean = false,
    sensitiveShowByDefault: Boolean = false,
    onOpenProfile: () -> Unit = {},
    onOpenDiscover: () -> Unit = {},
    onOpenHub: () -> Unit = {},
    /** APP-003/APP-004: bumped when the user re-taps the ACTIVE shell tab
     * (Home/Bitz) — scrolls to top, or refreshes when already at top. */
    retapTick: Int = 0,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions by viewModel.localActions.collectAsStateWithLifecycle()
    val publishState by notePublisher.state.collectAsStateWithLifecycle()
    var showComposer by rememberSaveable { mutableStateOf(false) }
    var showImportMedia by rememberSaveable { mutableStateOf(false) }
    val mediaState by mediaPublishViewModel.state.collectAsStateWithLifecycle()
    var showCommentsFor by androidx.compose.runtime.remember { mutableStateOf<FeedNote?>(null) }
    var zapTarget by androidx.compose.runtime.remember { mutableStateOf<FeedNote?>(null) }
    var authorTarget by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val authorState by authorRepository.state.collectAsStateWithLifecycle()
    val zapState by viewModel.zapState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pool = androidx.compose.runtime.remember { VideoPlayerPool(context) }
    // Shell split (user decision): Home tab = text notes; Bitz tab = reels.
    val feedNotes = remember(state.notes, videoOnly) {
        if (videoOnly) state.notes.filter { it.video != null } else state.notes.filter { it.video == null }
    }
    val pagerState = rememberPagerState(pageCount = { feedNotes.size })
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val revealPendingAtTop = {
        scope.launch {
            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                listState.animateScrollToItem(0)
            }
            viewModel.revealPendingNotes()
        }
        Unit
    }

    DisposableEffect(Unit) {
        onDispose { pool.releaseAll() }
    }
    LaunchedEffect(pagerState.settledPage, state.notes) {
        pool.update(pagerState.settledPage, state)
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
    LaunchedEffect(videoOnly, pagerState.settledPage, feedNotes.size) {
        if (videoOnly && feedNotes.isNotEmpty() && pagerState.settledPage >= feedNotes.size - 3) {
            viewModel.loadOlder()
        }
    }
    val listNearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(listNearEnd) {
        if (!videoOnly && listNearEnd) viewModel.loadOlder()
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
                                onLike = viewModel::toggleLike, onBookmark = viewModel::toggleBookmark,
                                onComment = { showCommentsFor = it }, onRepost = viewModel::repost,
                                onFollow = viewModel::toggleFollow,
                                onZap = { viewModel.loadZaps(it.id); zapTarget = it },
                                onAuthor = { authorTarget = it }, isMuted = viewModel.isMuted(feedNotes[page].pubkey),
                                onMuteToggle = { viewModel.toggleMute(feedNotes[page].pubkey) },
                                onReport = { reason -> viewModel.report(feedNotes[page], reason) },
                            )
                        } else NotesList(
                            notes = feedNotes, state = state, actions = actions, viewModel = viewModel,
                            listState = listState, onComment = { showCommentsFor = it }, onZap = { zapTarget = it },
                            onAuthor = { authorTarget = it }, onLike = viewModel::toggleLike,
                            onBookmark = viewModel::toggleBookmark, onRepost = viewModel::repost,
                            sensitiveShowByDefault = sensitiveShowByDefault,
                        )
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = !videoOnly && state.pendingNotes.isNotEmpty(),
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
            onClick = { showComposer = true },
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
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { authorRepository.close(); authorTarget = null }) {
            space.bitos.app.ui.profile.AuthorProfileContent(
                state = authorState,
                feedState = state,
                onOpen = authorRepository::open,
                onFollow = viewModel::toggleFollow,
                onClose = { authorRepository.close(); authorTarget = null },
            )
        }
    }

    zapTarget?.let { target ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { viewModel.dismissZap(); zapTarget = null }) {
            ZapContent(
                note = target,
                lud16 = state.profiles[target.pubkey]?.lud16,
                state = zapState,
                onAmountSelected = viewModel::selectZapAmount,
                onZap = { viewModel.zap(target) },
                onClose = { viewModel.dismissZap(); zapTarget = null },
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
                onLoadComments = viewModel::loadComments,
                onReply = { text, note -> viewModel.reply(text, note) },
                onClose = { showCommentsFor = null },
            )
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
        VideoNotePage(note, state, actions, pool, onLike, onBookmark, onComment, onRepost, onFollow, onZap, onAuthor, isMuted, onMuteToggle, onReport)
    } else {
        TextNotePage(note, state, actions, onLike, onBookmark, onComment, onRepost, onFollow, onZap, onAuthor)
    }
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
        PosterImage(url = note.video!!.posterUrl, modifier = Modifier.fillMaxSize())
        // Surface: aspect-fill video, no built-in controls.
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { view -> view.player = pool.playerFor(note.id) },
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
            PubkeyAvatar(pubkey = note.pubkey, size = 36, label = profile?.bestDisplayName, hasLightning = !profile?.lud16.isNullOrBlank())
            Spacer(Modifier.width(BitOSSpacing.sm))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile?.bestDisplayName ?: shortPubkey(note.pubkey), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!profile?.nip05.isNullOrBlank()) Icon(Icons.Rounded.CheckCircle, contentDescription = "NIP-05 identity claim", tint = BitOSColors.primary, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                }
                Text(
                    formatTimeAgo(note.createdAt, System.currentTimeMillis() / 1000),
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
            icon = if (isLiked) SolarFeedIcon.HeartFilled else SolarFeedIcon.Heart,
            label = if (isLiked) "Unlike" else "Like",
            tint = if (isLiked) BitOSColors.like else Color.White,
        ) { onLike(note) }
        RailButton(
            icon = if (isBookmarked) SolarFeedIcon.BookmarkFilled else SolarFeedIcon.Bookmark,
            label = if (isBookmarked) "Remove bookmark" else "Bookmark",
            tint = if (isBookmarked) BitOSColors.bookmark else Color.White,
        ) { onBookmark(note.id) }
        RailButton(
            icon = AppIcons.Share,
            label = "Share",
            tint = Color.White,
        ) { }
        MoreMenuButton(
            isMuted = isMuted,
            onMuteToggle = onMuteToggle,
            onReport = onReport,
        )
    }
}

/** APP-022: note ⋯ menu anchored at the trigger (spec §3.5). */
@Composable
private fun MoreMenuButton(
    isMuted: Boolean,
    onMuteToggle: () -> Unit,
    onReport: (String) -> Unit,
) {
    var expanded by androidx.compose.runtime.remember { mutableStateOf(false) }
    Box {
        RailButton(
            icon = SolarFeedIcon.More,
            label = "More options",
            tint = Color.White,
        ) { expanded = true }
        AppMenuDropdown(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            entries = listOf(
                AppMenuEntry.Item(AppMenuItem("mute", if (isMuted) "Unmute author" else "Mute author")),
                AppMenuEntry.Divider,
                AppMenuEntry.Item(AppMenuItem("report-spam", "Report as spam", destructive = true)),
                AppMenuEntry.Item(AppMenuItem("report-illicit", "Report as illicit", destructive = true)),
                AppMenuEntry.Item(AppMenuItem("report-harassment", "Report as harassment", destructive = true)),
            ),
            onSelect = { id ->
                when (id) {
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
                PubkeyAvatar(pubkey = note.pubkey)
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
                        formatTimeAgo(note.createdAt, System.currentTimeMillis() / 1000),
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
fun PosterImage(url: String?, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, url) {
        value = url?.let { loadBitmap(it) }
    }
    Box(modifier.background(Brush.linearGradient(listOf(BitOSColors.surface, BitOSColors.surfaceElevated)))) {
        bitmap?.let { image ->
            androidx.compose.foundation.Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private suspend fun loadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        BitmapFactory.decodeStream(URL(url).openStream())
    }.getOrNull()
}

// ---------------------------------------------------------------------
// Header / states (unchanged behavior)
// ---------------------------------------------------------------------

/** Home tab: scrolling compact NoteCard list (legacy UX parity). */
@Composable
private fun NotesList(
    notes: List<FeedNote>,
    state: FeedUiState,
    sensitiveShowByDefault: Boolean = false,
    actions: LocalActions,
    viewModel: HomeViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onComment: (FeedNote) -> Unit,
    onZap: (FeedNote) -> Unit,
    onAuthor: (String) -> Unit,
    onLike: (FeedNote) -> Unit,
    onBookmark: (String) -> Unit,
    onRepost: (FeedNote) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(notes, key = { it.id }) { note ->
            NoteCardRow(
                note = note,
                state = state,
                actions = actions,
                onLike = { onLike(note) },
                onBookmark = { onBookmark(note.id) },
                onComment = { onComment(note) },
                onRepost = { onRepost(note) },
                onZap = { onZap(note) },
                onAuthor = { onAuthor(note.pubkey) },
                isMuted = viewModel.isMuted(note.pubkey),
                onMuteToggle = { viewModel.toggleMute(note.pubkey) },
                onReport = { reason -> viewModel.report(note, reason) },
                sensitiveShowByDefault = sensitiveShowByDefault,
            )
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
    state: FeedUiState,
    actions: LocalActions,
    onLike: () -> Unit,
    onBookmark: () -> Unit,
    onComment: () -> Unit,
    onRepost: () -> Unit,
    onZap: () -> Unit,
    onAuthor: () -> Unit,
    isMuted: Boolean,
    onMuteToggle: () -> Unit,
    onReport: (String) -> Unit,
    sensitiveShowByDefault: Boolean = false,
) {
    val profile = state.profiles[note.pubkey]
    val bookmarked = note.id in state.bookmarkedIds || note.id in actions.bookmarked
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
            .padding(horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PubkeyAvatar(pubkey = note.pubkey, size = 36, label = profile?.bestDisplayName, hasLightning = !profile?.lud16.isNullOrBlank()).let {
                Box(Modifier.clickable(onClickLabel = "Open author") { onAuthor() }) { it }
            }
            Spacer(Modifier.width(BitOSSpacing.sm))
            Column {
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
                        formatTimeAgo(note.createdAt, System.currentTimeMillis() / 1000),
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textTertiary,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            MoreMenuButton(isMuted = isMuted, onMuteToggle = onMuteToggle, onReport = onReport)
        }
        if (note.contentWarning && !revealed && !sensitiveShowByDefault) {
            SensitiveCover(onReveal = { revealed = true })
        } else {
            RichText(
                tokens = androidx.compose.runtime.remember(note.content) { space.bitos.core.nostr.Nip27.tokenize(note.content) },
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
            MediaRow(urls = note.mediaUrls, onOpen = { lightboxUrl = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.base)) {
            CardAction(SolarFeedIcon.Comment, "Replies", BitOSColors.reply, onComment)
            CardAction(SolarFeedIcon.Repost, "Repost", BitOSColors.repost, onRepost)
            // APP-005 §2.4: scale-bounce + haptic on like.
            AnimatedLikeIcon(
                liked = note.id in actions.liked,
                tint = if (note.id in actions.liked) BitOSColors.like else BitOSColors.textSecondary,
                iconSize = 18.dp,
                onClick = onLike,
            )
            CardAction(SolarFeedIcon.Zap, "Zap", BitOSColors.zap, onZap)
            CardAction(if (bookmarked) SolarFeedIcon.BookmarkFilled else SolarFeedIcon.Bookmark, if (bookmarked) "Remove bookmark" else "Bookmark", if (bookmarked) BitOSColors.bookmark else BitOSColors.textSecondary, onBookmark)
        }
    }
}

/** APP-005: bodies collapse beyond 8 lines (line-based, so font scaling
 * cannot break the clamp); full-screen card pages never clamp. */
private const val NOTE_COLLAPSE_LINES = 8

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
