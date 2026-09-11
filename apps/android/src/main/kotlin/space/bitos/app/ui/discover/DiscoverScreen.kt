@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package space.bitos.app.ui.discover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import space.bitos.app.data.feed.SearchUiState
import space.bitos.app.data.feed.SearchScope
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.components.formatTimeAgo
import space.bitos.app.ui.components.shortPubkey
import space.bitos.app.ui.feed.PosterImage
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.feed.BitzFormat
import space.bitos.core.feed.FeedNote
import space.bitos.core.feed.NoteTally
import space.bitos.core.feed.SearchResults
import space.bitos.core.model.ProfileMetadata

/** Recent-searches memory bounds (prototype `S.recentSearches` parity). */
private const val RECENT_SEARCH_MAX = 6
private const val RECENT_SEARCH_MIN_CHARS = 3

/** Settle window before a typed query is remembered as a recent search. */
private const val RECENT_SEARCH_SETTLE_MS = 600L

/** Trending/creator rails are previews, not archives (bounded windows). */
private const val TRENDING_MAX = 8
private const val CREATORS_MAX = 8

/** Prototype mosaic is a 3×3 preview of the shared Bitz window. */
private const val MOSAIC_COLUMNS = 3
private const val MOSAIC_TILES = 9

/**
 * Discover surface (SOC-004): NIP-50 relay search with npub creator
 * resolution. The idle page follows the prototype `#/discover` home
 * (docs/ui/prototype/js/ui-discover.js) — recent searches, Trending on
 * Nostr, Creators to follow and the Explore Bitz mosaic, all derived from
 * the live shared feed window; query results render as the tabbed fan-in
 * (APP-010) with Posts reusing the home `FeedNoteCard`.
 */
