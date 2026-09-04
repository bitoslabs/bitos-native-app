@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package space.bitos.app.ui.bitz

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import space.bitos.app.R
import space.bitos.app.data.feed.AuthorRepository
import space.bitos.app.data.feed.FeedTimeline
import space.bitos.app.data.feed.FeedUiState
import space.bitos.app.data.feed.SearchRepository
import space.bitos.app.data.feed.SearchScope
import space.bitos.app.data.publish.NotePublisher
import space.bitos.app.data.settings.SettingsStore
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.player.VideoPlayerPool
import space.bitos.app.player.PosterPrefetcher
import space.bitos.app.ui.components.AppMenuDropdown
import space.bitos.app.ui.components.AppMenuEntry
import space.bitos.app.ui.components.AppMenuItem
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.feed.HomeViewModel
import space.bitos.app.ui.feed.LocalActions
import space.bitos.app.ui.feed.PosterImage
import space.bitos.app.ui.feed.ZapContent
import space.bitos.app.ui.feed.CommentContent
import space.bitos.app.ui.profile.AuthorProfileContent
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.designsystem.AppSkeletonTile
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.app.ui.theme.SolarFeedIcon
import space.bitos.app.ui.theme.SolarFeedIconImage
import space.bitos.app.ui.components.formatTimeAgo
import space.bitos.app.ui.components.shortPubkey
import space.bitos.core.feed.BitzExplore
import space.bitos.core.feed.BitzSearch
import space.bitos.core.feed.BitzTimelinePolicy
import space.bitos.core.feed.FeedNote
import space.bitos.core.feed.NoteShare
import space.bitos.core.identity.NostrKeyCodec
import space.bitos.core.model.MediaMetadata
import space.bitos.core.model.ProfileMetadata
import space.bitos.core.settings.BitzModeSetting
import space.bitos.core.settings.SettingsContract

/**
 * Native pager projection: author mode is an exact private window; the
 * normal lanes put bounded search picks ahead of the shared video window.
 * The no-splice path returns the existing immutable list so steady-state
 * recomposition does not copy the full feed.
 */
internal fun projectBitzPlayerNotes(
    authorMode: Boolean,
    videos: List<FeedNote>,
    spliced: List<FeedNote>,
): List<FeedNote> {
    if (authorMode || spliced.isEmpty()) return videos
    val windowIds = videos.mapTo(HashSet(videos.size)) { it.id }
    return spliced.filterNot { it.id in windowIds } + videos
}

/** Keeps a completed Explore refresh from replacing an inactive For You visit. */
internal fun bitzForYouDisplayNotes(
    mode: BitzModeSetting,
    forYouSnapshot: List<FeedNote>,
    liveVideos: List<FeedNote>,
): List<FeedNote> =
    if (mode == BitzModeSetting.FOR_YOU && forYouSnapshot.isNotEmpty()) forYouSnapshot else liveVideos

/**
 * Bitz short-video surface (APP-007, spec §3.7). Owns the glass top bar
 * with the persisted Explore · Following · For-you pills, the 3-column
 * explore grid, the snap player with inline controls (scrubber, ±10 s,
 * mute memory, double-tap like, sensitive gate) and the full-screen search
 * overlay. Deterministic rules (mode wire, search policy, paging bounds,
 * share copy, duration labels) come from `space.bitos.core.feed.Bitz` —
 * this screen only renders and dispatches.
 *
 * One shared HomeViewModel window feeds this surface and Home; the pool
 * reconciles against THIS surface's paged list, so filtered neighbors are
 * always the on-screen neighbors.
 */
