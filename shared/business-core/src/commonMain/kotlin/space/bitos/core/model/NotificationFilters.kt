package space.bitos.core.model

/**
 * APP-012 inbox filters (pure): primary tabs, activity chips and the
 * follow-dedupe insertion rule. All UI filtering funnels through these so
 * iOS and Android agree on what each tab/chip means.
 */
enum class NotificationTab { ALL, UNREAD, MENTIONS, REPLIES }

/** Activity chips; NONE is the unselected state. */
enum class NotificationActivity { NONE, ZAPS, LIKES, REPOSTS, FOLLOWS }

object NotificationFilters {

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
}
