package space.bitos.core.nostr

/**
 * Lightweight, explainable content classification (web
 * `nostr/content-classification.ts` parity, version 2026-08-22).
 *
 * This is not a moderation decision: callers may always offer the payload
 * in an advanced view or let people opt back in ([showProtocolNotes][space.bitos.core.settings.SettingsSnapshot.showProtocolNotes]).
 */
object ContentClassification {

    private val ROSTER_HEADER = Regex("^channel:\\s*__roster\\s*(?:\\n|\$)", RegexOption.IGNORE_CASE)

    /**
     * Serialized channel rosters — a `channel:__roster` header followed by a
     * sizeable hexadecimal blob — are machine traffic, not prose. Requiring
     * both signals avoids hiding ordinary notes that merely mention a
     * channel or a hash.
     */
    fun isProtocolPayload(content: String): Boolean {
        val text = content.trim()
        if (!ROSTER_HEADER.containsMatchIn(text)) return false
        val body = ROSTER_HEADER.replace(text, "").replace(whitespace, "")
        return body.length >= 96 && hexBlob.matches(body)
    }

    private val whitespace = Regex("\\s")
    private val hexBlob = Regex("^[0-9a-f]+$", RegexOption.IGNORE_CASE)

    /**
     * Machine-generated hashtags — coordination tags emitted by bots and
     * relayed swarm protocols, never typed by a person:
     * `udal-friend-<32 hex chars>`, `udal-peer-…`, …
     *
     * They are syntactically valid hashtags, so without this filter they
     * pollute every consumer of note tags: the Topics ranking signal learns
     * to boost them, tag mutes burn on an unrepeatable id, they render as
     * tag chips on cards, and they swamp search/discover tag counts.
     */
    private val MACHINE_TAG = Regex("^udal-(?:friend|peer|node)-[0-9a-f]{8,}$", RegexOption.IGNORE_CASE)

    fun isMachineTag(tag: String): Boolean = MACHINE_TAG.matches(tag.trim())

    /** Filter a tag list down to the human-meaningful entries. */
    fun humanTags(tags: List<String>): List<String> = tags.filter { !isMachineTag(it) }
}
