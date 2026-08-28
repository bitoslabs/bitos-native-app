package space.bitos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationFiltersTest {

    @Test
    fun primaryTabsFilterByKindAndReadState() {
        val reply = NotificationKind.REPLY
        val mention = NotificationKind.MENTION

        assertTrue(NotificationFilters.tabMatches(reply, NotificationTab.ALL, isRead = true))
        assertTrue(NotificationFilters.tabMatches(mention, NotificationTab.ALL, isRead = false))
        assertTrue(NotificationFilters.tabMatches(reply, NotificationTab.UNREAD, isRead = false))
        assertFalse(NotificationFilters.tabMatches(reply, NotificationTab.UNREAD, isRead = true))
        assertTrue(NotificationFilters.tabMatches(mention, NotificationTab.MENTIONS, isRead = true))
        assertFalse(NotificationFilters.tabMatches(reply, NotificationTab.MENTIONS, isRead = false))
        assertTrue(NotificationFilters.tabMatches(reply, NotificationTab.REPLIES, isRead = true))
        assertFalse(NotificationFilters.tabMatches(mention, NotificationTab.REPLIES, isRead = false))
    }

    @Test
    fun activityChipsMatchTheirKind() {
        assertTrue(NotificationFilters.activityMatches(NotificationKind.ZAP, NotificationActivity.ZAPS))
        assertTrue(NotificationFilters.activityMatches(NotificationKind.REACTION, NotificationActivity.LIKES))
        assertTrue(NotificationFilters.activityMatches(NotificationKind.REPOST, NotificationActivity.REPOSTS))
        assertTrue(NotificationFilters.activityMatches(NotificationKind.FOLLOW, NotificationActivity.FOLLOWS))
        assertFalse(NotificationFilters.activityMatches(NotificationKind.REPLY, NotificationActivity.ZAPS))
        // NONE passes everything (chip unselected).
        NotificationKind.entries.forEach { kind ->
            assertTrue(NotificationFilters.activityMatches(kind, NotificationActivity.NONE))
        }
    }

    @Test
    fun republishedFollowsFromKnownAuthorsAreDropped() {
        val first = NotificationItem("f1", "pk1", NotificationKind.FOLLOW, null, "", 1_000)
        val republish = NotificationItem("f2", "pk1", NotificationKind.FOLLOW, null, "", 2_000)
        val otherAuthor = NotificationItem("f3", "pk2", NotificationKind.FOLLOW, null, "", 3_000)
        val reply = NotificationItem("r1", "pk1", NotificationKind.REPLY, "t1", "", 4_000)

        assertFalse(NotificationFilters.shouldKeep(listOf(first), republish))
        assertTrue(NotificationFilters.shouldKeep(listOf(first), otherAuthor))
        assertTrue(NotificationFilters.shouldKeep(listOf(first), reply))
    }
}
