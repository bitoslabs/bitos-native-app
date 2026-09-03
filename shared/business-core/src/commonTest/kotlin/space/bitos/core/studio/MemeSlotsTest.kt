package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Continue-creating slots (plan MST-018, EDT-001/002): the slot wire
 * round-trips, the index is bounded/LRU, hostile files degrade to empty
 * and path-traversal asset refs are rejected — a broken store must never
 * block creating.
 */
class MemeSlotsTest {

    private val project = MemeProject(
        mode = MemeMode.IMAGE,
        assets = listOf(MemeAsset("a1", MemeMode.IMAGE)),
        overlays = listOf(
            MemeOverlay(
                id = "o1", kind = MemeOverlayKind.TEXT, text = "when the fee market opens",
                font = MemeFontSlot.IMPACT, size = 97, colorIndex = 0, outline = 2,
                shadow = false, x = 0.5f, y = 0.35f, scale = 1f, rotationDeg = 0f,
            ),
        ),
    )

    @Test
    fun slotDocumentRoundTripsProjectAssetsAndStamp() {
        val document = MemeSlotDocument(
            slotId = "s-1",
            project = project,
            assets = listOf(MemeSlotAsset("a1", "asset-a1.png", 0.5625f)),
            updatedAtMs = 1_700_000_000_000,
        )
        val decoded = MemeSlotCodec.decode(MemeSlotCodec.encode(document))!!
        assertEquals("s-1", decoded.slotId)
        assertEquals(1_700_000_000_000, decoded.updatedAtMs)
        assertEquals("when the fee market opens", decoded.project.overlays.single().text)
        assertEquals("asset-a1.png", decoded.assets.single().fileName)
        assertEquals(0.5625f, decoded.assets.single().aspect)
    }

    @Test
    fun hostileSlotFilesDegradeToNull() {
        assertNull(MemeSlotCodec.decode("junk"))
        assertNull(MemeSlotCodec.decode("""{"v":2,"id":"s"}"""))
        assertNull(MemeSlotCodec.decode("""{"v":1,"id":"s","project":"nope"}"""))
        assertNull(MemeSlotCodec.decode("x".repeat(MemeSlots.MAX_SLOT_FILE_LENGTH + 1)))
        // Path traversal and absolute paths never enter the asset table.
        val hostile = MemeSlotCodec.encode(
            MemeSlotDocument(
                "s", project,
                listOf(MemeSlotAsset("a1", "../../escape.png", 1f)), 0,
            ),
        ).replace("../../escape.png", "../../../etc/passwd")
        val decoded = MemeSlotCodec.decode(hostile)!!
        assertTrue(decoded.assets.isEmpty(), "traversal refs dropped: ${decoded.assets}")
    }

    @Test
    fun indexRoundTripsMostRecentFirst() {
        val entries = listOf(
            MemeSlotEntry("s1", 3_000, "p1.jpg", "first"),
            MemeSlotEntry("s2", 2_000, null, "second"),
        )
        val decoded = MemeSlotCodec.decodeIndex(MemeSlotCodec.encodeIndex(entries))
        assertEquals(entries, decoded)
        assertEquals(emptyList(), MemeSlotCodec.decodeIndex("junk"))
    }

    @Test
    fun upsertBumpsToFrontAndEvictsLruPastSix() {
        var index = emptyList<MemeSlotEntry>()
        val ids = (1..7).map { "s$it" }
        var evictions = mutableListOf<String>()
        ids.forEachIndexed { i, id ->
            val upsert = MemeSlotRules.upsert(index, id, 1_000L + i, "p$id.jpg", "meme $i")
            index = upsert.entries
            evictions += upsert.evicted
        }
        assertEquals(6, index.size, "cap holds")
        assertEquals("s7", index.first().slotId, "newest first")
        assertEquals(listOf("s1"), evictions, "the oldest slot evicted once")
        // Re-saving an existing id bumps it without evicting.
        val bumped = MemeSlotRules.upsert(index, "s3", 9_999, null, "meme 3 again")
        assertEquals("s3", bumped.entries.first().slotId)
        assertEquals(6, bumped.entries.size)
        assertTrue(bumped.evicted.isEmpty())
    }

    @Test
    fun labelsAndRelativeTimesAreHuman() {
        assertEquals(
            "when the fee market opens",
            MemeSlotRules.labelFor(project),
        )
        val long = project.copy(
            overlays = project.overlays.map {
                it.copy(text = "w".repeat(80))
            },
        )
        assertEquals(40, MemeSlotRules.labelFor(long).length, "≤40 chars with ellipsis")
        assertEquals("Image meme", MemeSlotRules.labelFor(MemeProject(mode = MemeMode.IMAGE)))
        assertEquals("GIF meme", MemeSlotRules.labelFor(MemeProject(mode = MemeMode.GIF)))

        val now = 10_000_000L
        assertEquals("just now", MemeSlotRules.relativeTime(now, now))
        assertEquals("30 min ago", MemeSlotRules.relativeTime(now - 30 * 60_000, now))
        assertEquals("5 h ago", MemeSlotRules.relativeTime(now - 5 * 3_600_000, now))
        assertEquals("yesterday", MemeSlotRules.relativeTime(now - 30 * 3_600_000, now))
        assertEquals("3 d ago", MemeSlotRules.relativeTime(now - 72 * 3_600_000, now))
    }
}
