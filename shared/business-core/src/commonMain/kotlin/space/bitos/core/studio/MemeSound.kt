package space.bitos.core.studio

/** One imported-audio soundtrack attached to a VIDEO project (wire row
 *  `"sound"`; additive — old readers simply ignore it). [url] exists only
 *  after the hash-verified Blossom upload (attach-time = blank);
 *  [sourceNoteId]/[sourceAuthorPubkey] carry the borrowed bitz's
 *  provenance, null for a local library pick. */
data class MemeSoundtrack(
    val url: String = "",
    /** 64-hex canonical content hash of the extracted audio bytes. */
    val sha256: String,
    val durationMs: Long,
    /** In-point into the audio (0 = from the top). */
    val startMs: Long = 0,
    /** Soundtrack gain (0 = silent, 1 = source; bounded 0..2). */
    val volume: Float = 1f,
    /** Placement on the video timeline (0 = from the start). */
    val offsetMs: Long = 0,
    val sourceNoteId: String? = null,
    val sourceAuthorPubkey: String? = null,
    /** Display label ("Original sound · author"). */
    val label: String = "",
)

/**
 * Imported-audio soundtrack contract ("use this sound", plan
 * docs/product/use-this-sound-plan.md): a video project may carry ONE
 * audio track extracted from another video — a feed bitz ("Use this
 * sound" on the source note) or a local library pick ("Pick sound from a
 * video…" in the editor's Sound tool). Pure + common-tested; the natives
 * only extract bytes, preview and mix down.
 *
 * Provenance is first-class (the Nostr-native twist on TikTok's loop):
 * the published note stamps `["sound", url, sha256, sourceEventId?]` +
 * `["p", sourceAuthor]` + `["attribution", "sound of …"]`, so credit
 * rides every borrow — and counting `sound` tags over the feed window is
 * the trending-sounds rank later (APP-021 / MST-047 bootstrap).
 */
object MemeSoundRules {

    /** One borrowed take, ≤ the clip cap (MemeVideoCutRules.MAX_CLIP_MS). */
    const val MAX_SOUND_DURATION_MS = MemeVideoCutRules.MAX_CLIP_MS

    /** Hostile-input bounds (url like relay hints; ids like remix ids). */
    const val MAX_URL_LENGTH = 2_048
    const val MAX_SHA_LENGTH = 128
    const val MAX_SOURCE_ID_LENGTH = 512
    const val MAX_AUTHOR_LENGTH = 128

    /** Display label bound ("Original sound · author"). */
    const val MAX_LABEL_LENGTH = 80

    /** 64 lowercase hex — the canonical extracted-audio content hash. */
    private val shaPattern = Regex("^[0-9a-f]{64}$")

    /**
     * Bounded/clamped copy of a raw soundtrack; null when it is unusable
     * (no positive duration within the cap, or a non-canonical sha256) —
     * junk degrades to "no soundtrack", never a broken project.
     */
    fun normalize(raw: MemeSoundtrack?): MemeSoundtrack? {
        if (raw == null) return null
        val duration = raw.durationMs
        if (duration !in 1..MAX_SOUND_DURATION_MS) return null
        if (!shaPattern.matches(raw.sha256.take(MAX_SHA_LENGTH).lowercase())) return null
        return raw.copy(
            url = raw.url.trim().take(MAX_URL_LENGTH),
            sha256 = raw.sha256.lowercase().take(MAX_SHA_LENGTH),
            startMs = raw.startMs.coerceIn(0, duration - 1),
            volume = if (raw.volume.isNaN()) 1f else raw.volume.coerceIn(0f, MemeProjectContract.MAX_CLIP_VOLUME),
            offsetMs = raw.offsetMs.coerceIn(0, MemeProjectContract.MAX_DURATION_MS),
            sourceNoteId = raw.sourceNoteId?.take(MAX_SOURCE_ID_LENGTH)?.takeIf(String::isNotBlank),
            sourceAuthorPubkey = raw.sourceAuthorPubkey?.take(MAX_AUTHOR_LENGTH)?.takeIf(String::isNotBlank),
            label = raw.label.take(MAX_LABEL_LENGTH),
        )
    }

    /**
     * Publish tags for a soundtrack; empty when nothing is stampable —
     * the URL exists only after the hash-verified Blossom upload, so an
     * un-uploaded soundtrack stamps NOTHING (never sign before the media
     * upload verifies).
     */
    fun tagsFor(soundtrack: MemeSoundtrack?): List<List<String>> {
        val sound = normalize(soundtrack) ?: return emptyList()
        if (sound.url.isBlank()) return emptyList()
        val tags = mutableListOf(
            listOf(
                "sound",
                sound.url,
                sound.sha256,
                *(sound.sourceNoteId?.let { listOf(it) } ?: emptyList()).toTypedArray(),
            ),
        )
        sound.sourceAuthorPubkey?.let { author -> tags += listOf("p", author) }
        if (sound.label.isNotBlank()) {
            tags += listOf(
                "attribution",
                "sound of ${sound.label}".take(space.bitos.core.feed.RemixRules.ATTRIBUTION_MAX),
            )
        }
        return tags
    }

    /** Parsed sound source of one note (`["sound", url, sha256, eventId?]`). */
    data class Source(val eventId: String?, val pubkey: String?, val url: String)

    /**
     * First borrowed-sound source declared in [tags], or null. The feed
     * chip ("♪ sound of …"), the editor's re-attach path and the trending
     * rank all read through this one seam.
     */
    fun sourceOf(tags: List<List<String>>): Source? {
        val sound = tags.firstOrNull { it.firstOrNull() == "sound" && it.size >= 3 } ?: return null
        val url = sound[1].take(MAX_URL_LENGTH).takeIf(String::isNotBlank) ?: return null
        val eventId = sound.getOrNull(3)?.take(MAX_SOURCE_ID_LENGTH)?.takeIf(String::isNotBlank)
        val pubkey = tags.firstOrNull { it.firstOrNull() == "p" && it.size >= 2 }
            ?.get(1)?.take(MAX_AUTHOR_LENGTH)?.takeIf(String::isNotBlank)
        return Source(eventId = eventId, pubkey = pubkey, url = url)
    }
}
