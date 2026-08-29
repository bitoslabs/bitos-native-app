package space.bitos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * APP-011 DM grouping: per-peer conversations (sent messages group under
 * the recipient), newest-first conversation order, chronological messages,
 * bounds.
 */
class DmGroupingTest {

    private val me = "aa".repeat(32)
    private val alice = "bb".repeat(32)
    private val bob = "cc".repeat(32)

    private fun msg(id: String, from: String, to: String, text: String, at: Long) =
        DmMessage(id, from, to, text, at)

    @Test
    fun groupsIntoPerPeerConversations() {
        val conversations = DmGrouping.group(
            listOf(
                msg("1", alice, me, "hi", 100),
                msg("2", me, alice, "hello", 200),
                msg("3", bob, me, "gm", 300),
                msg("4", alice, me, "bye", 400),
            ),
            myPubkey = me,
        )
        assertEquals(2, conversations.size)
        // Alice has the newest message (400) → first.
        assertEquals(alice, conversations[0].peerPubkey)
        assertEquals(3, conversations[0].messages.size)
        // Messages chronological within the conversation.
        assertEquals(listOf(100L, 200L, 400L), conversations[0].messages.map { it.createdAt })
        assertEquals(bob, conversations[1].peerPubkey)
    }

    @Test
    fun sentMessagesGroupUnderTheRecipient() {
        val conversations = DmGrouping.group(
            listOf(
                msg("1", me, bob, "sent to bob", 100),
                msg("2", me, alice, "sent to alice", 200),
            ),
            myPubkey = me,
        )
        assertEquals(2, conversations.size)
        assertTrue(conversations.any { it.peerPubkey == bob })
        assertTrue(conversations.any { it.peerPubkey == alice })
    }

    @Test
    fun boundsAreRespected() {
        // MAX_CONVERSATIONS distinct peers + extra messages per conversation.
        val messages = (0 until DmGrouping.MAX_CONVERSATIONS + 5).flatMap { peerIndex ->
            val peer = peerIndex.toString(16).padStart(64, '0')
            (0 until 3).map { msg("$peerIndex-$it", peer, me, "m$it", (peerIndex * 10 + it).toLong()) }
        }
        val conversations = DmGrouping.group(messages, me)
        assertEquals(DmGrouping.MAX_CONVERSATIONS, conversations.size)
    }

    @Test
    fun emptyInputYieldsNoConversations() {
        assertTrue(DmGrouping.group(emptyList(), me).isEmpty())
    }
}
