package space.bitos.core.feed

import space.bitos.core.model.ProfileMetadata

/**
 * APP-010 results fan-in (spec §3.10): the deterministic projection from
 * verified search results into the tabbed result lists — People (distinct
 * authors ranked by result count) and Hashtags (top tags by occurrence).
 * Pure: the repos own subscription; this owns what each tab shows.
 */
object SearchResults {

    /** People-tab row (bounded display fields only). */
    data class PeopleRow(
        val pubkey: String,
        val displayName: String,
        val nip05: String?,
        val picture: String?,
        val noteCount: Int,
    )

    /** Hashtag-tab row. */
    data class HashtagHit(val tag: String, val count: Int)

    const val MAX_PEOPLE = 24
    const val MAX_HASHTAGS = 16

    /**
     * Deterministic local Discover match over an already verified relay
     * event. Native stores use this instead of treating an unrelated relay
     * subscription as a search response. Hashtag searches are exact; free
     * text searches include both caption text and visible hashtags.
     */
    fun matches(note: FeedNote, query: String): Boolean {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return false
        if (normalized.startsWith("#")) {
            val tag = normalized.drop(1)
            return tag.isNotEmpty() && note.hashtags.any { it.lowercase() == tag }
        }
        val resolvedNpub = normalized.takeIf { it.startsWith("npub1") }
            ?.let(space.bitos.core.identity.NostrKeyCodec::parseNpub)
        if (resolvedNpub != null) return note.pubkey == resolvedNpub
        return note.content.lowercase().contains(normalized) ||
            note.hashtags.any { it.lowercase().contains(normalized) }
    }

    /** Distinct authors, most-results-first, name from profiles when known. */
    fun people(notes: List<FeedNote>, profiles: Map<String, ProfileMetadata>): List<PeopleRow> {
        val counts = LinkedHashMap<String, Int>()
        for (note in notes) counts[note.pubkey] = (counts[note.pubkey] ?: 0) + 1
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_PEOPLE)
            .map { (pubkey, count) ->
                val profile = profiles[pubkey]
                PeopleRow(
                    pubkey = pubkey,
                    displayName = profile?.bestDisplayName?.takeIf { it.isNotBlank() } ?: pubkey.take(8) + "…",
                    nip05 = profile?.nip05?.takeIf { it.isNotBlank() },
                    picture = profile?.picture?.takeIf { it.isNotBlank() },
                    noteCount = count,
                )
            }
    }

    /** Top hashtags across the result notes, count-desc, tag alpha tiebreak. */
    fun hashtags(notes: List<FeedNote>): List<HashtagHit> {
        val counts = HashMap<String, Int>()
        for (note in notes) {
            // Machine coordination tags (`udal-*` bot swarms) are not topics:
            // they would crowd out human tags from the Hashtags tab (web
            // `humanTags` parity).
            for (tag in space.bitos.core.nostr.ContentClassification.humanTags(note.hashtags)) {
                val key = tag.lowercase()
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_HASHTAGS)
            .map { (tag, count) -> HashtagHit(tag, count) }
    }
}
