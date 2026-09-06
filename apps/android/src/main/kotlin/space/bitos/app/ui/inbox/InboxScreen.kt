package space.bitos.app.ui.inbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.bitos.app.data.feed.AuthorRepository
import space.bitos.app.data.feed.NotificationRepository
import space.bitos.app.data.feed.NotificationUiState
import space.bitos.app.data.feed.OriginNoteState
import space.bitos.app.data.publish.NotePublisher
import space.bitos.app.identity.IdentityViewModel
import space.bitos.app.ui.feed.CommentThreadSheet
import space.bitos.app.ui.feed.HomeViewModel
import space.bitos.app.ui.components.AppMenuDropdown
import space.bitos.app.ui.components.AppMenuEntry
import space.bitos.app.ui.components.AppMenuItem
import space.bitos.app.ui.components.HexShape
import space.bitos.app.ui.components.RingHexAvatar
import space.bitos.app.ui.components.formatTimeAgo
import space.bitos.app.ui.components.shortPubkey
import space.bitos.app.ui.theme.AppIcons
import space.bitos.app.ui.theme.BitOSColors
import space.bitos.app.ui.theme.BitOSSpacing
import space.bitos.core.feed.FeedNote
import space.bitos.core.model.NotificationFilters
import space.bitos.core.model.NotificationGroup
import space.bitos.core.model.NotificationKind
import space.bitos.core.model.NotificationSections
import space.bitos.core.model.ProfileMetadata
import space.bitos.core.model.SentZapRecord
import java.time.LocalDate

/**
 * Activity surface (SOC-005 + APP-012, mock 06 `scr-activity` parity):
 * verified events targeting the account rendered as full-width bordered
 * rows — avatar stacks with "+N" plates, amber zap sats, a Follow-back
 * pill, mention quote cards with highlighted @handles, local sent-zap
 * (zap-out) rows from the APP-014 ledger with PAID chips, day sections
 * (Today / Yesterday / Earlier) and the mock chip filter row
 * (All / Zaps / Likes / Follows / Mentions). Visible-mark-read (1.4 s),
 * blocked-author filtering, per-type mutes, search, mark-all-read and the
 * long-press row menu ride the same shared rules as before.
 */

/** Mock 06 chip row: one-of-five source filter (single-select). */
private enum class InboxFilter(val label: String, val icon: ImageVector? = null) {
    ALL("All"),
    ZAPS("Zaps", AppIcons.Zap),
    LIKES("Likes", AppIcons.Heart),
    FOLLOWS("Follows"),
    MENTIONS("Mentions"),
}

/** Unified section row: relay-verified groups + local sent-zap rows. */
private sealed interface ActivityRow {
    val newestAt: Long

    data class Group(val group: NotificationGroup) : ActivityRow {
        override val newestAt: Long get() = group.newestAt
    }

    data class SentZap(val record: SentZapRecord) : ActivityRow {
        override val newestAt: Long get() = record.createdAt
    }
}