@Composable
fun DiscoverScreen(
    search: space.bitos.app.data.feed.SearchRepository,
    homeViewModel: space.bitos.app.ui.feed.HomeViewModel? = null,
    identityViewModel: space.bitos.app.identity.IdentityViewModel? = null,
    notePublisher: space.bitos.app.data.publish.NotePublisher? = null,
    /** UX-010: enables author taps (profile sheet + full profile page). */
    authorRepository: space.bitos.app.data.feed.AuthorRepository? = null,
    onOpenAuthorProfile: (String) -> Unit = {},
    /** Result cards are the shared home card: sensitive-media default. */
    sensitiveShowByDefault: Boolean = false,
    /** Zap preset for result-card zaps (settings default). */
    defaultZapSats: Int = 21,
    /** External-link tap → shell confirm host (never opens unattended). */
    onOpenExternalLink: (String) -> Unit = {},
    /** Prototype mosaic parity: Explore Bitz tiles open the shared player. */
    onOpenBitzPlayer: (authorPubkey: String, noteId: String) -> Unit = { _, _ -> },
) {
    val state by search.state.collectAsStateWithLifecycle()
    val homeState = if (homeViewModel != null) {
        homeViewModel.state.collectAsStateWithLifecycle().value
    } else {
        space.bitos.app.data.feed.FeedUiState()
    }
    var input by remember { mutableStateOf("") }
    // UX-010: creator/result author taps open the profile sheet.
    var authorTarget by remember { mutableStateOf<String?>(null) }
    var recentSearches by rememberSaveable { mutableStateOf(listOf<String>()) }

    LaunchedEffect(input) {
        search.search(input)
        // Recent-searches memory: the effect restarts per keystroke, so the
        // settle delay only fires once typing pauses — prefixes never land
        // in the chip row (prototype stores the typed query, capped at 6).
        if (input.length >= RECENT_SEARCH_MIN_CHARS) {
            delay(RECENT_SEARCH_SETTLE_MS)
            recentSearches = (listOf(input) + recentSearches.filter { q -> q != input }).take(RECENT_SEARCH_MAX)
        }
    }

    Column(Modifier.fillMaxSize().background(BitOSColors.background)) {
        // Compact = the design system's 48 dp medium ladder (§2.6) — the
        // 56 dp default read as an oversized search bar. The clear glyph
        // shrinks below the 48 dp minimum to fit the medium row.
        space.bitos.app.ui.components.BitosTextField(
            value = input,
            onValueChange = { if (it.length <= 200) input = it },
            placeholder = "Search notes, #hashtags, npub…",
            leadingIcon = {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = BitOSColors.textTertiary,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailingIcon = {
                if (input.isNotEmpty()) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement provides false,
                    ) {
                        IconButton(onClick = { input = "" }, modifier = Modifier.size(28.dp)) {
                            Icon(AppIcons.Close, contentDescription = "Clear search", tint = BitOSColors.textTertiary)
                        }
                    }
                }
            },
            singleLine = true,
            compact = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.md),
        )

        if (input.isBlank()) {
            DiscoverHome(
                homeViewModel = homeViewModel,
                sensitiveShowByDefault = sensitiveShowByDefault,
                recentSearches = recentSearches,
                onRecentPick = { input = it },
                onRecentClear = { recentSearches = emptyList() },
                onTopic = { topic -> input = "#$topic" },
                onOpenAuthor = { authorTarget = it },
                onOpenBitz = onOpenBitzPlayer,
            )
        } else {
            // Search the normal feed cache immediately. SearchRepository
            // additionally filters newly arriving verified relay events.
            val cachedMatches = remember(input, homeState.notes) {
                homeState.notes.filter { note ->
                    note.kind in SearchScope.GENERAL.kinds && SearchResults.matches(note, input)
                }
            }
            val visibleResults = remember(state.results, cachedMatches) {
                (state.results + cachedMatches).distinctBy { it.id }.sortedByDescending { it.createdAt }
            }
            SearchResults(
                state = state.copy(
                    results = visibleResults,
                    profiles = homeState.profiles + state.profiles,
                ),
                homeViewModel = homeViewModel,
                identityViewModel = identityViewModel,
                notePublisher = notePublisher,
                onOpenAuthor = { authorTarget = it },
                onPickHashtag = { tag -> input = "#$tag" },
                sensitiveShowByDefault = sensitiveShowByDefault,
                defaultZapSats = defaultZapSats,
                onOpenExternalLink = onOpenExternalLink,
            )
        }
    }

    // UX-010: author profile sheet (sheet → "View full profile" → in-app page).
    val authorPubkey = authorTarget
    if (authorPubkey != null && authorRepository != null && homeViewModel != null && identityViewModel != null && notePublisher != null) {
        val authorState by authorRepository.state.collectAsStateWithLifecycle()
        val feedState by homeViewModel.state.collectAsStateWithLifecycle()
        space.bitos.app.ui.profile.AuthorProfileSheetHost(
            authorPubkey = authorPubkey,
            state = authorState,
            feedState = feedState,
            homeViewModel = homeViewModel,
            identityViewModel = identityViewModel,
            notePublisher = notePublisher,
            onOpen = authorRepository::open,
            onClose = { authorRepository.close(); authorTarget = null },
            onOpenFullProfile = { authorRepository.close(); authorTarget = null; onOpenAuthorProfile(it) },
            onLoadMore = authorRepository::loadMoreNotes,
        )
    }
}

// ---------------------------------------------------------------------
// Idle page (prototype `#/discover` home parity)
// ---------------------------------------------------------------------

/**
 * Prototype Discover home: recent-search chips, the Trending on Nostr
 * card, a Creators-to-follow rail and the Explore Bitz mosaic. Sections
 * derive from the shared feed window through the APP-010 fan-in rules and
 * hide while empty (partial-data honesty, ux-ui-flows §3) — no seed data.
 */
