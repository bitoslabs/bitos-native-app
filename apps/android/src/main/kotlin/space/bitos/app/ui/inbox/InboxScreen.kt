package space.bitos.app.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.data.feed.AuthorRepository
import space.bitos.app.data.feed.NotificationRepository
import space.bitos.app.data.feed.NotificationUiState
import space.bitos.app.data.feed.OriginNoteState
import space.bitos.app.data.publish.NotePublisher
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.feed.CommentContent
import space.bitos.app.ui.feed.HomeViewModel
import space.bitos.app.ui.components.MediaLightbox
import space.bitos.app.ui.components.AppMenuDropdown
import space.bitos.app.ui.components.AppMenuEntry
import space.bitos.app.ui.components.AppMenuItem
import space.bitos.app.ui.components.PubkeyAvatar
import space.bitos.app.ui.components.formatTimeAgo
import space.bitos.app.ui.components.shortPubkey
import space.bitos.app.ui.profile.AuthorProfileContent
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.feed.FeedNote
import space.bitos.core.model.NotificationActivity
import space.bitos.core.model.NotificationFilters
import space.bitos.core.model.NotificationGroup
import space.bitos.core.model.NotificationItem
import space.bitos.core.model.NotificationKind
import space.bitos.core.model.NotificationSections
import space.bitos.core.model.NotificationTab
import java.time.LocalDate
import java.time.ZoneId