/** Day bucket after title collapse (mock: Today / Yesterday / Earlier). */
private data class DisplaySection(val title: String?, val rows: List<ActivityRow>)

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
    /** UX-010: opens the in-app full profile page for a pubkey. */
    onOpenAuthorProfile: (String) -> Unit = {},
    /** Prototype tab merge: Chats (NIP-17 DMs) render inside Activity as a
     *  chip. Unread count rides the chip; the shell supplies the surface. */
    dmUnreadCount: Int = 0,
    chats: @Composable () -> Unit = {},
) {
    val identity by identityViewModel.state.collectAsStateWithLifecycle()
    val state by notifications.state.collectAsStateWithLifecycle()
    val feedState by homeViewModel.state.collectAsStateWithLifecycle()
    val publishState by notePublisher.state.collectAsStateWithLifecycle()
    val authorState by authorRepository.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(InboxFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var headerMenu by remember { mutableStateOf(false) }
    var threadTarget by remember { mutableStateOf<FeedNote?>(null) }
    var authorTarget by remember { mutableStateOf<String?>(null) }
    /** Prototype `activityTab`: Activity | Chats (chats merged into this tab). */
    var activityTab by rememberSaveable { mutableStateOf("notif") }

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

    val profiles = feedState.profiles

    // APP-014 zap-out rows: the sent side is a local ledger, reloaded on
    // every entry to the tab (records only change from other surfaces).
    val sentZaps = remember { homeViewModel.sentZapRecords() }

    val sections = remember(state.items, state.readIds, filter, query, profiles, feedState.blocked, sentZaps) {
        displaySections(state, filter, query, profiles, feedState.blocked, sentZaps)
    }

    // Fetch previews for whatever targets are visible (groups + zap-out).
    LaunchedEffect(state.items, sentZaps) {
        notifications.requestOrigins(
            state.items.mapNotNull { it.targetEventId }.distinct() +
                sentZaps.mapNotNull { it.targetNoteId }.distinct(),
        )
    }

    Column(Modifier.fillMaxSize().background(BitOSColors.background)) {
        when {
            !state.hasAccount -> InboxPlaceholder(
                title = "Activity needs an identity",
                message = "Create or import a key (You tab) to see replies, mentions, reactions, reposts and zaps addressed to you.",
            )
            else -> {
                // Prototype `activityTab` chips: Activity | Chats (unread).
                // ALWAYS visible — they are the tab-level navigation inside
                // Activity and must never disappear into a sub-screen.
                ActivityTabRow(
                    selected = activityTab,
                    dmUnreadCount = dmUnreadCount,
                    onSelect = { activityTab = it },
                )
                if (activityTab == "chats") {
                    // Chats owns its whole surface below the chips: the
                    // Messages header/list (with new-chat) and the
                    // conversation view with its own back chevron.
                    chats()
                } else {
                // Notification-specific header (mark-all-read · search ·
                // type mutes) renders only on the Activity side.
                InboxHeader(
                    mutedKinds = state.mutedKinds,
                    menuExpanded = headerMenu,
                    onToggleMenu = { headerMenu = it },
                    onMarkAllRead = notifications::markAllRead,
                    onOpenSearch = { searchOpen = true },
                    onToggleMute = { kind ->
                        val next = if (kind in state.mutedKinds) state.mutedKinds - kind else state.mutedKinds + kind
                        notifications.setMutedKinds(next)
                    },
                )
                // APP-012 search row: name/content contains over the shared rule.
                if (searchOpen || query.isNotEmpty()) {
                    NotificationSearchRow(
                        query = query,
                        onQueryChange = { query = it },
                        expanded = searchOpen,
                        onToggle = { searchOpen = !searchOpen || query.isNotEmpty() },
                    )
                }
                FilterChipRow(selected = filter, onSelect = { filter = it })
                when {
                    state.items.isEmpty() && sentZaps.isEmpty() && state.offline -> InboxOfflineCard(
                        onReconnect = notifications::reconnect,
                    )
                    state.items.isEmpty() && sentZaps.isEmpty() -> InboxPlaceholder(
                        title = if (state.connected) "No notifications yet" else "Loading activity from relays…",
                        message = if (state.connected) {
                            "Notifications appear when someone replies, mentions you, reacts, reposts or zaps you."
                        } else {
                            "Your activity feed fills once a relay connection succeeds."
                        },
                    )
                    sections.isEmpty() -> InboxPlaceholder(
                        title = "Nothing in this filter",
                        message = if (query.isNotBlank()) {
                            "No notifications match “${query.trim()}”. Clear the search or switch filters to see more."
                        } else {
                            "Switch filters to see other activity."
                        },
                    )
                    else -> ActivityList(
                        sections = sections,
                        state = state,
                        profiles = profiles,
                        following = feedState.following,
                        sensitiveShowByDefault = sensitiveShowByDefault,
                        onMarkRead = notifications::markRead,
                        onFollow = homeViewModel::toggleFollow,
                        onToggleMute = { kind ->
                            val next = if (kind in state.mutedKinds) state.mutedKinds - kind else state.mutedKinds + kind
                            notifications.setMutedKinds(next)
                        },
                        onLoadMore = notifications::loadMore,
                        onOpenThread = { note -> threadTarget = note },
                        onOpenAuthor = { pubkey -> authorTarget = pubkey },
                    )
                }
                }
            }
        }
    }

    // Deep links: target note → thread sheet; otherwise → author sheet.
    threadTarget?.let { note ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { threadTarget = null }) {
            CommentThreadSheet(
                note = note,
                viewModel = homeViewModel,
                identityViewModel = identityViewModel,
                publisherState = publishState,
                onDismiss = { threadTarget = null },
                // UX-010: author taps inside the thread open the profile sheet.
                onOpenAuthor = { authorTarget = it },
            )
        }
    }
    authorTarget?.let { authorPubkey ->
        space.bitos.app.ui.profile.AuthorProfileSheetHost(
            authorPubkey = authorPubkey,
            state = authorState,
            feedState = feedState,
            homeViewModel = homeViewModel,
            identityViewModel = identityViewModel,
            notePublisher = notePublisher,
            onOpen = authorRepository::open,
            onClose = { authorRepository.close(); authorTarget = null },
            // UX-010: the full profile is an in-app page, never a browser link.
            onOpenFullProfile = { authorRepository.close(); authorTarget = null; onOpenAuthorProfile(it) },
            onLoadMore = authorRepository::loadMoreNotes,
        )
    }
}