@Composable
private fun DiscoverHome(
    homeViewModel: space.bitos.app.ui.feed.HomeViewModel?,
    sensitiveShowByDefault: Boolean,
    recentSearches: List<String>,
    onRecentPick: (String) -> Unit,
    onRecentClear: () -> Unit,
    onTopic: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onOpenBitz: (authorPubkey: String, noteId: String) -> Unit,
) {
    val homeState = if (homeViewModel != null) {
        homeViewModel.state.collectAsStateWithLifecycle().value
    } else {
        space.bitos.app.data.feed.FeedUiState()
    }
    // Trending = the shared hashtag fan-in over the live window (the same
    // rule that ranks the Hashtags tab), bounded to a preview rail.
    val trending = remember(homeState.notes) {
        SearchResults.hashtags(homeState.notes).take(TRENDING_MAX)
    }
    // Creators = the shared people fan-in minus me and accounts already
    // followed — the rail only suggests new voices.
    val creators = remember(homeState.notes, homeState.profiles, homeState.following, homeState.accountPubkey) {
        SearchResults.people(homeState.notes, homeState.profiles)
            .filter { it.pubkey != homeState.accountPubkey && it.pubkey !in homeState.following }
            .take(CREATORS_MAX)
    }
    // Explore Bitz = the standard media kinds (NIP-68/71) already riding
    // the shared Home+Bitz window; sensitive tiles stay out of previews.
    val bitzTiles = remember(homeState.notes, sensitiveShowByDefault) {
        homeState.notes
            .filter { it.video != null && (sensitiveShowByDefault || !it.contentWarning) }
            .take(MOSAIC_TILES)
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = BitOSSpacing.screen,
            end = BitOSSpacing.screen,
            bottom = BitOSSpacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        if (recentSearches.isNotEmpty()) {
            item(key = "recent") {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SectionHeader("Recent searches", Modifier.weight(1f))
                        TextButton(
                            onClick = onRecentClear,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(
                                "Clear",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.W700,
                                color = BitOSColors.error,
                            )
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm)) {
                        items(recentSearches) { query ->
                            RecentChip(query = query, onPick = { onRecentPick(query) })
                        }
                    }
                }
            }
        }
        if (trending.isNotEmpty()) {
            item(key = "trending") {
                Column {
                    SectionHeader("Trending on Nostr")
                    TrendingCard(hits = trending, onTopic = onTopic)
                }
            }
        }
        if (creators.isNotEmpty()) {
            item(key = "creators") {
                Column {
                    SectionHeader("Creators to follow")
                    CreatorsRail(
                        people = creators,
                        following = homeState.following,
                        canFollow = homeViewModel != null,
                        onToggleFollow = { homeViewModel?.toggleFollow(it) },
                        onOpenAuthor = onOpenAuthor,
                    )
                }
            }
        }
        if (bitzTiles.isNotEmpty()) {
            item(key = "bitz") {
                Column {
                    SectionHeader("Explore Bitz")
                    BitzMosaic(
                        tiles = bitzTiles,
                        tallies = homeState.tallies,
                        onOpen = onOpenBitz,
                    )
                }
            }
        }
        if (trending.isEmpty() && creators.isEmpty() && bitzTiles.isEmpty()) {
            item(key = "connecting") {
                if (homeState.hasLoadedAnyEvent) {
                    DiscoverFootnote()
                } else {
                    Box(Modifier.fillMaxWidth().padding(BitOSSpacing.xl), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = BitOSColors.primary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.height(BitOSSpacing.sm))
                            Text(
                                "Connecting to relays…",
                                style = MaterialTheme.typography.bodySmall,
                                color = BitOSColors.textSecondary,
                            )
                        }
                    }
                }
            }
        } else {
            item(key = "footnote") { DiscoverFootnote() }
        }
    }
}

/** Prototype `.field-label`: 11 sp w800 uppercase wide-tracked dim caption. */
@Composable
private fun SectionHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.W800,
        letterSpacing = 0.8.sp,
        color = BitOSColors.textTertiary,
        modifier = modifier.padding(top = BitOSSpacing.base, bottom = 6.dp),
    )
}

