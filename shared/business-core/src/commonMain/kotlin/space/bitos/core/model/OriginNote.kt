package space.bitos.core.model

import space.bitos.core.nostr.Nip27
import space.bitos.core.nostr.RichToken

/**
 * APP-012 origin-note preview (bounded projection of a verified event the
 * notification targets). Media links are stripped from the excerpt (the
 * first URL becomes the thumbnail candidate); the platform renders a video
 * glyph when [kind] is a video kind.
 */
data class OriginNote(
    val id: String,
    val authorPubkey: String,
    val kind: Int,
    val createdAt: Long,
    /** Bounded plain-text excerpt; links and nostr entities stripped. */
    val excerpt: String,
    /** First http(s) URL in the content (image or video), or null. */
    val thumbUrl: String?,
    /** Full raw content (already event-bounded by NostrLimits) for thread roots. */
    val content: String,
)

object OriginNotes {

    const val EXCERPT_MAX = 160

    fun project(event: NostrEvent): OriginNote {
        var thumbUrl: String? = null
        val excerptBuilder = StringBuilder()
        for (token in Nip27.tokenize(event.content)) {
            when (token) {
                is RichToken.Link -> if (thumbUrl == null) thumbUrl = token.url
                is RichToken.Hashtag -> excerptBuilder.append('#').append(token.tag).append(' ')
                is RichToken.Text -> excerptBuilder.append(token.value)
                is RichToken.Nostr -> Unit // entity text dropped; names need profile data
            }
        }
        val excerpt = excerptBuilder.toString()
            .replace(Regex("\\s+"), " ")
            .trim()
            .let { if (it.length > EXCERPT_MAX) it.take(EXCERPT_MAX - 1) + "…" else it }
        return OriginNote(
            id = event.id.value,
            authorPubkey = event.pubkey.value,
            kind = event.kind,
            createdAt = event.createdAt,
            excerpt = excerpt,
            thumbUrl = thumbUrl,
            content = event.content,
        )
    }
}