/**
 * Activity surface (SOC-005 + APP-012): verified events targeting the
 * account — day-sectioned, iOS-style aggregated rows ("A and N others…"),
 * tabs/chips per the shared filter rules, zap sats, origin-note previews,
 * per-type mutes, visible-mark-read (1.4 s), deep links (thread/author)
 * and a per-row ⋯ menu (mark read / copy id / raw JSON).
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun InboxScreen(
    identityViewModel: IdentityViewModel,
    notifications: NotificationRepository,
    homeViewModel: HomeViewModel,
    notePublisher: NotePublisher,
    authorRepository: AuthorRepository,
    /** APP-018 privacy: `show` renders NIP-36 media directly (no cover). */
    sensitiveShowByDefault: Boolean = false,
) {
    val identity by identityViewModel.state.collectAsStateWithLifecycle()
    val state by notifications.state.collectAsStateWithLifecycle()
    val feedState by homeViewModel.state.collectAsStateWithLifecycle()
    val publishState by notePublisher.state.collectAsStateWithLifecycle()
    val authorState by authorRepository.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(NotificationTab.ALL) }
    var activity by rememberSaveable { mutableStateOf(NotificationActivity.NONE) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var muteMenu by remember { mutableStateOf(false) }
    var threadTarget by remember { mutableStateOf<FeedNote?>(null) }
    var authorTarget by remember { mutableStateOf<String?>(null) }

    // Keep the subscription aligned with the active account.
    LaunchedEffect(identity.account?.pubkeyHex) {
        notifications.setAccount(identity.account?.pubkeyHex)
    }

    // Visible-mark-read (spec: 1.4 s): items unread on screen become read
    // after the timer settles; late arrivals restart it (stay unread ≥1.4 s).
    val unreadNow = remember(state.items, state.readIds) {
        state.items.filter { it.id !in state.readIds }.map { it.id }
    }
    LaunchedEffect(unreadNow) {
        if (unreadNow.isNotEmpty()) {
            delay(1_400)
            notifications.markRead(unreadNow)
        }
    }

    Column(Modifier.fillMaxSize().background(BitOSColors.background)) {
        Row(
            Modifier.fillMaxWidth()
                .padding(
                    start = BitOSSpacing.screen,
                    end = BitOSSpacing.screen,
                    top = BitOSSpacing.md,
                    bottom = BitOSSpacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Activity", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { muteMenu = true }) {
                    Icon(
                        AppIcons.Filter,
                        contentDescription = "Mute notification types",
                        tint = if (state.mutedKinds.isEmpty()) BitOSColors.textSecondary else BitOSColors.primary,
                    )
                }
                AppMenuDropdown(
                    expanded = muteMenu,
                    onDismissRequest = { muteMenu = false },
                    entries = NotificationKind.entries.map { kind ->
                        AppMenuEntry.Item(
                            AppMenuItem(
                                id = kind.name,
                                label = muteLabel(kind),
                                checked = kind !in state.mutedKinds,
                            ),
                        )
                    },
                    onSelect = { id ->
                        NotificationKind.entries.firstOrNull { it.name == id }?.let { kind ->
                            val next = if (kind in state.mutedKinds) state.mutedKinds + kind else state.mutedKinds - kind
                            notifications.setMutedKinds(next)
                        }
                    },
                )
            }
        }
        when {
            !state.hasAccount -> InboxPlaceholder(
                title = "Activity needs an identity",
                message = "Create or import a key (You tab) to see replies, mentions, reactions, reposts and zaps addressed to you.",
            )
            else -> {
                // APP-012 search row: name/content contains over the shared rule.
                NotificationSearchRow(
                    query = query,
                    onQueryChange = { query = it },
                    expanded = searchOpen,
                    onToggle = { searchOpen = !searchOpen || query.isNotEmpty() },
                )
                InboxTabs(
                    state = state,
                    selected = tab,
                    onSelect = { tab = it },
                )
                ActivityChips(selected = activity, onSelect = { chip ->
                    activity = if (activity == chip) NotificationActivity.NONE else chip
                })
                val filtered = remember(state.items, state.readIds, tab, activity, query, feedState.profiles, feedState.blocked) {
                    state.items.filter { item ->
                        item.authorPubkey !in feedState.blocked && // APP-012 blocked-author filtering (kind-10004 head)
                            NotificationFilters.tabMatches(item.kind, tab, isRead = item.id in state.readIds) &&
                            NotificationFilters.activityMatches(item.kind, activity) &&
                            NotificationFilters.queryMatches(
                                item,
                                query,
                                feedState.profiles[item.authorPubkey]?.bestDisplayName,
                            )
                    }
                }
                // Fetch previews for whatever targets are visible.
                LaunchedEffect(state.items) {
                    notifications.requestOrigins(state.items.mapNotNull { it.targetEventId }.distinct())
                }
                if (state.items.isEmpty()) {
                    InboxPlaceholder(
                        title = if (state.loaded) "No notifications yet" else "Connecting to relays…",
                        message = if (state.loaded) {
                            "Notifications appear when someone replies, mentions you, reacts, reposts or zaps you."
                        } else {
                            "Your activity feed fills once a relay connection succeeds."
                        },
                    )
                } else if (filtered.isEmpty()) {
                    InboxPlaceholder(
                        title = "Nothing in this filter",
                        message = if (query.isNotBlank()) {
                            "No notifications match “${query.trim()}”. Clear the search or switch tabs to see more."
                        } else {
                            "Switch tabs or clear the activity chips to see all notifications."
                        },
                    )
                } else {
                    GroupedNotificationList(
                        filtered = filtered,
                        state = state,
                        sensitiveShowByDefault = sensitiveShowByDefault,
                        onMarkRead = notifications::markRead,
                        onOpenThread = { note -> threadTarget = note },
                        onOpenAuthor = { pubkey -> authorTarget = pubkey },
                    )
                }
            }
        }
    }

    // Deep links: target note → thread sheet; otherwise → author sheet.
    threadTarget?.let { note ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { threadTarget = null }) {
            CommentContent(
                note = note,
                feedState = feedState,
                identityViewModel = identityViewModel,
                publisherState = publishState,
                onLoadComments = homeViewModel::loadComments,
                onReply = { text, target, attachments, pow -> homeViewModel.reply(text, target, attachments, pow) },
                onClose = { threadTarget = null },
            )
        }
    }
    authorTarget?.let { authorPubkey ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { authorRepository.close(); authorTarget = null }) {
            AuthorProfileContent(
                authorPubkey = authorPubkey,
                state = authorState,
                feedState = feedState,
                onOpen = authorRepository::open,
                onFollow = homeViewModel::toggleFollow,
                onClose = { authorRepository.close(); authorTarget = null },
            )
        }
    }
}

