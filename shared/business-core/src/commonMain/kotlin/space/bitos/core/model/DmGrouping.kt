package space.bitos.core.model

/**
 * APP-011 DM conversation grouping (pure rule): verified kind-14 rumors
 * (unwrapped gift wraps) → per-peer conversation windows, newest-first
 * message ordering, bounded per conversation. The repos own subscription
 * and unwrapping; this owns the display grouping.
 */
data class DmMessage(
    val id: String,
    /** Author of the message (the rumor's pubkey). */
    val authorPubkey: String,
    /** The other party in the conversation. */
    val peerPubkey: String,
    val content: String,
    val createdAt: Long,
)

data class DmConversation(
    val peerPubkey: String,
    val messages: List<DmMessage>,
) {
    val lastMessage: DmMessage? get() = messages.lastOrNull()
    val lastAt: Long get() = lastMessage?.createdAt ?: 0
}

object DmGrouping {

    /** Bounded conversations (distinct peers). */
    const val MAX_CONVERSATIONS = 32

    /** Bounded messages per conversation. */
    const val MAX_MESSAGES = 200

    /**
     * Groups messages into per-peer conversations (my pubkey decides the
     * peer side): the peer is the message author when it isn't me, else
     * the message's peer field (messages I sent). Conversations sort by
     * most recent activity; messages chronological within each.
     */
    fun group(messages: List<DmMessage>, myPubkey: String): List<DmConversation> {
        val byPeer = LinkedHashMap<String, MutableList<DmMessage>>()
        for (message in messages) {
            val peer = if (message.authorPubkey == myPubkey) message.peerPubkey else message.authorPubkey
            byPeer.getOrPut(peer) { mutableListOf() }.add(message)
        }
        return byPeer.entries
            .sortedByDescending { (_, list) -> list.maxOfOrNull { it.createdAt } ?: 0 }
            .take(MAX_CONVERSATIONS)
            .map { (peer, list) ->
                DmConversation(
                    peerPubkey = peer,
                    messages = list.sortedBy { it.createdAt }.takeLast(MAX_MESSAGES),
                )
            }
    }
}