@Composable
fun BitzScreen(
    viewModel: HomeViewModel,
    identityViewModel: IdentityViewModel,
    notePublisher: NotePublisher,
    authorRepository: AuthorRepository,
    settingsStore: SettingsStore,
    searchRepository: SearchRepository,
    retapTick: Int = 0,
    sensitiveShowByDefault: Boolean = false,
    /** Web `/bitz?author=<npub>` parity: author-scoped playback from a
     *  profile Bitz-grid tile. Null = the normal 3-tab surface. */
    authorPubkey: String? = null,
    /** Deep-link: the tapped profile grid tile lands first on screen. */
    initialNoteId: String? = null,
    /** Author-mode back bar → returns to the profile. */
    onExitAuthorMode: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenComposer: () -> Unit = {},
    /** Spec §3.7 record entry: opens the Create hub (camera/import). */
    onOpenCreate: () -> Unit = {},
    /** APP-007 remix: opens the composer seeded with remix attribution tags. */
    onOpenRemixComposer: (List<List<String>>) -> Unit = {},
    /** UX-010: opens the in-app full profile page for a pubkey. */
    onOpenAuthorProfile: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions by viewModel.localActions.collectAsStateWithLifecycle()
    val publishState by notePublisher.state.collectAsStateWithLifecycle()
    val authorState by authorRepository.state.collectAsStateWithLifecycle()
    val zapState by viewModel.zapState.collectAsStateWithLifecycle()
    val identityState by identityViewModel.state.collectAsStateWithLifecycle()
    val settingsSnapshot by settingsStore.snapshot.collectAsStateWithLifecycle()
    // Closure-stable settings view for the pool providers (read live at
    // reconciliation, like FeedScreen).
    val currentSettings = rememberUpdatedState(settingsSnapshot)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitzHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    // ── Author mode (web `/bitz?author=<npub>` parity) ──────────────
    // The same player surface scoped to one author: the page, the action
    // rail, comments/zap/remix sheets all work against the author's own
    // verified window. The tab rail is replaced by a back-to-profile bar
    // and the feed stays in loaded (chronological) order so the deep-linked
    // tile lands exactly where the user tapped in the profile grid.
    val authorMode = authorPubkey != null
    if (authorMode) {
        // One REQ per author (open() resets the store; never re-issue on
        // list changes). The deep-link jump resolves once the window holds
        // the tapped tile (see pendingJumpId below).
        LaunchedEffect(authorPubkey) { authorRepository.open(authorPubkey!!) }
    }

    // ── Mode (persisted through the shared settings contract) ─────────
    var mode by remember { mutableStateOf(if (authorMode) BitzModeSetting.FOR_YOU else settingsSnapshot.bitzMode) }
    if (!authorMode) {
        LaunchedEffect(mode) {
        // Three tabs (legacy Flutter parity): the pills drive the same
        // shared window Home uses — Following selects the follows
        // timeline, For-you/Explore the global one.
        val current = viewModel.state.value.timeline
        when (mode) {
            BitzModeSetting.FOR_YOU ->
                if (current != FeedTimeline.FOR_YOU) viewModel.selectTimeline(FeedTimeline.FOR_YOU)
            BitzModeSetting.FOLLOWING ->
                if (current != FeedTimeline.FOLLOWING) viewModel.selectTimeline(FeedTimeline.FOLLOWING)
            // Explore is the global/video window. It must reset to For You
            // before asking for an older page; otherwise a previous
            // Following selection makes Explore paginate the wrong lane.
            BitzModeSetting.EXPLORE ->
                if (current != FeedTimeline.FOR_YOU) viewModel.selectTimeline(FeedTimeline.FOR_YOU)
        }
        }
    }

    // ── Window + splice (search picks land ahead of the window) ────────
    val videos = remember(authorMode, state.notes, authorState.notes) {
        if (authorMode) authorState.notes.filter { it.video != null } else state.notes.filter { it.video != null }
    }
    val spliced = remember { mutableStateListOf<FeedNote>() }
    // The last For You visit is a presentation snapshot. Explore refreshes
    // the same global relay lane, but must not replace this pager when the
    // reader switches back to For You.
    val forYouNotes = remember { mutableStateListOf<FeedNote>() }

    // The paged list is the active tab's window (legacy `displayedEvents`):
    // search picks spliced ahead, then the verified video window. Author
    // mode plays the author's owned window in loaded order (no splice —
    // playback scope must match the profile grid).
    val playerNotes by remember(authorMode, videos, spliced) {
        derivedStateOf {
            val displayedVideos = bitzForYouDisplayNotes(mode, forYouNotes, videos)
            projectBitzPlayerNotes(authorMode = authorMode, videos = displayedVideos, spliced = spliced)
        }
    }
    // Per-session state only; it is intentionally never persisted.
    val revealed = remember { mutableStateMapOf<String, Boolean>() }

    // ── Player pool (bounded three-slot reconciliation) ────────────────
    val pool = remember {
        VideoPlayerPool(
            context = context,
            canAutoplay = { autoplayAllowed(context, currentSettings.value.mediaAutoPlay) },
            rateProvider = { currentSettings.value.videoPlaybackRate.rate.toFloat() },
            mutedProvider = { currentSettings.value.videoMuted },
            qualityProvider = { currentSettings.value.videoQuality },
        )
    }
    val playerBindings by pool.playerBindings.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        onDispose { pool.releaseAll() }
    }
    LaunchedEffect(settingsSnapshot.videoMuted) { pool.applyMuted(settingsSnapshot.videoMuted) }

    val pagerState = rememberPagerState(pageCount = { playerNotes.size })
    val gridState = rememberLazyGridState()
    var loadMoreCount by rememberSaveable { mutableStateOf(0) }
    val posterPrefetcher = remember(context) { PosterPrefetcher(context) }
    DisposableEffect(posterPrefetcher) {
        onDispose { posterPrefetcher.cancel() }
    }
    LaunchedEffect(mode, videos, gridState) {
        if (mode != BitzModeSetting.EXPLORE) {
            posterPrefetcher.cancel()
            return@LaunchedEffect
        }
        val widthPx = (context.resources.displayMetrics.widthPixels / 3).coerceAtLeast(1)
        val heightPx = widthPx * 16 / 9
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collectLatest { lastVisible ->
                posterPrefetcher.prefetch(
                    urls = videos.drop(lastVisible + 1).take(12).mapNotNull { it.video?.posterUrl },
                    widthPx = widthPx,
                    heightPx = heightPx,
                )
            }
    }

    // ── Explore stable snapshot (§ explore stability) ──────────────────
    // The feed window is a moving, bounded projection: live arrivals insert
    // at the head and the 200-item cap evicts the tail, so projecting
    // Explore straight off it re-ordered tiles mid-browse and deleted old
    // ones. Explore shows an append-only snapshot instead: captured on tab
    // entry and pull-to-refresh, merged (never re-ordered) as relay pages
    // and arrivals land.
    val exploreNotes = remember { mutableStateListOf<FeedNote>() }
    fun snapshotExploreReplace() {
        exploreNotes.clear()
        exploreNotes.addAll(videos)
    }
    // Entering Explore captures the window as the browse-stable snapshot.
    LaunchedEffect(mode) {
        if (mode == BitzModeSetting.EXPLORE) snapshotExploreReplace()
    }

    // Only the active For You visit receives live additions. In particular,
    // an Explore refresh cannot replace this snapshot behind its tab.
    LaunchedEffect(videos, mode) {
        if (mode == BitzModeSetting.FOR_YOU) {
            if (forYouNotes.isEmpty()) {
                forYouNotes.addAll(videos)
            } else {
                val known = forYouNotes.mapTo(HashSet()) { it.id }
                videos.forEach { if (it.id !in known) forYouNotes.add(it) }
            }
        }
    }

    fun refreshWindow() {
        spliced.clear()
        loadMoreCount = 0
        if (mode == BitzModeSetting.FOR_YOU) {
            forYouNotes.clear()
            forYouNotes.addAll(videos)
        }
        if (mode == BitzModeSetting.EXPLORE) snapshotExploreReplace()
        viewModel.refresh()
    }

    // ── Remix (web remix.ts parity: advisory license gate → seeded composer)
    var remixAskTarget by remember { mutableStateOf<FeedNote?>(null) }

    fun remixSeedTags(note: FeedNote): List<List<String>> {
        val label = state.profiles[note.pubkey]?.bestDisplayName ?: shortPubkey(note.pubkey)
        val base: List<List<String>> = space.bitos.core.feed.RemixRules.tagsFor(note.id, note.pubkey)
        return base + listOfNotNull(space.bitos.core.feed.RemixRules.attributionTag(label))
    }

    fun handleRemix(note: FeedNote) {
        // Restrictive licenses ask (advisory, never hidden) — web parity.
        if (space.bitos.core.feed.RemixRules.requiresAsk(note.license)) {
            remixAskTarget = note
        } else {
            onOpenRemixComposer(remixSeedTags(note))
        }
    }

    fun selectMode(next: BitzModeSetting) {
        // Author playback has no mode rail (web parity) — swipes stay inert.
        if (authorMode) return
        if (mode == next) {
            // Re-tap on the active pill: back to top; at top, refresh.
            if (next == BitzModeSetting.EXPLORE) {
                if (gridState.firstVisibleItemIndex != 0) {
                    scope.launch { gridState.animateScrollToItem(0) }
                } else {
                    refreshWindow()
                }
            } else {
                if (pagerState.currentPage != 0) {
                    scope.launch { pagerState.animateScrollToPage(0) }
                } else {
                    refreshWindow()
                }
            }
            return
        }
        mode = next
        settingsStore.setRaw(SettingsContract.KEY_BITZ_MODE, next.wire)
    }
    // Re-reconcile the pool only when the settled page's identity or its
    // immediate neighbors change — live arrivals appended to the tail do
    // not affect the three active slots.
    val bitzSettledId = playerNotes.getOrNull(pagerState.settledPage)?.id
    val bitzPrevId = playerNotes.getOrNull(pagerState.settledPage - 1)?.id
    val bitzNextId = playerNotes.getOrNull(pagerState.settledPage + 1)?.id
    val bitzVisibleCovered = playerNotes.getOrNull(pagerState.settledPage)?.let { note ->
        note.contentWarning && !sensitiveShowByDefault && revealed[note.id] != true
    } == true
    LaunchedEffect(mode, bitzSettledId, bitzPrevId, bitzNextId, settingsSnapshot.videoQuality, bitzVisibleCovered) {
        if (!authorMode && mode == BitzModeSetting.EXPLORE) {
            pool.releaseAll()
        } else {
            pool.update(
                visibleIndex = pagerState.settledPage,
                notes = playerNotes,
                autoplayAllowed = !bitzVisibleCovered && autoplayAllowed(context, settingsSnapshot.mediaAutoPlay),
            )
        }
    }
    // APP-004 hold rule: arrivals wait while the user is scrolled in.
    LaunchedEffect(pagerState.settledPage) {
        if (!authorMode) viewModel.holdNewNotes(pagerState.settledPage != 0)
    }
    // Re-anchor the pager by NOTE id (§ pager stability): the pager is
    // index-anchored, so an arrival merged at the head swapped the video
    // under the reader. Keep the settled note under the same finger
    // position; new pages appear above it, reachable by swiping back.
    val settledNoteId = playerNotes.getOrNull(pagerState.settledPage)?.id
    LaunchedEffect(playerNotes) {
        val id = settledNoteId ?: return@LaunchedEffect
        val index = playerNotes.indexOfFirst { it.id == id }
        if (index >= 0 && index != pagerState.settledPage && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(index)
        }
    }
    // Prepare the next ten videos before the active tab reaches its edge.
    // Author mode pages the author's own REQ backward (same 5-note pages
    // as the profile grid — one store, one cursor).
    LaunchedEffect(
        pagerState.settledPage,
        playerNotes.size,
        state.isLoadingOlder,
        state.noMoreOlder,
    ) {
        if (playerNotes.isEmpty()) return@LaunchedEffect
        if (authorMode) {
            if (pagerState.settledPage >= playerNotes.size - 2 && authorState.canLoadMore && !authorState.isLoadingMore) {
                authorRepository.loadMoreNotes()
            }
        } else if (!state.noMoreOlder && !state.isLoadingOlder &&
            pagerState.settledPage >= playerNotes.size - BitzTimelinePolicy.PREFETCH_BUFFER_THRESHOLD
        ) {
            viewModel.loadOlder()
        }
    }

    // ── Explore paging (shared bounds: 24 + 10/near-edge reveal) ──────
    // The grid renders the SNAPSHOT, falling back to the live window only
    // until the first snapshot lands. Merge never re-orders: new ids
    // append at the tail, whatever their arrival order.
    val exploreList = if (exploreNotes.isEmpty()) videos else exploreNotes
    LaunchedEffect(videos, mode) {
        if (mode == BitzModeSetting.EXPLORE) {
            val known = exploreNotes.mapTo(HashSet()) { it.id }
            videos.forEach { if (it.id !in known) exploreNotes.add(it) }
        }
    }
    val visibleTiles = BitzExplore.visibleCount(loadMoreCount)
    val gridNearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            info.totalItemsCount > 0 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >=
                info.totalItemsCount - BitzTimelinePolicy.PREFETCH_BUFFER_THRESHOLD
        }
    }
    LaunchedEffect(mode, gridNearEnd, exploreList.size, visibleTiles, state.isLoadingOlder, state.noMoreOlder) {
        if (mode == BitzModeSetting.EXPLORE && gridNearEnd) {
            // Grid near its end (Flutter `loadMoreExplore` parity): reveal
            // the next 10 snapshot tiles when hidden ones remain; when the
            // reveal catches the loaded window, ALSO warm the next relay
            // page so the footer never hits a cold boundary.
            if (BitzExplore.hasMore(exploreList.size, visibleTiles)) {
                loadMoreCount++
            } else if (!state.noMoreOlder && !state.isLoadingOlder) {
                viewModel.loadOlder()
            }
        }
    }

    // ── Shell re-tap (APP-003): same semantics as the active pill. ────
    LaunchedEffect(retapTick) {
        if (retapTick == 0) return@LaunchedEffect
        if (mode == BitzModeSetting.EXPLORE) {
            if (gridState.firstVisibleItemIndex != 0) {
                scope.launch { gridState.animateScrollToItem(0) }
            } else {
                refreshWindow()
            }
        } else {
            if (pagerState.currentPage != 0) {
                scope.launch { pagerState.animateScrollToPage(0) }
            } else {
                refreshWindow()
            }
        }
    }

    // Jump requests (explore tile / search pick) resolve once the paged
    // list reflects them.
    var pendingJumpId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingJumpId, playerNotes) {
        val id = pendingJumpId ?: return@LaunchedEffect
        val index = playerNotes.indexOfFirst { it.id == id }
        if (index >= 0) {
            pagerState.scrollToPage(index)
            pendingJumpId = null
        }
    }
    if (authorMode && initialNoteId != null) {
        // Web `#bitz=<id>` parity: the tapped profile grid tile is the first
        // thing on screen; the resolver above waits for the author window.
        LaunchedEffect(Unit) { pendingJumpId = initialNoteId }
    }

    fun openInPlayer(note: FeedNote) {
        if (note.id in playerNotes.map { it.id }) {
            mode = BitzModeSetting.FOR_YOU
            settingsStore.setRaw(SettingsContract.KEY_BITZ_MODE, BitzModeSetting.FOR_YOU.wire)
            pendingJumpId = note.id
        } else {
            if (spliced.size >= BitzSearch.RESULT_LIMIT) spliced.removeAt(spliced.lastIndex)
            if (spliced.size >= 8) spliced.removeAt(spliced.lastIndex)
            spliced.add(0, note)
            mode = BitzModeSetting.FOR_YOU
            settingsStore.setRaw(SettingsContract.KEY_BITZ_MODE, BitzModeSetting.FOR_YOU.wire)
            pendingJumpId = note.id
        }
    }

    // ── Sheets (same patterns as the Home surface) ─────────────────────
    var commentsTarget by remember { mutableStateOf<FeedNote?>(null) }
    var zapTarget by remember { mutableStateOf<FeedNote?>(null) }
    var authorTarget by remember { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    // APP-007 Chain: the note whose ancestry the sheet is showing.
    var chainTarget by remember { mutableStateOf<FeedNote?>(null) }
    val chainState by viewModel.remixChainState.collectAsStateWithLifecycle()

    /**
     * TikTok-style horizontal swipe (legacy bitz parity): a left swipe
     * advances Explore → Following → For you; the final left swipe on
     * For you opens the settled page's creator profile; a right swipe
     * steps back one mode. Sheet/search overlays swallow the gesture.
     */
    fun handleHorizontalSwipe(left: Boolean) {
        if (authorMode) return
        if (showSearch || commentsTarget != null || chainTarget != null ||
            zapTarget != null || authorTarget != null || remixAskTarget != null
        ) {
            return
        }
        // Web 5-tab swipe cycle (performance study §2.9): Explore →
        // Following → For you; a left swipe on For you keeps the
        // creator-profile shortcut (final tab, legacy Flutter parity).
        val order = listOf(
            BitzModeSetting.EXPLORE,
            BitzModeSetting.FOLLOWING,
            BitzModeSetting.FOR_YOU,
        )
        val index = order.indexOf(mode)
        if (left) {
            if (mode == BitzModeSetting.FOR_YOU) {
                val settled = playerNotes.getOrNull(pagerState.settledPage) ?: playerNotes.firstOrNull()
                if (settled != null) {
                    bitzHaptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    authorTarget = settled.pubkey
                }
            } else if (index in 0 until order.lastIndex) {
                selectMode(order[index + 1])
            }
        } else {
            // Right swipe on For You: open settled creator's profile (TikTok
            // pattern). Other modes step back one tab.
            if (mode == BitzModeSetting.FOR_YOU) {
                val settled = playerNotes.getOrNull(pagerState.settledPage) ?: playerNotes.firstOrNull()
                if (settled != null) {
                    bitzHaptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    authorTarget = settled.pubkey
                }
            } else if (index > 0) {
                selectMode(order[index - 1])
            }
        }
    }

    Box(Modifier.fillMaxSize().background(BitOSColors.background)) {
        if (authorMode) {
            // Author mode: same pager + rail, data scoped to one author
            // (web `/bitz?author=` parity). The tab rail becomes a
            // back-to-profile bar over the media.
            when {
                authorState.isLoading && playerNotes.isEmpty() -> BitzLoading()
                playerNotes.isEmpty() -> BitzMessage(
                    title = "No Bitz yet",
                    body = "Short videos this creator publishes will collect here.",
                )
                else -> VerticalPager(state = pagerState) { page ->
                    val note = playerNotes[page]
                    BitzVideoPage(
                        note = note,
                        state = state,
                        actions = actions,
                        pool = pool,
                        player = playerBindings[note.id],
                        isSettled = pagerState.settledPage == page,
                        muted = settingsSnapshot.videoMuted,
                        sensitiveShowByDefault = sensitiveShowByDefault,
                        revealed = revealed,
                        onHorizontalSwipe = ::handleHorizontalSwipe,
                        onToggleMute = {
                            settingsStore.setRaw(
                                SettingsContract.KEY_VIDEO_MUTED,
                                if (settingsSnapshot.videoMuted) "0" else "1",
                            )
                        },
                        onLike = viewModel::toggleLike,
                        onBookmark = viewModel::toggleBookmark,
                        onComment = { commentsTarget = it },
                        onRepost = viewModel::repost,
                        // Author mode: the follow button reflects this author.
                        onFollow = { viewModel.toggleFollow(authorPubkey!!) },
                        onZap = {
                            viewModel.loadZaps(it.id)
                            viewModel.selectZapAmount(settingsSnapshot.defaultZapAmount.toLong())
                            zapTarget = it
                        },
                        onRemix = ::handleRemix,
                        onChain = {
                            chainTarget = it
                            viewModel.loadRemixChain(it)
                        },
                        // Author mode owns one scope: identity taps leave the
                        // player for the full profile (no inner sheet — it
                        // would re-open the shared repository and wipe this
                        // author's window).
                        onAuthor = {
                            onExitAuthorMode()
                            onOpenAuthorProfile(authorPubkey ?: note.pubkey)
                        },
                        isMuted = viewModel.isMuted(note.pubkey),
                        onMuteToggle = { viewModel.toggleMute(note.pubkey) },
                        onReport = { reason -> viewModel.report(note, reason) },
                    )
                }
            }
            BitzAuthorBar(
                title = authorState.profile?.bestDisplayName
                    ?: shortPubkey(authorPubkey ?: ""),
                onBack = onExitAuthorMode,
                onOpenAuthor = { authorPubkey?.let(onOpenAuthorProfile) },
            )
        } else when (mode) {
            BitzModeSetting.EXPLORE -> ExploreGrid(
                state = state,
                // The append-only snapshot: stable while the user browses.
                videos = exploreList,
                visibleTiles = visibleTiles,
                gridState = gridState,
                sensitiveShowByDefault = sensitiveShowByDefault,
                revealed = revealed,
                onOpen = ::openInPlayer,
                onOpenAuthor = { authorTarget = it },
                onRevealMore = { loadMoreCount++ },
                onLoadOlder = viewModel::loadOlder,
                onRefresh = ::refreshWindow,
                onHorizontalSwipe = ::handleHorizontalSwipe,
            )
            BitzModeSetting.FOR_YOU, BitzModeSetting.FOLLOWING -> {
                when {
                    state.isLoading && playerNotes.isEmpty() -> BitzLoading()
                    mode == BitzModeSetting.FOLLOWING && state.accountPubkey == null && playerNotes.isEmpty() -> BitzMessage(
                        title = "Following needs an identity",
                        body = "Create, import or connect a Nostr identity to build a following timeline.",
                    )
                    mode == BitzModeSetting.FOLLOWING && playerNotes.isEmpty() -> BitzMessage(
                        title = "Nothing from your follows yet",
                        body = "Follow more creators and their short videos will land here.",
                        // Legacy parity: Explore CTA + Refresh.
                        actionLabel = "Explore Bitz",
                        onAction = { selectMode(BitzModeSetting.EXPLORE) },
                        secondaryLabel = "Refresh Bitz",
                        onSecondary = { refreshWindow() },
                    )
                    playerNotes.isEmpty() -> BitzMessage(
                        title = "No Bitz found",
                        body = if (state.relayHealth.isLive) {
                            "Connected relays have not returned verified videos yet. Retrying every few seconds."
                        } else {
                            "Relays are connecting. Bitz fills once a connection succeeds."
                        },
                        actionLabel = "Refresh Bitz",
                        onAction = { refreshWindow() },
                    )
                    else -> VerticalPager(state = pagerState) { page ->
                        val note = playerNotes[page]
                        BitzVideoPage(
                            note = note,
                            state = state,
                            actions = actions,
                            pool = pool,
                            player = playerBindings[note.id],
                            isSettled = pagerState.settledPage == page,
                            muted = settingsSnapshot.videoMuted,
                            sensitiveShowByDefault = sensitiveShowByDefault,
                            revealed = revealed,
                            onHorizontalSwipe = ::handleHorizontalSwipe,
                            onToggleMute = {
                                settingsStore.setRaw(
                                    SettingsContract.KEY_VIDEO_MUTED,
                                    if (settingsSnapshot.videoMuted) "0" else "1",
                                )
                            },
                            onLike = viewModel::toggleLike,
                            onBookmark = viewModel::toggleBookmark,
                            onComment = { commentsTarget = it },
                            onRepost = viewModel::repost,
                            onFollow = viewModel::toggleFollow,
                            onZap = {
                                viewModel.loadZaps(it.id)
                                viewModel.selectZapAmount(settingsSnapshot.defaultZapAmount.toLong())
                                zapTarget = it
                            },
                            onRemix = ::handleRemix,
                            onChain = {
                                chainTarget = it
                                viewModel.loadRemixChain(it)
                            },
                            onAuthor = { authorTarget = it },
                            isMuted = viewModel.isMuted(note.pubkey),
                            onMuteToggle = { viewModel.toggleMute(note.pubkey) },
                            onReport = { reason -> viewModel.report(note, reason) },
                        )
                    }
                }
            }
        }

        // Glass top chrome floats over the media (spec §3.7). Refresh is NOT
        // a header button: re-tap the active Bitz tab (bottom bar) or
        // long-press it to refresh (user decision 2026-08-28). Author mode
        // replaces it with the back-to-profile bar above.
        if (!authorMode) {
            BitzTopBar(
                mode = mode,
                onSelectMode = ::selectMode,
                onSearch = { showSearch = true },
                onRecord = onOpenCreate,
            )
        }
        // No "New note" FAB (user decision 2026-08-29): creation entries are
        // the header record button and the feed FAB.
    }

    authorTarget?.let { targetPubkey ->
        space.bitos.app.ui.profile.AuthorProfileSheetHost(
            authorPubkey = targetPubkey,
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
                hasIdentity = identityState.account != null,
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

    commentsTarget?.let { target ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { commentsTarget = null }) {
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
                onClose = { commentsTarget = null },
            )
        }
    }

    // APP-007 Chain: remix ancestry (web RemixChainDialog parity).
    chainTarget?.let { target ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { chainTarget = null }) {
            BitzChainContent(
                rootId = target.id,
                state = state,
                chainState = chainState,
                onOpenAncestor = { id ->
                    viewModel.remixAncestorNote(id)?.let { ancestor ->
                        chainTarget = null
                        commentsTarget = ancestor
                    }
                },
                onClose = { chainTarget = null },
            )
        }
    }

    if (showSearch) {
        BitzSearchOverlay(
            state = state,
            searchRepository = searchRepository,
            sensitiveShowByDefault = sensitiveShowByDefault,
            onOpenNote = ::openInPlayer,
            onDismiss = { showSearch = false },
        )
    }

    // Remix advisory (bitz/all-reserved · bitz/source-permission).
    remixAskTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { remixAskTarget = null },
            title = { Text("Remix anyway?") },
            text = {
                Text(
                    "This creator marked this bitz \"${target.license}\".\n\n" +
                        "Credit is added automatically when you publish.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    remixAskTarget = null
                    onOpenRemixComposer(remixSeedTags(target))
                }) { Text("Remix", color = BitOSColors.primary) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { remixAskTarget = null }) { Text("Cancel") }
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────
// Glass top chrome (spec §3.7): pills · record · search
// ─────────────────────────────────────────────────────────────────────

