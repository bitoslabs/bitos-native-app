package space.bitos.core.model

/**
 * APP-011 DM presentation rules (pure): read cursors → unread counts,
 * conversation acceptance (message requests) and generic previews.
 *
 * The repos own relay subscription, unwrapping and persistence; this owns
 * every deterministic display decision so iOS and Compose render identical
 * state from identical inputs (mock `app-06-inbox-activity-messages` +
 * spec §3.11 + §11 "secure messages keep notification payload generic").
 */
object DmPresentation {

    /** Preview cap: previews stay short by construction, list rows truncate. */
    const val PREVIEW_MAX_CHARS = 96

    /**
     * Unread messages in one conversation: count of received messages newer
     * than the peer's persisted read cursor. Sent messages never count.
     * Read-anywhere semantics: a cursor at/above the newest message marks
     * the whole conversation read.
     */
    fun unreadCount(conversation: DmConversation, myPubkey: String, lastReadAt: Long): Int =
        conversation.messages.count { message ->
            message.authorPubkey != myPubkey && message.createdAt > lastReadAt
        }

    /** True when the conversation has at least one unread received message. */
    fun hasUnread(conversation: DmConversation, myPubkey: String, lastReadAt: Long): Boolean =
        conversation.messages.any { message ->
            message.authorPubkey != myPubkey && message.createdAt > lastReadAt
        }

    /**
     * Next cursor value when a conversation opens: the newest message
     * timestamp (sent or received). Returns the current cursor when the
     * conversation is empty so opening an empty thread can't rewind reads.
     */
    fun nextCursor(conversation: DmConversation, currentCursor: Long): Long =
        maxOf(currentCursor, conversation.messages.maxOfOrNull { it.createdAt } ?: currentCursor)

    /**
     * Conversation acceptance: unaccepted peers stay in Message requests
     * until the user accepts; messages from any peer a later outgoing
     * message addressed are auto-accepted (talking is accepting — mockup
     * "accept starts a thread, delete stays silent to the sender").
     */
    fun isAccepted(
        peerPubkey: String,
        everSentTo: Set<String>,
        explicitlyAccepted: Set<String>,
        explicitlyDeclined: Set<String>,
    ): Boolean = when {
        peerPubkey in explicitlyDeclined -> false
        peerPubkey in explicitlyAccepted -> true
        peerPubkey in everSentTo -> true
        else -> false
    }

    /**
     * Generic list preview (NIP-17 privacy): covers are content-free —
     * "New message" when the last received message is unread, the lock
     * line otherwise. Content never renders outside the decrypted chat.
     */
    fun previewLine(
        conversation: DmConversation,
        myPubkey: String,
        lastReadAt: Long,
    ): String {
        val last = conversation.lastMessage ?: return "No messages"
        return if (hasUnread(conversation, myPubkey, lastReadAt) && last.authorPubkey != myPubkey) {
            "New message"
        } else {
            "Encrypted · decrypt in app"
        }
    }

    /** Truncation helper for chat rows (bounded previews). */
    fun truncate(text: String, maxChars: Int = PREVIEW_MAX_CHARS): String =
        if (text.length <= maxChars) text else text.take(maxChars - 1).trimEnd() + "…"
}
