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
            for (tag in note.hashtags) {
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
