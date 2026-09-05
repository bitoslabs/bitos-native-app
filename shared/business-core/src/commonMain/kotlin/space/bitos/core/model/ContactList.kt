package space.bitos.core.model

import space.bitos.core.nostr.EventHasher
import space.bitos.core.nostr.Sha256EventHasher

/**
 * Kind-3 contact-list projection (SOC-001/FED-006 foundation): the set of
 * followed pubkeys from the account's newest verified kind-3 event. Bounded
 * and deduplicated; invalid entries are dropped, not fatal.
 */
object ContactList {

    private val hex64 = Regex("^[0-9a-f]{64}$")

    fun followedPubkeys(event: NostrEvent): List<String> {
        if (event.kind != NostrKinds.CONTACT_LIST) return emptyList()
        if (event.tags.size > NostrLimits.MAX_TAGS) return emptyList()
        val seen = LinkedHashSet<String>()
        for (tag in event.tags) {
            if (tag.firstOrNull() != "p") continue
            val pubkey = tag.getOrNull(1) ?: continue
            if (pubkey.length != 64 || !hex64.matches(pubkey)) continue
            seen.add(pubkey)
            if (seen.size >= MAX_FOLLOWS) break
        }
        return seen.toList()
    }

    /** Newest-wins selection among verified kind-3 candidates. */
    fun newest(events: List<NostrEvent>): NostrEvent? =
        events.filter { it.kind == NostrKinds.CONTACT_LIST }.maxByOrNull { it.createdAt }

    /**
     * Just below the codec tag bound (256) so any accepted kind-3 parses in
     * full. Republishing a follow change must never emit fewer `p` tags than
     * the stored list, or unfollowed-but-parsed-only accounts get dropped.
     */
    const val MAX_FOLLOWS = 250

    private val hasher: EventHasher = Sha256EventHasher
}
