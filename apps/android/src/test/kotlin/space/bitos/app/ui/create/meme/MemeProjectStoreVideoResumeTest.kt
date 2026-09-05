package space.bitos.app.ui.create.meme

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M5 video resume regression: a SPLIT timeline (two clips over one source)
 * must survive save → relaunch → the screen's resume join
 * (wire clip rows joined against the slot's asset files by clip id).
 * JVM-only via injected bitmap edges + a temp dir.
 */
class MemeProjectStoreVideoResumeTest {

    private class FakeBitmaps : MemeProjectStore.BitmapIo {
        override fun aspectOf(bytes: ByteArray): Float? = 0.5f
        override fun encodePosterJpeg(bytes: ByteArray, longEdge: Int): ByteArray? = byteArrayOf(1, 2, 3)
    }

    private fun tempRoot(): java.io.File = createTempDirectory("meme-video-resume").toFile()

    /**
     * The wire the editor holds after splitting a 10 s clip at 5 s: two
     * clips over the same source + the asset rows appendClip registered.
     */
    private val splitWire = """
        {"v":1,"mode":"video","assets":[{"id":"v1","kind":"video"},{"id":"v2","kind":"video"}],
         "overlays":[],"trim":[0,5000],
         "clips":[{"id":"v1","start":0,"end":5000},{"id":"v2","start":5000,"end":10000}]}
    """.trimIndent()

    @Test
    fun splitTimelineSurvivesSaveAndRelaunch() {
        val root = tempRoot()
        val source = ByteArray(64) { it.toByte() } // one shared source video
        val editor = MemeProjectStore(root, FakeBitmaps())
        editor.save(
            slotId = "s1",
            projectWire = splitWire,
            assets = listOf(
                MemeProjectStore.AssetRef("v1", "mem:v1"),
                MemeProjectStore.AssetRef("v2", "mem:v2"),
            ),
            opener = { key -> if (key == "mem:v1" || key == "mem:v2") source else null },
            nowMs = 1_000,
        )

        // Relaunch: a fresh store over the same dir.
        val loaded = MemeProjectStore(root, FakeBitmaps()).loadSlot("s1")!!
        val clips = loaded.document.project.clips
        assertEquals(listOf("v1" to 0L, "v2" to 5000L), clips.map { it.id to it.startMs },
            "both halves of the split decode from the slot wire")
        assertEquals(5000L, clips[0].endMs)
        assertEquals(10000L, clips[1].endMs)

        // The screen's resume join: every wire clip id needs its file.
        clips.forEach { wireClip ->
            val file = loaded.assetFiles[wireClip.id]
            assertNotNull(file, "clip ${wireClip.id} has a slot asset file")
            assertTrue(file.exists() && file.length() > 0, "clip ${wireClip.id} bytes are on disk")
        }
    }

    /**
     * The editor session after resume holds NEW windows over the SAME
     * source; the next autosave must keep both clip rows (ids are stable,
     * so the idempotent copy must not drop the second half).
     */
    @Test
    fun reSaveAfterResumeKeepsBothClipRowsAndBytes() {
        val root = tempRoot()
        val source = ByteArray(32) { 7 }
        val editor = MemeProjectStore(root, FakeBitmaps())
        val refs = listOf(
            MemeProjectStore.AssetRef("v1", "mem:v1"),
            MemeProjectStore.AssetRef("v2", "mem:v2"),
        )
        val opener = MemeProjectStore.AssetOpener { key ->
            if (key == "mem:v1" || key == "mem:v2") source else null
        }
        editor.save("s1", splitWire, refs, opener, 1_000)
        val trimmedWire = """
            {"v":1,"mode":"video","assets":[{"id":"v1","kind":"video"},{"id":"v2","kind":"video"}],
             "overlays":[],"trim":[0,4000],
             "clips":[{"id":"v1","start":0,"end":4000},{"id":"v2","start":5000,"end":9000}]}
        """.trimIndent()
        editor.save("s1", trimmedWire, refs, opener, 2_000)

        val loaded = MemeProjectStore(root, FakeBitmaps()).loadSlot("s1")!!
        assertEquals(2, loaded.document.project.clips.size, "the second half survives a re-save")
        assertTrue(loaded.assetFiles.keys.containsAll(listOf("v1", "v2")))
    }
}
