package space.bitos.core.feed

import space.bitos.core.model.ProfileMetadata
import space.bitos.core.nostr.NostrEventCodec

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

    /** Hashtag queries are single `#tag` tokens; tags stay bounded. */
    const val MAX_TAG_LENGTH = 64

    /**
     * Classifies a query as a hashtag search: exactly one `#token` after
     * trimming, `[a-zA-Z0-9_]+`, bounded — returns the lowercase tag, or
     * null for free-text/npub/ref queries. NIP-50 leaves `#` undefined in
     * search strings, so clients must classify and query tags via the
     * NIP-01 `#t` filter instead.
     */
    fun queryTag(query: String): String? {
        val token = query.trim()
        if (!token.startsWith("#")) return null
        return normalizeTag(token.drop(1))
    }

    /** Bounded `[a-zA-Z0-9_]+` tag, lowercased (NIP-24 tags SHOULD be). */
    private fun normalizeTag(token: String): String? {
        if (token.isEmpty() || token.length > MAX_TAG_LENGTH) return null
        if (token.any { !it.isLetterOrDigit() && it != '_' }) return null
        return token.lowercase()
    }

    /**
     * NIP-01 hashtag REQ (`{"kinds":[..],"#t":["tag"],"limit":N}`): the
     * recall seam for local search — single-letter tag filters are indexed
     * by every conforming relay, so `#tag` queries pull matching verified
     * events into the normal stream without needing NIP-50 support. Takes
     * the bare tag (no `#`); null when it is not a bounded single token.
     */
    fun tagRequest(subscriptionId: String, tag: String, kinds: List<Int>, limit: Int): String? {
        val normalized = normalizeTag(tag) ?: return null
        val boundedKinds = kinds.filter { it in 0..65_535 }.take(8)
        if (boundedKinds.isEmpty()) return null
        val boundedLimit = limit.coerceIn(1..100)
        val escaped = NostrEventCodec.escape(normalized)
        return NostrEventCodec.encodeRequest(
            subscriptionId,
            """{"kinds":[${boundedKinds.joinToString(",")}],"#t":["$escaped"],"limit":$boundedLimit}""",
        )
    }

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
