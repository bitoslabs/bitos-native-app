package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MST-047 W4: the local shared-sound library index — bounded, LRU,
 *  hostile-tolerant (plan §3.5: ≤ 30 sounds, ≤ 15 s). */
class SharedSoundLibraryTest {

    private val sha = "9f2c4a1b7e5d3a6f8c0b2d4e6f8a0c2b4d6e8f0a2c4b6d8e0f2a4c6b8d0e2f4a"

    private fun entry(
        id: String = "s1",
        savedAtMs: Long = 1_000L,
        durationMs: Long = 12_000L,
        sha256: String = sha,
        url: String = "https://blossom.example/b/$sha.webm",
    ) = SharedSoundLibrary.Entry(
        id = id, label = "Sound $id", url = url, sha256 = sha256,
        license = "CC0-1.0", durationMs = durationMs, savedAtMs = savedAtMs,
    )

    @Test
    fun encodeDecodeRoundTrips() {
        val library = SharedSoundLibrary.add(SharedSoundLibrary.EMPTY, entry())
        val decoded = SharedSoundLibrary.decode(SharedSoundLibrary.encode(library))
        assertEquals(1, decoded.size)
        val restored = decoded["s1"]!!
        assertEquals("Sound s1", restored.label)
        assertEquals(sha, restored.sha256)
        assertEquals(12_000L, restored.durationMs)
        assertEquals(1_000L, restored.savedAtMs)
        assertEquals("CC0-1.0", restored.license)
    }

    @Test
    fun addDedupsByIdNewestWins() {
        var library = SharedSoundLibrary.add(SharedSoundLibrary.EMPTY, entry(savedAtMs = 1_000L))
        library = SharedSoundLibrary.add(library, entry(savedAtMs = 2_000L))
        assertEquals(1, library.size)
        assertEquals(2_000L, library["s1"]!!.savedAtMs)
    }

    @Test
    fun lruEvictionKeepsTheNewestThirty() {
        var library = SharedSoundLibrary.EMPTY
        for (i in 1..32) library = SharedSoundLibrary.add(library, entry(id = "s$i", savedAtMs = i * 1_000L))
        assertEquals(SharedSoundContract.MAX_LOCAL_SOUNDS, library.size)
        assertNull(library["s1"]) // oldest evicted
        assertNull(library["s2"])
        assertTrue(library["s3"] != null && library["s32"] != null)
    }

    @Test
    fun removeDeletesById() {
        val library = SharedSoundLibrary.add(SharedSoundLibrary.EMPTY, entry())
        assertEquals(0, SharedSoundLibrary.remove(library, "s1").size)
        assertEquals(1, SharedSoundLibrary.remove(library, "other").size)
    }

    @Test
    fun junkEntriesLeaveTheLibraryUnchanged() {
        val library = SharedSoundLibrary.add(SharedSoundLibrary.EMPTY, entry())
        val unchanged = SharedSoundLibrary.encode(library)
        assertEquals(unchanged, SharedSoundLibrary.encode(SharedSoundLibrary.add(library, entry(sha256 = "junk"))))
        assertEquals(unchanged, SharedSoundLibrary.encode(SharedSoundLibrary.add(library, entry(durationMs = 0))))
        assertEquals(unchanged, SharedSoundLibrary.encode(SharedSoundLibrary.add(library, entry(durationMs = 16_000))))
        assertEquals(unchanged, SharedSoundLibrary.encode(SharedSoundLibrary.add(library, entry(id = " "))))
    }

    @Test
    fun decodeIsHostileTolerant() {
        assertEquals(0, SharedSoundLibrary.decode("not json").size)
        assertEquals(0, SharedSoundLibrary.decode("""{"v":2,"sounds":[]}""").size) // wrong version
        assertEquals(0, SharedSoundLibrary.decode("""{"v":1}""").size) // missing sounds

        // One valid row survives among junk rows.
        val mixed = """
            {"v":1,"sounds":[
              {"id":"good","label":"G","url":"","sha256":"$sha","license":"CC0-1.0",
               "durationMs":9000,"savedAtMs":5},
              {"id":"bad-sha","sha256":"nope","durationMs":9000},
              {"id":"no-duration","sha256":"$sha"},
              "not-even-an-object"
            ]}
        """.trimIndent()
        val decoded = SharedSoundLibrary.decode(mixed)
        assertEquals(1, decoded.size)
        assertEquals("good", decoded.entries.single().id)

        // Over-cap lists truncate to 30; dedup keeps first.
        fun row(i: Int) =
            """{"id":"s$i","label":"S$i","url":"","sha256":"$sha","license":"CC0-1.0","durationMs":1000,"savedAtMs":$i}"""
        assertEquals(SharedSoundContract.MAX_LOCAL_SOUNDS, SharedSoundLibrary.decode("""{"v":1,"sounds":[${(1..40).joinToString(",") { row(it) }}]}""").size)
        val dup = """{"v":1,"sounds":[${row(1)},${row(1)}]}"""
        assertEquals(1, SharedSoundLibrary.decode(dup).size)
    }

    @Test
    fun blankUrlIsAllowedForLocalOnlyPicks() {
        val library = SharedSoundLibrary.add(SharedSoundLibrary.EMPTY, entry(url = ""))
        assertEquals("", SharedSoundLibrary.decode(SharedSoundLibrary.encode(library))["s1"]!!.url)
    }
}
