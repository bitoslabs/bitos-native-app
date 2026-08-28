package space.bitos.core.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * APP-009 X-style threading: NIP-10 root/parent extraction and the
 * display assembly (chronological children, depth cap, cycle guard,
 * orphan surfacing, window bound).
 */
class ThreadAssemblyTest {

    private fun reply(
        id: String,
        createdAt: Long,
        root: String? = null,
        parent: String? = null,
    ) = FeedNote(
        id = id, pubkey = "pk-$id", content = "c", createdAt = createdAt, kind = 1,
        replyTo = parent, threadRootId = root, threadParentId = parent,
        hashtags = emptyList(), mentions = emptyList(), mediaUrls = emptyList(),
        isProtocolPayload = false,
    )

    // ── NIP-10 anchors ─────────────────────────────────────────────

    @Test
    fun rootAndParentPreferExplicitMarkers() {
        val tags = listOf(
            listOf("e", "root-id", "", "", "root"),
            listOf("e", "reply-id", "", "", "reply"),
            listOf("p", "author"),
        )
        assertEquals("root-id" to "reply-id", ThreadAssembly.rootAndParent(tags))
    }

    @Test
    fun rootOnlyLegacyTagsReplyToTheRoot() {
        val tags = listOf(listOf("e", "only-id"))
        assertEquals("only-id" to "only-id", ThreadAssembly.rootAndParent(tags))
    }

    @Test
    fun positionalFormUsesFirstAsRootAndLastAsParent() {
        val tags = listOf(
            listOf("e", "first"),
            listOf("e", "middle"),
            listOf("e", "last"),
        )
        assertEquals("first" to "last", ThreadAssembly.rootAndParent(tags))
        // Two positional tags (legacy double-e): parent is the second.
        assertEquals("first" to "middle", ThreadAssembly.rootAndParent(tags.take(2)))
    }

    @Test
    fun noETagsYieldNoAnchors() {
        assertEquals(null to null, ThreadAssembly.rootAndParent(listOf(listOf("p", "x"))))
    }

    // ── Assembly ───────────────────────────────────────────────────

    @Test
    fun topLevelRepliesSortChronologicallyWithDescendantsIndented() {
        val items = ThreadAssembly.assemble(
            "root",
            listOf(
                reply("b", 2_000, root = "root"),                     // second top-level
                reply("b1", 2_100, root = "root", parent = "b"),      // b's child
                reply("a", 1_000, root = "root"),                     // first top-level
                reply("a1", 1_100, root = "root", parent = "a"),      // a's child
                reply("a1a", 1_200, root = "root", parent = "a1"),    // grandchild
            ),
        )
        assertEquals(
            listOf("a", "a1", "a1a", "b", "b1"),
            items.map { it.id },
        )
        assertEquals(listOf(0, 1, 2, 0, 1), items.map { it.depth })
        assertEquals("root", items.first().parentId)
        assertTrue(items.none { it.orphan })
    }

    @Test
    fun cyclesAreGuardedInsteadOfRecursingForever() {
        // a → parent b, b → parent a: neither reachable from root → both
        // orphaned at top level exactly once (no infinite emission).
        val items = ThreadAssembly.assemble(
            "root",
            listOf(reply("a", 1, root = "root", parent = "b"), reply("b", 2, root = "root", parent = "a")),
        )
        assertEquals(2, items.size)
        assertTrue(items.all { it.orphan })
        assertTrue(items.all { it.depth == 0 })
    }

    @Test
    fun cycleReachableFromRootEmitsEachNoteOnce() {
        // c is a real reply to root; a↔b hang off c and loop.
        val items = ThreadAssembly.assemble(
            "root",
            listOf(
                reply("c", 1, root = "root"),
                reply("a", 2, root = "root", parent = "c"),
                reply("b", 3, root = "root", parent = "a"),
                reply("c2", 4, root = "root", parent = "b"), // pretends b is a parent
            ),
        )
        assertEquals(setOf("c", "a", "b", "c2"), items.map { it.id }.toSet())
        assertEquals(items.size, items.map { it.id }.toSet().size)
    }

    @Test
    fun repliesWithMissingParentsSurfaceAsOrphans() {
        // parent "ghost" never arrived → not reachable → orphan top-level.
        val items = ThreadAssembly.assemble(
            "root",
            listOf(
                reply("real", 1, root = "root"),
                reply("lost", 2, root = "root", parent = "ghost"),
            ),
        )
        val orphan = items.single { it.id == "lost" }
        assertTrue(orphan.orphan)
        assertEquals(0, orphan.depth)
        assertEquals(null, orphan.parentId)
        assertTrue(!items.single { it.id == "real" }.orphan)
    }

    @Test
    fun depthCapsAndFlattensDeepChains() {
        val replies = (0..ThreadAssembly.MAX_DEPTH + 3).map { index ->
            reply("n$index", index.toLong(), root = "root", parent = if (index == 0) null else "n${index - 1}")
        }
        // n0 has no parent → threadRootId root → top level; the chain nests.
        val items = ThreadAssembly.assemble("root", replies)
        assertEquals(ThreadAssembly.MAX_DEPTH, items.maxOf { it.depth })
        assertEquals(replies.size, items.size) // everything still visible, flattened at cap
    }

    @Test
    fun windowIsBounded() {
        val replies = (0 until ThreadAssembly.MAX_ITEMS + 40).map { index ->
            reply("r$index", index.toLong(), root = "root")
        }
        val items = ThreadAssembly.assemble("root", replies)
        assertEquals(ThreadAssembly.MAX_ITEMS, items.size)
    }
}
