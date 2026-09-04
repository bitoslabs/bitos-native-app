package space.bitos.core.feed

/** A safe, image-only preview for an allowlisted external video provider. */
data class ExternalVideoPreview(
    val url: String,
    val providerName: String,
    val thumbnailUrl: String,
)

/**
 * Provider recognition stays in shared code so native cards agree on which
 * links get a preview. It never loads an embed or executes provider content.
 */
object ExternalVideoPreviews {
    private val youtubeWatch = Regex(
        """^https?://(?:www\.|m\.)?youtube\.com/watch\?(?:[^#]*&)?v=([A-Za-z0-9_-]{11})(?:[&#].*)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val youtubePath = Regex(
        """^https?://(?:www\.|m\.)?youtube\.com/(?:shorts|embed)/([A-Za-z0-9_-]{11})(?:[/?#].*)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val youtubeShort = Regex(
        """^https?://(?:www\.)?youtu\.be/([A-Za-z0-9_-]{11})(?:[/?#].*)?$""",
        RegexOption.IGNORE_CASE,
    )

    fun fromUrl(url: String): ExternalVideoPreview? {
        // NIP-27 link tokens intentionally include adjacent punctuation; it
        // belongs to the caption, not to the provider URL.
        val canonicalUrl = url.trimEnd('.', ',', ')', ']', '}', '!', ':', ';')
        val videoId = sequenceOf(youtubeWatch, youtubePath, youtubeShort)
            .mapNotNull { it.matchEntire(canonicalUrl)?.groupValues?.get(1) }
            .firstOrNull()
            ?: return null
        return ExternalVideoPreview(
            url = canonicalUrl,
            providerName = "YouTube",
            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
        )
    }

    fun fromContent(content: String): List<ExternalVideoPreview> =
        space.bitos.core.nostr.Nip27.tokenize(content)
            .filterIsInstance<space.bitos.core.nostr.RichToken.Link>()
            .mapNotNull { fromUrl(it.url) }
            .distinctBy { it.url }
            .take(2)
}
