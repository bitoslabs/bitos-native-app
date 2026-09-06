package space.bitos.core.model

/**
 * APP-006 story interactions (web `stories.svelte.ts` parity): pure rules
 * for the stories engagement lane — target tags for reactions/replies, the
 * `a`-address of a parameterized slide, and per-event classification
 * against the tracked slide set. Aggregation (dedupe, latest-per-pubkey,
 * sums) stays in the native stores; every protocol rule lives here so the
 * platforms agree.
 */
object StoriesInteractions {

    /** Anonymous "view" reaction contents (NIP-25 view-receipt convention). */
    val VIEW_EMOJIS = setOf("👁️", "👁", "👀")

    /** Reaction kind — likes and (private-by-default) view receipts. */
    const val REACTION_KIND = 7

    /** Public reply kind (NIP-10). */
    const val REPLY_KIND = 1

    /** Zap receipt kind (NIP-57). */
    const val ZAP_KIND = 9_735

    /** The `a` address (`kind:pubkey:d`) of a parameterized slide. */
    fun addressFor(pubkey: String, d: String?): String? =
        d?.takeIf { it.isNotBlank() }?.let { "${Stories.STORY_KIND}:$pubkey:$it" }

    /** The `a` address of a parsed slide. */
    fun addressOf(slide: StorySlide): String? = addressFor(slide.pubkey, slide.d)

    /** `e` + `p` (+ `a`) tags pointing a reaction at a story slide. */
    fun targetTagsFor(slideId: String, pubkey: String, d: String?): List<List<String>> = buildList {
        add(listOf("e", slideId))
        add(listOf("p", pubkey))
        addressFor(pubkey, d)?.let { add(listOf("a", it)) }
    }

    /** Target tags for a parsed slide. */
    fun targetTags(slide: StorySlide): List<List<String>> = targetTagsFor(slide.id, slide.pubkey, slide.d)

    /** Reply tags (web `stories.reply`): e/p/a with `reply` markers. */
    fun replyTagsFor(slideId: String, pubkey: String, d: String?): List<List<String>> = buildList {
        add(listOf("e", slideId, "", "reply"))
        add(listOf("p", pubkey))
        addressFor(pubkey, d)?.let { add(listOf("a", it, "", "reply")) }
    }

    /** Reply tags for a parsed slide. */
    fun replyTags(slide: StorySlide): List<List<String>> = replyTagsFor(slide.id, slide.pubkey, slide.d)

    /** True when a reaction content is a view receipt rather than a like. */
    fun isViewContent(content: String): Boolean = content.trim() in VIEW_EMOJIS

    /**
     * Engagement filter JSON set for tracked slides (web `activityFilters`
     * parity): kind 7 + 9735 by `#e`, kind 1 by `#e` and — when
     * parameterized — `#a`. Null when nothing is tracked.
     */
    fun activityFilters(slideIds: List<String>, addresses: List<String>): List<String>? {
        val boundedIds = slideIds.filter { it.matches(Regex("^[0-9a-f]{64}$")) }.distinct().take(50)
        if (boundedIds.isEmpty()) return null
        fun jsonArray(values: List<String>) = values.joinToString("\",\"", prefix = "[\"", postfix = "\"]")
        val filters = mutableListOf(
            """{"kinds":[$REACTION_KIND,$ZAP_KIND],"#e":${jsonArray(boundedIds)},"limit":200}""",
            """{"kinds":[$REPLY_KIND],"#e":${jsonArray(boundedIds)},"limit":200}""",
        )
        val boundedAddresses = addresses.filter { it.contains(':') }.distinct().take(50)
        if (boundedAddresses.isNotEmpty()) {
            filters.add("""{"kinds":[$REPLY_KIND],"#a":${jsonArray(boundedAddresses)},"limit":200}""")
        }
        return filters
    }

    /** Classified engagement on one tracked slide. */
    data class StoryActivityEvent(
        val slideId: String,
        val type: Type,
        val pubkey: String,
        /** Reaction emoji (likes) — ❤️ fallback when the content is empty. */
        val emoji: String,
        /** Reply body (replies). */
        val text: String,
        /** Zap amount in sats (zaps). */
        val sats: Long,
        val at: Long,
        /** Source event id — the dedupe key for relay replays. */
        val eventId: String,
    ) {
        enum class Type { LIKE, VIEW, REPLY, ZAP }
    }

    /**
     * Classify one verified event against the tracked slide ids/addresses;
     * null when the event targets nothing tracked. `addressToId` maps
     * `kind:pubkey:d` → slide id (parameterized replaceables).
     */
    fun project(
        event: NostrEvent,
        slideIds: Set<String>,
        addressToId: Map<String, String>,
    ): StoryActivityEvent? {
        val slideId = slideIdFromTags(event.tags, slideIds, addressToId) ?: return null
        return when (event.kind) {
            ZAP_KIND -> StoryActivityEvent(
                slideId = slideId,
                type = StoryActivityEvent.Type.ZAP,
                pubkey = event.pubkey.value,
                emoji = "",
                text = "",
                sats = Bolt11.amountMillisats(
                    event.tags.firstOrNull { it.firstOrNull() == "bolt11" }?.getOrNull(1) ?: ""
                )?.let { it / 1000 } ?: 0L,
                at = event.createdAt,
                eventId = event.id.value,
            )
            REACTION_KIND -> {
                val emoji = event.content.trim()
                StoryActivityEvent(
                    slideId = slideId,
                    type = if (isViewContent(emoji)) StoryActivityEvent.Type.VIEW else StoryActivityEvent.Type.LIKE,
                    pubkey = event.pubkey.value,
                    emoji = emoji.ifEmpty { "❤️" },
                    text = "",
                    sats = 0L,
                    at = event.createdAt,
                    eventId = event.id.value,
                )
            }
            REPLY_KIND -> StoryActivityEvent(
                slideId = slideId,
                type = StoryActivityEvent.Type.REPLY,
                pubkey = event.pubkey.value,
                emoji = "",
                text = event.content.trim(),
                sats = 0L,
                at = event.createdAt,
                eventId = event.id.value,
            )
            else -> null
        }
    }

    private fun slideIdFromTags(
        tags: List<List<String>>,
        slideIds: Set<String>,
        addressToId: Map<String, String>,
    ): String? {
        for (tag in tags) {
            if (tag.firstOrNull() == "e" && tag.getOrNull(1) in slideIds) return tag[1]
        }
        for (tag in tags) {
            if (tag.firstOrNull() == "a") addressToId[tag.getOrNull(1)]?.let { return it }
        }
        return null
    }
}
