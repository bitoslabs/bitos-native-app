package space.bitos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * APP-011 DM presentation rules: unread cursors (received-only counting),
 * read-anywhere semantics, requests acceptance, generic NIP-17 previews.
 */
class DmPresentationTest {

    private val me = "aa".repeat(32)
    private val alice = "bb".repeat(32)

    private fun conversation(vararg messages: DmMessage) =
        DmConversation(peerPubkey = alice, messages = messages.toList())

    @Test
    fun unreadCountsOnlyReceivedMessagesAfterTheCursor() {
        val conv = conversation(
            DmMessage("1", alice, me, "read", 100),
            DmMessage("2", me, alice, "sent", 200),
            DmMessage("3", alice, me, "new", 300),
            DmMessage("4", alice, me, "newer", 400),
        )
        assertEquals(2, DmPresentation.unreadCount(conv, me, lastReadAt = 100))
        assertEquals(0, DmPresentation.unreadCount(conv, me, lastReadAt = 400))
        // The sent message at 200 never counts (not received, and not > 200).
        assertEquals(2, DmPresentation.unreadCount(conv, me, lastReadAt = 200))
    }

    @Test
    fun cursorAdvancesToNewestAndNeverRewinds() {
        val conv = conversation(DmMessage("1", alice, me, "m", 300))
        assertEquals(300, DmPresentation.nextCursor(conv, currentCursor = 100))
        // Opening an older/empty thread must not rewind reads.
        assertEquals(400, DmPresentation.nextCursor(conversation(), currentCursor = 400))
    }

    @Test
    fun acceptanceAllowsExplicitSentAndRevokesAfterDecline() {
        assertTrue(DmPresentation.isAccepted(alice, everSentTo = setOf(alice), explicitlyAccepted = emptySet(), explicitlyDeclined = emptySet()))
        assertTrue(DmPresentation.isAccepted(alice, everSentTo = emptySet(), explicitlyAccepted = setOf(alice), explicitlyDeclined = emptySet()))
        // Decline wins: a later inbound message stays in requests.
        assertFalse(DmPresentation.isAccepted(alice, everSentTo = emptySet(), explicitlyAccepted = setOf(alice), explicitlyDeclined = setOf(alice)))
        assertFalse(DmPresentation.isAccepted(alice, everSentTo = emptySet(), explicitlyAccepted = emptySet(), explicitlyDeclined = emptySet()))
    }

    @Test
    fun previewsStayGenericUntilRead() {
        val unread = conversation(
            DmMessage("1", alice, me, "secret plaintext", 100),
        )
        assertEquals("New message", DmPresentation.previewLine(unread, me, lastReadAt = 0))
        val read = conversation(
            DmMessage("1", alice, me, "secret plaintext", 100),
            DmMessage("2", me, alice, "reply", 200),
        )
        // Reminder line, not plaintext.
        assertEquals("Encrypted · decrypt in app", DmPresentation.previewLine(read, me, lastReadAt = 100))
        assertEquals("No messages", DmPresentation.previewLine(conversation(), me, lastReadAt = 0))
    }

    @Test
    fun truncationIsBounded() {
        val long = "x".repeat(200)
        assertEquals(96, DmPresentation.truncate(long).length)
        assertTrue(DmPresentation.truncate(long).endsWith("…"))
        assertEquals("abc", DmPresentation.truncate("abc"))
    }
}
