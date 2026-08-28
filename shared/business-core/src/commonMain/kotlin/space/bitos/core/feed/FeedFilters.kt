package space.bitos.core.feed

/**
 * Feed content filters (unified feature spec §3.4, APP-004): pure,
 * deterministic client-side windows over the verified feed. The legacy
 * clients exposed the same six filters single-select with "All" as the
 * reset; protocol-payload (machine traffic) notes stay hidden in every
 * window that shows human content.
 */
enum class FeedFilter {
    ALL, ORIGINALS, REPLIES, MEDIA, LIKED, MINE;

    val label: String
        get() = when (this) {
            ALL -> "All"
            ORIGINALS -> "Original"
            REPLIES -> "Replies"
            MEDIA -> "Media"
            LIKED -> "Liked"
            MINE -> "Mine"
        }
}

object FeedFilters {

    /**
     * @param ownPubkey active account pubkey (null = signed out; MINE
     *        matches nothing).
     * @param likedIds locally liked note ids (optimistic + reconciled).
     */
    fun passes(
        note: FeedNote,
        filter: FeedFilter,
        ownPubkey: String?,
        likedIds: Set<String> = emptySet(),
    ): Boolean = when (filter) {
        FeedFilter.ALL -> !note.isProtocolPayload
        FeedFilter.ORIGINALS -> !note.isProtocolPayload && note.replyTo == null
        FeedFilter.REPLIES -> note.replyTo != null
        FeedFilter.MEDIA -> !note.isProtocolPayload && (note.video != null || note.mediaUrls.isNotEmpty())
        FeedFilter.LIKED -> note.id in likedIds
        FeedFilter.MINE -> ownPubkey != null && note.pubkey == ownPubkey
    }
}
