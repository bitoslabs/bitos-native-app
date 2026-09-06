package space.bitos.core.model

/**
 * Follower projection for the You surface (SOC-001/FED-006 family): kind-3
 * contact lists authored by OTHER accounts that p-tag this account.
 * Followers are derived, not canonical — relays only return the contact
 * lists they know — so the projection applies the newest-head-per-follower
 * rule: a newer verified list that no longer p-tags the account removes the
 * follower, and a newer list that does adds them back. Bounded fan-in;
 * hostile or malformed input changes nothing.
 */
class FollowerIndex(private val max: Int = MAX_TRACKED) {

    /** Explicit no-arg construction (Kotlin default arguments do not bridge
     *  to Swift initializers — native stores construct the bounded default). */
    constructor() : this(MAX_TRACKED)

    /** Follower pubkey → newest accepted kind-3 head createdAt. */
    private val heads = HashMap<String, Long>()

    /**
     * Absorbs one verified event. True when the follower set changed (a new
     * follower appeared or an unfollow removed one). The account's own
     * contact list, non-kind-3 events, oversized tag lists and stale heads
     * (older than the newest head already seen for that author) are ignored.
     */
    fun absorb(event: NostrEvent, accountPubkey: String): Boolean {
        if (event.kind != NostrKinds.CONTACT_LIST) return false
        if (event.tags.size > NostrLimits.MAX_TAGS) return false
        val follower = event.pubkey.value
        if (follower == accountPubkey) return false
        val previous = heads[follower]
        if (previous != null && previous >= event.createdAt) return false
        val followsNow = event.tags.any { tag ->
            tag.firstOrNull() == "p" && tag.getOrNull(1) == accountPubkey
        }
        return if (followsNow) {
            if (previous == null && heads.size >= max) return false
            heads[follower] = event.createdAt
            true
        } else {
            heads.remove(follower) != null
        }
    }

    /** Current follower count (bounded by [MAX_TRACKED]). */
    fun count(): Int = heads.size

    /** Current follower pubkeys (unordered). */
    fun pubkeys(): List<String> = heads.keys.toList()

    /** True when [pubkey] follows the account per the newest absorbed head. */
    fun contains(pubkey: String): Boolean = heads.containsKey(pubkey)

    /** Account switch: the projection is scoped to one identity. */
    fun clear() = heads.clear()

    companion object {
        /** Hostile fan-in bound for the derived set. */
        const val MAX_TRACKED = 500

        /** One REQ page: relays cap `#p` result sets well below this. */
        const val REQUEST_LIMIT = 400
    }
}