/** Mock 06 header: "Inbox" + ⋯ (mark all read · search · type filters). */
@Composable
private fun InboxHeader(
    mutedKinds: Set<NotificationKind>,
    menuExpanded: Boolean,
    onToggleMenu: (Boolean) -> Unit,
    onMarkAllRead: () -> Unit,
    onOpenSearch: () -> Unit,
    onToggleMute: (NotificationKind) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Inbox", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { onToggleMenu(true) }) {
                Icon(AppIcons.More, contentDescription = "Inbox options", tint = BitOSColors.textPrimary)
            }
            AppMenuDropdown(
                expanded = menuExpanded,
                onDismissRequest = { onToggleMenu(false) },
                entries = buildList {
                    add(AppMenuEntry.Item(AppMenuItem("read-all", "Mark all read", icon = AppIcons.Check)))
                    add(AppMenuEntry.Item(AppMenuItem("search", "Search", icon = AppIcons.Search)))
                    add(AppMenuEntry.Divider)
                    NotificationKind.entries.forEach { kind ->
                        add(
                            AppMenuEntry.Item(
                                AppMenuItem(
                                    id = kind.name,
                                    label = muteLabel(kind),
                                    checked = kind !in mutedKinds,
                                ),
                            ),
                        )
                    }
                },
                onSelect = { id ->
                    when (id) {
                        "read-all" -> onMarkAllRead()
                        "search" -> onOpenSearch()
                        else -> NotificationKind.entries.firstOrNull { it.name == id }?.let(onToggleMute)
                    }
                },
            )
        }
    }
}

/** Mock 06 chip row: All (orange) / ⚡ Zaps / ♥ Likes / Follows / Mentions. */
/** Prototype `activityTab` chips: Activity | Chats (chats merged into this tab).
 *  Spec (docs/ui/prototype + honeycomb.css): row px-16 · pt-8 · pb-10 · gap-8
 *  with a hairline bottom border; chips are 6×12 full pills, 12sp/600, with a
 *  13dp leading icon and 1dp border; active = solid accent fill. */
@Composable
private fun ActivityTabRow(selected: String, dmUnreadCount: Int, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InboxModeChip(
                icon = AppIcons.Inbox,
                label = "Activity",
                selected = selected == "notif",
                modifier = Modifier,
                onClick = { onSelect("notif") },
            )
            InboxModeChip(
                icon = AppIcons.Chat,
                label = if (dmUnreadCount > 0) "Chats · $dmUnreadCount" else "Chats",
                selected = selected == "chats",
                modifier = Modifier,
                onClick = { onSelect("chats") },
            )
        }
        androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = BitOSColors.divider)
    }
}

@Composable
private fun InboxModeChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
        color = if (selected) BitOSColors.primary else BitOSColors.surface,
        contentColor = if (selected) androidx.compose.ui.graphics.Color(0xFF0A0A0F) else BitOSColors.textSecondary,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) BitOSColors.primary else BitOSColors.border,
        ),
        modifier = modifier.clickable(onClickLabel = label) { onClick() },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun FilterChipRow(selected: InboxFilter, onSelect: (InboxFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InboxFilter.entries.forEach { option ->
            val active = option == selected
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (active) BitOSColors.primary.copy(alpha = 0.14f) else BitOSColors.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (active) BitOSColors.primary.copy(alpha = 0.4f) else BitOSColors.border,
                ),
                modifier = Modifier.clickable(onClickLabel = "Filter ${option.label}") { onSelect(option) },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    option.icon?.let { icon ->
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = if (active) BitOSColors.primary else BitOSColors.textSecondary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.W600,
                        color = if (active) BitOSColors.primary else BitOSColors.textSecondary,
                    )
                }
            }
        }
    }
}

