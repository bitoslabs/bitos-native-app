package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MST-047 read path: kind-30078 shared-sound events → hostile-tolerant
 *  parse with a license gate (only CC0 / CC-BY / CC-BY-NC ingest). */
class SharedSoundContractTest {

    private val sha = "9f2c4a1b7e5d3a6f8c0b2d4e6f8a0c2b4d6e8f0a2c4b6d8e0f2a4c6b8d0e2f4a"
    private val author = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun tags(
        d: String = "com.bitos.bitz:sound:raid-shadow-meme",
        url: String? = "https://blossom.example/b/$sha.webm",
        x: String? = sha,
        license: String? = "CC-BY-4.0",
        attribution: String? = "sound of dj satsoshi",
        topics: Int = 2,
        image: String? = "https://blossom.example/b/cover.jpg",
        p: String? = author,
    ): List<List<String>> = buildList {
        add(listOf("d", d))
        url?.let { add(listOf("url", it)) }
        x?.let { add(listOf("x", it)) }
        license?.let { add(listOf("license", it)) }
        attribution?.let { add(listOf("attribution", it)) }
        repeat(topics) { add(listOf("t", if (it == 0) "meme" else "gaming")) }
        image?.let { add(listOf("image", it)) }
        p?.let { add(listOf("p", it)) }
    }

    private fun content(
        label: String? = "Raid Shadow Meme",
        durationSec: Any? = 12,
        mime: String? = "audio/webm",
        description: String? = "The one sound every gaming meme needs",
        schema: String? = "com.bitos.bitz.sound",
        version: Int? = 1,
    ): String {
        val fields = buildList {
            schema?.let { add("\"schema\":\"$it\"") }
            version?.let { add("\"version\":$it") }
            label?.let { add("\"label\":\"$it\"") }
            durationSec?.let { add("\"durationSec\":$it") }
            mime?.let { add("\"mime\":\"$it\"") }
            description?.let { add("\"description\":\"$it\"") }
        }
        return "{${fields.joinToString(",")}}"
    }

    @Test
    fun fullEventParsesWithEveryField() {
        // Verbatim shape from contracts/meme/sound-event-v1.json (repo
        // rule: protocol changes pin fixtures).
        val sound = SharedSoundContract.parse(tags(), content())!!
        assertEquals("raid-shadow-meme", sound.id)
        assertEquals("Raid Shadow Meme", sound.label)
        assertEquals("https://blossom.example/b/$sha.webm", sound.url)
        assertEquals(sha, sound.sha256)
        assertEquals("CC-BY-4.0", sound.license)
        assertEquals("sound of dj satsoshi", sound.attribution)
        assertEquals(12_000L, sound.durationMs)
        assertEquals("audio/webm", sound.mime)
        assertEquals("The one sound every gaming meme needs", sound.description)
        assertEquals(listOf("meme", "gaming"), sound.topics)
        assertEquals("https://blossom.example/b/cover.jpg", sound.imageUrl)
        assertEquals(author, sound.authorPubkey)
    }

    @Test
    fun minimalEventDefaultsMimeLabelAndSkipsOptionals() {
        val sound = SharedSoundContract.parse(
            tags(attribution = null, topics = 0, image = null, p = null),
            content(label = null, durationSec = null, mime = null, description = null, schema = null, version = null),
        )!!
        assertEquals("raid-shadow-meme", sound.label) // falls back to id
        assertEquals(0L, sound.durationMs) // undeclared → decode decides
        assertEquals(SharedSoundContract.DEFAULT_MIME, sound.mime)
        assertEquals("", sound.attribution)
        assertTrue(sound.topics.isEmpty())
        assertEquals("", sound.imageUrl)
        assertEquals("", sound.authorPubkey)
    }

    @Test
    fun foreignShapesAreRejected() {
        assertNull(SharedSoundContract.parse(tags(d = "com.bitos.bitz:template:x"), content())) // wrong namespace
        assertNull(SharedSoundContract.parse(tags(d = "com.bitos.bitz:sound:"), content())) // blank id
        assertNull(SharedSoundContract.parse(tags(), "not json"))
        assertNull(SharedSoundContract.parse(tags(), content(schema = "com.other.sound")))
        assertNull(SharedSoundContract.parse(tags(), content(version = 2)))
    }

