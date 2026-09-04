@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package space.bitos.app.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.data.feed.SearchUiState
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.components.formatTimeAgo
import space.bitos.app.ui.components.shortPubkey
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.feed.FeedNote
import space.bitos.core.model.ProfileMetadata

private val topics = listOf("bitcoin", "lightning", "nostr", "memes", "video")

/**
 * Discover surface (SOC-004): NIP-50 relay search with npub creator
 * resolution, hashtag topic chips feeding the same pipeline, and verified
 * results rendered as compact feed cards.
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
) {
    val state by search.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    // APP-009 root resolution: a note1/nevent1/naddr1 query fetches the
    // thread head; the result opens the X-style thread sheet.
    var threadTarget by remember { mutableStateOf<FeedNote?>(null) }
    // UX-010: creator/result author taps open the profile sheet.
    var authorTarget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(input) { search.search(input) }

    Column(Modifier.fillMaxSize().background(BitOSColors.background)) {
        space.bitos.app.ui.components.BitosTextField(
            value = input,
            onValueChange = { if (it.length <= 200) input = it },
            placeholder = "Search notes, #hashtags, npub…",
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = BitOSColors.textTertiary) },
            trailingIcon = {
                if (input.isNotEmpty()) {
                    IconButton(onClick = { input = "" }) {
                        Icon(AppIcons.Close, contentDescription = "Clear search", tint = BitOSColors.textTertiary)
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.md),
        )

        if (input.isBlank()) {
            TopicChips(
            onTopic = { topic -> input = "#$topic" },
            isFollowed = { homeViewModel?.isHashtagFollowed(it) == true },
            onToggleFollow = { homeViewModel?.toggleHashtagFollow(it) },
        )
        } else {
            SearchResults(
                state = state,
                homeViewModel = homeViewModel,
                identityViewModel = identityViewModel,
                notePublisher = notePublisher,
                onOpenAuthor = { authorTarget = it },
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

@Composable
private fun TopicChips(
    onTopic: (String) -> Unit,
    isFollowed: (String) -> Boolean = { false },
    onToggleFollow: (String) -> Unit = {},
) {
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = BitOSSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        item {
            Text("Explore topics", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.size(BitOSSpacing.md))
        }
        items(topics) { topic ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = BitOSColors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Search #$topic") { onTopic(topic) },
            ) {
                Row(Modifier.padding(BitOSSpacing.base), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(10.dp), color = BitOSColors.primaryContainer) {
                        Text(
                            "#",
                            color = BitOSColors.primary,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.width(BitOSSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text("#$topic", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
                        Text("Search across connected relays", style = MaterialTheme.typography.bodySmall, color = BitOSColors.textSecondary)
                    }
                    val followed = isFollowed(topic)
                    TextButton(
                        onClick = { onToggleFollow(topic) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            if (followed) "Following ✓" else "Follow",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.W700,
                            color = if (followed) BitOSColors.textTertiary else BitOSColors.primary,
                        )
                    }
                }
            }
        }
        item {
            Text(
                "Search runs on relays that support NIP-50; results may vary by relay.",
                style = MaterialTheme.typography.labelSmall,
                color = BitOSColors.textTertiary,
                modifier = Modifier.padding(top = BitOSSpacing.base),
            )
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun SearchResults(
    state: SearchUiState,
    homeViewModel: space.bitos.app.ui.feed.HomeViewModel? = null,
    identityViewModel: space.bitos.app.identity.IdentityViewModel? = null,
    notePublisher: space.bitos.app.data.publish.NotePublisher? = null,
    onOpenAuthor: (String) -> Unit = {},
    sensitiveShowByDefault: Boolean = false,
    defaultZapSats: Int = 21,
    onOpenExternalLink: (String) -> Unit = {},
) {
    var threadTarget by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<FeedNote?>(null) }
    // APP-010 results tabs: Posts · People · Hashtags (shared fan-in rule).
    var tab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    val people = androidx.compose.runtime.remember(state.results, state.profiles) {
        space.bitos.core.feed.SearchResults.people(state.results, state.profiles)
    }
    val hashtags = androidx.compose.runtime.remember(state.results) {
        space.bitos.core.feed.SearchResults.hashtags(state.results)
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
                androidx.compose.material3.TextButton(onClick = { tab = index }) {
                    Text(
                        "$label $count",
                        fontWeight = if (tab == index) androidx.compose.ui.text.font.FontWeight.W800 else androidx.compose.ui.text.font.FontWeight.W600,
                        color = if (tab == index) BitOSColors.primary else BitOSColors.textSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        when (tab) {
            1 -> PeopleTab(people = people, homeViewModel = homeViewModel, onOpenAuthor = onOpenAuthor)
            2 -> HashtagsTab(hits = hashtags, onPick = { tag -> /* router hop next */ })
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
    var zapTarget by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<FeedNote?>(null) }
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

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = BitOSSpacing.screen,
            vertical = BitOSSpacing.sm,
        ),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        // npub hit: creator card at top
        val creatorProfile = state.resolvedNpub?.let { state.profiles[it] }
        if (state.resolvedNpub != null) {
            item {
                CreatorCard(
                    pubkey = state.resolvedNpub!!,
                    profile = creatorProfile,
                    onOpen = { onOpenAuthor(state.resolvedNpub!!) },
                )
            }
        }
        if (state.results.isEmpty() && !state.isSearching) {
            item {
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
        items(state.results, key = { it.id }) { note ->
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
                    tagDemoted = note.hashtags.firstOrNull()?.let { vm.interaction?.isTagDemoted(it) == true } == true,
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
        }
    }

}

/** APP-010 People tab: follow/unfollow rows from the shared fan-in. */
@Composable
private fun PeopleTab(
    people: List<space.bitos.core.feed.SearchResults.PeopleRow>,
    homeViewModel: space.bitos.app.ui.feed.HomeViewModel?,
    onOpenAuthor: (String) -> Unit = {},
) {
    val homeState = if (homeViewModel != null) {
        homeViewModel.state.collectAsStateWithLifecycle().value
    } else {
        space.bitos.app.data.feed.FeedUiState()
    }
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
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
                            fontWeight = androidx.compose.ui.text.font.FontWeight.W700,
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
                            fontWeight = androidx.compose.ui.text.font.FontWeight.W700,
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
    hits: List<space.bitos.core.feed.SearchResults.HashtagHit>,
    onPick: (String) -> Unit,
    isFollowed: (String) -> Boolean = { false },
    onToggleFollow: (String) -> Unit = {},
) {
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = BitOSSpacing.screen,
            vertical = BitOSSpacing.sm,
        ),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        items(hits, key = { it.tag }) { hit ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = BitOSColors.surface,
                modifier = Modifier.clickable(onClickLabel = "Search #${'$'}{hit.tag}") { onPick(hit.tag) },
            ) {
                Row(
                    Modifier.padding(BitOSSpacing.md).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "#${'$'}{hit.tag}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.W700,
                        color = BitOSColors.primary,
                    )
                    Spacer(Modifier.width(BitOSSpacing.sm))
                    Text(
                        "${'$'}{hit.count} note" + if (hit.count == 1) "" else "s",
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    val followed = isFollowed(hit.tag)
                    TextButton(
                        onClick = { onToggleFollow(hit.tag) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Text(
                            if (followed) "Following ✓" else "Follow",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.W700,
                            color = if (followed) BitOSColors.textTertiary else BitOSColors.primary,
                        )
                    }
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
