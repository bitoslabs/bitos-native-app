package space.bitos.core.model

/**
 * APP-012 inbox filters (pure): primary tabs, activity chips, the
 * follow-dedupe insertion rule, blocked-author eviction, search matching
 * and the read-cursor rule. All UI filtering funnels through these so
 * iOS and Android agree on what each tab/chip means.
 */
enum class NotificationTab { ALL, UNREAD, MENTIONS, REPLIES }

/** Activity chips; NONE is the unselected state. */
enum class NotificationActivity { NONE, ZAPS, LIKES, REPOSTS, FOLLOWS }

object NotificationFilters {

    /** Search queries are bounded (spec §3.12 search row). */
    const val QUERY_MAX = 64

    fun tabMatches(kind: NotificationKind, tab: NotificationTab, isRead: Boolean): Boolean = when (tab) {
        NotificationTab.ALL -> true
        NotificationTab.UNREAD -> !isRead
        NotificationTab.MENTIONS -> kind == NotificationKind.MENTION
        NotificationTab.REPLIES -> kind == NotificationKind.REPLY
    }

    fun activityMatches(kind: NotificationKind, activity: NotificationActivity): Boolean = when (activity) {
        NotificationActivity.NONE -> true
        NotificationActivity.ZAPS -> kind == NotificationKind.ZAP
        NotificationActivity.LIKES -> kind == NotificationKind.REACTION
        NotificationActivity.REPOSTS -> kind == NotificationKind.REPOST
        NotificationActivity.FOLLOWS -> kind == NotificationKind.FOLLOW
    }

    /** Blocked authors never surface in the inbox or the unread badge. */
    fun blockedEvicted(item: NotificationItem, blockedPubkeys: Set<String>): Boolean =
        item.authorPubkey in blockedPubkeys

    /**
     * Search row (spec §3.12): case-insensitive contains over the summary
     * and the author display name; a blank query matches everything.
     */
    fun queryMatches(item: NotificationItem, query: String, authorName: String? = null): Boolean {
        val needle = query.trim().take(QUERY_MAX).lowercase()
        if (needle.isEmpty()) return true
        if (item.summary.lowercase().contains(needle)) return true
        val name = authorName?.trim()?.lowercase()
        return !name.isNullOrEmpty() && name.contains(needle)
    }

    /**
     * Insertion rule for the bounded notification window: a new item is kept
     * unless it is a republished follow from an author whose (newer) follow
     * is already in the window — kind-3 lists are replaceable and republish
     * often, which would spam one row per publish.
     */
    fun shouldKeep(existing: List<NotificationItem>, candidate: NotificationItem): Boolean {
        if (candidate.kind != NotificationKind.FOLLOW) return true
        return existing.none { it.kind == NotificationKind.FOLLOW && it.authorPubkey == candidate.authorPubkey }
    }

    /**
     * Read-cursor rule (spec §3.12 cursor persistence): beyond the explicit
     * per-id marks, every item at or below the cursor's created-at second is
     * read — relay redelivery of already-seen history never re-rings the
     * badge. A null cursor (fresh install) reads nothing implicitly.
     */
    fun isRead(
        item: NotificationItem,
        cursorSeconds: Long?,
        explicitlyRead: Set<String>,
    ): Boolean = item.id in explicitlyRead ||
        (cursorSeconds != null && item.createdAt <= cursorSeconds)
}
