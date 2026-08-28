package space.bitos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationFiltersTest {

    private fun item(
        id: String = "a".repeat(64),
        author: String = "e93fbf1000405bc8bb536a8ae37eebe349ebde8ecae3779ad3786def739aa301",
        kind: NotificationKind = NotificationKind.REPLY,
        createdAt: Long = 1_000,
        summary: String = "nice post",
    ) = NotificationItem(id = id, authorPubkey = author, kind = kind, targetEventId = null, summary = summary, createdAt = createdAt)

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

    /** APP-012 search row: summary + author-name contains, blank = match. */
    @Test
    fun queryMatchesSummaryAndAuthorNameCaseInsensitively() {
        val reply = item(summary = "Great post about Bitcoin")
        assertTrue(NotificationFilters.queryMatches(reply, ""))
        assertTrue(NotificationFilters.queryMatches(reply, "   "))
        assertTrue(NotificationFilters.queryMatches(reply, "bitcoin"))
        assertTrue(NotificationFilters.queryMatches(reply, "GREAT POST"))
        assertFalse(NotificationFilters.queryMatches(reply, "zap"))
        // Author display name participates when the platform supplies it.
        assertTrue(NotificationFilters.queryMatches(reply, "satoshi", authorName = "Satoshi Nakamoto"))
        assertFalse(NotificationFilters.queryMatches(reply, "satoshi", authorName = null))
        assertFalse(NotificationFilters.queryMatches(reply, "satoshi", authorName = ""))
        // Queries are bounded: an oversized needle is truncated to QUERY_MAX
        // before matching (the tail past 64 chars is ignored, never matched).
        assertFalse(NotificationFilters.queryMatches(reply, "x".repeat(NotificationFilters.QUERY_MAX) + "bitcoin"))
    }

    /** APP-012 blocked-author eviction. */
    @Test
    fun blockedAuthorsAreEvicted() {
        val blocked = setOf("e93fbf1000405bc8bb536a8ae37eebe349ebde8ecae3779ad3786def739aa301")
        assertTrue(NotificationFilters.blockedEvicted(item(), blockedPubkeys = blocked))
        assertFalse(NotificationFilters.blockedEvicted(item(author = "aa".repeat(32)), blockedPubkeys = blocked))
        assertFalse(NotificationFilters.blockedEvicted(item(), blockedPubkeys = emptySet()))
    }

    /** APP-012 read cursor: redelivered history never re-rings. */
    @Test
    fun readCursorMarksHistoryReadBeyondExplicitIds() {
        val old = item(id = "old", createdAt = 900)
        val atCursor = item(id = "at", createdAt = 1_000)
        val fresh = item(id = "new", createdAt = 1_100)
        val explicit = setOf("new")

        // No cursor (fresh install): only explicit marks read.
        assertFalse(NotificationFilters.isRead(old, cursorSeconds = null, explicitlyRead = explicit))
        assertTrue(NotificationFilters.isRead(fresh, cursorSeconds = null, explicitlyRead = explicit))

        // Cursor at 1000: everything at or below is implicitly read.
        val read = setOf(old.id, atCursor.id)
        listOf(old, atCursor).forEach {
            assertTrue(NotificationFilters.isRead(it, cursorSeconds = 1_000, explicitlyRead = emptySet()), "${it.id} should be cursor-read")
        }
        assertFalse(NotificationFilters.isRead(fresh, cursorSeconds = 1_000, explicitlyRead = emptySet()))
        assertTrue(read.size == 2)
    }
}