/**
 * Author-mode chrome (web `/bitz?author=` back-to-profile bar): back chevron
 * + creator name; the name opens the full profile. Replaces the mode rail.
 */
@Composable
private fun BitzAuthorBar(
    title: String,
    onBack: () -> Unit,
    onOpenAuthor: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x99000000), Color.Transparent),
                ),
            )
            // No statusBarsPadding: the shell Scaffold already applies the
            // system-bar inset to this surface.
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back to profile",
                tint = Color.White,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.W800,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable(onClickLabel = "Open full profile") { onOpenAuthor() },
        )
        // Status spacer keeps the bar symmetric with the leading back button.
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun BitzTopBar(
    mode: BitzModeSetting,
    onSelectMode: (BitzModeSetting) -> Unit,
    onSearch: () -> Unit,
    onRecord: () -> Unit,
) {
    // Clean chrome (user decision 2026-08-29): no bar background, tabs are
    // bare text (legacy Flutter TikTok parity), no borders.
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = BitOSSpacing.sm, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(0.7f))
        Row {
            ModePill("Explore", mode == BitzModeSetting.EXPLORE) { onSelectMode(BitzModeSetting.EXPLORE) }
            ModePill("Following", mode == BitzModeSetting.FOLLOWING) { onSelectMode(BitzModeSetting.FOLLOWING) }
            ModePill("For you", mode == BitzModeSetting.FOR_YOU) { onSelectMode(BitzModeSetting.FOR_YOU) }
        }
        Spacer(Modifier.weight(1f))
        // Spec §3.7 record entry: camera capture → trim → publish.
        IconButton(onClick = onRecord) {
            Icon(AppIcons.Camera, contentDescription = "Record Bitz", tint = Color.White)
        }
        IconButton(onClick = onSearch) {
            Icon(AppIcons.Search, contentDescription = "Search Bitz", tint = Color.White)
        }
    }
}

