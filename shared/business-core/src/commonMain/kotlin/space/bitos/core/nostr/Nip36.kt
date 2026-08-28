package space.bitos.core.nostr

/**
 * NIP-36 content-warning classification (shared by the feed card and the
 * notification origin preview so both surfaces gate identical content the
 * same way): tag `["content-warning", …]` or label
 * `["L"|"l", "content warning", …]`.
 */
object Nip36 {

    fun hasContentWarning(tags: List<List<String>>): Boolean = tags.any { tag ->
        tag.firstOrNull() == "content-warning" ||
            ((tag.firstOrNull() == "L" || tag.firstOrNull() == "l") && tag.getOrNull(1) == "content warning")
    }
}
