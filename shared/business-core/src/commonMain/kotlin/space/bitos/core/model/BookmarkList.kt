package space.bitos.core.model

/**
 * NIP-51 bookmark list (kind 30003, addressable): the account's saved event
 * ids under the empty `d` coordinate. Bounded and deduplicated; newest
 * verified list wins (replaceable semantics by created_at).
 */
object BookmarkList {

    const val KIND = 30_003
    const val D_TAG = ""
    const val MAX_BOOKMARKS = 500
    private val hex64 = Regex("^[0-9a-f]{64}$")

    /** Event ids referenced by `e` tags (deduped, bounded, order-kept). */
    fun bookmarkedIds(event: NostrEvent): List<String> {
        if (event.kind != KIND) return emptyList()
        if (event.tags.size > NostrLimits.MAX_TAGS) return emptyList()
        val seen = LinkedHashSet<String>()
        for (tag in event.tags) {
            if (tag.firstOrNull() != "e") continue
            val id = tag.getOrNull(1) ?: continue
            if (!hex64.matches(id)) continue
            seen.add(id)
            if (seen.size >= MAX_BOOKMARKS) break
        }
        return seen.toList()
    }

    /** Newest-wins among verified candidates (replaceable head). */
    fun newest(events: List<NostrEvent>): NostrEvent? =
        events.filter { it.kind == KIND }.maxByOrNull { it.createdAt }
}
