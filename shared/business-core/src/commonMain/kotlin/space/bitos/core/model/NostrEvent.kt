package space.bitos.core.model

/**
 * Hard structural limits applied before any untrusted event payload is
 * accepted into domain state. Relays and indexes are untrusted inputs; a
 * hostile payload must fail bounded validation, never unbounded work.
 */
object NostrLimits {
    const val MAX_EVENT_BYTES: Int = 76_800
    const val MAX_TAGS: Int = 256
    const val MAX_TAG_ITEMS: Int = 32
    const val MAX_TAG_ITEM_LENGTH: Int = 1_024
    const val MAX_CONTENT_LENGTH: Int = 65_536
    const val MAX_SUBSCRIPTION_ID_LENGTH: Int = 128
}

/** Nostr event kinds used by the BitOS feed surfaces. */
object NostrKinds {
    const val PROFILE_METADATA = 0
    const val SHORT_TEXT_NOTE = 1
    const val CONTACT_LIST = 3
    /** NIP-09 event deletion. */
    const val EVENT_DELETION = 5
    const val REPOST = 6
    const val GENERIC_REACTION = 7
    /** NIP-18 generic repost (embedded JSON payload). */
    const val GENERIC_REPOST = 16
    const val LONG_FORM = 30_023
    /** NIP-78 namespaced app data (BitOS template/sound envelope). */
    const val APP_DATA = 30_078
    /** NIP-68 picture event (image reels). */
    const val PICTURE = 20
    /** NIP-71 normal video event. */
    const val NORMAL_VIDEO = 21
    /** NIP-71 short-form portrait video event. */
    const val SHORT_VIDEO = 22
    /** Compatibility name retained for existing kind-22 publishing code. */
    const val VIDEO = SHORT_VIDEO
    /** NIP-71 addressable (replaceable) normal video event. */
    const val ADDRESSABLE_VIDEO = 34_235
    /** NIP-71 addressable (replaceable) short-form video event. */
    const val ADDRESSABLE_SHORT_VIDEO = 34_236
    const val VIDEO_COMMENT = 1_111
    /** Poll response vote (web `votePoll` wire: `e` + `response` tags). */
    const val POLL_RESPONSE = 1_018

    /** Kinds whose events project into the feed window (reads + projection). */
    val feedKinds: List<Int> = listOf(
        SHORT_TEXT_NOTE,
        PICTURE,
        NORMAL_VIDEO,
        SHORT_VIDEO,
        ADDRESSABLE_VIDEO,
        ADDRESSABLE_SHORT_VIDEO,
    )

    /**
     * Dedicated reel-media kinds (FED-004 pagination): queried deep because
     * they are ~100% renderable bitz, unlike kind-1 text notes.
     */
    val reelMediaKinds: List<Int> = listOf(
        PICTURE,
        NORMAL_VIDEO,
        SHORT_VIDEO,
        ADDRESSABLE_VIDEO,
        ADDRESSABLE_SHORT_VIDEO,
    )
}

/**
 * A structurally valid Nostr event after decoding and ID verification.
 *
 * Signature verification is a separate trust stage (see `NostrEventCodec`):
 * an event with a verified ID but unverified signature may only reach
 * surfaces explicitly marked as unauthenticated-trust, never signing flows.
 */
data class NostrEvent(
    val id: EventId,
    val pubkey: Pubkey,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val signature: String?,
    val receivedFromRelay: RelayUrl?,
) {
    fun tag(name: String): List<String> = tags.firstOrNull { it.firstOrNull() == name } ?: emptyList()

    fun tagsNamed(name: String): List<List<String>> = tags.filter { it.firstOrNull() == name }
}
