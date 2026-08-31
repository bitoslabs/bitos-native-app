package space.bitos.core.model

/**
 * NIP-51 interest set (kind 30015, addressable `d=interest`): the hashtags
 * the account follows — web `hashtag-follows` parity, syncs across clients.
 * Bounded and deduplicated; newest verified list wins (replaceable by
 * created_at).
 */
object InterestSet {

    const val KIND = 30_015
    const val D_TAG = "interest"
    const val MAX_TAGS = 200

    /** NIP-01 hashtag charset (web parity: 2–60 of L/N/_/-). */
    private val tagPattern = Regex("^[\\p{L}\\p{N}_-]{2,60}$")

    fun normalize(tag: String): String = tag.trim().removePrefix("#").lowercase()

    fun isValid(tag: String): Boolean = tagPattern.matches(tag)

    /** Followed hashtags from `t` tags (normalized, deduped, bounded). */
    fun followedHashtags(event: NostrEvent): List<String> {
        if (event.kind != KIND) return emptyList()
        if (event.tags.size > NostrLimits.MAX_TAGS) return emptyList()
        if (event.tags.none { it.firstOrNull() == "d" && it.getOrNull(1) == D_TAG }) return emptyList()
        val seen = LinkedHashSet<String>()
        for (tag in event.tags) {
            if (tag.firstOrNull() != "t") continue
            val value = tag.getOrNull(1) ?: continue
            val normalized = normalize(value)
            if (!isValid(normalized)) continue
            seen += normalized
            if (seen.size >= MAX_TAGS) break
        }
        return seen.toList()
    }

    /** Newest-wins among verified candidates (replaceable head). */
    fun newest(events: List<NostrEvent>): NostrEvent? =
        events.filter { it.kind == KIND }.maxByOrNull { it.createdAt }
}
