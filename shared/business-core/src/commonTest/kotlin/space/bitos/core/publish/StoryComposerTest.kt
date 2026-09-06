package space.bitos.core.publish

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import space.bitos.core.model.Stories

/**
 * APP-006 story composer (web `stories.publish` wire parity): tag order,
 * imeta carousel, alt-on-first, background/content-warning gating, the
 * image-mirrored content body and the round trip through `Stories.parseSlide`.
 */
class StoryComposerTest {

    private val now = 1_700_000_000L
    private val author = "aa".repeat(32)
    private val clock = NoteComposer(clock = { now })
    private val dTag = Stories.storyDTag(now, 0xABCDEF)

    private fun tag(note: UnsignedNote, name: String): List<List<String>> =
        note.tags.filter { it.firstOrNull() == name }

    @Test
    fun storyDTagCarriesTimestampAndNonce() {
        val d = Stories.storyDTag(now, 0xABCDEF)
        assertTrue(d.startsWith("bitos-story-$now-"))
        assertTrue(d.length > "bitos-story-$now-".length)
        // Nonces never collide within the same second.
        assertTrue(d != Stories.storyDTag(now, 0xABCDF0))
    }

    @Test
    fun textOnlyStoryCarriesBackgroundAndExpiration() {
        val note = clock.composeStory(
            author, "hello #nostr", emptyList(),
            "linear-gradient(135deg, #2f95f6, #55d69a)", null, false, dTag,
        )!!
        assertEquals(Stories.STORY_KIND, note.kind)
        assertEquals(listOf(listOf("d", dTag)), tag(note, "d"))
        assertEquals(now + Stories.STORY_TTL_SECONDS, tag(note, "expiration").first()[1].toLong())
        assertEquals(listOf("t", "nostr"), tag(note, "t").first())
        assertEquals("linear-gradient(135deg, #2f95f6, #55d69a)", tag(note, "background").first()[1])
        assertTrue(tag(note, "imeta").isEmpty())
        assertTrue(tag(note, "content-warning").isEmpty())
        assertEquals("hello #nostr", note.content)
    }

    @Test
    fun imageCarouselMirrorsUrlsAndKeepsAltOnFirstOnly() {
        val note = clock.composeStory(
            author, "caption", listOf("https://cdn.example/a.png", "https://cdn.example/b.gif"),
            null, "two pictures", true, dTag,
        )!!
        val imetas = tag(note, "imeta")
        assertEquals(2, imetas.size)
        assertEquals(listOf("url https://cdn.example/a.png", "alt two pictures"), imetas[0].drop(1))
        assertEquals(listOf("url https://cdn.example/b.gif"), imetas[1].drop(1))
        assertEquals(listOf("Sensitive media"), tag(note, "content-warning").map { it[1] })
        assertTrue(tag(note, "background").isEmpty())
        // URLs mirror into the content for link-only clients.
        assertEquals("caption\nhttps://cdn.example/a.png\nhttps://cdn.example/b.gif", note.content)
    }

    @Test
    fun rejectsEmptyStoriesAndBoundsHostileInput() {
        assertNull(clock.composeStory(author, "  ", emptyList(), null, null, false, dTag))
        assertNull(clock.composeStory("nothex", "hi", emptyList(), null, null, false, dTag))
        assertNull(clock.composeStory(author, "hi", emptyList(), null, null, false, ""))
        // Over-cap images drop; sensitive sticks while images survive the cap.
        val capped = clock.composeStory(
            author, "", (0..7).map { "https://cdn.example/$it.png" }, null, null, true, dTag,
        )
        assertNotNull(capped)
        assertEquals(Stories.MAX_STORY_IMAGES, tag(capped, "imeta").size)
        assertEquals(1, tag(capped, "content-warning").size)
    }

    @Test
    fun publishedStoryParsesBackThroughTheViewer() {
        val note = clock.composeStory(
            author, "vacation https://cdn.example/a.png", listOf("https://cdn.example/a.png"),
            null, null, false, dTag,
        )!!
        val event = space.bitos.core.model.NostrEvent(
            id = space.bitos.core.model.EventId.parse("11".repeat(32))!!,
            pubkey = space.bitos.core.model.Pubkey.parse(author)!!,
            createdAt = now,
            kind = note.kind,
            tags = note.tags,
            content = note.content,
            signature = null,
            receivedFromRelay = null,
        )
        val slide = Stories.parseSlide(event, now)!!
        assertEquals("vacation", slide.content)
        assertEquals(listOf("https://cdn.example/a.png"), slide.imageUrls)
        assertEquals(dTag, slide.d)
        assertEquals(now + Stories.STORY_TTL_SECONDS, slide.expiresAt)
    }
}