/** Bare-text tab (legacy parity): active bold opaque, inactive 70%. */
@Composable
private fun ModePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.W800 else FontWeight.W600,
        color = if (selected) Color.White else Color(0xB3FFFFFF),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Show $label videos") { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

// ─────────────────────────────────────────────────────────────────────
// Explore tab (legacy Flutter `_ExploreGrid`/`_ExploreTile` parity):
// video-first 3-column 9:16 grid, caption + author identity + likes in
// the bottom scrim, blur-covered sensitive tiles, one trailing spinner
// tile while the next page walks — no footer buttons.
// ─────────────────────────────────────────────────────────────────────

/** Tile grid Chrome top padding (Flutter 76 under the pill bar + tabs). */
private val ExploreGridTopPadding = 76.dp

@Composable
private fun ExploreGrid(
    state: FeedUiState,
    videos: List<FeedNote>,
    visibleTiles: Int,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    sensitiveShowByDefault: Boolean,
    revealed: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    onOpen: (FeedNote) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onRevealMore: () -> Unit,
    onLoadOlder: () -> Unit,
    onRefresh: () -> Unit,
    /** Left/right horizontal swipe on the grid (mode cycling, player parity). */
    onHorizontalSwipe: (Boolean) -> Unit = {},
) {
    // Legacy bitz parity: a centered spinner while the first page loads —
    // skeleton tiles are a native addition the user never sees land.
    if (state.isLoading && videos.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = BitOSColors.textTertiary, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
        return
    }
    if (videos.isEmpty()) {
        BitzExploreEmpty(onRefresh = onRefresh)
        return
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val swipeThreshold = 72.dp.toPx()
                var drag = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        drag += amount
                    },
                    onDragEnd = {
                        if (drag <= -swipeThreshold) onHorizontalSwipe(true)
                        else if (drag >= swipeThreshold) onHorizontalSwipe(false)
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                )
            },
    ) {
        // Flutter RefreshIndicator parity — the grid always claims pulls.
        PullToRefreshBox(isRefreshing = state.isLoading, onRefresh = onRefresh) {
        LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        // Flutter bitz grid: padding (10, 76, 10, 16), 4 dp gutters.
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = ExploreGridTopPadding, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val tiles = videos.take(visibleTiles)
        itemsIndexed(tiles, key = { _, note -> note.id }) { _, note ->
            BitzTile(
                note = note,
                state = state,
                sensitiveShowByDefault = sensitiveShowByDefault,
                revealed = revealed,
                onOpen = { onOpen(note) },
                onOpenAuthor = { onOpenAuthor(note.pubkey) },
            )
        }
        // One trailing spinner tile while the next relay page is walking
        // (Flutter `_ExploreLoadingTile` parity; no footer buttons).
        if (state.isLoadingOlder && !state.noMoreOlder) {
            item(key = "grid-loading") { ExploreLoadingTile() }
        }
        // UX U7: exhausted walk — an explicit boundary spanning the grid
        // instead of a silent dead-end at the last tile.
        if (state.noMoreOlder && videos.isNotEmpty()) {
            item(
                key = "grid-caught-up",
                span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
            ) {
                Text(
                    "You're all caught up",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = BitOSColors.textTertiary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp),
                )
            }
        }
    }
        }
    }
}

