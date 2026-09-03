package space.bitos.app.ui.create.meme

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MST-018 store contract (EDT-002): save → relaunch (a fresh store over
 * the same dir) restores the exact project + copied assets; the index
 * holds ≤6 with LRU eviction; deletes remove everything; corrupt files
 * degrade to empty. JVM-only via injected bitmap edges + a temp dir.
 */
class MemeProjectStoreTest {

    private class FakeBitmaps : MemeProjectStore.BitmapIo {
        override fun aspectOf(bytes: ByteArray): Float? = 0.5f
        override fun encodePosterJpeg(bytes: ByteArray, longEdge: Int): ByteArray? = byteArrayOf(1, 2, 3)
    }

    private val projectWire = """
        {"v":1,"mode":"image","assets":[{"id":"a1","kind":"image"}],"overlays":[
         {"id":"o1","kind":"text","text":"when the fee market opens","font":"impact",
          "size":97,"color":0,"outline":2,"shadow":false,"x":0.5,"y":0.35,
          "scale":1,"rot":0}]}
    """.trimIndent()

    private fun store(dir: java.io.File) = MemeProjectStore(dir, FakeBitmaps())

    private fun tempRoot(): java.io.File =
        createTempDirectory("meme-slots").toFile()

    @Test
    fun saveThenRelaunchRestoresTheExactProject() {
        val root = tempRoot()
        val editor = store(root)
        val evicted = editor.save(
            slotId = "s1",
            projectWire = projectWire,
            assets = listOf(MemeProjectStore.AssetRef("a1", "content://pick/1")),
            opener = { key -> if (key == "content://pick/1") byteArrayOf(9, 9) else null },
            nowMs = 1_000,
        )
        assertTrue(evicted.isEmpty())

        // Relaunch: a fresh store over the same dir sees the same slot.
        val relaunched = store(root)
        val slots = relaunched.listSlots()
        assertEquals(1, slots.size)
        assertEquals("s1", slots.single().slotId)
        assertEquals("when the fee market opens", slots.single().label)
        assertNotNull(slots.single().posterName)

        val loaded = relaunched.loadSlot("s1")!!
        assertEquals("when the fee market opens", loaded.document.project.overlays.single().text)
        assertEquals(0.5f, loaded.document.assets.single().aspect)
        val assetFile = loaded.assetFiles["a1"]!!
        assertTrue(assetFile.exists(), "asset bytes copied in (CAP-005)")
        assertEquals(2, assetFile.length())
        assertTrue(loaded.posterFile!!.exists())
    }

    @Test
    fun reSaveIsIdempotentOnAssetBytesAndBumpsTheIndex() {
        val root = tempRoot()
        val editor = store(root)
        var copyCount = 0
        val opener = MemeProjectStore.AssetOpener { key ->
            copyCount += 1
            byteArrayOf(7)
        }
        editor.save("s1", projectWire, listOf(MemeProjectStore.AssetRef("a1", "uri1")), opener, 1_000)
        editor.save("s1", projectWire, listOf(MemeProjectStore.AssetRef("a1", "uri1")), opener, 2_000)
        assertEquals(1, copyCount, "the second save reuses the copied file")
        assertEquals(2_000L, editor.listSlots().single().updatedAtMs)
    }

    @Test
    fun sevenSlotsEvictTheOldestAndDeleteTheirFiles() {
        val root = tempRoot()
        val editor = store(root)
        var evictedTotal = mutableListOf<String>()
        (1..7).forEach { index ->
            evictedTotal += editor.save(
                "s$index", projectWire,
                listOf(MemeProjectStore.AssetRef("a1", "uri$index")),
                { byteArrayOf(1) },
                nowMs = index.toLong(),
            )
        }
        val slots = editor.listSlots()
        assertEquals(6, slots.size)
        assertEquals("s7", slots.first().slotId)
        assertNull(editor.loadSlot("s1"), "the evicted slot's files are gone")
        assertEquals(listOf("s1"), evictedTotal)
    }

    @Test
    fun deleteRemovesTheSlotDirAndIndexRow() {
        val root = tempRoot()
        val editor = store(root)
        editor.save("s1", projectWire, listOf(MemeProjectStore.AssetRef("a1", "u")), { byteArrayOf(1) }, 1)
        editor.save("s2", projectWire, listOf(MemeProjectStore.AssetRef("a1", "u")), { byteArrayOf(1) }, 2)
        editor.deleteSlot("s1")
        assertEquals(listOf("s2"), editor.listSlots().map { it.slotId })
        assertNull(editor.loadSlot("s1"))
    }

    @Test
    fun corruptFilesDegradeToEmptyAndNeverBlockCreating() {
        val root = tempRoot()
        val editor = store(root)
        // Corrupt index + a junk slot file.
        java.io.File(root, MemeProjectStore.INDEX_FILE).writeText("not json")
        java.io.File(java.io.File(root, MemeProjectStore.SLOTS_DIR), "junk").mkdirs()
        java.io.File(
            java.io.File(java.io.File(root, MemeProjectStore.SLOTS_DIR), "junk"),
            MemeProjectStore.SLOT_FILE,
        ).writeText("garbage")
        assertEquals(emptyList(), editor.listSlots())
        assertNull(editor.loadSlot("junk"))
        // And a fresh save still works.
        editor.save("fresh", projectWire, listOf(MemeProjectStore.AssetRef("a1", "u")), { byteArrayOf(1) }, 5)
        assertEquals("fresh", editor.listSlots().single().slotId)
        assertNotNull(editor.loadSlot("fresh"))
    }
}
