package space.bitos.core.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import space.bitos.core.publish.NoteComposer

/**
 * MST-047 W4: publish-your-own kind-30078 sound events. The composer's
 * output must round-trip through `SharedSoundContract.parse` — rail
 * readers see exactly what this built (plan §3.5 wire).
 */
class SharedSoundComposerTest {

    private val author = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val sha = "9f2c4a1b7e5d3a6f8c0b2d4e6f8a0c2b4d6e8f0a2c4b6d8e0f2a4c6b8d0e2f4a"
    private val url = "https://blossom.example/b/$sha.webm"

    private fun compose(
        label: String = "Raid Shadow Meme",
        license: String = "CC-BY-4.0",
        durationSec: Int = 12,
        soundId: String = "raid-shadow-meme",
        url: String = this.url,
        sha256Hex: String = sha,
        topics: List<String> = listOf("meme", "gaming"),
        attribution: String? = null,
        description: String? = null,
        imageUrl: String? = null,
    ) = NoteComposer(clock = { 1_700_000_000L }).composeSharedSound(
        authorPubkey = author,
        soundId = soundId,
        label = label,
        url = url,
        sha256Hex = sha256Hex,
        license = license,
        durationSec = durationSec,
        topics = topics,
        attribution = attribution,
        description = description,
        imageUrl = imageUrl,
    )

    @Test
    fun composedEventRoundTripsThroughTheRailContract() {
        val note = compose(
            attribution = "sound of dj satsoshi",
            description = "The one sound every gaming meme needs",
            imageUrl = "https://blossom.example/b/cover.jpg",
        )!!
        assertEquals(30_078, note.kind)
        assertEquals(1_700_000_000L, note.createdAtSeconds)

        val sound = SharedSoundContract.parse(note.tags, note.content)!!
        assertEquals("raid-shadow-meme", sound.id)
        assertEquals("Raid Shadow Meme", sound.label)
        assertEquals(url, sound.url)
        assertEquals(sha, sound.sha256)
        assertEquals("CC-BY-4.0", sound.license)
        assertEquals("sound of dj satsoshi", sound.attribution)
        assertEquals(12_000L, sound.durationMs)
        assertEquals("audio/webm", sound.mime)
        assertEquals("The one sound every gaming meme needs", sound.description)
        assertEquals(listOf("meme", "gaming"), sound.topics)
        assertEquals("https://blossom.example/b/cover.jpg", sound.imageUrl)
    }

    @Test
    fun optionalFieldsOmitCleanly() {
        val note = compose(topics = emptyList())!!
        val sound = SharedSoundContract.parse(note.tags, note.content)!!
        assertEquals("", sound.attribution)
        assertTrue(sound.topics.isEmpty())
        assertEquals("", sound.imageUrl)
        assertEquals("", sound.description)
        // Only the mandatory d/url/x/license tags ride the event.
        assertEquals(4, note.tags.size)
        assertTrue(note.tags.all { it.first() in setOf("d", "url", "x", "license") })
    }

    @Test
    fun idIsStableForPinnedClock() {
        val a = compose()
        val b = compose()
        assertEquals(a!!.idHex, b!!.idHex)
    }

    @Test
    fun contractViolationsReturnNull() {
        assertNull(compose(license = "bitz/all-reserved")) // not ingestable
        assertNull(compose(license = "CC-BY-SA-4.0"))
        assertNull(compose(sha256Hex = "NOT-HEX"))
        assertNull(compose(sha256Hex = sha.take(63)))
        assertNull(compose(durationSec = 0))
        assertNull(compose(durationSec = 16))
        assertNull(compose(label = "   ")) // blank
        assertNull(compose(url = "http://insecure.example/b.webm")) // publish = https only
        assertNull(compose(soundId = "UPPER")) // slug only
        assertNull(compose(soundId = "")) // blank
        assertNull(compose(soundId = "a".repeat(49))) // > 48
        assertNull(
            NoteComposer(clock = { 1L }).composeSharedSound(
                authorPubkey = "zz", soundId = "ok", label = "L", url = url,
                sha256Hex = sha, license = "CC0-1.0", durationSec = 5,
            ),
        ) // junk pubkey
    }

    @Test
    fun boundsClampBeforeTheWire() {
        val note = compose(
            label = "L".repeat(60),
            topics = (1..15).map { "topic-$it" } + listOf("meme"),
        )!!
        val sound = SharedSoundContract.parse(note.tags, note.content)!!
        assertEquals(SharedSoundContract.MAX_LABEL, sound.label.length)
        assertEquals(SharedSoundContract.MAX_TOPICS, sound.topics.size) // take(10)
    }

    @Test
    fun uppercaseShaNormalizesBeforeSigning() {
        val note = compose(sha256Hex = sha.uppercase())!!
        val sound = SharedSoundContract.parse(note.tags, note.content)!!
        assertEquals(sha, sound.sha256) // x tag canonical lowercase
    }

    @Test
    fun publishMessageCarriesTheSignedKind30078Frame() {
        val composer = NoteComposer(clock = { 1_700_000_000L })
        val unsigned = compose()!!
        val frame = composer.publishMessage(unsigned, "ab".repeat(64)) ?: return kotlin.test.fail("no frame")
        assertTrue(frame.contains("\"kind\":30078"), frame)
        assertTrue(frame.contains("\"id\":\"${unsigned.idHex}\""), frame)
    }
}
