package space.bitos.core.model

/**
 * NIP-51 public block list (kind 10004) — APP-012 blocked-author
 * filtering. The `p` tags of the account's newest verified list are the
 * blocked set; the set is bounded so a hostile list cannot grow state
 * without limit. Only the parser lives here: subscription, head selection
 * (newest verified event wins) and persistence are adapter concerns.
 */
object BlockList {

    const val KIND = 10_004

    /** Bounded blocked set (spec §3.12 read-model remainder). */
    const val MAX_BLOCKS = 500

    private fun isHexPubkey(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * Blocked pubkeys from a verified kind-10004 event; non-hex `p` values
     * are dropped and the set caps at [MAX_BLOCKS]. Returns null for any
     * other kind (callers keep the previous head).
     */
    fun blockedPubkeys(event: NostrEvent): Set<String>? {
        if (event.kind != KIND) return null
        val blocked = LinkedHashSet<String>()
        for (tag in event.tags) {
            if (tag.firstOrNull() != "p") continue
            val pubkey = tag.getOrNull(1) ?: continue
            if (isHexPubkey(pubkey)) {
                blocked.add(pubkey)
                if (blocked.size >= MAX_BLOCKS) break
            }
        }
        return blocked
    }

    /** Head selection: the account's newest verified list wins (ties by id). */
    fun newest(events: List<NostrEvent>): NostrEvent? =
        events.filter { it.kind == KIND }.maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id.value })
}