@Composable
private fun InboxTabs(state: NotificationUiState, selected: NotificationTab, onSelect: (NotificationTab) -> Unit) {
    val unread = state.items.count { it.id !in state.readIds }
    val mentions = state.items.count { it.kind == NotificationKind.MENTION }
    val replies = state.items.count { it.kind == NotificationKind.REPLY }
    val counts = mapOf(
        NotificationTab.ALL to state.items.size,
        NotificationTab.UNREAD to unread,
        NotificationTab.MENTIONS to mentions,
        NotificationTab.REPLIES to replies,
    )
    ScrollableTabRow(
        selectedTabIndex = NotificationTab.entries.indexOf(selected),
        edgePadding = BitOSSpacing.screen,
        containerColor = Color.Transparent,
        divider = {},
    ) {
        NotificationTab.entries.forEach { option ->
            Tab(
                selected = option == selected,
                onClick = { onSelect(option) },
                text = {
                    Text("${option.label()} (${counts[option] ?: 0})", fontWeight = FontWeight.SemiBold)
                },
            )
        }
    }
}

private fun NotificationTab.label(): String = when (this) {
    NotificationTab.ALL -> "All"
    NotificationTab.UNREAD -> "Unread"
    NotificationTab.MENTIONS -> "Mentions"
    NotificationTab.REPLIES -> "Replies"
}

private fun NotificationActivity.label(): String = when (this) {
    NotificationActivity.ZAPS -> "Zaps"
    NotificationActivity.LIKES -> "Likes"
    NotificationActivity.REPOSTS -> "Reposts"
    NotificationActivity.FOLLOWS -> "Follows"
    NotificationActivity.NONE -> "All"
}

@Composable
private fun ActivityChips(selected: NotificationActivity, onSelect: (NotificationActivity) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        listOf(NotificationActivity.ZAPS, NotificationActivity.LIKES, NotificationActivity.REPOSTS, NotificationActivity.FOLLOWS)
            .forEach { chip ->
                FilterChip(
                    selected = chip == selected,
                    onClick = { onSelect(chip) },
                    label = { Text(chip.label()) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = BitOSColors.surface,
                        selectedContainerColor = BitOSColors.primaryContainer,
                    ),
                )
            }
    }
}

@Composable
private fun GroupedNotificationList(
    filtered: List<NotificationItem>,
    state: NotificationUiState,
    sensitiveShowByDefault: Boolean,
    onMarkRead: (List<String>) -> Unit,
    onOpenThread: (FeedNote) -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
    val sections = remember(filtered) { NotificationSections.sections(filtered, System.currentTimeMillis() / 1000) }
    var rawJsonFor by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = BitOSSpacing.screen, vertical = BitOSSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        sections.forEach { section ->
            item(key = "section-${section.epochDay}") {
                Text(
                    sectionTitle(section.epochDay),
                    style = MaterialTheme.typography.labelLarge,
                    color = BitOSColors.textTertiary,
                    modifier = Modifier.padding(top = BitOSSpacing.sm, bottom = BitOSSpacing.xs),
                )
            }
            section.groups.forEach { group ->
                item(key = group.id) {
                    NotificationGroupRow(
                        group = group,
                        isRead = group.itemIds.all { it in state.readIds },
                        origin = group.targetEventId?.let { state.origins[it] },
                        sensitiveShowByDefault = sensitiveShowByDefault,
                        onMarkRead = { onMarkRead(group.itemIds) },
                        onOpen = {
                            val originReady = group.targetEventId
                                ?.let { state.origins[it] } as? OriginNoteState.Ready
                            val note = originReady?.let { originFeedNote(group.targetEventId!!, it.note) }
                            if (note != null) {
                                onOpenThread(note)
                            } else {
                                group.actors.firstOrNull()?.let(onOpenAuthor)
                            }
                        },
                        onShowRaw = {
                            rawJsonFor = group.itemIds.firstOrNull { it in state.rawEvents }
                                ?.let { state.rawEvents[it] }
                        },
                    )
                }
            }
        }
    }
    rawJsonFor?.let { raw ->
        AlertDialog(
            onDismissRequest = { rawJsonFor = null },
            confirmButton = {
                TextButton(onClick = { rawJsonFor = null }) { Text("Close") }
            },
            title = { Text("Raw event") },
            text = {
                Text(raw, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            },
        )
    }
}

