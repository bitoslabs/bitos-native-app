package space.bitos.core.nostr

/**
 * NIP-36 content-warning classification (shared by the feed card and the
 * notification origin preview so both surfaces gate identical content the
 * same way): canonical tag `["content-warning", …]` or label
 * `["L"|"l", "content warning", …]`. A conservative, case-insensitive
 * hashtag fallback protects readers of legacy posts that omitted NIP-36.
 */
object Nip36 {

    private val sensitiveHashtags = setOf(
        "nsfw", "nswf", "adult", "porn", "pornography", "nudity", "nude",
        "explicit", "sexual", "18plus",
    )
    private val standaloneHashtag = Regex("(?:^|\\s)#([\\p{L}\\p{N}_-]{2,60})(?=\\s|$)")

    fun hasContentWarning(tags: List<List<String>>, content: String = ""): Boolean =
        tags.any { tag ->
            tag.firstOrNull() == "content-warning" ||
                ((tag.firstOrNull() == "L" || tag.firstOrNull() == "l") && tag.getOrNull(1) == "content warning") ||
                (tag.firstOrNull() == "t" && tag.getOrNull(1)?.lowercase() in sensitiveHashtags)
        } || standaloneHashtag.findAll(content).any { match ->
            match.groupValues[1].lowercase() in sensitiveHashtags
        }
}
