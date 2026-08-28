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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.data.feed.SearchUiState
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.components.formatTimeAgo
import space.bitos.app.ui.components.shortPubkey
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
) {
    val state by search.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    // APP-009 root resolution: a note1/nevent1/naddr1 query fetches the
    // thread head; the result opens the X-style thread sheet.
    var threadTarget by remember { mutableStateOf<FeedNote?>(null) }

    LaunchedEffect(input) { search.search(input) }

    Column(Modifier.fillMaxSize().background(BitOSColors.background)) {
        OutlinedTextField(
            value = input,
            onValueChange = { if (it.length <= 200) input = it },
            placeholder = { Text("Search notes, #hashtags, npub…") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = BitOSColors.textTertiary) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = BitOSColors.primary,
                unfocusedBorderColor = BitOSColors.border,
                focusedTextColor = BitOSColors.textPrimary,
                unfocusedTextColor = BitOSColors.textPrimary,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search.search(input) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.md),
        )

        if (input.isBlank()) {
            TopicChips { topic -> input = "#$topic" }
        } else {
            SearchResults(
                state = state,
                homeViewModel = homeViewModel,
                identityViewModel = identityViewModel,
                notePublisher = notePublisher,
            )
        }
    }
}

@Composable
private fun TopicChips(onTopic: (String) -> Unit) {
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
                    Column {
                        Text("#$topic", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
                        Text("Search across connected relays", style = MaterialTheme.typography.bodySmall, color = BitOSColors.textSecondary)
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
) {
    var threadTarget by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<FeedNote?>(null) }
    if (state.isSearching && state.results.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = BitOSColors.primary, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
        return
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
            SearchCard(
                note = note,
                profile = state.profiles[note.pubkey],
                onOpen = {
                    if (homeViewModel != null && identityViewModel != null && notePublisher != null) {
                        homeViewModel.loadComments(note.id)
                        threadTarget = note
                    }
                },
            )
        }
    }

    // APP-009: thread sheet reuses the feed's X-style CommentContent.
    if (threadTarget != null && homeViewModel != null && identityViewModel != null && notePublisher != null) {
        val target = threadTarget!!
        val homeState by homeViewModel.state.collectAsStateWithLifecycle()
        val publisherState by notePublisher.state.collectAsStateWithLifecycle()
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { threadTarget = null }) {
            space.bitos.app.ui.feed.CommentContent(
                note = target,
                feedState = homeState,
                identityViewModel = identityViewModel,
                publisherState = publisherState,
                onLoadComments = { homeViewModel.loadComments(it) },
                onReply = { text, note -> homeViewModel.reply(text, note) },
                onClose = { threadTarget = null },
            )
        }
    }
}

@Composable
private fun CreatorCard(pubkey: String, profile: ProfileMetadata?) {
    Surface(shape = RoundedCornerShape(14.dp), color = BitOSColors.primaryContainer) {
        Row(Modifier.padding(BitOSSpacing.base).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PubkeyAvatar(pubkey = pubkey, size = 48)
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
private fun SearchCard(note: FeedNote, profile: ProfileMetadata?, onOpen: () -> Unit = {}) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BitOSColors.surface,
        modifier = Modifier.clickable(onClickLabel = "Open thread") { onOpen() },
    ) {
        Column(Modifier.padding(BitOSSpacing.base).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PubkeyAvatar(pubkey = note.pubkey, size = 28)
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
