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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.snapshotFlow
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
import space.bitos.app.ui.components.BrandWordmark
import space.bitos.app.ui.components.FeedNoteCard
import space.bitos.app.ui.components.MediaLightbox
import space.bitos.app.ui.components.MediaRow
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.components.AnimatedLikeIcon
import space.bitos.app.ui.components.RichText
import space.bitos.app.ui.components.SensitiveCover
import space.bitos.app.ui.components.formatTimeAgo
import space.bitos.app.ui.components.rememberRelativeTimeNow
import space.bitos.app.ui.components.shortPubkey
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.designsystem.AppSkeletonRow
import space.bitos.core.feed.FeedFilter
import space.bitos.core.feed.BitzTimelinePolicy
import space.bitos.core.model.ProfileMetadata
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.app.ui.theme.SolarFeedIcon
import space.bitos.app.ui.theme.SolarFeedIconImage
import space.bitos.core.feed.FeedNote

/**
 * APP-009 ref-open states plate (mockup app-10 "Loading / not found"):
 * the shared `ThreadOpen` classification drives which plate shows; the
 * not-found plate carries the nevent TLV relay hints for Retry/Add relay.
 */
private sealed interface RefOpenPlate {
    data object Loading : RefOpenPlate
    data object Invalid : RefOpenPlate
    data class NotFound(val raw: String, val hints: List<String>) : RefOpenPlate
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
    /** APP-006: story viewer "DM" mode sends through the DM repository. */
    dmRepository: space.bitos.app.data.dm.DmRepository? = null,
    /** APP-009 not-found "Add relay" applies nevent TLV hints here. */
    relayManager: space.bitos.app.data.relay.RelayManager? = null,
    /** UX-010: opens the in-app full profile page for a pubkey. */
    onOpenAuthorProfile: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val feedContent by viewModel.feedContentState.collectAsStateWithLifecycle()
    val actions by viewModel.localActions.collectAsStateWithLifecycle()
    val publishState by notePublisher.state.collectAsStateWithLifecycle()
    var showComposer by rememberSaveable { mutableStateOf(false) }
    var showCommentsFor by androidx.compose.runtime.remember { mutableStateOf<FeedNote?>(null) }
    var zapTarget by androidx.compose.runtime.remember { mutableStateOf<FeedNote?>(null) }
    /** External-link confirm sheet (never opens the browser unattended). */
    var externalLink by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    /** In-place note-ref open (note1/nevent1/naddr1/hex → thread sheet). */
    var refOpenTarget by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    /** APP-009 states plate: Loading → Loaded | Invalid | NotFound
     *  (shared `ThreadOpen` classification + `ThreadOpenCopy` strings). */
    var refOpenState by androidx.compose.runtime.remember { mutableStateOf<RefOpenPlate?>(null) }
    var authorTarget by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val authorState by authorRepository.state.collectAsStateWithLifecycle()
    val zapState by viewModel.zapState.collectAsStateWithLifecycle()
    val identityState by identityViewModel.state.collectAsStateWithLifecycle()
    // Local ranking signals (web interaction-profile parity).
    val interactionProfile = viewModel.interaction
    val demotedAuthorIds by (interactionProfile?.demotedAuthors
        ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet())).collectAsStateWithLifecycle()
    val demotedTagSet by (interactionProfile?.demotedTags
        ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet())).collectAsStateWithLifecycle()
    val context = LocalContext.current
    // APP-006: stories bar + viewer.
    val storiesState by (storiesRepository?.state
        ?: kotlinx.coroutines.flow.MutableStateFlow(space.bitos.app.data.stories.StoriesUiState())
    ).collectAsStateWithLifecycle()
    var storyViewerTarget by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<space.bitos.core.model.StoryAuthor?>(null)
    }
    // Story zap target: author + the slide the zap receipt tags.
    var storyZapTarget by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Pair<space.bitos.core.model.StoryAuthor, String>?>(null)
    }
    // APP-006: dedicated story composer (kind-30315, web `StoryComposer` parity).
    var showStoryComposer by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.accountPubkey, state.following) {
        storiesRepository?.setAccount(state.accountPubkey, state.following, context)
    }
    // APP-006: fetch kind-0 metadata for story authors missing from the
    // profile cache (web `profiles.ensure` parity — display names/pictures).
    androidx.compose.runtime.LaunchedEffect(storiesState.authors, storiesState.publicAuthors) {
        val missing = (storiesState.authors + storiesState.publicAuthors)
            .map { it.pubkey }
            .filterNot { state.profiles.containsKey(it) }
            .distinct()
            .take(30)
        if (missing.isNotEmpty()) viewModel.requestStoryAuthorProfiles(missing)
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
            qualityProvider = { currentSettings.value.videoQuality },
        )
    }
    val playerBindings by pool.playerBindings.collectAsStateWithLifecycle()
    // Shell split (user decision): Home tab = text notes; Bitz tab = reels.
    val feedNotes = remember(feedContent.notes, videoOnly) {
        if (videoOnly) feedContent.notes.filter { it.video != null } else feedContent.notes.filter { it.video == null }
    }
    val pagerState = rememberPagerState(pageCount = { feedNotes.size })
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        onDispose { pool.releaseAll() }
    }
    // Re-reconcile the pool only when the settled page's identity or its
    // immediate neighbors change — live arrivals appended to the tail do
    // not affect the three active slots.
    val settledNoteId = feedNotes.getOrNull(pagerState.settledPage)?.id
    val prevNoteId = feedNotes.getOrNull(pagerState.settledPage - 1)?.id
    val nextNoteId = feedNotes.getOrNull(pagerState.settledPage + 1)?.id
    LaunchedEffect(settledNoteId, prevNoteId, nextNoteId, settingsSnapshot.videoQuality) {
        pool.update(pagerState.settledPage, feedNotes)
    }
    // APP-004: arrivals are held while the user is scrolled into the ACTIVE
    // surface (list first row / pager page 0 = top); at top auto-reveals.
    // snapshotFlow re-emits only when the derived at-top value CHANGES —
    // keying the effect on the raw scroll offset relaunched a coroutine on
    // every scroll frame (audit §4 recomposition churn).
    LaunchedEffect(videoOnly) {
        if (videoOnly) {
            snapshotFlow { pagerState.settledPage == 0 }
                .collect { atTop -> viewModel.holdNewNotes(!atTop) }
        } else {
            snapshotFlow {
                listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            }.collect { atTop -> viewModel.holdNewNotes(!atTop) }
        }
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
    // Re-tapping Home returns to the head; arrivals merge automatically once
    // that top position is reached, without a pending-count control.
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
                    // Wait for identity RESOLUTION, not just absence: gating
                    // on accountPubkey alone flashed the guest banner to
                    // every signed-in cold start while the key restored.
                    if (identityState.account == null && identityState.identityResolved) {
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
                                note = feedNotes[page],
                                // Narrow slices, not the whole FeedUiState: a
                                // tally/relay-health-only publish must not
                                // recompose visible video pages (audit §4).
                                profiles = state.profiles,
                                following = state.following,
                                bookmarkedIds = state.bookmarkedIds,
                                actions = actions, pool = pool,
                                player = playerBindings[feedNotes[page].id],
                                onLike = viewModel::toggleLike, onBookmark = viewModel::toggleBookmark,
                                onComment = { showCommentsFor = it }, onRepost = viewModel::repost,
                                onFollow = viewModel::toggleFollow,
                                onZap = { viewModel.loadZaps(it.id); viewModel.selectZapAmount(settingsSnapshot.defaultZapAmount.toLong()); zapTarget = it },
                                onAuthor = { authorTarget = it }, isMuted = viewModel.isMuted(feedNotes[page].pubkey),
                                onMuteToggle = { viewModel.toggleMute(feedNotes[page].pubkey) },
                                onReport = { reason -> viewModel.report(feedNotes[page], reason) },
                                authorDemoted = feedNotes[page].pubkey in demotedAuthorIds,
                                tagDemoted = feedNotes[page].hashtags.firstOrNull()?.let { it in demotedTagSet } == true,
                                interactionAuthorName = state.profiles[feedNotes[page].pubkey]?.bestDisplayName,
                                onNotInterested = { viewModel.notInterested(feedNotes[page]) },
                                onHideNote = { viewModel.hideNote(feedNotes[page]) },
                                onToggleAuthorDemotion = { viewModel.toggleShowLessFrom(feedNotes[page].pubkey) },
                                onToggleTagDemotion = viewModel::toggleShowLessAbout,
                                onOpenAttachment = { externalLink = it },
                            )
                        } else NotesList(
                            notes = feedNotes, content = feedContent, actions = actions, viewModel = viewModel,
                            // APP-006: stories rail as the list's first item
                            // (scrolls away with the feed) instead of a pinned
                            // overlay floating over the notes.
                            header = {
                                space.bitos.app.ui.stories.StoriesBar(
                                    authors = storiesState.authors,
                                    publicAuthors = storiesState.publicAuthors,
                                    seenIds = storiesState.seenIds,
                                    onOpenViewer = { storyViewerTarget = it },
                                    onCreateStory = { showStoryComposer = true },
                                    onOpenPublicStories = onOpenDiscover,
                                    profileFor = { pubkey -> state.profiles[pubkey] },
                                    modifier = Modifier.padding(top = BitOSSpacing.base, bottom = BitOSSpacing.sm),
                                )
                            },
                            onLoadOlder = viewModel::loadOlder,
                            compact = settingsSnapshot.compactMode,
                            listState = listState, onComment = { showCommentsFor = it }, onZap = { viewModel.selectZapAmount(settingsSnapshot.defaultZapAmount.toLong()); zapTarget = it },
                            onAuthor = { authorTarget = it }, onLike = { note ->
                                if (settingsSnapshot.hapticEnabled) {
                                    feedHaptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                }
                                viewModel.toggleLike(note)
                            },
                            onBookmark = viewModel::toggleBookmark, onRepost = viewModel::repost,
                            pollTallies = feedContent.pollTallies,
                            canVotePoll = identityState.account != null,
                            onVotePoll = { note, optionIndex -> viewModel.votePoll(note, optionIndex) },
                            onOpenAttachment = { externalLink = it },
                            onOpenExternalLink = { externalLink = it },
                            onOpenNoteRef = { raw ->
                                // In-place note-ref open (web parity): fetch
                                // the head, then show the thread sheet.
                                refOpenTarget = raw
                                viewModel.openNoteReference(raw)
                            },
                            // Mention taps open the mentioned user's sheet,
                            // not the note author's.
                            onOpenMentionProfile = { authorTarget = it },
                            sensitiveShowByDefault = sensitiveShowByDefault,
                            mediaPreview = settingsSnapshot.mediaPreview,
                        )
                    }
                }
                // APP-006: story viewer full-screen overlay.
        storyViewerTarget?.let { storyAuthor ->
            androidx.compose.runtime.LaunchedEffect(storyAuthor.pubkey) {
                // Web parity: engagement loads with the viewer.
                storiesRepository?.loadActivity(storyAuthor.slides)
            }
            space.bitos.app.ui.stories.StoryViewer(
                author = storyAuthor,
                interactionFor = { slideId -> storiesState.interactions[slideId] },
                profileFor = { pubkey -> state.profiles[pubkey] },
                isMine = storyAuthor.pubkey == state.accountPubkey,
                hasIdentity = state.accountPubkey != null,
                onLike = viewModel::likeStorySlide,
                onUnlike = viewModel::unlikeStorySlide,
                onReply = viewModel::replyToStorySlide,
                onDm = { text ->
                    val repo = dmRepository
                    if (repo != null) {
                        scope.launch {
                            // Web `privateMessageDraft` parity: reference the
                            // story, then the typed message.
                            val draft = buildString {
                                append("Replying to your story:")
                                storyAuthor.slides.firstOrNull()?.let { slide ->
                                    if (slide.content.isNotBlank()) {
                                        append("\n")
                                        append(slide.content)
                                    }
                                    slide.imageUrls.forEach { url -> append("\n").append(url) }
                                    slide.videoUrl?.let { url -> append("\n").append(url) }
                                }
                                append("\n\n")
                                append(text)
                            }
                            repo.sendMessage(storyAuthor.pubkey, draft)
                        }
                    }
                },
                onZap = { slide ->
                    viewModel.selectZapAmount(settingsSnapshot.defaultZapAmount.toLong())
                    storyZapTarget = storyAuthor to slide.id
                },
                onDelete = { slide ->
                    viewModel.deleteStorySlide(slide.id)
                    storiesRepository?.removeSlide(slide.id)
                    storyViewerTarget = null
                },
                onSeen = { slideId -> storiesRepository?.markSeen(slideId, context) },
                onClose = { storyViewerTarget = null },
            )
        }

        // APP-006: story composer (kind-30315, web `StoryComposer` parity).
        if (showStoryComposer) {
            androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showStoryComposer = false }) {
                space.bitos.app.ui.stories.StoryComposerSheet(
                    identityViewModel = identityViewModel,
                    onPublish = { text, imageUrls, background, altText, sensitive, videoUrl, videoMime, videoDurationMs, videoPoster, pow ->
                        if (pow != null) {
                            notePublisher.publishStoryWithPow(
                                text, imageUrls, background, altText, sensitive,
                                pow.dTag, pow.nonce, pow.targetDifficulty, pow.createdAtSeconds,
                                { identityViewModel.createSigner() },
                                space.bitos.app.data.feed.DefaultRelays.writeUrls,
                                videoUrl, videoMime, videoDurationMs, videoPoster,
                            )
                        } else {
                            notePublisher.publishStory(
                                text, imageUrls, background, altText, sensitive,
                                { identityViewModel.createSigner() },
                                space.bitos.app.data.feed.DefaultRelays.writeUrls,
                                videoUrl, videoMime, videoDurationMs, videoPoster,
                            )
                        }
                    },
                    onClose = { showStoryComposer = false },
                )
            }
        }

        // APP-006: story zap sheet (web NoteZapDialog parity over the slide id).
        storyZapTarget?.let { (zapAuthor, slideId) ->
            val zapLud16 = state.profiles[zapAuthor.pubkey]?.lud16
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { viewModel.dismissZap(); storyZapTarget = null },
            ) {
                space.bitos.app.ui.feed.ZapContent(
                    note = null,
                    recipientPubkey = zapAuthor.pubkey,
                    lud16 = zapLud16,
                    state = zapState,
                    profileName = state.profiles[zapAuthor.pubkey]?.bestDisplayName,
                    hasIdentity = state.accountPubkey != null,
                    onAmountSelected = viewModel::selectZapAmount,
                    onZap = { sats, comment, anonymous ->
                        viewModel.selectZapAmount(sats)
                        viewModel.zapStory(zapAuthor.pubkey, zapLud16, slideId, comment, anonymous)
                    },
                    onClose = { viewModel.dismissZap(); storyZapTarget = null },
                    profilePictureUrl = state.profiles[zapAuthor.pubkey]?.picture,
                )
            }
        }

            }
        }

        // Prototype tabdock parity: creation moved to the shell's center ＋
        // (the Home FAB conflicted with the bottom bar and is removed).
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
                onDelete = viewModel::deleteNote,
                onComment = viewModel::comment,
                onClose = { showCommentsFor = null },
            )
        }
    }

    // In-place note-ref open (web parity, mockup app-10 states): classify
    // immediately (invalid never issues a REQ), poll the bounded side-store
    // for 3 s, then either open the thread sheet or surface Not found with
    // Retry / Add relay (nevent TLV hints).
    LaunchedEffect(refOpenTarget) {
        val raw = refOpenTarget ?: return@LaunchedEffect
        val ref = space.bitos.core.feed.ThreadOpen.classify(raw)
        if (ref == null) {
            refOpenTarget = null
            refOpenState = RefOpenPlate.Invalid
            return@LaunchedEffect
        }
        refOpenState = RefOpenPlate.Loading
        repeat(20) {
            val fetched = viewModel.refNote(raw)
            if (fetched != null) {
                refOpenTarget = null
                refOpenState = null
                showCommentsFor = fetched
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(150)
        }
        refOpenTarget = null
        refOpenState = RefOpenPlate.NotFound(
            raw = raw,
            hints = ref.relayHints,
        )
    }

    // APP-009 states plate: loading / invalid / not-found + retry actions.
    refOpenState?.let { plate ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { refOpenState = null },
            title = {
                Text(
                    when (plate) {
                        RefOpenPlate.Loading -> space.bitos.core.feed.ThreadOpenCopy.LOADING_TITLE
                        RefOpenPlate.Invalid -> space.bitos.core.feed.ThreadOpenCopy.INVALID_TITLE
                        is RefOpenPlate.NotFound -> space.bitos.core.feed.ThreadOpenCopy.NOT_FOUND_TITLE
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.W700,
                )
            },
            text = {
                Column {
                    if (plate is RefOpenPlate.Loading) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                            color = BitOSColors.primary,
                        )
                        Spacer(Modifier.height(BitOSSpacing.sm))
                    }
                    Text(
                        when (plate) {
                            RefOpenPlate.Loading -> "REQ ids / coordinate · readable relays"
                            RefOpenPlate.Invalid -> space.bitos.core.feed.ThreadOpenCopy.INVALID_BODY
                            is RefOpenPlate.NotFound -> space.bitos.core.feed.ThreadOpenCopy.NOT_FOUND_BODY
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = BitOSColors.textSecondary,
                    )
                    if (plate is RefOpenPlate.NotFound && plate.hints.isNotEmpty()) {
                        Spacer(Modifier.height(BitOSSpacing.sm))
                        Text(
                            "Author relay hints:",
                            style = MaterialTheme.typography.labelSmall,
                            color = BitOSColors.textTertiary,
                        )
                        plate.hints.forEach { hint ->
                            Text(
                                hint,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                color = BitOSColors.textSecondary,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                when (plate) {
                    is RefOpenPlate.NotFound -> {
                        androidx.compose.material3.TextButton(onClick = {
                            val raw = plate.raw
                            refOpenState = null
                            refOpenTarget = raw
                            viewModel.openNoteReference(raw)
                        }) { Text(space.bitos.core.feed.ThreadOpenCopy.RETRY, color = BitOSColors.primary) }
                    }
                    RefOpenPlate.Loading, RefOpenPlate.Invalid -> {}
                }
            },
            dismissButton = {
                if (plate is RefOpenPlate.NotFound && plate.hints.isNotEmpty()) {
                    androidx.compose.material3.TextButton(onClick = {
                        plate.hints.forEach { hint -> relayManager?.add(hint) }
                        refOpenState = null
                    }) { Text(space.bitos.core.feed.ThreadOpenCopy.ADD_RELAY, color = BitOSColors.primary) }
                }
            },
        )
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
                profiles = state.profiles,
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
}

@Composable
private fun FeedPage(
    note: FeedNote,
    profiles: Map<String, ProfileMetadata>,
    following: Set<String>,
    bookmarkedIds: Set<String>,
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
    /** Local ranking signals (web interaction-profile parity). */
    authorDemoted: Boolean = false,
    tagDemoted: Boolean = false,
    interactionAuthorName: String? = null,
    onNotInterested: () -> Unit = {},
    onHideNote: () -> Unit = {},
    onToggleAuthorDemotion: () -> Unit = {},
    onToggleTagDemotion: (String) -> Unit = {},
    /** Opens the first attachment through the external-link confirm gate. */
    onOpenAttachment: (String) -> Unit = {},
) {
    if (note.video != null) {
        VideoNotePage(
            note, profiles, following, bookmarkedIds, actions, pool, player, onLike, onBookmark, onComment, onRepost, onFollow, onZap, onAuthor,
            isMuted, onMuteToggle, onReport,
            authorDemoted, tagDemoted, interactionAuthorName,
            onNotInterested, onHideNote, onToggleAuthorDemotion, onToggleTagDemotion, onOpenAttachment,
        )
    } else {
        TextNotePage(note, profiles, bookmarkedIds, actions, onLike, onBookmark, onComment, onRepost, onFollow, onZap, onAuthor)
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
    profiles: Map<String, ProfileMetadata>,
    following: Set<String>,
    bookmarkedIds: Set<String>,
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
    /** Local ranking signals (web interaction-profile parity). */
    authorDemoted: Boolean = false,
    tagDemoted: Boolean = false,
    interactionAuthorName: String? = null,
    onNotInterested: () -> Unit = {},
    onHideNote: () -> Unit = {},
    onToggleAuthorDemotion: () -> Unit = {},
    onToggleTagDemotion: (String) -> Unit = {},
    onOpenAttachment: (String) -> Unit = {},
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
        CaptionOverlay(note, profiles, following, onFollow, onAuthor, Modifier.align(Alignment.BottomStart).fillMaxWidth())
        VideoActionRail(
            note = note,
            isLiked = note.id in actions.liked,
            isBookmarked = note.id in bookmarkedIds || note.id in actions.bookmarked,
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
            authorDemoted = authorDemoted,
            tagDemoted = tagDemoted,
            interactionAuthorName = interactionAuthorName,
            onNotInterested = onNotInterested,
            onHideNote = onHideNote,
            onToggleAuthorDemotion = onToggleAuthorDemotion,
            onToggleTagDemotion = onToggleTagDemotion,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun CaptionOverlay(
    note: FeedNote,
    profiles: Map<String, ProfileMetadata>,
    following: Set<String>,
    onFollow: (String) -> Unit,
    onAuthor: (String) -> Unit,
    modifier: Modifier,
) {
    val profile = profiles[note.pubkey]
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
                isFollowing = note.pubkey in following,
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
        // Hashtags render once, inline in the caption body — no second tag row.
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
    /** Local ranking signals (web interaction-profile parity). */
    authorDemoted: Boolean = false,
    tagDemoted: Boolean = false,
    interactionAuthorName: String? = null,
    onNotInterested: () -> Unit = {},
    onHideNote: () -> Unit = {},
    onToggleAuthorDemotion: () -> Unit = {},
    onToggleTagDemotion: (String) -> Unit = {},
    onOpenAttachment: (String) -> Unit = {},
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
            authorDemoted = authorDemoted,
            tagDemoted = tagDemoted,
            authorName = interactionAuthorName,
            onNotInterested = onNotInterested,
            onHideNote = onHideNote,
            onToggleAuthorDemotion = onToggleAuthorDemotion,
            onToggleTagDemotion = onToggleTagDemotion,
            onOpenAttachment = onOpenAttachment,
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
    /** Local ranking signals (web interaction-profile parity). */
    authorDemoted: Boolean = false,
    tagDemoted: Boolean = false,
    authorName: String? = null,
    onNotInterested: () -> Unit = {},
    onHideNote: () -> Unit = {},
    onToggleAuthorDemotion: () -> Unit = {},
    onToggleTagDemotion: (String) -> Unit = {},
    /** Opens the first attachment through the external-link confirm gate. */
    onOpenAttachment: (String) -> Unit = {},
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
        // Web PostCard menu parity: attachment actions when media rides along.
        val attachmentEntries: List<AppMenuEntry> = if (note.mediaUrls.isNotEmpty()) {
            listOf(
                AppMenuEntry.Item(AppMenuItem("open-attachment", "Open attachment", icon = AppIcons.Globe)),
                AppMenuEntry.Item(AppMenuItem("copy-attachment", "Copy attachment URL", icon = AppIcons.Copy)),
            )
        } else {
            emptyList()
        }
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
                // Web interaction-profile parity: local ranking signals.
                AppMenuEntry.Item(AppMenuItem("not-interested", "Not interested", icon = AppIcons.Close)),
                AppMenuEntry.Item(AppMenuItem("hide-note", "Hide this note", icon = AppIcons.Delete)),
                AppMenuEntry.Item(
                    AppMenuItem(
                        "show-less-from",
                        (if (authorDemoted) "Show more from " else "Show less from ") + (authorName ?: "this author"),
                        icon = AppIcons.User,
                    ),
                ),
                *if (note.hashtags.isNotEmpty()) {
                    arrayOf(
                        AppMenuEntry.Item(
                            AppMenuItem(
                                "show-less-about",
                                (if (tagDemoted) "Show more about #" else "Show less about #") + note.hashtags.first(),
                                icon = AppIcons.Delete,
                            ),
                        ),
                        AppMenuEntry.Divider,
                    )
                } else {
                    arrayOf(AppMenuEntry.Divider)
                },
                AppMenuEntry.Item(
                    AppMenuItem("mute", if (isMuted) "Unmute author" else "Mute author", icon = AppIcons.Mute),
                ),
                AppMenuEntry.Divider,
                AppMenuEntry.Item(AppMenuItem("report-spam", "Report as spam", icon = AppIcons.ReportSpam, destructive = true)),
                AppMenuEntry.Item(AppMenuItem("report-illicit", "Report as illicit", icon = AppIcons.ReportIllicit, destructive = true)),
                AppMenuEntry.Item(AppMenuItem("report-harassment", "Report as harassment", icon = AppIcons.ReportHarassment, destructive = true)),
            ) + attachmentEntries,
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
                    "open-attachment" -> note.mediaUrls.firstOrNull()?.let(onOpenAttachment)
                    "copy-attachment" -> note.mediaUrls.firstOrNull()?.let {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(it))
                    }
                    "mute" -> onMuteToggle()
                    "not-interested" -> onNotInterested()
                    "hide-note" -> onHideNote()
                    "show-less-from" -> onToggleAuthorDemotion()
                    "show-less-about" -> note.hashtags.firstOrNull()?.let(onToggleTagDemotion)
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
    profiles: Map<String, ProfileMetadata>,
    bookmarkedIds: Set<String>,
    actions: LocalActions,
    onLike: (space.bitos.core.feed.FeedNote) -> Unit,
    onBookmark: (String) -> Unit,
    onComment: (space.bitos.core.feed.FeedNote) -> Unit,
    onRepost: (space.bitos.core.feed.FeedNote) -> Unit,
    onFollow: (String) -> Unit,
    onZap: (space.bitos.core.feed.FeedNote) -> Unit,
    onAuthor: (String) -> Unit,
) {
    val profile = profiles[note.pubkey]
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
            // Hashtags render once, inline in the note body — no second tag row.
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
                icon = if (note.id in bookmarkedIds || note.id in actions.bookmarked) SolarFeedIcon.BookmarkFilled else SolarFeedIcon.Bookmark,
                label = if (note.id in bookmarkedIds || note.id in actions.bookmarked) "Remove bookmark" else "Bookmark",
                tint = if (note.id in bookmarkedIds || note.id in actions.bookmarked) BitOSColors.bookmark else BitOSColors.textSecondary,
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
    /** Centered progress while the poster loads (Explore grid tiles —
     * a relay page lands as ONE state batch, but each poster still
     * decodes on its own; the placeholder says "loading" instead of
     * an empty slab popping in one tile at a time). */
    showLoadingProgress: Boolean = false,
) {
    Box(modifier.background(if (backgroundColor == BitOSColors.surface) {
        Brush.linearGradient(listOf(BitOSColors.surface, BitOSColors.surfaceElevated))
    } else {
        Brush.linearGradient(listOf(backgroundColor, backgroundColor))
    })) {
        if (url != null) {
            if (showLoadingProgress) {
                // Grid tiles want a per-tile loading slot. AsyncImage's state
                // callbacks drive the overlay WITHOUT subcomposition —
                // SubcomposeAsyncImage pays a subcomposition per instance,
                // which is measurable on a 3-wide scrolling grid (§ bitz
                // explore tiles).
                var tileState by androidx.compose.runtime.remember(url) {
                    androidx.compose.runtime.mutableStateOf<coil.compose.AsyncImagePainter.State?>(
                        coil.compose.AsyncImagePainter.State.Empty,
                    )
                }
                coil.compose.AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                    onLoading = { tileState = it },
                    onSuccess = { tileState = it },
                    onError = { tileState = it },
                )
                when (tileState) {
                    is coil.compose.AsyncImagePainter.State.Loading -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = BitOSColors.textTertiary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    is coil.compose.AsyncImagePainter.State.Error -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            AppIcons.Play,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    else -> {}
                }
            } else {
                coil.compose.AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
            }
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
    content: HomeFeedContentState,
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
    /** Profile-mention tap → the mentioned user's profile sheet. */
    onOpenMentionProfile: (String) -> Unit = {},
    /** APP-006: optional first list item (stories rail) — scrolls away with
     *  the feed like the web shell; own key + contentType so it never shares
     *  the note-row reuse pool. */
    header: (@Composable () -> Unit)? = null,
    onLoadOlder: () -> Unit,
    /** APP-008 poll voting. */
    pollTallies: Map<String, space.bitos.core.model.PollTally> = emptyMap(),
    canVotePoll: Boolean = false,
    onVotePoll: (FeedNote, Int) -> Unit = { _, _ -> },
    onOpenAttachment: (String) -> Unit = {},
) {
    androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        // The stories rail rides INSIDE the list (web parity: it scrolls away
        // with the feed). Re-tap Home lands here as the true top.
        if (header != null) {
            item(key = "feed-header", contentType = { "feed_header" }) { header() }
        }
        itemsIndexed(
            notes,
            key = { _, note -> note.id },
            // Stable content type: note rows and the pagination footer are
            // different reuse pools (native-performance.md §6).
            contentType = { _, _ -> "feed_note" },
        ) { index, note ->
            space.bitos.app.ui.components.FeedNoteCard(
                note = note,
                profile = content.profiles[note.pubkey],
                bookmarked = note.id in content.bookmarkedIds || note.id in actions.bookmarked,
                liked = note.id in actions.liked,
                resolveMentionName = { hex -> content.profiles[hex]?.bestDisplayName },
                onLike = { onLike(note) },
                onBookmark = { onBookmark(note.id) },
                onComment = { onComment(note) },
                onRepost = { onRepost(note) },
                onZap = { onZap(note) },
                onAuthor = { onAuthor(note.pubkey) },
                isMuted = viewModel.isMuted(note.pubkey),
                onMuteToggle = { viewModel.toggleMute(note.pubkey) },
                onReport = { reason -> viewModel.report(note, reason) },
                // Local ranking signals (web interaction-profile parity).
                authorDemoted = viewModel.interaction?.isAuthorDemoted(note.pubkey) == true,
                tagDemoted = note.hashtags.firstOrNull()?.let { viewModel.interaction?.isTagDemoted(it) == true } == true,
                interactionAuthorName = content.profiles[note.pubkey]?.bestDisplayName,
                onNotInterested = { viewModel.notInterested(note) },
                onHideNote = { viewModel.hideNote(note) },
                onToggleAuthorDemotion = { viewModel.toggleShowLessFrom(note.pubkey) },
                onToggleTagDemotion = viewModel::toggleShowLessAbout,
                // APP-008 poll voting.
                pollTally = pollTallies[note.id],
                canVotePoll = canVotePoll,
                onLoadPollVotes = { viewModel.loadPollVotes(note.id) },
                onVotePoll = { optionIndex -> onVotePoll(note, optionIndex) },
                onOpenAttachment = onOpenAttachment,
                onOpenExternalLink = onOpenExternalLink,
                onOpenNoteRef = onOpenNoteRef,
                onOpenMentionProfile = onOpenMentionProfile,
                rawEventJson = { viewModel.rawEventJson(note.id) },
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
                    // Feed separators mark the next item boundary, not the
                    // text column: span the entire viewport like the web UI.
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // A dedicated end sentinel is more reliable than relying solely on
        // the final card's visibility: it also covers short lists and list
        // mutations while the reader is already at the bottom. The repository
        // owns all in-flight/exhausted guards.
        if (!content.noMoreOlder && notes.isNotEmpty()) {
            item(key = "older-trigger", contentType = { "older_trigger" }) {
                androidx.compose.runtime.LaunchedEffect(notes.last().id, content.isLoadingOlder) {
                    if (!content.isLoadingOlder) onLoadOlder()
                }
                Spacer(Modifier.height(1.dp))
            }
        }
        // APP-004 pagination: footer spinner while an older page loads.
        if (content.isLoadingOlder) {
            item(key = "older-footer", contentType = { "footer" }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BitOSColors.primary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
            }
        }
        // UX U7: the walk is exhausted for this lane — an explicit boundary
        // instead of a silent dead-end (parity with the iOS footer).
        if (content.noMoreOlder && notes.isNotEmpty()) {
            item(key = "caught-up-footer", contentType = { "footer" }) {
                Text(
                    "You're all caught up",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = BitOSColors.textTertiary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// Note card internals (FeedNoteCard / PollOptions / CardAction) now live
// in ui/components/FeedNoteCard.kt — shared with the profile surfaces.
// ---------------------------------------------------------------------

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
            // Official wordmark, ~22dp like the web/Flutter app bar —
            // theme-aware (black art on the light shell, white on dark).
            BrandWordmark(height = 22.dp, contentDescription = "BitOS")
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
            // Solar widget-linear opens the account hub.
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
            TimelineTab("For You", AppIcons.Sparkles, state.timeline == FeedTimeline.FOR_YOU) { onTimeline(FeedTimeline.FOR_YOU) }
            Spacer(Modifier.width(BitOSSpacing.lg))
            TimelineTab("Following", AppIcons.People, state.timeline == FeedTimeline.FOLLOWING) { onTimeline(FeedTimeline.FOLLOWING) }
            Spacer(Modifier.weight(1f))
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

/** Underline tab (legacy parity: icon + label, primary underline, hairline row border — spec §3.4). */
@Composable
private fun TimelineTab(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
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
        }
    }
}

@Composable
private fun FeedLoading() {
    // UX U2 (§2.5 skeletons): matched skeleton rows instead of a bare
    // spinner — the shape of the incoming content; shimmer honors reduce-
    // motion inside AppSkeleton.
    Column(Modifier.fillMaxSize()) {
        repeat(6) { AppSkeletonRow() }
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
