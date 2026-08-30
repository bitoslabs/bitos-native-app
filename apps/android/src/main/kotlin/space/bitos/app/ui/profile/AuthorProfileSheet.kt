package space.bitos.app.ui.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.bitos.app.data.feed.AuthorUiState
import space.bitos.app.data.feed.FeedUiState
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.components.SheetCloseIcon
import space.bitos.app.ui.components.formatTimeAgo
import space.bitos.app.ui.components.shortPubkey
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.app.ui.theme.SolarFeedIcon
import space.bitos.app.ui.theme.SolarFeedIconImage
import space.bitos.core.feed.FeedNote
import space.bitos.core.model.ProfileMetadata

/**
 * Author profile bottom sheet: banner hero, all profile fields (about,
 * website, lightning), follow/unfollow, copy-npub, and a "View full
 * profile" link that opens njump.me in the browser.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuthorProfileContent(
    authorPubkey: String,
    state: AuthorUiState,
    feedState: FeedUiState,
    onOpen: (String) -> Unit,
    onFollow: (String) -> Unit,
    onClose: () -> Unit,
) {
    LaunchedEffect(authorPubkey) { onOpen(authorPubkey) }

    val profile = state.profile
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var aboutExpanded by remember { mutableStateOf(false) }
    var npubCopied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.90f),
    ) {
        // ── Banner ──────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
        ) {
            val bannerUrl = profile?.banner?.takeIf { it.isNotBlank() }
            if (bannerUrl != null) {
                coil.compose.AsyncImage(
                    model = bannerUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    BitOSColors.primary.copy(alpha = 0.55f),
                                    Color(0xFF14103A),
                                )
                            )
                        ),
                )
            }
            // Bottom scrim
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, BitOSColors.background.copy(alpha = 0.5f)),
                        )
                    ),
            )
            // Close button in top-right corner of the banner
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                SheetCloseIcon(onClose = onClose)
            }
        }

        // ── Content below banner ────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = BitOSSpacing.screen,
                    vertical = BitOSSpacing.base,
                ),
                verticalArrangement = Arrangement.spacedBy(BitOSSpacing.md),
            ) {
                // ── Avatar + action row ──────────────────────────────
                item(key = "header") {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.offset(y = (-32).dp).padding(bottom = (-32).dp),
                    ) {
                        // Avatar overlapping the banner
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(BitOSColors.background)
                                .padding(3.dp),
                        ) {
                            PubkeyAvatar(
                                pubkey = authorPubkey,
                                size = 74,
                                pictureUrl = profile?.picture,
                                label = profile?.bestDisplayName,
                                hasLightning = !profile?.lud16.isNullOrBlank(),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // Copy npub
                        OutlinedButton(
                            onClick = {
                                val npub = space.bitos.core.identity.NostrKeyCodec.npub(authorPubkey)
                                if (npub != null) {
                                    clipboard.setText(AnnotatedString(npub))
                                    npubCopied = true
                                }
                            },
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.height(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (npubCopied) "Copied" else "npub",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Spacer(Modifier.width(BitOSSpacing.sm))
                        // Follow / Unfollow
                        val isFollowing = feedState.following.contains(authorPubkey)
                        if (isFollowing) {
                            OutlinedButton(
                                onClick = { onFollow(authorPubkey) },
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.height(36.dp),
                            ) {
                                Text("Following ✓", style = MaterialTheme.typography.labelMedium)
                            }
                        } else {
                            Button(
                                onClick = { onFollow(authorPubkey) },
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.height(36.dp),
                            ) {
                                Text("Follow", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                // ── Name block ───────────────────────────────────────
                item(key = "name") {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                profile?.bestDisplayName ?: shortPubkey(authorPubkey),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.W700,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (!profile?.nip05.isNullOrBlank()) {
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = "Verified",
                                    tint = BitOSColors.accent,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        profile?.nip05?.takeIf { it.isNotBlank() }?.let { nip05 ->
                            Text(nip05, style = MaterialTheme.typography.bodySmall, color = BitOSColors.accent)
                        }
                        Text(
                            shortPubkey(authorPubkey),
                            style = MaterialTheme.typography.labelSmall,
                            color = BitOSColors.textTertiary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                }

                // ── About ────────────────────────────────────────────
                val about = profile?.about?.trim()?.takeIf { it.isNotBlank() }
                if (about != null) {
                    item(key = "about") {
                        Column {
                            Text(
                                about,
                                style = MaterialTheme.typography.bodyMedium,
                                color = BitOSColors.textSecondary,
                                maxLines = if (aboutExpanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (about.length > 160) {
                                TextButton(
                                    onClick = { aboutExpanded = !aboutExpanded },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 0.dp, vertical = 0.dp,
                                    ),
                                ) {
                                    Text(
                                        if (aboutExpanded) "Show less" else "Show more",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = BitOSColors.primary,
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Info chips: lightning + website ──────────────────
                val lud16 = profile?.lud16?.trim()?.takeIf { it.isNotBlank() }
                val website = profile?.website?.trim()?.takeIf { it.isNotBlank() }
                if (lud16 != null || website != null) {
                    item(key = "chips") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
                            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.xs),
                        ) {
                            if (lud16 != null) {
                                InfoChip(
                                    icon = { SolarFeedIconImage(SolarFeedIcon.Zap, contentDescription = null, tint = BitOSColors.zap, modifier = Modifier.size(13.dp)) },
                                    text = lud16,
                                    tint = BitOSColors.zap,
                                    onClick = {
                                        clipboard.setText(AnnotatedString(lud16))
                                    },
                                )
                            }
                            if (website != null) {
                                val url = if (website.startsWith("http")) website else "https://$website"
                                InfoChip(
                                    icon = { Icon(Icons.Rounded.Language, contentDescription = null, tint = BitOSColors.textSecondary, modifier = Modifier.size(13.dp)) },
                                    text = website,
                                    tint = BitOSColors.textSecondary,
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                // ── View full profile ────────────────────────────────
                item(key = "full-profile") {
                    val npub = remember(authorPubkey) {
                        space.bitos.core.identity.NostrKeyCodec.npub(authorPubkey)
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BitOSColors.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val link = "https://njump.me/${npub ?: authorPubkey}"
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                                }
                            },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.OpenInNew,
                                contentDescription = null,
                                tint = BitOSColors.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "View full profile",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.W600,
                                color = BitOSColors.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = BitOSColors.textTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                // ── Divider ──────────────────────────────────────────
                item(key = "divider") {
                    androidx.compose.material3.HorizontalDivider(color = BitOSColors.divider)
                }

                // ── Notes ────────────────────────────────────────────
                item(key = "notes-header") {
                    when {
                        state.isLoading && state.notes.isEmpty() ->
                            Box(Modifier.fillMaxWidth().padding(BitOSSpacing.xl), contentAlignment = Alignment.Center) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    color = BitOSColors.primary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        state.notes.isEmpty() ->
                            Box(Modifier.fillMaxWidth().padding(BitOSSpacing.xl), contentAlignment = Alignment.Center) {
                                Text(
                                    "No notes yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BitOSColors.textSecondary,
                                )
                            }
                        else ->
                            Text(
                                "Notes",
                                style = MaterialTheme.typography.labelMedium,
                                color = BitOSColors.textTertiary,
                                letterSpacing = androidx.compose.ui.unit.TextUnit(0.8f, androidx.compose.ui.unit.TextUnitType.Sp),
                            )
                    }
                }

                items(state.notes, key = { it.id }) { note ->
                    AuthorNoteCard(note, profile)
                }
            }
        }
    }
}

@Composable
private fun InfoChip(
    icon: @Composable () -> Unit,
    text: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(tint.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon()
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

@Composable
private fun AuthorNoteCard(note: FeedNote, profile: ProfileMetadata?) {
    var expanded by remember(note.id) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(12.dp), color = BitOSColors.surface) {
        Column(Modifier.padding(BitOSSpacing.md).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTimeAgo(note.createdAt, System.currentTimeMillis() / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                if (note.video != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(imageVector = Icons.Rounded.PlayCircle, contentDescription = null, tint = BitOSColors.accent, modifier = Modifier.size(12.dp))
                        Text("Video", style = MaterialTheme.typography.labelSmall, color = BitOSColors.accent)
                    }
                } else if (note.mediaUrls.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(imageVector = Icons.Rounded.Photo, contentDescription = null, tint = BitOSColors.textSecondary, modifier = Modifier.size(12.dp))
                        Text("${note.mediaUrls.size}", style = MaterialTheme.typography.labelSmall, color = BitOSColors.textSecondary)
                    }
                }
            }
            if (note.content.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    note.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.content.length > 200 && !expanded) {
                    TextButton(
                        onClick = { expanded = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("Show more", style = MaterialTheme.typography.labelSmall, color = BitOSColors.primary)
                    }
                }
            }
        }
    }
}
