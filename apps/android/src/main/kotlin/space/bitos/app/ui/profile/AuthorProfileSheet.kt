package space.bitos.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.data.feed.AuthorUiState
import space.bitos.app.data.feed.FeedUiState
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.components.formatTimeAgo
import space.bitos.app.ui.components.shortPubkey
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.feed.FeedNote
import space.bitos.core.model.ProfileMetadata

/**
 * Author profile sheet: profile header with follow/unfollow + the author's
 * verified notes (newest first). Opened by tapping any author row.
 */
@Composable
fun AuthorProfileContent(
    authorPubkey: String,
    state: AuthorUiState,
    feedState: FeedUiState,
    onOpen: (String) -> Unit,
    onFollow: (String) -> Unit,
    onClose: () -> Unit,
) {
    // The sheet is visible before the first relay response. Drive the request
    // from its immutable target instead of waiting for repository state, or a
    // new sheet would render empty and never subscribe.
    LaunchedEffect(authorPubkey) { onOpen(authorPubkey) }

    // Bounded height so the notes list can fill the sheet (ModalBottomSheet
    // measures content against the screen; weight needs definite bounds).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .padding(horizontal = BitOSSpacing.screen)
            .padding(bottom = BitOSSpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Profile", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            space.bitos.app.ui.components.SheetCloseIcon(onClose = onClose)
        }
        Spacer(Modifier.height(BitOSSpacing.md))

        ProfileHeader(
            pubkey = authorPubkey,
            profile = state.profile,
            isFollowing = feedState.following.contains(authorPubkey),
            onFollow = { onFollow(authorPubkey) },
        )

        Spacer(Modifier.height(BitOSSpacing.md))

        when {
            state.isLoading && state.notes.isEmpty() -> {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BitOSColors.primary, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }
            state.notes.isEmpty() -> {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "No notes yet, or relays haven't returned this author's posts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BitOSColors.textSecondary,
                    )
                }
            }
            else -> {
                Text("Notes (${state.notes.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.W600)
                Spacer(Modifier.height(BitOSSpacing.sm))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                ) {
                    items(state.notes, key = { it.id }) { note ->
                        AuthorNoteCard(note)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(
    pubkey: String,
    profile: ProfileMetadata?,
    isFollowing: Boolean,
    onFollow: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = BitOSColors.surface) {
        Column(Modifier.padding(BitOSSpacing.base)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PubkeyAvatar(pubkey = pubkey, size = 64, pictureUrl = profile?.picture, label = profile?.bestDisplayName)
                Spacer(Modifier.width(BitOSSpacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        profile?.bestDisplayName ?: shortPubkey(pubkey),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.W700,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    profile?.nip05?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = BitOSColors.accent)
                    }
                    Text(
                        shortPubkey(pubkey),
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textTertiary,
                    )
                }
                Spacer(Modifier.width(BitOSSpacing.sm))
                Button(onClick = onFollow) {
                    Text(if (isFollowing) "Following ✓" else "Follow")
                }
            }
            profile?.about?.let { about ->
                Spacer(Modifier.height(BitOSSpacing.sm))
                Text(about, style = MaterialTheme.typography.bodySmall, color = BitOSColors.textSecondary, maxLines = 3)
            }
            profile?.lud16?.let { lud16 ->
                Spacer(Modifier.height(BitOSSpacing.xs))
                Text("⚡ $lud16", style = MaterialTheme.typography.labelSmall, color = BitOSColors.zap)
            }
        }
    }
}

@Composable
private fun AuthorNoteCard(note: FeedNote) {
    Surface(shape = RoundedCornerShape(12.dp), color = BitOSColors.surfaceElevated) {
        Column(Modifier.padding(BitOSSpacing.md).fillMaxWidth()) {
            Text(
                formatTimeAgo(note.createdAt, System.currentTimeMillis() / 1000),
                style = MaterialTheme.typography.labelSmall,
                color = BitOSColors.textTertiary,
            )
            Spacer(Modifier.size(BitOSSpacing.xs))
            Text(
                note.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (note.video != null) {
                Text("🎬 video", style = MaterialTheme.typography.labelSmall, color = BitOSColors.accent)
            }
        }
    }
}
