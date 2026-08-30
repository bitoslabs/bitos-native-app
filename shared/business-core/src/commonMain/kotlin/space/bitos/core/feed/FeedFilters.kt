package space.bitos.core.feed

/**
 * Feed content filters (unified feature spec §3.4, APP-004): pure,
 * deterministic client-side windows over the verified feed. The legacy
 * clients exposed the same six filters single-select with "All" as the
 * reset; protocol-payload (machine traffic) notes stay hidden in every
 * window that shows human content unless the reader opted in through
 * [showProtocolNotes][space.bitos.core.settings.SettingsSnapshot.showProtocolNotes]
 * (web `feedPreferences` parity).
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
     * @param showProtocolNotes explicit reader opt-in that re-admits
     *        protocol-payload notes into every window (default false).
     */
    fun passes(
        note: FeedNote,
        filter: FeedFilter,
        ownPubkey: String?,
        likedIds: Set<String> = emptySet(),
        showProtocolNotes: Boolean = false,
    ): Boolean {
        val readable = showProtocolNotes || !note.isProtocolPayload
        return when (filter) {
            FeedFilter.ALL -> readable
            FeedFilter.ORIGINALS -> readable && note.replyTo == null
            FeedFilter.REPLIES -> readable && note.replyTo != null
            FeedFilter.MEDIA -> readable && (note.video != null || note.mediaUrls.isNotEmpty())
            // Explicit user selections stay visible regardless of traffic class.
            FeedFilter.LIKED -> note.id in likedIds
            FeedFilter.MINE -> ownPubkey != null && note.pubkey == ownPubkey
        }
    }
}
