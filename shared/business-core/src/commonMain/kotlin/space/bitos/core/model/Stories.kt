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
 *
 * Media (web `stories.svelte.ts` parity): every NIP-92 `imeta` url line
 * then every bare image link in the content forms the image carousel
 * (capped, deduped); the first `imeta` with a video mime (else the first
 * bare video link) is the slide's video; `content-warning`/`warning`/`cw`
 * tags mark the slide sensitive (blurred until revealed in the viewer).
 */
data class StorySlide(
    val id: String,
    val pubkey: String,
    val content: String,
    val createdAt: Long,
    val expiresAt: Long,
    /** Parameterized-replaceable identifier (null = ephemeral unique). */
    val d: String? = null,
    /** All attached images (NIP-92 imeta urls + bare links), capped. */
    val imageUrls: List<String> = emptyList(),
    /** Video attachment (video-mime imeta, else bare video link). */
    val videoUrl: String? = null,
    /** Poster/thumbnail for the video (NIP-92 `thumb` on the imeta). */
    val videoPoster: String? = null,
    /** Approximate play duration ms — caps the auto-advance fallback. */
    val videoDurationMs: Long? = null,
    /** True when the slide is tagged sensitive (blurred until revealed). */
    val sensitive: Boolean = false,
    /** Gradient token for text-only slides (`#hex>to>#hex`). */
    val gradient: String? = null,
    /** NIP-13 difficulty when the event carries a nonce commitment. */
    val pow: Int? = null,
) {
    /** Primary attached image (`imageUrls.first()` — single-image consumers). */
    val imageUrl: String? get() = imageUrls.firstOrNull()

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

    /** Hard cap on images per slide — keeps events + viewer carousels sane. */
    const val MAX_STORY_IMAGES = 6

    /** Future-dated guard (±10 min clock skew). */
    const val CLOCK_SKEW_SECONDS = 600

    private val IMAGE_URL_REGEX = Regex(
        """https?://[^\s<>"')]+?\.(?:apng|avif|gif|jpe?g|png|webp)(?:[?#][^\s<>"')]*)?""",
        RegexOption.IGNORE_CASE,
    )
    private val VIDEO_URL_REGEX = Regex(
        """https?://[^\s<>"')]+?\.(?:m4v|mov|mp4|webm|mkv)(?:[?#][^\s<>"')]*)?""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parses a kind-30315 event into a slide; null when expired,
     * future-dated beyond the skew guard, or not a story kind.
     */
    fun parseSlide(event: NostrEvent, nowSeconds: Long): StorySlide? {
        if (event.kind != STORY_KIND) return null
        // Future-dated guard (web parity).
        if (event.createdAt > nowSeconds + CLOCK_SKEW_SECONDS) return null

        val expiration = event.tags.firstOrNull { it.firstOrNull() == "expiration" }?.getOrNull(1)
        val expiresAt = expiration?.toLongOrNull() ?: (event.createdAt + STORY_TTL_SECONDS)
        if (expiresAt <= nowSeconds) return null // already expired

        val d = event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)?.takeIf { it.isNotBlank() }
        val nonceTag = event.tags.firstOrNull { it.firstOrNull() == "nonce" }
        val pow = nonceTag?.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }

        val images = extractImageUrls(event)
        val video = extractVideo(event)
        val content = cleanContent(event.content, images + listOfNotNull(video?.url))

        return StorySlide(
            id = event.id.value,
            pubkey = event.pubkey.value,
            content = content,
            createdAt = event.createdAt,
            expiresAt = expiresAt,
            d = d,
            imageUrls = images,
            videoUrl = video?.url,
            videoPoster = video?.poster,
            videoDurationMs = video?.durationMs,
            sensitive = isSensitive(event),
            gradient = extractGradient(event),
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

    /**
     * Every attached image: each NIP-92 `imeta` url line first (ordered,
     * author-curated), then any bare image links in the content. Deduped,
     * capped at MAX_STORY_IMAGES so a hostile event can't bloat the viewer.
     * Video-mime imetas are skipped here — their url belongs to
     * [extractVideo], not the image carousel.
     */
    internal fun extractImageUrls(event: NostrEvent): List<String> {
        val urls = LinkedHashSet<String>()
        for (tag in event.tags) {
            if (tag.firstOrNull() != "imeta") continue
            val isVideoImeta = tag.any { segment ->
                segment.startsWith("m ") && segment.removePrefix("m ").trim().startsWith("video/")
            }
            if (isVideoImeta) continue
            for (segment in tag.drop(1)) {
                if (segment.startsWith("url ")) {
                    segment.removePrefix("url ").trim().takeIf { it.isNotEmpty() }?.let(urls::add)
                }
            }
        }
        IMAGE_URL_REGEX.findAll(event.content).forEach { urls.add(it.value) }
        return urls.toList().take(MAX_STORY_IMAGES)
    }

    /** A video attachment parsed off a story event (imeta url/m/thumb/duration). */
    internal data class StoryVideo(
        val url: String,
        val poster: String? = null,
        val durationMs: Long? = null,
    )

    /**
     * The slide's video: the first NIP-92 `imeta` whose `m` line is a video
     * mime, else the first bare video link in the content. Relays and
     * clients that never set `m` still work via the extension sniff.
     */
    internal fun extractVideo(event: NostrEvent): StoryVideo? {
        for (tag in event.tags) {
            if (tag.firstOrNull() != "imeta") continue
            var url: String? = null
            var mime: String? = null
            var thumb: String? = null
            var durationMs: Long? = null
            for (segment in tag.drop(1)) {
                when {
                    segment.startsWith("url ") -> url = segment.removePrefix("url ").trim()
                    segment.startsWith("m ") -> mime = segment.removePrefix("m ").trim()
                    segment.startsWith("thumb ") -> thumb = segment.removePrefix("thumb ").trim()
                    segment.startsWith("duration ") -> {
                        val seconds = segment.removePrefix("duration ").trim()
                            .removeSuffix("s").toDoubleOrNull()
                        if (seconds != null && seconds > 0) durationMs = (seconds * 1000).toLong()
                    }
                }
            }
            if (!url.isNullOrEmpty() && mime?.startsWith("video/") == true) {
                return StoryVideo(url = url, poster = thumb?.takeIf { it.isNotEmpty() }, durationMs = durationMs)
            }
        }
        val bare = VIDEO_URL_REGEX.find(event.content)?.value ?: return null
        return StoryVideo(url = bare)
    }

    /** True when the slide carries a content warning (sensitive media). */
    internal fun isSensitive(event: NostrEvent): Boolean =
        event.tags.any { tag ->
            (tag.firstOrNull() == "content-warning" || tag.firstOrNull() == "warning" || tag.firstOrNull() == "cw") &&
                !tag.getOrNull(1).isNullOrBlank()
        }

    /** Strips every attached media URL out of the caption text (web parity). */
    internal fun cleanContent(content: String, mediaUrls: List<String>): String {
        var out = content
        for (url in mediaUrls) out = out.replace(url, " ")
        return out.replace(Regex("\\*{2,}"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Gradient token for text-only slides: the web `background` tag (CSS —
     * first two hex colors) when present, else the legacy `#hex>to>#hex`
     * token in the content.
     */
    internal fun extractGradient(event: NostrEvent): String? {
        val background = event.tags.firstOrNull { it.firstOrNull() == "background" }?.getOrNull(1)
        if (!background.isNullOrBlank()) {
            val hexes = Regex("#([0-9a-fA-F]{6})").findAll(background)
                .map { it.groupValues[1] }
                .toList()
            if (hexes.size >= 2) return "#${hexes[0]}>to>#${hexes[1]}"
        }
        return Regex("#([0-9a-fA-F]{6})>to>#([0-9a-fA-F]{6})").find(event.content)?.value
    }
}
