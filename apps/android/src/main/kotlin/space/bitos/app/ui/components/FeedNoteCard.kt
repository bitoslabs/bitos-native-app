package space.bitos.app.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import space.bitos.core.feed.FeedNote
import space.bitos.core.model.ProfileMetadata
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.app.ui.theme.SolarFeedIcon
import space.bitos.app.ui.theme.SolarFeedIconImage

/**
 * APP-005 body clamp (web PostCard parity): bodies collapse beyond 8 lines,
 * font-scale safe because the clamp is line-based.
 */
const val NOTE_COLLAPSE_LINES = 8

/**
 * A visible row owns its relative-time clock (web parity): ticks each second
 * only for the first minute, then wakes on minute boundaries. Relay state is
 * never republished just to advance a label.
 */
@Composable
fun rememberRelativeTimeNow(createdAtSeconds: Long): Long {
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
 * The feed note card, promoted from FeedScreen so every surface (home feed,
 * own profile / "You" page, author profile) renders notes with identical
 * behavior: NIP-27 rich body with tappable entities + line clamp, sensitive
 * cover, polls, media tiles + lightbox, relative time, and the full
 * like · comment · repost · zap · bookmark action row with the ⋯ menu.
 *
 * This is the single source of truth — profile screens must not fork it.
 */
@Composable
fun FeedNoteCard(
    note: FeedNote,
    profile: ProfileMetadata?,
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
    /** Local ranking signals (web interaction-profile parity). */
    authorDemoted: Boolean = false,
    tagDemoted: Boolean = false,
    interactionAuthorName: String? = null,
    onNotInterested: () -> Unit = {},
    onHideNote: () -> Unit = {},
    onToggleAuthorDemotion: () -> Unit = {},
    onToggleTagDemotion: (String) -> Unit = {},
    /** APP-008 poll voting. */
    pollTally: space.bitos.core.model.PollTally? = null,
    canVotePoll: Boolean = false,
    onLoadPollVotes: () -> Unit = {},
    onVotePoll: (Int) -> Unit = {},
    onOpenAttachment: (String) -> Unit = {},
    /** External-link tap → confirm sheet (owned by the screen). */
    onOpenExternalLink: (String) -> Unit = {},
    /** note1/nevent1/naddr1 tap → in-place thread open. */
    onOpenNoteRef: (String) -> Unit = {},
    /** Profile-mention tap → the mentioned user's profile (not the author). */
    onOpenMentionProfile: (String) -> Unit = {},
    /** ⋯ raw-event viewer: canonical event JSON provider; null hides the row. */
    rawEventJson: (() -> String?)? = null,
    sensitiveShowByDefault: Boolean = false,
    mediaPreview: Boolean = true,
    compact: Boolean = false,
) {
    var revealed by remember(note.id) { mutableStateOf(false) }
    // Show more/less: font-scale-safe line clamp.
    var expanded by remember(note.id) { mutableStateOf(false) }
    var canExpand by remember(note.id) { mutableStateOf(false) }
    var lightboxUrl by remember(note.id) { mutableStateOf<String?>(null) }
    lightboxUrl?.let { url ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { lightboxUrl = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            MediaLightbox(url = url, onDismiss = { lightboxUrl = null })
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = BitOSSpacing.screen,
                vertical = if (compact) 4.dp else BitOSSpacing.md,
            ),
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
                    Text(
                        profile?.bestDisplayName ?: shortPubkey(note.pubkey),
                        style = MaterialTheme.typography.titleSmall,
                        color = BitOSColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!profile?.nip05.isNullOrBlank()) {
                        Icon(
                            AppIcons.CheckCircle,
                            contentDescription = "NIP-05 identity claim",
                            tint = BitOSColors.primary,
                            modifier = Modifier.padding(start = 4.dp).size(13.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (note.repostedBy != null) {
                        SolarFeedIconImage(
                            SolarFeedIcon.Repost,
                            contentDescription = null,
                            tint = BitOSColors.repost,
                            modifier = Modifier.size(10.dp),
                        )
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
            NoteMoreMenuButton(
                note = note,
                isSaved = bookmarked,
                isMuted = isMuted,
                onSaveToggle = onBookmark,
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
                rawEventJson = rawEventJson,
            )
        }
        if (note.contentWarning && !revealed && !sensitiveShowByDefault) {
            SensitiveCover(onReveal = { revealed = true })
        } else {
            RichText(
                tokens = remember(note.content) { space.bitos.core.nostr.Nip27.tokenize(note.content) },
                hiddenMediaUrls = remember(note.mediaUrls) { note.mediaUrls.toSet() },
                resolveMentionName = resolveMentionName,
                onOpenNoteRef = onOpenNoteRef,
                onOpenExternalLink = onOpenExternalLink,
                maxLines = if (expanded) Int.MAX_VALUE else NOTE_COLLAPSE_LINES,
                onOverflow = { canExpand = it },
                onOpenProfile = onOpenMentionProfile,
            )
            if (canExpand || expanded) {
                TextButton(
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
            note.poll?.let { poll ->
                PollOptions(
                    poll = poll,
                    noteId = note.id,
                    tally = pollTally,
                    canVote = canVotePoll,
                    onLoadVotes = onLoadPollVotes,
                    onVote = onVotePoll,
                )
            }
            if (mediaPreview) {
                MediaRow(urls = note.mediaUrls, onOpen = { lightboxUrl = it })
            } else {
                Text(
                    if (note.mediaUrls.size == 1) "1 attachment (previews off)" else "${note.mediaUrls.size} attachments (previews off)",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textTertiary,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.base)) {
            // User decision 2026-08-29: order [like · comment · repost ·
            // zap · bookmark]; scale-bounce + haptic on like.
            AnimatedLikeIcon(
                liked = liked,
                tint = if (liked) BitOSColors.like else BitOSColors.textSecondary,
                iconSize = 18.dp,
                onClick = onLike,
            )
            NoteCardAction(SolarFeedIcon.Comment, "Replies", BitOSColors.reply, onComment)
            NoteCardAction(SolarFeedIcon.Repost, "Repost", BitOSColors.repost, onRepost)
            NoteCardAction(SolarFeedIcon.Zap, "Zap", BitOSColors.zap, onZap)
            NoteCardAction(
                if (bookmarked) SolarFeedIcon.BookmarkFilled else SolarFeedIcon.Bookmark,
                if (bookmarked) "Remove bookmark" else "Bookmark",
                if (bookmarked) BitOSColors.bookmark else BitOSColors.textSecondary,
                onBookmark,
            )
        }
    }
}

/** APP-008 poll display + voting (web `Poll.svelte` parity): option rows
 *  with proportional bars, counts, my-vote highlight, total votes; votes
 *  lazy-load once per poll and taps publish a kind-1018. */
@Composable
fun PollOptions(
    poll: space.bitos.core.model.Poll,
    noteId: String,
    tally: space.bitos.core.model.PollTally?,
    canVote: Boolean,
    onLoadVotes: () -> Unit,
    onVote: (Int) -> Unit,
) {
    LaunchedEffect(noteId) { onLoadVotes() }
    val voted = tally?.myVote != null
    val total = tally?.total ?: 0
    Column(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        poll.options.forEach { option ->
            val count = tally?.counts?.get(option.index) ?: 0
            val fraction = if (total <= 0) 0f else count.toFloat() / total
            val mine = tally?.myVote == option.index
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(BitOSColors.surface)
                    .clickable(
                        enabled = canVote,
                        onClickLabel = "Vote ${option.label}",
                    ) { onVote(option.index) },
            ) {
                // Proportional result bar (web parity: fills after voting).
                Box(
                    Modifier
                        .fillMaxWidth(if (voted) fraction.coerceIn(0.02f, 1f) else 0f)
                        .fillMaxHeight()
                        .background(
                            if (mine) BitOSColors.primary.copy(alpha = 0.30f)
                            else BitOSColors.primary.copy(alpha = 0.14f),
                        ),
                )
                Row(
                    Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .border(
                                1.5.dp,
                                if (mine) BitOSColors.primary else BitOSColors.textTertiary,
                                androidx.compose.foundation.shape.CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (mine) {
                            Box(
                                Modifier.size(8.dp)
                                    .background(BitOSColors.primary, androidx.compose.foundation.shape.CircleShape),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        option.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BitOSColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.weight(1f))
                    if (voted && total > 0) {
                        Text(
                            "${(fraction * 100).toInt()}% · $count",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (mine) BitOSColors.primary else BitOSColors.textSecondary,
                        )
                    }
                }
            }
        }
        val footer = when {
            tally == null -> "Poll · ${poll.totalOptions} options"
            else -> {
                val word = if (total == 1) "vote" else "votes"
                "$total $word" + if (voted) " · tap an option to change" else ""
            }
        }
        Text(
            footer,
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textTertiary,
        )
    }
}

@Composable
fun NoteCardAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun NoteCardAction(icon: SolarFeedIcon, label: String, tint: Color, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        SolarFeedIconImage(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * ⋯ overflow → bottom sheet (web PostCard menu parity). Icon-only trigger —
 * no label, no dead rail Share.
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun NoteMoreMenuButton(
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
    /** Raw-event viewer: canonical JSON provider; null hides the menu row. */
    rawEventJson: (() -> String?)? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val npub = remember(note.pubkey) {
        space.bitos.core.identity.NostrKeyCodec.npub(note.pubkey) ?: note.pubkey
    }
    var showSheet by remember { mutableStateOf(false) }
    var rawEventFor by remember { mutableStateOf<String?>(null) }
    rawEventFor?.let { raw ->
        RawEventDialog(json = raw, onDismiss = { rawEventFor = null })
    }
    androidx.compose.material3.IconButton(
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
        AppBottomSheetMenu(
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
                *if (rawEventJson != null) {
                    arrayOf(
                        AppMenuEntry.Item(AppMenuItem("raw-event", "View raw event JSON", icon = AppIcons.AppsGrid)),
                    )
                } else {
                    arrayOf()
                },
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
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, space.bitos.core.feed.NoteShare.text(note.content, npub))
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }
                    "save" -> onSaveToggle()
                    "copy-id" -> clipboard.setText(AnnotatedString(note.id))
                    "copy-text" -> clipboard.setText(AnnotatedString(note.content))
                    "copy-npub" -> clipboard.setText(AnnotatedString(npub))
                    "raw-event" -> {
                        showSheet = false
                        rawEventFor = rawEventJson?.invoke()
                    }
                    "open-attachment" -> note.mediaUrls.firstOrNull()?.let(onOpenAttachment)
                    "copy-attachment" -> note.mediaUrls.firstOrNull()?.let {
                        clipboard.setText(AnnotatedString(it))
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

/**
 * Shared raw-event viewer (web "View raw event JSON" parity): the NIP-01
 * canonical event object, monospace and scrollable for long payloads.
 */
@Composable
fun RawEventDialog(json: String, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Raw event") },
        text = {
            Text(
                json,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