/** Shimmer-less loading tile: soft slab + centered 22 dp spinner. */
@Composable
private fun ExploreLoadingTile() {
    Box(
        Modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x0DFFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = BitOSColors.textTertiary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
    }
}

/** Empty state (Flutter `_BitsEmptyState` parity): rounded icon box,
 * bold title, muted hint — pull anywhere to refresh. */
@Composable
private fun BitzExploreEmpty(onRefresh: () -> Unit) {
    PullToRefreshBox(isRefreshing = false, onRefresh = onRefresh) {
        Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.Play, contentDescription = null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(32.dp))
                }
                Text("No Bitz found", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.W800, color = Color.White)
                Text(
                    "Your configured relays did not return kind-1 notes with video links.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xB3F8F8FF),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SkeletonTile() {
    Box(Modifier.aspectRatio(9f / 16f).background(BitOSColors.surfaceElevated))
}

/** One 9:16 explore tile (Flutter `_ExploreTile` parity): press
 *  scale/dim, poster cover, bottom scrim = caption · author identity
 *  (hex avatar + ⚡ + ✓ NIP-05) · like count. Sensitive tiles blur.
 *  Tapping opens For-you at that bit; the reveal gate lives there. */
@Composable
private fun BitzTile(
    note: FeedNote,
    state: FeedUiState,
    sensitiveShowByDefault: Boolean,
    revealed: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    onOpen: () -> Unit,
    /** Author identity row → profile sheet (Flutter `Routes.profileOf`). */
    onOpenAuthor: () -> Unit = {},
) {
    val covered = note.contentWarning && !sensitiveShowByDefault && revealed[note.id] != true
    // Press feedback (Flutter AnimatedScale 0.97 / opacity 0.85 @90 ms).
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, animationSpec = tween(90), label = "tile-press-scale")
    val pressAlpha by animateFloatAsState(if (pressed) 0.85f else 1f, animationSpec = tween(90), label = "tile-press-alpha")
    val profile = state.profiles[note.pubkey]
    val likes = state.tallies[note.id]?.reactions ?: 0
    Box(
        Modifier
            .aspectRatio(9f / 16f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = pressAlpha
            }
            .clip(RoundedCornerShape(8.dp))
            .background(BitOSColors.surface)
            .pointerInput(note.id) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onOpen() },
                )
            },
    ) {
        PosterImage(
            url = note.video?.posterUrl,
            modifier = Modifier.fillMaxSize(),
            // Explore grid parity with iOS: centered progress while each
            // poster loads — a 6-item relay page structurally lands as one
            // batch; posters still decode tile-by-tile.
            showLoadingProgress = true,
        )
        if (note.video?.posterUrl == null) {
            Icon(
                AppIcons.Play,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.Center).size(30.dp),
            )
        }
        if (covered) {
            // Sensitive: blur + dim + eye-off label (Flutter parity); tap
            // still opens For-you — the reveal gate lives in the player.
            Box(
                Modifier
                    .fillMaxSize()
                    .blur(14.dp)
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.VisibilityOff,
                    contentDescription = "Sensitive content",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                ExploreTileFooter(
                    note = note,
                    profile = profile,
                    likes = likes,
                    onOpenAuthor = onOpenAuthor,
                )
            }
        }
        // UX U8: duration affordance on the tile (shared formatter;
        // top-end so it never collides with the footer's like count).
        // Hidden while duration is unknown.
        note.video?.durationSeconds?.takeIf { it > 0 }?.let { duration ->
            Text(
                space.bitos.core.model.MediaMetadata.formatDuration(duration),
                color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    fontSize = 9.sp,
                    letterSpacing = 0.sp,
                ),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** Bottom scrim (Flutter `_ExploreTileFooter` parity): caption (2 lines,
 *  10 sp w600) · author identity row (hex avatar 20, ⚡ badge, ✓ NIP-05)
 *  · like count. The identity row opens the author profile. */
@Composable
private fun ExploreTileFooter(
    note: FeedNote,
    profile: ProfileMetadata?,
    likes: Int,
    onOpenAuthor: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color(0x4D000000), 1f to Color(0xD9000000)))
            .padding(6.dp),
    ) {
        val caption = remember(note.content) { stripMediaUrlsForCaption(note.content) }
        if (caption.isNotEmpty()) {
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.W600,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "View author profile") { onOpenAuthor() },
            ) {
                PubkeyAvatar(
                    pubkey = note.pubkey,
                    size = 20,
                    pictureUrl = profile?.picture,
                    label = profile?.bestDisplayName,
                    hasLightning = !profile?.lud16.isNullOrBlank(),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    profile?.bestDisplayName ?: shortPubkey(note.pubkey),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W700,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!profile?.nip05.isNullOrBlank()) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "NIP-05 identity claim",
                        tint = BitOSColors.primary,
                        modifier = Modifier.padding(start = 2.dp).size(11.dp),
                    )
                }
            }
            if (likes > 0) {
                Spacer(Modifier.width(4.dp))
                Icon(AppIcons.Heart, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(2.dp))
                Text(
                    space.bitos.core.feed.BitzFormat.count(likes.toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.W700,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }
    }
}

/** Caption text = content minus image/video URLs, whitespace-collapsed
 *  (Flutter `stripMediaUrls` parity, bounded to 2 shown lines). */