/** Bordered full-width rows + day headers + paging + verified-events footer. */
@Composable
private fun ActivityList(
    sections: List<DisplaySection>,
    state: NotificationUiState,
    profiles: Map<String, ProfileMetadata>,
    following: Set<String>,
    sensitiveShowByDefault: Boolean,
    onMarkRead: (List<String>) -> Unit,
    onFollow: (String) -> Unit,
    onToggleMute: (NotificationKind) -> Unit,
    onLoadMore: () -> Unit,
    onOpenThread: (FeedNote) -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
    var rawJsonFor by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        sections.forEach { section ->
            section.title?.let { title ->
                item(key = "section-$title-${section.rows.firstOrNull()?.newestAt}") {
                    Text(
                        title.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.W700,
                        letterSpacing = 1.sp,
                        color = BitOSColors.textTertiary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
                    )
                }
            }
            section.rows.forEach { row ->
                item(key = rowKey(row), contentType = "activity-row") {
                    when (row) {
                        is ActivityRow.Group -> {
                            val group = row.group
                            val isRead = group.itemIds.all { it in state.readIds }
                            val rowActions = RowActions(
                                isRead = isRead,
                                targetEventId = group.targetEventId,
                                kind = group.kind,
                                actorPubkey = group.actors.firstOrNull(),
                                isMuted = group.kind in state.mutedKinds,
                                onMarkRead = { onMarkRead(group.itemIds) },
                                // Web `openRow`: opening marks the row read.
                                onOpen = {
                                    if (!isRead) onMarkRead(group.itemIds)
                                    openGroup(group, state, onOpenThread, onOpenAuthor)
                                },
                                onOpenAuthor = { group.actors.firstOrNull()?.let(onOpenAuthor) },
                                onToggleMute = { onToggleMute(group.kind) },
                                onShowRaw = {
                                    rawJsonFor = group.itemIds.firstOrNull { it in state.rawEvents }
                                        ?.let { state.rawEvents[it] }
                                },
                            )
                            if (group.kind == NotificationKind.MENTION || group.kind == NotificationKind.REPLY) {
                                MentionCardRow(
                                    group = group,
                                    preview = group.itemIds.firstOrNull()?.let { state.previews[it] },
                                    sensitiveShowByDefault = sensitiveShowByDefault,
                                    profiles = profiles,
                                    actions = rowActions,
                                )
                            } else {
                                GroupRow(
                                    group = group,
                                    origin = group.targetEventId?.let { state.origins[it] },
                                    profiles = profiles,
                                    following = following,
                                    onFollow = onFollow,
                                    actions = rowActions,
                                )
                            }
                        }
                        is ActivityRow.SentZap -> SentZapRow(
                            record = row.record,
                            origin = row.record.targetNoteId?.let { state.origins[it] },
                            profiles = profiles,
                            onOpen = { openSentZap(row.record, state, onOpenThread, onOpenAuthor) },
                        )
                    }
                }
            }
        }
        item(key = "paging-footer") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    state.loadingMore -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = BitOSColors.textSecondary,
                        )
                        Text(
                            "Loading older activity…",
                            style = MaterialTheme.typography.labelMedium,
                            color = BitOSColors.textSecondary,
                        )
                    }
                    state.hasMore -> TextButton(
                        onClick = onLoadMore,
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Text("Load older notifications", fontWeight = FontWeight.W600)
                    }
                    else -> Text(
                        "End of relay results",
                        style = MaterialTheme.typography.labelMedium,
                        color = BitOSColors.textTertiary,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Activity is built from events your relays can verify — no invented counts, no engagement theater.",
                    style = MaterialTheme.typography.labelMedium,
                    color = BitOSColors.textTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    rawJsonFor?.let { raw ->
        space.bitos.app.ui.components.RawEventDialog(json = raw, onDismiss = { rawJsonFor = null })
    }
}

/** Shared long-press ⋯ menu affordance for relay-verified rows. */
private class RowActions(
    val isRead: Boolean,
    val targetEventId: String?,
    val kind: NotificationKind,
    val actorPubkey: String?,
    val isMuted: Boolean,
    val onMarkRead: () -> Unit,
    val onOpen: () -> Unit,
    val onOpenAuthor: () -> Unit,
    val onToggleMute: () -> Unit,
    val onShowRaw: () -> Unit,
)

@Composable
private fun RowMenuHost(
    actions: RowActions,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    AppMenuDropdown(
        expanded = expanded,
        onDismissRequest = { onExpand(false) },
        entries = buildList {
            if (!actions.isRead) add(AppMenuEntry.Item(AppMenuItem("read", "Mark read", icon = AppIcons.Check)))
            actions.actorPubkey?.let {
                add(AppMenuEntry.Item(AppMenuItem("profile", "View profile", icon = AppIcons.User)))
            }
            if (actions.targetEventId != null) {
                add(AppMenuEntry.Item(AppMenuItem("copy", "Copy note id", icon = AppIcons.Copy)))
            }
            add(AppMenuEntry.Item(AppMenuItem("raw", "Raw event JSON", icon = AppIcons.AppsGrid)))
            add(
                AppMenuEntry.Item(
                    AppMenuItem(
                        "mute",
                        if (actions.isMuted) "Unmute this type" else "Mute this type",
                        icon = AppIcons.Mute,
                    ),
                ),
            )
        },
        onSelect = { id ->
            when (id) {
                "read" -> actions.onMarkRead()
                "profile" -> actions.onOpenAuthor()
                "copy" -> clipboard.setText(AnnotatedString(actions.targetEventId ?: ""))
                "raw" -> actions.onShowRaw()
                "mute" -> actions.onToggleMute()
            }
        },
        modifier = modifier,
    )
}

private fun rowKey(row: ActivityRow): String = when (row) {
    is ActivityRow.Group -> "g-${row.group.id}"
    is ActivityRow.SentZap -> "z-${row.record.id}"
}

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

/** Deep link: verified origin note → thread sheet; otherwise → author. */
private fun openGroup(
    group: NotificationGroup,
    state: NotificationUiState,
    onOpenThread: (FeedNote) -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
    val originReady = group.targetEventId?.let { state.origins[it] } as? OriginNoteState.Ready
    val note = originReady?.let { originFeedNote(group.targetEventId!!, it.note) }
    if (note != null) onOpenThread(note) else group.actors.firstOrNull()?.let(onOpenAuthor)
}