@Composable
private fun DiscoverFootnote() {
    Text(
        "Search runs across your relays; text matching may vary by relay.",
        style = MaterialTheme.typography.labelSmall,
        color = BitOSColors.textTertiary,
        modifier = Modifier.padding(top = BitOSSpacing.base),
    )
}

/** Prototype recent-search chip: pill with a search glyph and the query. */
@Composable
private fun RecentChip(query: String, onPick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = BitOSColors.surfaceElevated,
        border = BorderStroke(1.dp, BitOSColors.border),
        modifier = Modifier.clickable(onClickLabel = "Search $query") { onPick() },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                AppIcons.Search,
                contentDescription = null,
                tint = BitOSColors.textTertiary,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                query,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.W600,
                color = BitOSColors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

/**
 * Prototype trending list: ONE card with hairline-divided rows — flame for
 * the hot head of the window, hash for the rest, chevron affordance. A row
 * tap runs the hashtag through the same NIP-50 search pipeline.
 */
@Composable
private fun TrendingCard(hits: List<SearchResults.HashtagHit>, onTopic: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BitOSColors.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            hits.forEachIndexed { index, hit ->
                val hot = index < 3
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Search #${hit.tag}") { onTopic(hit.tag) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    Icon(
                        if (hot) AppIcons.Flame else AppIcons.Hash,
                        contentDescription = null,
                        tint = if (hot) BitOSColors.accent else BitOSColors.textTertiary,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "#${hit.tag}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.W700,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${hit.count} post${if (hit.count == 1) "" else "s"}" +
                                if (hot) " · trending now" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = BitOSColors.textSecondary,
                        )
                    }
                    Icon(
                        AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = BitOSColors.textTertiary,
                        modifier = Modifier.size(15.dp),
                    )
                }
                if (index < hits.lastIndex) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = BitOSColors.divider,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Prototype creator card: fixed-width rail tile (avatar, name, follow chip). */
@Composable
private fun CreatorsRail(
    people: List<SearchResults.PeopleRow>,
    following: Set<String>,
    canFollow: Boolean,
    onToggleFollow: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(people, key = { it.pubkey }) { person ->
            val followed = person.pubkey in following
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = BitOSColors.surface,
                modifier = Modifier
                    .width(150.dp)
                    .clickable(onClickLabel = "Open ${person.displayName}") { onOpenAuthor(person.pubkey) },
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                ) {
                    PubkeyAvatar(
                        pubkey = person.pubkey,
                        size = 54,
                        pictureUrl = person.picture,
                        label = person.displayName,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        person.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.W700,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${person.noteCount} recent note${if (person.noteCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textTertiary,
                    )
                    Spacer(Modifier.height(8.dp))
                    FollowChip(
                        followed = followed,
                        enabled = canFollow,
                        onClick = { onToggleFollow(person.pubkey) },
                    )
                }
            }
        }
    }
}

/** Prototype `.chip.chip-orange` (Follow) vs plain `.chip` (Following). */
@Composable
private fun FollowChip(followed: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (followed) BitOSColors.surfaceElevated else BitOSColors.primary.copy(alpha = 0.14f),
        border = if (followed) {
            BorderStroke(1.dp, BitOSColors.border)
        } else {
            BorderStroke(1.dp, BitOSColors.primary.copy(alpha = 0.4f))
        },
        modifier = if (enabled) {
            Modifier.clickable(onClickLabel = if (followed) "Unfollow" else "Follow") { onClick() }
        } else {
            Modifier
        },
    ) {
        Text(
            if (followed) "Following" else "Follow",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.W700,
            color = if (followed) BitOSColors.textTertiary else BitOSColors.primary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
        )
    }
}

/**
 * Prototype "Explore Bitz" mosaic: a 3-column preview of the shared Bitz
 * window (standard NIP-68/71 media kinds only). Tiles reuse the explore
 * poster loader; the ▶ overlay shows the live reaction tally. Tap opens
 * the shared player in author mode (sensitive gate lives there).
 */