private fun stripMediaUrlsForCaption(content: String): String {
    val urlPattern = Regex("https?://\\S+")
    return content
        .replace('\u00A0', ' ')
        .replace(urlPattern, "")
        .replace(Regex("[ \t]+"), " ")
        .replace(Regex("\n[ \t]+"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

// ─────────────────────────────────────────────────────────────────────
// Player page: gestures, controls, caption, action rail
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun BitzVideoPage(
    note: FeedNote,
    state: FeedUiState,
    actions: LocalActions,
    pool: VideoPlayerPool,
    player: androidx.media3.exoplayer.ExoPlayer?,
    isSettled: Boolean,
    muted: Boolean,
    sensitiveShowByDefault: Boolean,
    revealed: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    /** Left/right swipe on the media: `true` = leftward (advance). */
    onHorizontalSwipe: (Boolean) -> Unit = {},
    onToggleMute: () -> Unit,
    onLike: (FeedNote) -> Unit,
    onBookmark: (String) -> Unit,
    onComment: (FeedNote) -> Unit,
    onRepost: (FeedNote) -> Unit,
    onFollow: (String) -> Unit,
    onZap: (FeedNote) -> Unit,
    onRemix: (FeedNote) -> Unit,
    onChain: (FeedNote) -> Unit,
    onAuthor: (String) -> Unit,
    isMuted: Boolean,
    onMuteToggle: () -> Unit,
    onReport: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val npub = remember(note.pubkey) { NostrKeyCodec.npub(note.pubkey) ?: note.pubkey }
    var likeBurst by remember { mutableStateOf(false) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    /** Double-tap thirds + long-press 2× state (legacy parity). */
    var surfaceWidthPx by remember { mutableStateOf(0) }
    var fastForward by remember { mutableStateOf(false) }
    var positionMs by remember(note.id) { mutableStateOf(0L) }
    /** External-link confirm sheet (never opens the browser unattended). */
    var externalLink by remember { mutableStateOf<String?>(null) }
    var durationMs by remember(note.id) { mutableStateOf(0L) }
    // A PlayerView has no aspect-ratio constraint until Media3 reports the
    // source dimensions. Keep its first draw behind the fit poster so a
    // landscape file cannot flash as `cover` during that short interval.
    var videoSizeKnown by remember(note.id, player) { mutableStateOf(false) }
    val covered = note.contentWarning && !sensitiveShowByDefault && revealed[note.id] != true

    if (isSettled) {
        LaunchedEffect(note.id) {
            while (true) {
                positionMs = pool.positionMs(note.id)
                durationMs = pool.durationMs(note.id)
                delay(500)
            }
        }
    }
    LaunchedEffect(likeBurst) {
        if (likeBurst) {
            delay(700)
            likeBurst = false
        }
    }
    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(800)
            seekHint = null
        }
    }
    DisposableEffect(note.id, player) {
        val activePlayer = player ?: return@DisposableEffect onDispose {}
        fun hasVideoSize() = activePlayer.videoSize.let { it.width > 0 && it.height > 0 }
        videoSizeKnown = hasVideoSize()
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoSizeKnown = videoSize.width > 0 && videoSize.height > 0
            }
        }
        activePlayer.addListener(listener)
        onDispose { activePlayer.removeListener(listener) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The native layer stays behind a single fit poster until it knows
        // the source size. Sharing ONE poster avoids the extra loader's black
        // first frame and makes video entry feel like a normal crossfade.
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { view -> view.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        AnimatedVisibility(
            visible = !videoSizeKnown,
            exit = fadeOut(animationSpec = tween(120)),
        ) {
            PosterImage(
                url = note.video!!.posterUrl,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                showLoadingProgress = true,
            )
        }
        if (!covered) {
            // Gestures (legacy Flutter parity): tap pause/play; double-tap by
            // thirds — left −10 s, center like, right +10 s; long-press = 2×;
            // horizontal swipe cycles the modes (final left swipe on
            // For you opens the creator profile — TikTok parity).
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { surfaceWidthPx = it.width }
                    .pointerInput(note.id) {
                        detectTapGestures(
                            onTap = { pool.togglePlay(note.id) },
                            onDoubleTap = { offset ->
                                val width = surfaceWidthPx
                                when {
                                    width <= 0 -> {
                                        onLike(note)
                                        likeBurst = true
                                    }
                                    offset.x < width / 3f -> {
                                        pool.seekBy(note.id, -10_000)
                                        seekHint = "10 seconds back"
                                    }
                                    offset.x > width * 2f / 3f -> {
                                        pool.seekBy(note.id, 10_000)
                                        seekHint = "10 seconds forward"
                                    }
                                    else -> {
                                        onLike(note)
                                        likeBurst = true
                                    }
                                }
                            },
                            onLongPress = {
                                fastForward = true
                                pool.setRateOverride(note.id, 2f)
                            },
                            onPress = {
                                awaitRelease()
                                if (fastForward) {
                                    fastForward = false
                                    pool.setRateOverride(note.id, null)
                                }
                            },
                        )
                    }
                    .pointerInput(note.id) {
                        val swipeThreshold = 72.dp.toPx()
                        var drag = 0f
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                drag += amount
                            },
                            onDragEnd = {
                                if (drag <= -swipeThreshold) onHorizontalSwipe(true)
                                else if (drag >= swipeThreshold) onHorizontalSwipe(false)
                                drag = 0f
                            },
                            onDragCancel = { drag = 0f },
                        )
                    },
            )
        }
        // Long-press fast-forward indicator (legacy 2× pill).
        if (fastForward) {
            Surface(
                shape = RoundedCornerShape(50),
                color = BitOSColors.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = BitOSSpacing.xl, end = BitOSSpacing.base),
            ) {
                Text(
                    "2×",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.W800,
                    color = Color(0xFF0A0A0F),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
        // Double-tap heart burst.
        AnimatedVisibility(
            visible = likeBurst,
            enter = scaleIn() + fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Icon(AppIcons.Heart, contentDescription = null, tint = BitOSColors.like, modifier = Modifier.size(96.dp))
        }
        // Seek-hint overlay for the ±10 s pills.
        seekHint?.let { hint ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xB3000000),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        if (covered) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                // Full-viewport blur ensures the underlying media is never
                // legible at the edges of the sensitive-content decision.
                PosterImage(
                    url = note.video!!.posterUrl,
                    contentScale = ContentScale.Crop,
                    backgroundColor = Color.Black,
                    modifier = Modifier.fillMaxSize().blur(32.dp),
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)))
                BitzSensitiveGlassGate(onReveal = { revealed[note.id] = true })
            }
        }
        BitzCaption(
            note = note,
            // Narrow slices: tally-only publishes must not repaint the
            // caption (profile/following content-equal → caption skips).
            profiles = state.profiles,
            following = state.following,
            onFollow = onFollow,
            onAuthor = onAuthor,
            onOpenExternalLink = { externalLink = it },
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 48.dp),
        )
        BitzActionRail(
            note = note,
            state = state,
            actions = actions,
            npub = npub,
            context = context,
            clipboard = clipboard,
            onLike = onLike,
            onBookmark = onBookmark,
            onComment = onComment,
            onRepost = onRepost,
            onZap = onZap,
            onRemix = onRemix,
            onChain = onChain,
            isMuted = isMuted,
            onMuteToggle = onMuteToggle,
            onReport = onReport,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
        VideoControlsRow(
            positionMs = positionMs,
            durationMs = durationMs,
            muted = muted,
            onToggleMute = onToggleMute,
            onBack10 = {
                pool.seekBy(note.id, -10_000)
                seekHint = "10 seconds back"
            },
            onForward10 = {
                pool.seekBy(note.id, 10_000)
                seekHint = "10 seconds forward"
            },
            onScrub = { absolute ->
                pool.seekTo(note.id, absolute)
                positionMs = absolute
            },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        )
    }

    // External-link confirm: the browser only opens on an explicit Open.
    externalLink?.let { url ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { externalLink = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = BitOSSpacing.base).padding(bottom = BitOSSpacing.lg)) {
                Text("Open external link?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W700, color = Color.White)
                Spacer(Modifier.height(BitOSSpacing.sm))
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = Color(0xB3F8F8FF),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text("This link leaves BitOS.", style = MaterialTheme.typography.labelSmall, color = Color(0x80F8F8FF))
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
                    ) { Text("Open", fontWeight = FontWeight.W600) }
                    androidx.compose.material3.OutlinedButton(onClick = { externalLink = null }) {
                        Text("Cancel", color = BitOSColors.primary)
                    }
                }
            }
        }
    }
}

/** Full-height Bitz safety gate. Unlike a feed-card cover, this is a clear
 * decision surface over the entire player viewport. */
