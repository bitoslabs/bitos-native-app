package space.bitos.core.feed

import space.bitos.core.model.NostrEvent
import space.bitos.core.model.NostrKinds
import space.bitos.core.model.RelayUrl
import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.NostrEventCodec
import space.bitos.core.nostr.Sha256EventHasher

/**
 * NIP-18 repost resolution (SOC-003): kind-6 events reference their target
 * in the `e` tag; many clients embed the full original event in content.
 * Embedded events are decoded AND verified before display — a repost of a
 * forged event never reaches projection.
 */
object RepostParser {

    /**
     * Resolves the reposted note from a kind-6 event. Returns the original
     * event (embedded and verified, when present) plus the reposter's
     * pubkey for attribution, or null when nothing displayable exists.
     */
    fun resolve(
        repost: NostrEvent,
        hasher: EventHasher = Sha256EventHasher,
    ): Pair<NostrEvent, String>? {
        if (repost.kind != NostrKinds.REPOST) return null

        // Embedded original (NIP-18 permits it; the common client pattern).
        if (repost.content.isNotBlank()) {
            val embedded = decodeEmbedded(repost.content, repost.receivedFromRelay, hasher)
            if (embedded != null) {
                return embedded to repost.pubkey.value
            }
        }

        // No embedded event and empty content: nothing to display inline.
        return null
    }

    /** Decodes + ID-verifies an embedded event from kind-6 content. */
    private fun decodeEmbedded(content: String, source: RelayUrl?, hasher: EventHasher): NostrEvent? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") || trimmed.length > space.bitos.core.model.NostrLimits.MAX_EVENT_BYTES) return null
        return try {
            // Verify the embedded event's own ID commitment — a reposted
            // forged event is as dangerous as a forged event.
            val event = NostrEventCodec.decodeEventObject(hasher, trimmed, source)
            if (!FeedNote.isFeedKind(event.kind)) null else event
        } catch (_: NostrEventCodec.Rejected) {
            null
        }
    }
}