    @Test
    fun unusableUrlShaOrLicenseAreRejected() {
        assertNull(SharedSoundContract.parse(tags(url = null), content()))
        assertNull(SharedSoundContract.parse(tags(url = "   "), content()))
        assertNull(SharedSoundContract.parse(tags(x = null), content()))
        assertNull(SharedSoundContract.parse(tags(x = "not-hex"), content()))
        assertNull(SharedSoundContract.parse(tags(x = sha.take(63)), content()))
        assertNull(SharedSoundContract.parse(tags(license = null), content()))
        assertNull(SharedSoundContract.parse(tags(license = "bitz/all-reserved"), content())) // not ingestable
        assertNull(SharedSoundContract.parse(tags(license = "CC-BY-SA-4.0"), content())) // not ingestable
    }

    @Test
    fun uppercaseShaNormalizesToCanonicalLowercase() {
        val sound = SharedSoundContract.parse(tags(x = sha.uppercase()), content())!!
        assertEquals(sha, sound.sha256)
    }

    @Test
    fun ingestableLicensesAllPassTheGate() {
        for (license in SharedSoundContract.INGESTABLE_LICENSES) {
            val sound = SharedSoundContract.parse(tags(license = license), content())
            assertTrue(sound != null, "license $license should ingest")
            assertEquals(license, sound!!.license)
        }
    }

    @Test
    fun declaredDurationOutOfBoundsIsRejectedAndJunkDegrades() {
        assertNull(SharedSoundContract.parse(tags(), content(durationSec = 16)))
        assertNull(SharedSoundContract.parse(tags(), content(durationSec = -1)))
        assertNull(SharedSoundContract.parse(tags(), content(durationSec = "1e999"))) // Infinity > cap
        // Boundary 15 s parses; junk-typed fields degrade to undeclared.
        assertEquals(15_000L, SharedSoundContract.parse(tags(), content(durationSec = 15))!!.durationMs)
        assertEquals(0L, SharedSoundContract.parse(tags(), content(durationSec = "\"junk\""))!!.durationMs)
        assertEquals(0L, SharedSoundContract.parse(tags(), content(durationSec = true))!!.durationMs)
    }

    @Test
    fun textBoundsClamp() {
        val longLabel = "L".repeat(200)
        val sound = SharedSoundContract.parse(
            tags(
                attribution = "a".repeat(300),
                topics = 0,
                image = "https://x.example/" + "c".repeat(4_000),
            ),
            content(label = longLabel, description = "d".repeat(900)),
        )!!
        assertEquals(SharedSoundContract.MAX_LABEL, sound.label.length)
        assertEquals(SharedSoundContract.MAX_ATTRIBUTION, sound.attribution.length)
        assertEquals(SharedSoundContract.MAX_IMAGE_URL_LENGTH, sound.imageUrl.length)
        assertEquals(SharedSoundContract.MAX_DESCRIPTION, sound.description.length)
        val emptyAttribution = SharedSoundContract.parse(tags(attribution = ""), content())!!
        assertEquals("", emptyAttribution.attribution)
    }

    @Test
    fun topicsDedupTakeTenAndClampLength() {
        val manyTopics = (1..14).map { listOf("t", if (it == 1) "meme" else "topic-$it") }
        val sound = SharedSoundContract.parse(
            tags() + manyTopics,
            content(),
        )!!
        assertEquals(SharedSoundContract.MAX_TOPICS, sound.topics.size)
        assertTrue(sound.topics.distinct().size == sound.topics.size)
        val longTopic = listOf(listOf("t", "t".repeat(80)))
        val clamped = SharedSoundContract.parse(tags(topics = 0) + longTopic, content())!!
        assertEquals(SharedSoundContract.MAX_TOPIC_LENGTH, clamped.topics.single().length)
    }

    @Test
    fun isSoundDTagAddressesOnlyTheSoundNamespace() {
        assertTrue(SharedSoundContract.isSoundDTag("com.bitos.bitz:sound:x"))
        assertFalse(SharedSoundContract.isSoundDTag("com.bitos.bitz:template:x"))
        assertFalse(SharedSoundContract.isSoundDTag("other:app:sound:x"))
    }

    @Test
    fun ingestCheckGatesTheDownloadSideBounds() {
        assertTrue(SharedSoundContract.ingestCheck(15_000L, SharedSoundContract.MAX_BYTES))
        assertTrue(SharedSoundContract.ingestCheck(1L, 1L))
        assertFalse(SharedSoundContract.ingestCheck(0L, 1L)) // no zero-duration
        assertFalse(SharedSoundContract.ingestCheck(15_001L, 1L)) // over 15 s
        assertFalse(SharedSoundContract.ingestCheck(1_000L, 0L)) // no empty files
        assertFalse(SharedSoundContract.ingestCheck(1_000L, SharedSoundContract.MAX_BYTES + 1)) // over 8 MB
        assertFalse(SharedSoundContract.ingestCheck(-5L, 100L)) // junk duration
    }
}