private fun sectionTitle(epochDay: Long): String {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone).toEpochDay()
    return when (epochDay) {
        today -> "Today"
        today - 1 -> "Yesterday"
        else -> LocalDate.ofEpochDay(epochDay).dayOfWeek.name.lowercase()
            .replaceFirstChar { it.uppercase() }
    }
}

/** Thread-root note built from the verified origin preview (full content). */
private fun originFeedNote(targetEventId: String, origin: space.bitos.core.model.OriginNote): FeedNote = FeedNote(
    id = origin.id,
    pubkey = origin.authorPubkey,
    content = origin.content,
    createdAt = origin.createdAt,
    kind = origin.kind,
    replyTo = null,
    hashtags = emptyList(),
    mentions = emptyList(),
    mediaUrls = emptyList(),
    isProtocolPayload = false,
)

private fun muteLabel(kind: NotificationKind): String = when (kind) {
    NotificationKind.REPLY -> "Replies"
    NotificationKind.MENTION -> "Mentions"
    NotificationKind.REACTION -> "Likes"
    NotificationKind.REPOST -> "Reposts"
    NotificationKind.ZAP -> "Zaps"
    NotificationKind.FOLLOW -> "Follows"
}

@Composable
private fun NotificationGroupRow(
    group: NotificationGroup,
    isRead: Boolean,
    origin: OriginNoteState?,
    sensitiveShowByDefault: Boolean,
    onMarkRead: () -> Unit,
    onOpen: () -> Unit,
    onShowRaw: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BitOSColors.surface,
        modifier = Modifier.clickable(onClick = onOpen),
    ) {
        Row(Modifier.fillMaxWidth().padding(BitOSSpacing.base), verticalAlignment = Alignment.CenterVertically) {
            if (!isRead) {
                Box(
                    Modifier.size(4.dp).background(BitOSColors.primary, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(BitOSSpacing.sm))
            }
            Box { AvatarStack(group) }
            Spacer(Modifier.width(BitOSSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    groupTitleLine(group),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.W600,
                    color = group.kind.tint(),
                )
                if (group.sampleSummary.isNotBlank() && group.kind != NotificationKind.REPOST && group.kind != NotificationKind.FOLLOW) {
                    Text(
                        group.sampleSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = BitOSColors.textSecondary,
                        maxLines = 2,
                    )
                }
                OriginPreview(origin, group, sensitiveShowByDefault)
                Text(
                    formatTimeAgo(group.newestAt, System.currentTimeMillis() / 1000) +
                        if (group.itemIds.size > 1) " · ${group.itemIds.size}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textTertiary,
                )
            }
            Box {
                androidx.compose.material3.IconButton(onClick = { menuExpanded = true }) {
                    androidx.compose.material3.Icon(
                        AppIcons.More,
                        contentDescription = "Notification options",
                        tint = BitOSColors.textSecondary,
                    )
                }
                AppMenuDropdown(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    entries = buildList {
                        if (!isRead) add(AppMenuEntry.Item(AppMenuItem("read", "Mark read", icon = AppIcons.Check)))
                        group.targetEventId?.let {
                            add(AppMenuEntry.Item(AppMenuItem("copy", "Copy note id", icon = AppIcons.Copy)))
                        }
                        add(AppMenuEntry.Item(AppMenuItem("raw", "Raw event JSON", icon = AppIcons.AppsGrid)))
                    },
                    onSelect = { id ->
                        when (id) {
                            "read" -> onMarkRead()
                            "copy" -> clipboard.setText(AnnotatedString(group.targetEventId ?: ""))
                            "raw" -> onShowRaw()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AvatarStack(group: NotificationGroup) {
    val actors = group.actors.ifEmpty { listOf("") }
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            actors.take(3).forEachIndexed { index, actor ->
                PubkeyAvatar(
                    pubkey = actor.ifEmpty { "00".repeat(32) },
                    size = if (index == 0) 40 else 28,
                    modifier = if (index == 0) Modifier else Modifier.offset(x = (-10).dp),
                )
            }
        }
        KindBadge(group.kind, Modifier.align(Alignment.BottomEnd))
    }
}

/** APP-012 expandable search row (name/content; shared predicate applies). */
@Composable
private fun NotificationSearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BitOSSpacing.screen)
            .padding(bottom = BitOSSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        androidx.compose.material3.IconButton(onClick = onToggle) {
            Icon(
                AppIcons.Search,
                contentDescription = if (expanded) "Close search" else "Search notifications",
                tint = if (query.isNotEmpty() || expanded) BitOSColors.primary else BitOSColors.textSecondary,
            )
        }
        if (expanded) {
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { onQueryChange(it.take(NotificationFilters.QUERY_MAX)) },
                placeholder = { Text("Search names and notes…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        } else if (query.isNotEmpty()) {
            Text(
                "“${query.trim()}”",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.primary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        if (query.isNotEmpty()) {
            androidx.compose.material3.TextButton(onClick = { onQueryChange("") }) {
                Text("Clear", color = BitOSColors.primary, fontWeight = FontWeight.W600)
            }
        }
    }
}

@Composable
private fun OriginPreview(origin: OriginNoteState?, group: NotificationGroup, sensitiveShowByDefault: Boolean) {
    val state = origin ?: return
    when (state) {
        is OriginNoteState.Loading -> Text(
            "Loading note…",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textTertiary,
            maxLines = 1,
        )
        is OriginNoteState.Unavailable -> Text(
            "Note unavailable",
            style = MaterialTheme.typography.labelSmall,
            color = BitOSColors.textTertiary,
            maxLines = 1,
        )
        is OriginNoteState.Ready -> {
            var revealed by remember(state.note.id) { mutableStateOf(false) }
            var lightboxUrl by remember(state.note.id) { mutableStateOf<String?>(null) }
            lightboxUrl?.let { url ->
                androidx.compose.ui.window.Dialog(onDismissRequest = { lightboxUrl = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                    MediaLightbox(url = url, onDismiss = { lightboxUrl = null })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.note.kind == 22 || (state.note.thumbUrl != null && state.note.mediaUrls.isEmpty())) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = BitOSColors.surfaceOverlay,
                    modifier = Modifier.size(width = 44.dp, height = 28.dp),
                ) {
                    if (state.note.kind == 22) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(
                                AppIcons.Play,
                                contentDescription = "Video note",
                                tint = BitOSColors.textSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(BitOSSpacing.sm))
            }
            Column {
                Text(
                    state.note.excerpt.ifEmpty { "Media note" },
                    style = MaterialTheme.typography.bodySmall,
                    color = BitOSColors.textSecondary,
                    maxLines = 2,
                )
                // APP-012 media strip: ≤4 16:9 tiles behind a NIP-36 cover
                // (the APP-018 sensitive-media default gates it).
                if (state.note.mediaUrls.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    if (state.note.contentWarning && !revealed && !sensitiveShowByDefault) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = BitOSColors.surfaceOverlay,
                            modifier = Modifier
                                .clickable(onClickLabel = "Reveal sensitive media") { revealed = true },
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    AppIcons.Photo,
                                    contentDescription = null,
                                    tint = BitOSColors.textTertiary,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    "Sensitive content — tap to reveal",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BitOSColors.textTertiary,
                                )
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            state.note.mediaUrls.forEach { url ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = BitOSColors.surfaceOverlay,
                                    modifier = Modifier
                                        .size(width = 56.dp, height = 32.dp)
                                        .clickable(onClickLabel = "Open media") { lightboxUrl = url },
                                ) {
                                    space.bitos.app.ui.feed.PosterImage(url = url, modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    shortPubkey(state.note.authorPubkey) + " · " + formatTimeAgo(state.note.createdAt, System.currentTimeMillis() / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textTertiary,
                    maxLines = 1,
                )
            }
        }
        }
    }
}

@Composable
private fun KindBadge(kind: NotificationKind, modifier: Modifier = Modifier) {
    val (symbol, tint) = when (kind) {
        NotificationKind.REPLY -> "💬" to BitOSColors.reply
        NotificationKind.MENTION -> "@" to BitOSColors.accent
        NotificationKind.REACTION -> "❤" to BitOSColors.like
        NotificationKind.REPOST -> "↻" to BitOSColors.repost
        NotificationKind.ZAP -> "⚡" to BitOSColors.zap
        NotificationKind.FOLLOW -> "+1" to BitOSColors.primary
    }
    Surface(shape = RoundedCornerShape(8.dp), color = tint.copy(alpha = 0.15f), modifier = modifier.size(18.dp)) {
        Text(symbol, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** "pk1…abc and 2 others liked your note" (iOS-style aggregation) with zap sats. */
private fun groupTitleLine(group: NotificationGroup): String {
    val verb = when (group.kind) {
        NotificationKind.REPLY -> "replied to your note"
        NotificationKind.MENTION -> "mentioned you"
        NotificationKind.REACTION -> "liked your note"
        NotificationKind.REPOST -> "reposted your note"
        NotificationKind.ZAP -> "zapped your note"
        NotificationKind.FOLLOW -> "followed you"
    }
    val sats = if (group.kind == NotificationKind.ZAP && group.totalMsat > 0) {
        " · " + formatSats(group.totalMsat)
    } else {
        ""
    }
    if (group.kind == NotificationKind.ZAP && group.actors.isEmpty()) {
        val count = group.itemIds.size
        return "$count zap${if (count == 1) "" else "s"} on your note$sats"
    }
    val primary = group.actors.firstOrNull()?.let { shortPubkey(it) } ?: "Someone"
    val others = group.actorCount - 1
    val attribution = when {
        others <= 0 -> "$primary $verb"
        else -> "$primary and $others other${if (others == 1) "" else "s"} $verb"
    }
    return attribution + sats
}

/** "21 sats" / "1,234 sats" / "500 msat" for fractional amounts. */
private fun formatSats(msat: Long): String =
    if (msat % 1_000 == 0L) "%,d sats".format(msat / 1_000) else "%,d msat".format(msat)

private fun NotificationKind.tint(): Color = when (this) {
    NotificationKind.ZAP -> BitOSColors.zap
    NotificationKind.REACTION -> BitOSColors.like
    NotificationKind.REPOST -> BitOSColors.repost
    NotificationKind.FOLLOW -> BitOSColors.primary
    else -> BitOSColors.textPrimary
}

@Composable
private fun InboxPlaceholder(title: String, message: String) {
    Box(Modifier.fillMaxSize().padding(BitOSSpacing.xxl), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodySmall, color = BitOSColors.textSecondary)
        }
    }
}