@Composable
private fun BitzSensitiveGlassGate(onReveal: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.78f)
            .heightIn(min = 236.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(BitOSColors.surfaceElevated.copy(alpha = 0.82f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(28.dp))
            .padding(horizontal = 28.dp, vertical = 30.dp)
            .semantics { contentDescription = "Sensitive video hidden. Show video button." },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SolarFeedIconImage(
            icon = SolarFeedIcon.EyeClosed,
            contentDescription = null,
            tint = BitOSColors.textSecondary,
            modifier = Modifier.size(44.dp),
        )
        Text(
            "Sensitive video",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.W800,
            color = Color.White,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "This video may contain sensitive content. It will not play until you choose to show it.",
            style = MaterialTheme.typography.bodyMedium,
            color = BitOSColors.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
        Button(onClick = onReveal, modifier = Modifier.padding(top = 22.dp)) {
            Text("Show video")
        }
    }
}

/** Compact bottom controls: mute memory, ±10 s pills, scrubber (spec §3.7). */
@Composable
private fun VideoControlsRow(
    positionMs: Long,
    durationMs: Long,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onBack10: () -> Unit,
    onForward10: () -> Unit,
    onScrub: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(
        // Clean chrome (user decision 2026-08-29): no black strip behind
        // the compact controls.
        modifier = modifier.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleMute, modifier = Modifier.size(32.dp)) {
            Icon(
                if (muted) AppIcons.Mute else AppIcons.SoundOn,
                contentDescription = if (muted) "Unmute video" else "Mute video",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onBack10, modifier = Modifier.size(32.dp)) {
            Icon(AppIcons.Back10, contentDescription = "Seek 10 seconds back", tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Slider(
            value = fraction,
            onValueChange = { fraction ->
                if (durationMs > 0) onScrub((fraction * durationMs).toLong())
            },
            enabled = durationMs > 0,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = BitOSColors.primary,
                activeTrackColor = BitOSColors.primary,
                inactiveTrackColor = Color(0x66FFFFFF),
            ),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp)
                .height(24.dp)
                .semantics { contentDescription = "Video position" },
        )
        IconButton(onClick = onForward10, modifier = Modifier.size(32.dp)) {
            Icon(AppIcons.Forward10, contentDescription = "Seek 10 seconds forward", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun BitzCaption(
    note: FeedNote,
    profiles: Map<String, ProfileMetadata>,
    following: Set<String>,
    onFollow: (String) -> Unit,
    onAuthor: (String) -> Unit,
    /** External-link tap → confirm sheet (owned by the screen). */
    onOpenExternalLink: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val profile = profiles[note.pubkey]
    Column(
        // Clean chrome (user decision 2026-08-29): no black scrim behind
        // the caption — the text stands on the media directly.
        modifier = modifier.padding(horizontal = BitOSSpacing.base, vertical = BitOSSpacing.lg),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClickLabel = "View author profile") { onAuthor(note.pubkey) },
        ) {
            PubkeyAvatar(pubkey = note.pubkey, size = 36, pictureUrl = profile?.picture, label = profile?.bestDisplayName, hasLightning = !profile?.lud16.isNullOrBlank())
            Spacer(Modifier.width(BitOSSpacing.sm))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile?.bestDisplayName ?: shortPubkey(note.pubkey),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!profile?.nip05.isNullOrBlank()) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = "NIP-05 identity claim",
                            tint = BitOSColors.primary,
                            modifier = Modifier.padding(start = 4.dp).size(14.dp),
                        )
                    }
                }
                Text(
                    formatTimeAgo(note.createdAt, System.currentTimeMillis() / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xB3F8F8FF),
                )
            }
            Spacer(Modifier.width(BitOSSpacing.sm))
            BitzFollowChip(isFollowing = note.pubkey in following, onToggle = { onFollow(note.pubkey) })
        }
        Spacer(Modifier.height(BitOSSpacing.sm))
        note.repostedBy?.let { _ ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SolarFeedIconImage(SolarFeedIcon.Repost, contentDescription = null, tint = BitOSColors.repost, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reposted", style = MaterialTheme.typography.labelSmall, color = BitOSColors.repost)
            }
            Spacer(Modifier.height(2.dp))
        }
        // Rich body: NIP-27 entities + external links tappable (white);
        // bare media links render as tiles and disappear from the body.
        space.bitos.app.ui.components.RichText(
            tokens = remember(note.content) { space.bitos.core.nostr.Nip27.tokenize(note.content) },
            color = Color.White,
            maxLines = 3,
            hiddenMediaUrls = remember(note.mediaUrls) { note.mediaUrls.toSet() },
            onOpenProfile = onAuthor,
            onOpenExternalLink = onOpenExternalLink,
            modifier = Modifier.fillMaxWidth(),
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
private fun BitzFollowChip(isFollowing: Boolean, onToggle: () -> Unit) {
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
private fun BitzActionRail(
    note: FeedNote,
    state: FeedUiState,
    actions: LocalActions,
    npub: String,
    context: android.content.Context,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    onLike: (FeedNote) -> Unit,
    onBookmark: (String) -> Unit,
    onComment: (FeedNote) -> Unit,
    onRepost: (FeedNote) -> Unit,
    onZap: (FeedNote) -> Unit,
    onRemix: (FeedNote) -> Unit,
    onChain: (FeedNote) -> Unit,
    isMuted: Boolean,
    onMuteToggle: () -> Unit,
    onReport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLiked = note.id in actions.liked
    val isBookmarked = note.id in state.bookmarkedIds || note.id in actions.bookmarked
    // Legacy parity: counts replace the label when > 0 (K/M + zap sats).
    val tally = state.tallies[note.id]
    val likeCount = (tally?.reactions ?: 0) + if (isLiked && (tally?.reactions ?: 0) == 0) 1 else 0
    val commentCount = state.comments[note.id]?.size ?: 0
    val zapSats = space.bitos.core.feed.BitzFormat.sats(tally?.zapMillisats ?: 0)
        ?: state.zapCounts[note.id]?.takeIf { it > 0 }?.let { space.bitos.core.feed.BitzFormat.count(it.toLong()) }
    Column(
        modifier = modifier.padding(end = BitOSSpacing.base),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // User decision 2026-08-29: action order [like · comment · repost ·
        // zap · bookmark], after Remix/Chain; Share lives in the ⋯ sheet.
        BitzRailButton(AppIcons.Sparkles, "Remix", Color(0xFF8B5CF6)) { onRemix(note) }
        // Chain: only when this note declares a remix source (web parity).
        if (note.remixOfEventId != null) {
            BitzRailButton(AppIcons.AppsGrid, "Chain", Color.White) { onChain(note) }
        }
        BitzRailButton(
            icon = if (isLiked) SolarFeedIcon.HeartFilled else SolarFeedIcon.Heart,
            label = if (likeCount > 0) space.bitos.core.feed.BitzFormat.count(likeCount.toLong()) else if (isLiked) "Unlike" else "Like",
            tint = if (isLiked) BitOSColors.like else Color.White,
        ) { onLike(note) }
        BitzRailButton(
            SolarFeedIcon.Comment,
            if (commentCount > 0) space.bitos.core.feed.BitzFormat.count(commentCount.toLong()) else "Comments",
            Color.White,
        ) { onComment(note) }
        BitzRailButton(
            SolarFeedIcon.Repost,
            (tally?.reposts ?: 0).takeIf { it > 0 }?.let { space.bitos.core.feed.BitzFormat.count(it.toLong()) } ?: "Repost",
            Color.White,
        ) { onRepost(note) }
        BitzRailButton(SolarFeedIcon.Zap, zapSats ?: "Zap", BitOSColors.zap) { onZap(note) }
        BitzRailButton(
            icon = if (isBookmarked) SolarFeedIcon.BookmarkFilled else SolarFeedIcon.Bookmark,
            label = if (isBookmarked) "Saved" else "Save",
            tint = if (isBookmarked) BitOSColors.bookmark else Color.White,
        ) { onBookmark(note.id) }
        BitzMoreMenu(
            note = note,
            npub = npub,
            context = context,
            isMuted = isMuted,
            isSaved = isBookmarked,
            clipboard = clipboard,
            onMuteToggle = onMuteToggle,
            onReport = onReport,
            onSaveToggle = { onBookmark(note.id) },
        )
    }
}

/**
 * ⋯ overflow → bottom sheet (legacy parity: web item set in the Flutter
 * sheet chrome). Icon-only rail trigger — no label under the ⋯.
 */
@Composable
private fun BitzMoreMenu(
    note: FeedNote,
    npub: String,
    context: android.content.Context,
    isMuted: Boolean,
    isSaved: Boolean,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    onMuteToggle: () -> Unit,
    onReport: (String) -> Unit,
    onSaveToggle: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    BitzRailIconButton(SolarFeedIcon.More, "More options", Color.White) { showSheet = true }
    if (showSheet) {
        space.bitos.app.ui.components.AppBottomSheetMenu(
            onDismissRequest = { showSheet = false },
            title = "Bitz actions",
            entries = listOf(
                AppMenuEntry.Item(AppMenuItem("share", "Share", icon = AppIcons.Share)),
                AppMenuEntry.Item(
                    AppMenuItem("save", if (isSaved) "Unsave bitz" else "Save bitz", icon = AppIcons.Bookmark),
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
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, NoteShare.text(note.content, npub))
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }
                    "save" -> onSaveToggle()
                    "copy-id" -> clipboard.setText(AnnotatedString(note.id))
                    "copy-text" -> clipboard.setText(AnnotatedString(note.content))
                    "copy-npub" -> clipboard.setText(AnnotatedString(npub))
                    "mute" -> onMuteToggle()
                    "report-spam" -> onReport("spam")
                    "report-illicit" -> onReport("illicit")
                    "report-harassment" -> onReport("harassment")
                }
            },
        )
    }
}

