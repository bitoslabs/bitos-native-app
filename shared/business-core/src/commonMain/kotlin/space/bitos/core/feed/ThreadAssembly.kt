package space.bitos.core.feed

/**
 * APP-009 X-style threading (spec §3.9): a deterministic assembly of the
 * reply window into a display list — top-level replies followed by their
 * flattened descendants behind depth indents, NIP-10 markers deciding
 * parentage, cycle-guarded, with orphaned replies surfaced at the top
 * level instead of silently dropped.
 */
data class ThreadItem(
    val id: String,
    /** 0 = top-level reply; descendants indent by depth. */
    val depth: Int,
    /** Immediate display parent (null for top level — the root post). */
    val parentId: String?,
    /** True when the chain above this reply is missing from the window. */
    val orphan: Boolean,
)

object ThreadAssembly {

    /** Indentation bound (deeper replies flatten at this depth). */
    const val MAX_DEPTH = 8

    /** Bounded thread window (spec: size-bounded aggregates). */
    const val MAX_ITEMS = 200

    /**
     * NIP-10 root/parent extraction from an event's tags:
     *  • explicit `["e", id, …, "root"]` / `"reply"` markers win;
     *  • legacy positional form: first `e` = root, last `e` = parent
     *    (single `e` → reply to root);
     *  • no `e` tags at all → neither.
     */
    fun rootAndParent(tags: List<List<String>>): Pair<String?, String?> {
        val eTags = tags.filter { it.firstOrNull() == "e" }
        if (eTags.isEmpty()) return null to null
        val root = eTags.firstOrNull { it.getOrNull(3) == "root" }?.getOrNull(1)
            ?: eTags.getOrNull(0)?.getOrNull(1)
        val reply = eTags.firstOrNull { it.getOrNull(3) == "reply" }?.getOrNull(1)
        val parent = reply
            ?: eTags.lastOrNull()?.getOrNull(1)?.takeIf { it != root }
            ?: root
        return root to parent
    }

    /**
     * Assembles the display list for one thread. [replies] are the verified
     * replies collected for the thread (any order; deduped by id).
     *
     * Rules: children sort chronologically (conversation flow); descendants
     * flatten at [MAX_DEPTH]; already-emitted ids never re-emit (cycle
     * guard); replies whose parent chain is absent surface at the top level
     * flagged `orphan=true`; the list caps at [MAX_ITEMS].
     */
    fun assemble(rootId: String, replies: List<FeedNote>): List<ThreadItem> {
        val byId = LinkedHashMap<String, FeedNote>()
        replies.forEach { reply -> if (byId[reply.id] == null) byId[reply.id] = reply }

        val children = HashMap<String, MutableList<FeedNote>>()
        for (note in byId.values) {
            children.getOrPut(parentOf(note, rootId)) { mutableListOf() }.add(note)
        }
        children.values.forEach { it.sortBy { reply -> reply.createdAt } }

        val out = mutableListOf<ThreadItem>()
        val emitted = HashSet<String>()

        fun emitChildren(parentId: String, depth: Int) {
            for (child in children[parentId].orEmpty()) {
                if (child.id in emitted) continue // cycle guard
                if (out.size >= MAX_ITEMS) return
                emitted += child.id
                out += ThreadItem(id = child.id, depth = depth, parentId = parentId, orphan = false)
                emitChildren(child.id, minOf(depth + 1, MAX_DEPTH))
            }
        }
        emitChildren(rootId, 0)

        // Orphans: nothing reachable from the root references them — their
        // parent events are missing. Surface at the top level, newest last.
        byId.values
            .filter { it.id !in emitted }
            .sortedBy { it.createdAt }
            .take((MAX_ITEMS - out.size).coerceAtLeast(0))
            .forEach { out += ThreadItem(id = it.id, depth = 0, parentId = null, orphan = true) }
        return out
    }

    /** Display parent of a reply within this thread: explicit NIP-10
     * parent, else the thread root marker, else the thread head. Replies
     * whose parent is missing surface as orphans (not reachable). */
    private fun parentOf(note: FeedNote, rootId: String): String =
        note.threadParentId ?: note.threadRootId ?: rootId
}