private fun openSentZap(
    record: SentZapRecord,
    state: NotificationUiState,
    onOpenThread: (FeedNote) -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
    val originReady = record.targetNoteId?.let { state.origins[it] } as? OriginNoteState.Ready
    val note = originReady?.let { originFeedNote(record.targetNoteId!!, it.note) }
    if (note != null) onOpenThread(note) else onOpenAuthor(record.recipientPubkey)
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
    mediaUrls = origin.mediaUrls,
    isProtocolPayload = false,
)

/**
 * Shared-rule filtering + mock chip mapping, day sections with sent-zap
 * rows merged in, and the mock title collapse (Today / Yesterday / one
 * "Earlier" for everything older).
 */
private fun displaySections(
    state: NotificationUiState,
    filter: InboxFilter,
    query: String,
    profiles: Map<String, ProfileMetadata>,
    blocked: Set<String>,
    sentZaps: List<SentZapRecord>,
): List<DisplaySection> {
    val filtered = state.items.filter { item ->
        item.authorPubkey !in blocked && // APP-012 blocked-author filtering (kind-10004 head)
            NotificationFilters.queryMatches(item, query, profiles[item.authorPubkey]?.bestDisplayName) &&
            filterMatches(item.kind, filter)
    }
    val zapOutIncluded = filter == InboxFilter.ALL || filter == InboxFilter.ZAPS
    // Zap-out rows are local ledger records, not notifications — an active
    // search indexes notifications only, so they stay out of query results.
    val zapOutRows = if (zapOutIncluded && query.isBlank()) sentZaps else emptyList()
    val now = System.currentTimeMillis() / 1000
    val today = NotificationSections.epochDayOf(now)
    val oldestDay = today - (NotificationSections.MAX_SECTIONS - 1)

    val byDay = LinkedHashMap<Long, MutableList<ActivityRow>>()
    NotificationSections.sections(filtered, now).forEach { section ->
        byDay[section.epochDay] = section.groups.map { ActivityRow.Group(it) }.toMutableList()
    }
    zapOutRows.forEach { record ->
        val day = NotificationSections.epochDayOf(record.createdAt)
        if (day in oldestDay..today + 1) {
            byDay.getOrPut(day) { mutableListOf() }.add(ActivityRow.SentZap(record))
        }
    }

    var previousTitle: String? = null
    return byDay.entries
        .sortedByDescending { it.key }
        .map { (day, rows) ->
            val raw = sectionTitle(day)
            val title = if (raw == previousTitle) null else raw
            previousTitle = raw
            DisplaySection(title, rows.sortedByDescending { it.newestAt })
        }
        .filter { it.rows.isNotEmpty() }
}

/** Mock 06 titles: Today / Yesterday / Earlier (collapsed, not repeated). */
private fun sectionTitle(epochDay: Long): String {
    val today = LocalDate.now().toEpochDay()
    return when (epochDay) {
        today -> "Today"
        today - 1 -> "Yesterday"
        else -> "Earlier"
    }
}

/** Mock chip → shared filter rules (Mentions covers mentions + replies). */
private fun filterMatches(kind: NotificationKind, filter: InboxFilter): Boolean = when (filter) {
    InboxFilter.ALL -> true
    InboxFilter.ZAPS -> kind == NotificationKind.ZAP
    InboxFilter.LIKES -> kind == NotificationKind.REACTION
    InboxFilter.FOLLOWS -> kind == NotificationKind.FOLLOW
    InboxFilter.MENTIONS -> kind == NotificationKind.MENTION || kind == NotificationKind.REPLY
}

private fun muteLabel(kind: NotificationKind): String = when (kind) {
    NotificationKind.REPLY -> "Replies"
    NotificationKind.MENTION -> "Mentions"
    NotificationKind.REACTION -> "Likes"
    NotificationKind.REPOST -> "Reposts"
    NotificationKind.ZAP -> "Zaps"
    NotificationKind.FOLLOW -> "Follows"
}

/** One bordered row for aggregated kinds (zap/likes/repost/follow). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(
    group: NotificationGroup,
    origin: OriginNoteState?,
    profiles: Map<String, ProfileMetadata>,
    following: Set<String>,
    onFollow: (String) -> Unit,
    actions: RowActions,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().background(BitOSColors.background)) {
        RowMenuHost(actions, menuExpanded, { menuExpanded = it }, Modifier.align(Alignment.TopEnd))
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = actions.onOpen, onLongClick = { menuExpanded = true })
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GroupLeading(group, profiles)
            Column(Modifier.weight(1f)) {
                Text(
                    groupTitle(group, profiles, originIsBitz(origin)),
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                groupSubtitle(group, origin)?.let { subtitle ->
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = BitOSColors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            GroupTrailing(group, following, onFollow)
        }
        if (!actions.isRead) {
            UnreadStripe(kindTint(group.kind))
        }
        RowDivider()
    }
}

/** The mock 3 dp left accent bar marking an unread row. */
@Composable
private fun BoxScope.UnreadStripe(tint: Color) {
    Spacer(
        Modifier
            .fillMaxHeight()
            .width(3.dp)
            .background(tint)
            .align(Alignment.CenterStart),
    )
}