/** Icon-only ⋯ trigger (legacy rail: no label under the more button). */
@Composable
private fun BitzRailIconButton(icon: SolarFeedIcon, label: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        SolarFeedIconImage(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun BitzRailButton(icon: SolarFeedIcon, label: String, tint: Color, onClick: () -> Unit) {
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

@Composable
private fun BitzRailButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0x33000000),
            modifier = Modifier.clickable(onClickLabel = label) { onClick() },
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(10.dp).size(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Search overlay (spec §3.7: instant local + debounced NIP-50, deduped)
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun BitzSearchOverlay(
    state: FeedUiState,
    searchRepository: SearchRepository,
    sensitiveShowByDefault: Boolean,
    onOpenNote: (FeedNote) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val searchState by searchRepository.state.collectAsStateWithLifecycle()
    // Fire on every keystroke; the repository applies the shared 400 ms
    // relay debounce (BitzSearch.RELAY_DEBOUNCE_MS parity). Bitz searches
    // the standard media kinds only (Bitz discovery/query standard).
    LaunchedEffect(query) { searchRepository.search(query, SearchScope.BITZ_MEDIA) }

    val localEntries = remember(state.notes, state.profiles) {
        state.notes
            .filter { it.video != null }
            .map { BitzSearch.Entry(it.id, it.content, state.profiles[it.pubkey]?.bestDisplayName ?: "") }
    }
    val relayEntries = remember(searchState.results, searchState.profiles) {
        searchState.results
            .filter { it.video != null }
            .map { BitzSearch.Entry(it.id, it.content, searchState.profiles[it.pubkey]?.bestDisplayName ?: "") }
    }
    val merged = remember(query, localEntries, relayEntries) {
        BitzSearch.results(query, localEntries, relayEntries)
    }
    val noteById = remember(state.notes, searchState.results) {
        (state.notes + searchState.results).associateBy { it.id }
    }
    val overlayRevealed = remember { mutableStateMapOf<String, Boolean>() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xF20A0A0F)).systemBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(end = BitOSSpacing.sm, top = BitOSSpacing.sm),
                ) {
                    // Shared brand field (iOS BitosSearchField parity):
                    // compact = the 48 dp medium ladder (§2.6); the clear
                    // glyph shrinks below the 48 dp minimum so it fits the
                    // medium row (the field itself is the touch target).
                    space.bitos.app.ui.components.BitosTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Search Bitz",
                        leadingIcon = {
                            Icon(
                                AppIcons.Search,
                                contentDescription = null,
                                tint = BitOSColors.textSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                androidx.compose.runtime.CompositionLocalProvider(
                                    androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement provides false,
                                ) {
                                    IconButton(onClick = { query = "" }, modifier = Modifier.size(28.dp)) {
                                        Icon(AppIcons.Close, contentDescription = "Clear search", tint = BitOSColors.textTertiary)
                                    }
                                }
                            }
                        },
                        singleLine = true,
                        compact = true,
                        modifier = Modifier.weight(1f).padding(start = BitOSSpacing.base),
                    )
                    TextButton(onClick = onDismiss) { Text("Cancel", color = BitOSColors.primary) }
                }
                when {
                    query.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Search captions and creators",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BitOSColors.textTertiary,
                        )
                    }
                    merged.isEmpty() && searchState.isSearching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BitOSColors.primary, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    }
                    merged.isEmpty() && searchState.hasSearched -> Box(Modifier.fillMaxSize().padding(BitOSSpacing.xxl), contentAlignment = Alignment.Center) {
                        Text(
                            "No videos match \"${query.trim()}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BitOSColors.textSecondary,
                        )
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(merged, key = { _, entry -> entry.id }) { _, entry ->
                            val note = noteById[entry.id] ?: return@itemsIndexed
                            BitzTile(
                                note = note,
                                state = state,
                                sensitiveShowByDefault = sensitiveShowByDefault,
                                revealed = overlayRevealed,
                                onOpen = {
                                    onDismiss()
                                    onOpenNote(note)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// States
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun BitzLoading() {
    // UX U2: skeleton tiles in the explore-grid shape while the first relay
    // page loads (§2.5 parity with the iOS skeleton grid).
    Column(
        Modifier.fillMaxSize().padding(top = 76.dp, start = 10.dp, end = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { AppSkeletonTile(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun BitzMessage(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize().padding(BitOSSpacing.xxl), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Color(0xB3F8F8FF), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (actionLabel != null && onAction != null) {
                androidx.compose.material3.Button(
                    onClick = onAction,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = BitOSColors.primary,
                        contentColor = Color(0xFF0A0A0F),
                    ),
                ) {
                    Text(actionLabel, fontWeight = FontWeight.W600)
                }
            }
            if (secondaryLabel != null && onSecondary != null) {
                androidx.compose.material3.OutlinedButton(onClick = onSecondary) {
                    Text(secondaryLabel, color = BitOSColors.primary, fontWeight = FontWeight.W600)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Chain sheet (APP-007, web RemixChainDialog parity)
// ─────────────────────────────────────────────────────────────────────

/** Rows paged 8-per-"Show more" like the legacy dialog. */
private const val CHAIN_PAGE_SIZE = 8

@Composable
private fun BitzChainContent(
    rootId: String,
    state: FeedUiState,
    chainState: space.bitos.app.ui.feed.RemixChainUiState,
    onOpenAncestor: (String) -> Unit,
    onClose: () -> Unit,
) {
    var visibleRows by rememberSaveable(rootId) { mutableStateOf(CHAIN_PAGE_SIZE) }
    Column(Modifier.fillMaxWidth().padding(BitOSSpacing.base)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (chainState.outcome is space.bitos.core.feed.RemixChain.Outcome.Completed) {
                    "${chainState.outcome.steps.size} remix source${if (chainState.outcome.steps.size == 1) "" else "s"} traced"
                } else {
                    "Remix chain"
                },
                style = MaterialTheme.typography.titleMedium,
                color = BitOSColors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            space.bitos.app.ui.components.SheetCloseIcon(onClose = onClose)
        }
        when {
            chainState.isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = BitOSSpacing.xxl), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                    CircularProgressIndicator(color = BitOSColors.primary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    Text("Tracing the chain…", style = MaterialTheme.typography.bodySmall, color = BitOSColors.textSecondary)
                }
            }
            chainState.outcome == space.bitos.core.feed.RemixChain.Outcome.Cycle -> Text(
                "Couldn't read the full chain — the lineage loops or a relay failed.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
                modifier = Modifier.padding(vertical = BitOSSpacing.md),
            )
            else -> {
                val completed = chainState.outcome as space.bitos.core.feed.RemixChain.Outcome.Completed
                if (completed.steps.isEmpty()) {
                    Text(
                        "No remix ancestry found on your relays.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BitOSColors.textSecondary,
                        modifier = Modifier.padding(vertical = BitOSSpacing.md),
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm), modifier = Modifier.heightIn(max = 420.dp)) {
                        items(completed.steps.take(visibleRows), key = { it.eventId }) { step ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(onClickLabel = "Open remix source") { onOpenAncestor(step.eventId) }
                                    .padding(horizontal = BitOSSpacing.sm, vertical = 6.dp),
                            ) {
                                val profile = step.pubkey?.let { state.profiles[it] }
                                PubkeyAvatar(
                                    pubkey = step.pubkey ?: "",
                                    size = 32,
                                    pictureUrl = profile?.picture,
                                    label = profile?.bestDisplayName,
                                    hasLightning = !profile?.lud16.isNullOrBlank(),
                                )
                                Spacer(Modifier.width(BitOSSpacing.md))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        profile?.bestDisplayName ?: (step.pubkey?.let { shortPubkey(it) } ?: "Unknown author"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = BitOSColors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        when (step.depth) {
                                            0 -> "Direct source"
                                            else -> "${step.depth} steps back"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = BitOSColors.textTertiary,
                                    )
                                }
                                if (step.depth == 0) {
                                    Surface(shape = RoundedCornerShape(8.dp), color = BitOSColors.primaryContainer) {
                                        Text(
                                            "Source",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = BitOSColors.primary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                        if (visibleRows < completed.steps.size) {
                            item(key = "chain-more") {
                                androidx.compose.material3.TextButton(
                                    onClick = { visibleRows += CHAIN_PAGE_SIZE },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "Show more (${completed.steps.size - visibleRows} older)",
                                        color = BitOSColors.primary,
                                    )
                                }
                            }
                        }
                        if (completed.truncated) {
                            item(key = "chain-truncated") {
                                Text(
                                    "Chain longer than ${space.bitos.core.feed.RemixChain.MAX_DEPTH} — oldest steps hidden.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BitOSColors.textTertiary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
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
