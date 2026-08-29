@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package space.bitos.app.ui.bookmarks

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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.components.formatTimeAgo
import space.bitos.app.ui.components.shortPubkey
import space.bitos.app.ui.feed.CommentContent
import space.bitos.app.ui.feed.HomeViewModel
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.app.ui.theme.SolarFeedIcon
import space.bitos.app.ui.theme.SolarFeedIconImage
import space.bitos.core.feed.FeedNote

/**
 * APP-015 Bookmarks page (spec §3.15): the account's NIP-51 kind-30003
 * saved notes as compact rows, newest-saved first. Relay re-fetch on open
 * (ids REQ for saved notes outside the feed window); tap opens the thread;
 * the trailing action removes the bookmark (optimistic + list publish).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    viewModel: HomeViewModel,
    identityViewModel: IdentityViewModel,
    notePublisher: space.bitos.app.data.publish.NotePublisher,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val publishState by notePublisher.state.collectAsStateWithLifecycle()
    var threadTarget by remember { mutableStateOf<FeedNote?>(null) }

    // Spec: live relay re-fetch every time the page opens.
    LaunchedEffect(Unit) { viewModel.loadBookmarked() }

    Scaffold(
        containerColor = BitOSColors.background,
        topBar = {
            TopAppBar(
                title = { Text("Saved", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    TextButton(onClick = onClose) { Text("Close", color = BitOSColors.textSecondary) }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(BitOSColors.background),
        ) {
            when {
                state.bookmarkedIds.isEmpty() -> BookmarksEmpty()
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.bookmarkedNotes, key = { it.id }) { note ->
                        SavedRow(
                            note = note,
                            state = state,
                            onOpen = { threadTarget = note },
                            onRemove = { viewModel.toggleBookmark(note.id) },
                        )
                    }
                    // Saved ids whose notes are still loading from relays.
                    val missing = state.bookmarkedIds.size - state.bookmarkedNotes.size
                    if (missing > 0) {
                        item(key = "saved-pending") {
                            Row(
                                Modifier.fillMaxWidth().padding(BitOSSpacing.md),
                                horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    color = BitOSColors.primary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    "Loading $missing saved note${if (missing == 1) "" else "s"}…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BitOSColors.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    threadTarget?.let { target ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { threadTarget = null }) {
            CommentContent(
                note = target,
                feedState = state,
                identityViewModel = identityViewModel,
                publisherState = publishState,
                onLoadComments = viewModel::loadComments,
                onReply = { text, note -> viewModel.reply(text, note) },
                onClose = { threadTarget = null },
            )
        }
    }
}

/** Compact saved row (spec: NoteCard compact variant): author + excerpt. */
@Composable
private fun SavedRow(
    note: FeedNote,
    state: space.bitos.app.data.feed.FeedUiState,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val profile = state.profiles[note.pubkey]
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open saved note") { onOpen() }
            .padding(horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PubkeyAvatar(
                pubkey = note.pubkey,
                size = 28,
                label = profile?.bestDisplayName,
                hasLightning = !profile?.lud16.isNullOrBlank(),
            )
            Spacer(Modifier.width(BitOSSpacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text(
                    profile?.bestDisplayName ?: shortPubkey(note.pubkey),
                    style = MaterialTheme.typography.titleSmall,
                    color = BitOSColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!profile?.nip05.isNullOrBlank()) {
                    Icon(
                        androidx.compose.material.icons.Icons.Rounded.CheckCircle,
                        contentDescription = "NIP-05 identity claim",
                        tint = BitOSColors.primary,
                        modifier = Modifier.padding(start = 4.dp).size(12.dp),
                    )
                }
                Spacer(Modifier.width(BitOSSpacing.sm))
                Text(
                    formatTimeAgo(note.createdAt, System.currentTimeMillis() / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textTertiary,
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                SolarFeedIconImage(
                    SolarFeedIcon.BookmarkFilled,
                    contentDescription = "Remove bookmark",
                    tint = BitOSColors.bookmark,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            note.content,
            style = MaterialTheme.typography.bodyMedium,
            color = BitOSColors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BookmarksEmpty() {
    Box(Modifier.fillMaxSize().padding(BitOSSpacing.xxl), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            SolarFeedIconImage(
                SolarFeedIcon.Bookmark,
                contentDescription = null,
                tint = BitOSColors.textTertiary,
                modifier = Modifier.size(40.dp),
            )
            Text("Nothing saved yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Bookmark notes from any card or reel to find them here.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
            )
        }
    }
}