@Composable
private fun BitzMosaic(
    tiles: List<FeedNote>,
    tallies: Map<String, NoteTally>,
    onOpen: (authorPubkey: String, noteId: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tiles.chunked(MOSAIC_COLUMNS).forEach { rowNotes ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                rowNotes.forEach { note ->
                    val likes = tallies[note.id]?.reactions ?: 0
                    Box(
                        Modifier
                            .weight(1f)
                            .height(112.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BitOSColors.surface)
                            .clickable(onClickLabel = "Open Bitz") { onOpen(note.pubkey, note.id) },
                    ) {
                        PosterImage(
                            url = note.video?.posterUrl,
                            modifier = Modifier.fillMaxSize(),
                            showLoadingProgress = true,
                        )
                        if (note.video?.posterUrl == null) {
                            Icon(
                                AppIcons.Play,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.align(Alignment.Center).size(26.dp),
                            )
                        }
                        // Prototype "▶ 12k" overlay — real tally, scrim badge
                        // for contrast on any poster.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(5.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Icon(
                                AppIcons.Play,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(10.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                BitzFormat.count(likes.toLong()),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.W700,
                            )
                        }
                    }
                }
                // Partial last row keeps the grid's column rhythm.
                repeat(MOSAIC_COLUMNS - rowNotes.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Query results (APP-010 tabbed fan-in)
// ---------------------------------------------------------------------

@Composable
private fun SearchResults(
    state: SearchUiState,
    homeViewModel: space.bitos.app.ui.feed.HomeViewModel? = null,
    identityViewModel: space.bitos.app.identity.IdentityViewModel? = null,
    notePublisher: space.bitos.app.data.publish.NotePublisher? = null,
    onOpenAuthor: (String) -> Unit = {},
    /** Hashtag pick re-runs the search pipeline with the #tag query. */
    onPickHashtag: (String) -> Unit = {},
    sensitiveShowByDefault: Boolean = false,
    defaultZapSats: Int = 21,
    onOpenExternalLink: (String) -> Unit = {},
) {
    var threadTarget by remember { mutableStateOf<FeedNote?>(null) }
    // APP-010 results tabs: Posts · People · Hashtags (shared fan-in rule).
    var tab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    val people = remember(state.results, state.profiles) {
        SearchResults.people(state.results, state.profiles)
    }
    val hashtags = remember(state.results) {
        SearchResults.hashtags(state.results)
    }
    if (state.isSearching && state.results.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = BitOSColors.primary, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
        return
    }

    Column {
        // Tab row with live counts.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.screen),
            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
        ) {
            listOf(
                "Posts" to state.results.size,
                "People" to people.size,
                "Hashtags" to hashtags.size,
            ).forEachIndexed { index, (label, count) ->
                TextButton(onClick = { tab = index }) {
                    Text(
                        "$label $count",
                        fontWeight = if (tab == index) FontWeight.W800 else FontWeight.W600,
                        color = if (tab == index) BitOSColors.primary else BitOSColors.textSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        when (tab) {
            1 -> PeopleTab(people = people, homeViewModel = homeViewModel, onOpenAuthor = onOpenAuthor)
            2 -> HashtagsTab(hits = hashtags, onPick = onPickHashtag)
            else -> PostsTab(
                state = state,
                homeViewModel = homeViewModel,
                identityViewModel = identityViewModel,
                notePublisher = notePublisher,
                threadTarget = threadTarget,
                onThreadTarget = { threadTarget = it },
                onOpenAuthor = onOpenAuthor,
                sensitiveShowByDefault = sensitiveShowByDefault,
                defaultZapSats = defaultZapSats,
                onOpenExternalLink = onOpenExternalLink,
            )
        }
    }
}

@Composable
private fun PostsTab(
    state: SearchUiState,
    homeViewModel: space.bitos.app.ui.feed.HomeViewModel?,
    identityViewModel: space.bitos.app.identity.IdentityViewModel?,
    notePublisher: space.bitos.app.data.publish.NotePublisher?,
    threadTarget: FeedNote?,
    onThreadTarget: (FeedNote?) -> Unit,
    onOpenAuthor: (String) -> Unit = {},
    sensitiveShowByDefault: Boolean = false,
    defaultZapSats: Int = 21,
    onOpenExternalLink: (String) -> Unit = {},
) {
    val thread = threadTarget
    if (thread != null && homeViewModel != null && identityViewModel != null && notePublisher != null) {
        val publisherState by notePublisher.state.collectAsStateWithLifecycle()
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { onThreadTarget(null) }) {
            space.bitos.app.ui.feed.CommentThreadSheet(
                note = thread,
                viewModel = homeViewModel,
                identityViewModel = identityViewModel,
                publisherState = publisherState,
                onDismiss = { onThreadTarget(null) },
                onOpenAuthor = onOpenAuthor,
            )
        }
    }

    // Result cards are the shared home feed card: like/comment/repost/zap/
    // bookmark/polls ride the same HomeViewModel pipelines as the timeline
    // (iOS FeedNoteCard parity). Hosts without a feed VM keep the plain card.
    val vm = homeViewModel
    var zapTarget by remember { mutableStateOf<FeedNote?>(null) }
    val homeState = if (vm != null) {
        vm.state.collectAsStateWithLifecycle().value
    } else {
        space.bitos.app.data.feed.FeedUiState()
    }
    val actions = if (vm != null) {
        vm.localActions.collectAsStateWithLifecycle().value
    } else {
        space.bitos.app.ui.feed.LocalActions()
    }
    val canVotePoll = identityViewModel?.state?.value?.account != null

    val zapTargetNote = zapTarget
    if (zapTargetNote != null && vm != null && identityViewModel != null) {
        val zapState by vm.zapState.collectAsStateWithLifecycle()
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { vm.dismissZap(); zapTarget = null }) {
            space.bitos.app.ui.feed.ZapContent(
                note = zapTargetNote,
                lud16 = homeState.profiles[zapTargetNote.pubkey]?.lud16,
                state = zapState,
                profileName = homeState.profiles[zapTargetNote.pubkey]?.bestDisplayName,
                hasIdentity = identityViewModel.state.value.account != null,
                zapCount = homeState.zapCounts[zapTargetNote.id] ?: 0,
                paidRequestIds = homeState.zapRequestIds[zapTargetNote.id] ?: emptySet(),
                onPaid = { sats, memo -> vm.onZapPaid(zapTargetNote, sats, memo) },
                onAmountSelected = vm::selectZapAmount,
                onZap = { sats, comment, anonymous ->
                    vm.selectZapAmount(sats)
                    vm.zap(zapTargetNote, comment, anonymous)
                },
                onClose = { vm.dismissZap(); zapTarget = null },
            )
        }
    }

    // Home parity: full-bleed cards separated by hairline dividers — the
    // same list chrome as the timeline, not floating inset cards.
    LazyColumn(
        contentPadding = PaddingValues(vertical = BitOSSpacing.sm),
    ) {
        // npub hit: creator card at top (inset above the full-bleed rows)
        val creatorProfile = state.resolvedNpub?.let { state.profiles[it] }
        if (state.resolvedNpub != null) {
            item(key = "creator") {
                Box(Modifier.padding(horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.sm)) {
                    CreatorCard(
                        pubkey = state.resolvedNpub!!,
                        profile = creatorProfile,
                        onOpen = { onOpenAuthor(state.resolvedNpub!!) },
                    )
                }
            }
        }
        if (state.results.isEmpty() && !state.isSearching) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(BitOSSpacing.xl), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.hasSearched) "No results. Relays may not support search or the query is too narrow."
                        else "Type to search",
                        style = MaterialTheme.typography.bodySmall,
                        color = BitOSColors.textSecondary,
                    )
                }
            }
        }
        itemsIndexed(
            state.results,
            key = { _, note -> note.id },
            // Stable content type: note rows and the chrome items above are
            // different reuse pools (native-performance.md §6).
            contentType = { _, _ -> "feed_note" },
        ) { index, note ->
            if (vm == null) {
                SearchCard(
                    note = note,
                    profile = state.profiles[note.pubkey],
                    onOpen = {
                        if (identityViewModel != null && notePublisher != null) {
                            onThreadTarget(note)
                        }
                    },
                    onOpenAuthor = { onOpenAuthor(note.pubkey) },
                )
            } else {
                space.bitos.app.ui.components.FeedNoteCard(
                    note = note,
                    profile = homeState.profiles[note.pubkey] ?: state.profiles[note.pubkey],
                    bookmarked = note.id in homeState.bookmarkedIds || note.id in actions.bookmarked,
                    liked = note.id in actions.liked,
                    resolveMentionName = { hex -> homeState.profiles[hex]?.bestDisplayName },
                    onLike = { vm.toggleLike(note) },
                    onBookmark = { vm.toggleBookmark(note.id) },
                    onComment = {
                        vm.loadComments(note.id)
                        onThreadTarget(note)
                    },
                    onRepost = { vm.repost(note) },
                    onZap = {
                        vm.loadZaps(note.id)
                        vm.selectZapAmount(defaultZapSats.toLong())
                        zapTarget = note
                    },
                    onAuthor = { onOpenAuthor(note.pubkey) },
                    isMuted = vm.isMuted(note.pubkey),
                    onMuteToggle = { vm.toggleMute(note.pubkey) },
                    onReport = { reason -> vm.report(note, reason) },
                    // Local ranking signals (web interaction-profile parity).
                    authorDemoted = vm.interaction?.isAuthorDemoted(note.pubkey) == true,
                    tagDemoted = note.hashtags.firstOrNull()?.let { vm.interaction?.isTagDemoted(it) } == true,
                    interactionAuthorName = homeState.profiles[note.pubkey]?.bestDisplayName,
                    onNotInterested = { vm.notInterested(note) },
                    onHideNote = { vm.hideNote(note) },
                    onToggleAuthorDemotion = { vm.toggleShowLessFrom(note.pubkey) },
                    onToggleTagDemotion = vm::toggleShowLessAbout,
                    // APP-008 poll voting.
                    pollTally = homeState.pollTallies[note.id],
                    canVotePoll = canVotePoll,
                    onLoadPollVotes = { vm.loadPollVotes(note.id) },
                    onVotePoll = { optionIndex -> vm.votePoll(note, optionIndex) },
                    onOpenExternalLink = onOpenExternalLink,
                    onOpenMentionProfile = { onOpenAuthor(it) },
                    rawEventJson = { vm.rawEventJson(note.id) },
                    sensitiveShowByDefault = sensitiveShowByDefault,
                )
            }
            // Home parity: hairline divider between cards (not after the
            // last one) — the timeline's exact separator chrome.
            if (index < state.results.lastIndex) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = BitOSColors.divider,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** APP-010 People tab: follow/unfollow rows from the shared fan-in. */
@Composable
private fun PeopleTab(
    people: List<SearchResults.PeopleRow>,
    homeViewModel: space.bitos.app.ui.feed.HomeViewModel?,
    onOpenAuthor: (String) -> Unit = {},
) {
    val homeState = if (homeViewModel != null) {
        homeViewModel.state.collectAsStateWithLifecycle().value
    } else {
        space.bitos.app.data.feed.FeedUiState()
    }
    LazyColumn(
        contentPadding = PaddingValues(
            horizontal = BitOSSpacing.screen,
            vertical = BitOSSpacing.sm,
        ),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        items(people, key = { it.pubkey }) { person ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = BitOSColors.surface,
                modifier = Modifier.clickable(onClickLabel = "Open profile") { onOpenAuthor(person.pubkey) },
            ) {
                Row(
                    Modifier.padding(BitOSSpacing.md).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    space.bitos.app.ui.components.PubkeyAvatar(
                        pubkey = person.pubkey,
                        size = 44,
                        pictureUrl = person.picture,
                        label = person.displayName,
                    )
                    Spacer(Modifier.width(BitOSSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            person.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.W700,
                            maxLines = 1,
                        )
                        if (person.nip05 != null) {
                            Text(person.nip05!!, style = MaterialTheme.typography.labelSmall, color = BitOSColors.primary, maxLines = 1)
                        }
                        Text(
                            "${person.noteCount} note" + if (person.noteCount == 1) "" else "s",
                            style = MaterialTheme.typography.labelSmall,
                            color = BitOSColors.textTertiary,
                        )
                    }
                    val following = person.pubkey in homeState.following
                    androidx.compose.material3.TextButton(onClick = { homeViewModel?.toggleFollow(person.pubkey) }) {
                        Text(
                            if (following) "Following" else "Follow",
                            color = if (following) BitOSColors.textTertiary else BitOSColors.primary,
                            fontWeight = FontWeight.W700,
                        )
                    }
                }
            }
        }
    }
}

/** APP-010 Hashtags tab: ranked hits from the shared fan-in. */
@Composable
private fun HashtagsTab(
    hits: List<SearchResults.HashtagHit>,
    onPick: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(
            horizontal = BitOSSpacing.screen,
            vertical = BitOSSpacing.sm,
        ),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        items(hits, key = { it.tag }) { hit ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = BitOSColors.surface,
                modifier = Modifier.clickable(onClickLabel = "Search #${hit.tag}") { onPick(hit.tag) },
            ) {
                Row(
                    Modifier.padding(BitOSSpacing.md).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "#${hit.tag}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.W700,
                        color = BitOSColors.primary,
                    )
                    Spacer(Modifier.width(BitOSSpacing.sm))
                    Text(
                        "${hit.count} note" + if (hit.count == 1) "" else "s",
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textTertiary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CreatorCard(pubkey: String, profile: ProfileMetadata?, onOpen: () -> Unit = {}) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BitOSColors.primaryContainer,
        modifier = Modifier.clickable(onClickLabel = "Open creator profile") { onOpen() },
    ) {
        Row(Modifier.padding(BitOSSpacing.base).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PubkeyAvatar(pubkey = pubkey, size = 48, pictureUrl = profile?.picture, label = profile?.bestDisplayName)
            Spacer(Modifier.width(BitOSSpacing.md))
            Column {
                Text(
                    profile?.bestDisplayName ?: shortPubkey(pubkey),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.W700,
                    color = BitOSColors.primary,
                )
                profile?.nip05?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = BitOSColors.textSecondary)
                }
                Text("Creator", style = MaterialTheme.typography.labelSmall, color = BitOSColors.textTertiary)
            }
        }
    }
}

@Composable
private fun SearchCard(note: FeedNote, profile: ProfileMetadata?, onOpen: () -> Unit = {}, onOpenAuthor: () -> Unit = {}) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BitOSColors.surface,
        modifier = Modifier.clickable(onClickLabel = "Open thread") { onOpen() },
    ) {
        Column(Modifier.padding(BitOSSpacing.base).fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Open author profile") { onOpenAuthor() },
            ) {
                PubkeyAvatar(pubkey = note.pubkey, size = 28, pictureUrl = profile?.picture, label = profile?.bestDisplayName)
                Spacer(Modifier.width(BitOSSpacing.sm))
                Text(
                    profile?.bestDisplayName ?: shortPubkey(note.pubkey),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.W600,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(BitOSSpacing.sm))
                Text(
                    formatTimeAgo(note.createdAt, System.currentTimeMillis() / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textTertiary,
                )
            }
            Spacer(Modifier.size(BitOSSpacing.sm))
            Text(
                note.content,
                style = MaterialTheme.typography.bodyMedium,
                color = BitOSColors.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (note.video != null) {
                Text("🎬 video", style = MaterialTheme.typography.labelSmall, color = BitOSColors.accent)
            }
        }
    }
}
