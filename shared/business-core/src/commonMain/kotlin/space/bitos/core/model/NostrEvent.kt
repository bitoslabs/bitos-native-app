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
    const val REPOST = 6
    const val GENERIC_REACTION = 7
    const val LONG_FORM = 30_023
    const val VIDEO = 22
    const val VIDEO_COMMENT = 1_111

    val feedKinds: List<Int> = listOf(SHORT_TEXT_NOTE, VIDEO)
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
