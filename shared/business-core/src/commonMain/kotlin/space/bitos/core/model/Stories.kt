package space.bitos.core.model

/**
 * APP-006 Stories (NIP-38 kind-30315 user status + NIP-40 expiration,
 * legacy Flutter `stories_controller` / web `stories` parity).
 *
 * One ephemeral slide = a kind-30315 event with a 24h TTL via the
 * `expiration` tag. Parameterized-replaceable (`d` tag): a newer event
 * with the same d replaces the older one. Kind-5 deletions remove slides
 * by e-tag. Expired slides prune at parse time. Pure rules — the repos
 * own subscription and persistence.
 */
data class StorySlide(
    val id: String,
    val pubkey: String,
    val content: String,
    val createdAt: Long,
    val expiresAt: Long,
    /** Parameterized-replaceable identifier (null = ephemeral unique). */
    val d: String? = null,
    /** First image URL in the content (the visual). */
    val imageUrl: String? = null,
    /** CSS-like gradient token for text-only slides (`#hex>to>#hex`). */
    val gradient: String? = null,
    /** NIP-13 difficulty when the event carries a nonce commitment. */
    val pow: Int? = null,
) {
    fun isExpired(nowSeconds: Long): Boolean = expiresAt <= nowSeconds
}

data class StoryAuthor(
    val pubkey: String,
    val slides: List<StorySlide>,
    val isPublicDiscovery: Boolean = false,
) {
    val latestAt: Long get() = slides.maxOfOrNull { it.createdAt } ?: 0
}

object Stories {

    const val STORY_KIND = 30_315
    const val DELETE_KIND = 5

    /** 24 h TTL (NIP-40 default when no expiration tag). */
    const val STORY_TTL_SECONDS = 24 * 60 * 60

    /** Slides per author bound (spec §3.6: ≤12). */
    const val MAX_SLIDES_PER_AUTHOR = 12

    /** Future-dated guard (±10 min clock skew). */
    const val CLOCK_SKEW_SECONDS = 600

    /**
     * Parses a kind-30315 event into a slide; null when expired,
     * future-dated beyond the skew guard, or not a story kind.
     */
    fun parseSlide(event: NostrEvent, nowSeconds: Long): StorySlide? {
        if (event.kind != STORY_KIND) return null
        // Future-dated guard (web parity).
        if (event.createdAt > nowSeconds + CLOCK_SKEW_SECONDS) return null

        val expiration = event.tags.firstOrNull { it.firstOrNull() == "expiration" }
            ?.getOrNull(1)?.toLongOrNull()
        val expiresAt = expiration ?: (event.createdAt + STORY_TTL_SECONDS)
        if (expiresAt <= nowSeconds) return null // already expired

        val d = event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)?.takeIf { it.isNotBlank() }
        val nonceTag = event.tags.firstOrNull { it.firstOrNull() == "nonce" }
        val pow = nonceTag?.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }

        val imageUrl = extractImageUrl(event.content)
        val content = cleanContent(event.content, imageUrl)
        val gradient = extractGradient(event.content)

        return StorySlide(
            id = event.id.value,
            pubkey = event.pubkey.value,
            content = content,
            createdAt = event.createdAt,
            expiresAt = expiresAt,
            d = d,
            imageUrl = imageUrl,
            gradient = gradient,
            pow = pow,
        )
    }

    /**
     * Insertion rule: replaces a same-id older slide; parameterized
     * replaceable (same `d` + newer createdAt replaces older); bounded
     * per author (newest kept). Returns the updated list.
     */
    fun insert(existing: List<StorySlide>, candidate: StorySlide): List<StorySlide> {
        // Same id: newer wins.
        val withoutSameId = existing.filter { it.id != candidate.id }
        // Parameterized-replaceable: same d + newer-or-equal replaces.
        val withoutSameD = if (candidate.d != null) {
            withoutSameId.filter { !(it.pubkey == candidate.pubkey && it.d == candidate.d && candidate.createdAt >= it.createdAt) }
        } else {
            withoutSameId
        }
        val next = (withoutSameD + candidate)
            .sortedByDescending { it.createdAt }
            .filter { it.pubkey == candidate.pubkey }
        if (next.size > MAX_SLIDES_PER_AUTHOR) return next.take(MAX_SLIDES_PER_AUTHOR)
        // Keep the full set (other authors unchanged).
        return (withoutSameD.filter { it.pubkey != candidate.pubkey } +
            next.take(MAX_SLIDES_PER_AUTHOR))
            .sortedByDescending { it.createdAt }
    }

    /**
     * Groups active slides per author (expired pruned), newest-first
     * slides within each author; authors ordered by latest slide.
     */
    fun authors(slides: List<StorySlide>, nowSeconds: Long): List<StoryAuthor> {
        val active = slides.filter { !it.isExpired(nowSeconds) }
        return active
            .groupBy { it.pubkey }
            .map { (pubkey, list) ->
                StoryAuthor(pubkey = pubkey, slides = list.sortedByDescending { it.createdAt })
            }
            .sortedByDescending { it.latestAt }
    }

    /** First http(s) image URL in the content (web `extractImage`). */
    internal fun extractImageUrl(content: String): String? {
        val match = Regex("https?://\\S+\\.(?:png|jpe?g|gif|webp|avif)(?:[?#]\\S*)?", RegexOption.IGNORE_CASE)
            .find(content) ?: return null
        return match.value
    }

    /** Strips the image URL from the caption text (web `cleanContent`). */
    internal fun cleanContent(content: String, imageUrl: String?): String =
        if (imageUrl != null) content.replace(imageUrl, "").trim() else content.trim()

    /** `#hex>to>#hex` gradient token (web parity). */
    internal fun extractGradient(content: String): String? =
        Regex("#([0-9a-fA-F]{6})>to>#([0-9a-fA-F]{6})").find(content)?.value
}
