package space.bitos.core.studio

import space.bitos.core.feed.FeedNote
import space.bitos.core.feed.RemixRules

/**
 * Deterministic preflight rules for the Create-hub "Remix a Bitz" flow
 * (plan `create-hub-sound-remix-plan.md` §Remix source picker, tasks
 * MEM-005a/MEM-005c). Pure, common-tested so both platforms classify a
 * fetched source identically: the SAME note produces the SAME preflight
 * (license signal, attribution preview, media eligibility) and the SAME
 * fallback decision (no loadable media → seeded credited post, never a
 * blank studio project pretending to be a remix).
 *
 * The lookup/network lifecycle stays native (adapters own verified relay
 * fetch); this object only decides what a verified [FeedNote] means.
 */
object RemixPreflight {

    /** Kinds the lookup accepts (bitz media + plain notes). */
    val LOOKUP_KINDS = listOf(1, 20, 21, 22)

    /** By-id REQ filter for one paste/scan target (bounded, single id). */
    fun lookupFilter(eventId: String): String {
        val id = eventId.take(64).filter { it in '0'..'9' || it in 'a'..'f' }
        require(id.length == 64) { "lookup id must be 64-hex" }
        return """{"kinds":[${LOOKUP_KINDS.joinToString(",")}],"ids":["$id"],"limit":1}"""
    }

    /**
     * MEM-005c source-mismatch guard: the event that arrived must be the
     * event that was requested. The relay pool verifies signatures; this
     * verifies INTENT — a relay bug or a hostile replay can never hand the
     * editor a different note than the one the creator pasted.
     */
    fun idMatches(requestedId: String, receivedNote: FeedNote): Boolean =
        requestedId.lowercase() == receivedNote.id.lowercase()

    /**
     * The loadable remix media URL, or null. Mirrors the Bitz-card handoff
     * precedence (video imeta first, then inline media) plus the
     * https-or-loopback transport gate the editor's downloader enforces —
     * a source that cannot load is not a remix source.
     */
    fun mediaUrlOf(note: FeedNote): String? {
        val candidate = note.video?.url ?: note.mediaUrls.firstOrNull() ?: return null
        return candidate.takeIf(::isLoadableUrl)
    }

    /** True for https (prod rule) or the local-development loopback hosts. */
    fun isLoadableUrl(url: String): Boolean {
        val lowered = url.lowercase()
        if (!lowered.startsWith("https://")) {
            if (!lowered.startsWith("http://")) return false
            val host = lowered.removePrefix("http://").substringBefore('/').substringBefore(':')
            if (host != "localhost" && host != "127.0.0.1") return false
        }
        return url.length in 1..MemeSoundRules.MAX_URL_LENGTH
    }

    /** Preflight media-type label ("video"/"image"); null = no media row. */
    fun mediaKindOf(note: FeedNote): String? = when {
        mediaUrlOf(note) == null -> null
        note.video != null -> "video"
        else -> "image"
    }

    /** License/rights signal for the preflight; null = none set (permissive). */
    fun licenseOf(note: FeedNote): String? = note.license

    /** A restrictive license needs an explicit continue — never a hidden gate. */
    fun requiresAsk(note: FeedNote): Boolean = RemixRules.requiresAsk(note.license)

    /** Attribution preview line (the publish stamps this automatically). */
    fun attributionPreview(creatorLabel: String): String =
        RemixRules.attributionTag(creatorLabel)[1]
}