@Composable
private fun BoxScope.RowDivider() {
    HorizontalDivider(
        modifier = Modifier.align(Alignment.BottomCenter),
        // Home-feed parity: soft divider token as a hairline.
        thickness = 0.5.dp,
        color = BitOSColors.divider,
    )
}

/** Avatar stack (zap/likes/repost) or single verified avatar (follow). */
@Composable
private fun GroupLeading(group: NotificationGroup, profiles: Map<String, ProfileMetadata>) {
    if (group.kind == NotificationKind.FOLLOW) {
        val actor = group.actors.firstOrNull()
        RingHexAvatar(
            pubkey = actor ?: "00".repeat(32),
            size = 40,
            label = actor?.let { profiles[it]?.bestDisplayName },
            imageUrl = actor?.let { profiles[it]?.picture },
            verified = actor?.let { profiles[it]?.nip05 } != null,
        )
        return
    }
    if (group.actors.isEmpty()) {
        // Anonymous zap receipts: an amber bolt plate stands in for actors.
        HexKindPlate(tint = BitOSColors.zap) {
            Icon(AppIcons.Zap, contentDescription = "Zaps", tint = BitOSColors.zap, modifier = Modifier.size(16.dp))
        }
        return
    }
    val withPlate = group.actorCount > 3
    val actors = if (withPlate) group.actors.take(2) else group.actors.take(3)
    Row(verticalAlignment = Alignment.CenterVertically) {
        actors.forEachIndexed { index, actor ->
            Box(
                Modifier
                    .offset(x = if (index == 0) 0.dp else (-8).dp)
                    .zIndex((actors.size - index).toFloat()),
            ) {
                RingHexAvatar(
                    pubkey = actor,
                    size = 40,
                    label = profiles[actor]?.bestDisplayName,
                    imageUrl = profiles[actor]?.picture,
                    verified = index == 0 && profiles[actor]?.nip05 != null,
                )
            }
        }
        if (withPlate) {
            HexCountPlate(count = group.actorCount - actors.size, modifier = Modifier.offset(x = (-8).dp))
        }
    }
}

/** Dim hex tile used for the anonymous-zap bolt plate. */
@Composable
private fun HexKindPlate(tint: Color, content: @Composable () -> Unit) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).clip(HexShape()).background(tint.copy(alpha = 0.4f)))
        Box(
            Modifier.size(36.dp).clip(HexShape()).background(BitOSColors.surface),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/** Mock "+12" hex plate for aggregated actors beyond the shown stack. */
@Composable
private fun HexCountPlate(count: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).clip(HexShape()).background(BitOSColors.border))
        Box(
            Modifier.size(36.dp).clip(HexShape()).background(BitOSColors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "+$count",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.W700,
                color = BitOSColors.textSecondary,
            )
        }
    }
}

/** Trailing affordance per kind: bolt / heart / repost icon / follow pill. */
@Composable
private fun GroupTrailing(
    group: NotificationGroup,
    following: Set<String>,
    onFollow: (String) -> Unit,
) {
    when (group.kind) {
        NotificationKind.ZAP -> Icon(
            AppIcons.Zap,
            contentDescription = "Zap",
            tint = BitOSColors.zap,
            modifier = Modifier.size(18.dp),
        )
        NotificationKind.REACTION -> Icon(
            AppIcons.Heart,
            contentDescription = "Like",
            tint = BitOSColors.like,
            modifier = Modifier.size(18.dp),
        )
        NotificationKind.REPOST -> Icon(
            AppIcons.Repost,
            contentDescription = "Repost",
            tint = BitOSColors.repost,
            modifier = Modifier.size(18.dp),
        )
        NotificationKind.FOLLOW -> {
            val actor = group.actors.firstOrNull()
            when {
                actor == null -> Unit
                actor in following -> Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BitOSColors.border),
                ) {
                    Text(
                        "Following ✓",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.W700,
                        color = BitOSColors.textTertiary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                else -> Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = BitOSColors.textPrimary,
                    modifier = Modifier.clickable(onClickLabel = "Follow back") { onFollow(actor) },
                ) {
                    Text(
                        "Follow back",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.W700,
                        color = BitOSColors.background,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    )
                }
            }
        }
        else -> Unit
    }
}

/** Zap-out row (APP-014): dimmed local-ledger record with a PAID chip. */
@Composable
private fun SentZapRow(
    record: SentZapRecord,
    origin: OriginNoteState?,
    profiles: Map<String, ProfileMetadata>,
    onOpen: () -> Unit,
) {
    val name = profiles[record.recipientPubkey]?.bestDisplayName ?: shortPubkey(record.recipientPubkey)
    // Media-only origins show a "Media" stand-in (links never surface as text).
    val ready = (origin as? OriginNoteState.Ready)?.note
    val excerpt = ready?.excerpt?.takeIf { it.isNotBlank() }
        ?: ready?.mediaUrls?.takeIf { it.isNotEmpty() }?.let { "Media" }
    Box(
        Modifier
            .fillMaxWidth()
            .alpha(0.8f)
            .background(BitOSColors.background),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                AppIcons.Zap,
                contentDescription = "Zap sent",
                tint = BitOSColors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        append("You zapped ")
                        withStyle(SpanStyle(fontWeight = FontWeight.W700)) { append(name) }
                        withStyle(SpanStyle(fontWeight = FontWeight.W700)) { append(" ${formatSats(record.amountSats * 1_000)}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(excerpt?.let { "“$it”" }, "paid", formatTimeAgo(record.createdAt, nowSeconds())).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(1.dp, BitOSColors.success.copy(alpha = 0.4f)),
            ) {
                Text(
                    "PAID",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.W700,
                    color = BitOSColors.success,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        RowDivider()
    }
}

/** Mention/reply row (mock): header line + quoted content card (web preview
 * parity: cleaned excerpt + media strip behind the NIP-36 cover). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MentionCardRow(
    group: NotificationGroup,
    preview: space.bitos.core.model.OriginNote?,
    sensitiveShowByDefault: Boolean,
    profiles: Map<String, ProfileMetadata>,
    actions: RowActions,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val actor = group.actors.firstOrNull()
    val name = actor?.let { profiles[it]?.bestDisplayName } ?: "Someone"
    Box(Modifier.fillMaxWidth().background(BitOSColors.background)) {
        RowMenuHost(actions, menuExpanded, { menuExpanded = it }, Modifier.align(Alignment.TopEnd))
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = actions.onOpen, onLongClick = { menuExpanded = true })
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RingHexAvatar(
                    pubkey = actor ?: "00".repeat(32),
                    size = 32,
                    label = profiles[actor]?.bestDisplayName,
                    imageUrl = actor?.let { profiles[it]?.picture },
                )
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.W700)) { append(name) }
                        append(if (group.kind == NotificationKind.REPLY) " replied to your note" else " mentioned you")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatTimeAgo(group.newestAt, nowSeconds()),
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textTertiary,
                )
            }
            Spacer(Modifier.height(8.dp))
            // Quote card aligns with the name line: avatar (32) + gap (10).
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = BitOSColors.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, BitOSColors.border),
                modifier = Modifier.padding(start = 42.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        mentionAnnotated(preview?.excerpt?.takeIf { it.isNotBlank() } ?: group.sampleSummary),
                        style = MaterialTheme.typography.bodySmall,
                        color = BitOSColors.textSecondary,
                        lineHeight = 18.sp,
                    )
                    preview?.let { note ->
                        if (note.mediaUrls.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            NotificationMediaStrip(note, sensitiveShowByDefault)
                        }
                    }
                }
            }
        }
        if (!actions.isRead) {
            UnreadStripe(kindTint(group.kind))
        }
        RowDivider()
    }
}

/** APP-012 media strip: ≤4 16:9 tiles behind a NIP-36 cover (APP-018 gate). */
@Composable
private fun NotificationMediaStrip(
    note: space.bitos.core.model.OriginNote,
    sensitiveShowByDefault: Boolean,
) {
    var revealed by remember(note.id) { mutableStateOf(false) }
    var lightboxUrl by remember(note.id) { mutableStateOf<String?>(null) }
    lightboxUrl?.let { url ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { lightboxUrl = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            space.bitos.app.ui.components.MediaLightbox(url = url, onDismiss = { lightboxUrl = null })
        }
    }
    if (note.contentWarning && !revealed && !sensitiveShowByDefault) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = BitOSColors.surfaceOverlay,
            modifier = Modifier.clickable(onClickLabel = "Reveal sensitive media") { revealed = true },
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(AppIcons.Photo, contentDescription = null, tint = BitOSColors.textTertiary, modifier = Modifier.size(13.dp))
                Text(
                    "Sensitive content — tap to reveal",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitOSColors.textTertiary,
                )
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            note.mediaUrls.forEach { url ->
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

/** @handles and nostr:npub tokens render in brand orange (mock parity). */
private val mentionToken = Regex("(?:@[A-Za-z0-9_.]+|nostr:npub1[0-9a-z]+)")

@Composable
private fun mentionAnnotated(content: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in mentionToken.findAll(content)) {
        append(content.substring(cursor, match.range.first))
        withStyle(SpanStyle(color = BitOSColors.primary)) { append(match.value) }
        cursor = match.range.last + 1
    }
    append(content.substring(cursor))
}

/** "A, B and N others zapped 1,021 sats" — two named actors max (web
 * `actorSummary` parity), bold names, amber sats. */
@Composable
private fun groupTitle(
    group: NotificationGroup,
    profiles: Map<String, ProfileMetadata>,
    isBitz: Boolean,
): AnnotatedString = buildAnnotatedString {
    fun nameAt(index: Int): String? = group.actors.getOrNull(index)
        ?.let { profiles[it]?.bestDisplayName ?: shortPubkey(it) }
    val named = listOfNotNull(nameAt(0), nameAt(1).takeIf { group.actorCount >= 2 })
    val others = (group.actorCount - named.size).coerceAtLeast(0)
    fun leadActors() {
        named.forEachIndexed { index, actor ->
            if (index > 0) append(", ")
            withStyle(SpanStyle(fontWeight = FontWeight.W700)) { append(actor) }
        }
        if (others > 0) append(" and $others other${if (others == 1) "" else "s"}")
    }
    when (group.kind) {
        NotificationKind.ZAP -> {
            if (named.isEmpty()) {
                val count = group.itemIds.size
                append("${count} zap${if (count == 1) "" else "s"} on your note")
            } else {
                leadActors()
                append(" zapped ")
                if (group.totalMsat > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.W700, color = BitOSColors.zap)) {
                        append(formatSats(group.totalMsat))
                    }
                }
            }
        }
        NotificationKind.REACTION -> {
            if (named.isEmpty()) {
                append("Someone liked your ${if (isBitz) "Bitz" else "note"}")
            } else {
                leadActors()
                append(" liked your ${if (isBitz) "Bitz" else "note"}")
            }
        }
        NotificationKind.REPOST -> {
            if (named.isEmpty()) append("Someone") else leadActors()
            append(" reposted your note")
        }
        NotificationKind.FOLLOW -> {
            if (named.isEmpty()) append("Someone") else leadActors()
            append(" followed you")
        }
        else -> Unit
    }
}

/** "“note excerpt” · 2m ago" — the quote rides the verified origin preview.
 * Media-only origins show a "Media" stand-in (links never surface as text). */
private fun groupSubtitle(group: NotificationGroup, origin: OriginNoteState?): String? {
    val ready = (origin as? OriginNoteState.Ready)?.note
    val excerpt = ready?.excerpt?.takeIf { it.isNotBlank() }
        ?: ready?.mediaUrls?.takeIf { it.isNotEmpty() }?.let { "Media" }
    val time = formatTimeAgo(group.newestAt, nowSeconds())
    return when (group.kind) {
        NotificationKind.FOLLOW -> time
        NotificationKind.ZAP, NotificationKind.REACTION, NotificationKind.REPOST ->
            if (excerpt != null) "“$excerpt” · $time" else time
        else -> null
    }
}

private fun originIsBitz(origin: OriginNoteState?): Boolean =
    (origin as? OriginNoteState.Ready)?.note?.kind == 22

/** Unread stripe tint per kind (zap amber, like pink, repost green…). */
@Composable
private fun kindTint(kind: NotificationKind): Color = when (kind) {
    NotificationKind.ZAP -> BitOSColors.zap
    NotificationKind.REACTION -> BitOSColors.like
    NotificationKind.REPOST -> BitOSColors.repost
    NotificationKind.FOLLOW -> BitOSColors.primary
    NotificationKind.MENTION -> BitOSColors.accent
    NotificationKind.REPLY -> BitOSColors.reply
}

/** "21 sats" / "1,234 sats" / "500 msat" for fractional amounts. */
private fun formatSats(msat: Long): String =
    if (msat % 1_000 == 0L) "%,d sats".format(msat / 1_000) else "%,d msat".format(msat)

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
            .padding(horizontal = 16.dp)
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
    ) {
        IconButton(onClick = onToggle) {
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
            TextButton(onClick = { onQueryChange("") }) {
                Text("Clear", color = BitOSColors.primary, fontWeight = FontWeight.W600)
            }
        }
    }
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

/** Web parity offline card: the head REQ never answered — offer retry. */
@Composable
private fun InboxOfflineCard(onReconnect: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(BitOSSpacing.xxl), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BitOSSpacing.sm),
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = BitOSColors.error.copy(alpha = 0.12f)) {
                Icon(
                    AppIcons.Globe,
                    contentDescription = null,
                    tint = BitOSColors.error,
                    modifier = Modifier.padding(14.dp).size(26.dp),
                )
            }
            Text("Couldn't reach relays", style = MaterialTheme.typography.titleMedium)
            Text(
                "We'll keep retrying, or tap below to try again.",
                style = MaterialTheme.typography.bodySmall,
                color = BitOSColors.textSecondary,
            )
            Button(
                onClick = onReconnect,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.padding(top = BitOSSpacing.sm),
            ) {
                Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reconnect", fontWeight = FontWeight.W700)
            }
        }
    }
}
