package space.bitos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * APP-006 Stories: slide parsing (TTL, expiration tag, future-dated
 * guard, image/gradient extraction), insertion rules (same-id,
 * parameterized-replaceable, per-author bound), and author grouping.
 */
class StoriesTest {

    private val now = 1_700_000_000L
    private val author = "aa".repeat(32)

    private fun storyEvent(
        id: String = "11".repeat(32),
        pubkey: String = author,
        createdAt: Long = now,
        content: String = "My story",
        tags: List<List<String>> = emptyList(),
        kind: Int = Stories.STORY_KIND,
    ) = NostrEvent(
        id = EventId.parse(id)!!,
        pubkey = Pubkey.parse(pubkey)!!,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        signature = null,
        receivedFromRelay = null,
    )

    @Test
    fun parsesSlideWithDefaultTtl() {
        val slide = Stories.parseSlide(storyEvent(), now)!!
        assertEquals(now + Stories.STORY_TTL_SECONDS, slide.expiresAt)
        assertEquals("My story", slide.content)
        assertNull(slide.imageUrl)
        assertNull(slide.gradient)
    }

    @Test
    fun parsesExpirationTagImageAndGradient() {
        val event = storyEvent(
            content = "#ff6600>to>#3300cc check https://cdn.example/pic.png end",
            tags = listOf(listOf("expiration", "${now + 3_600}")),
        )
        val slide = Stories.parseSlide(event, now)!!
        assertEquals(now + 3_600, slide.expiresAt)
        assertEquals("https://cdn.example/pic.png", slide.imageUrl)
        assertEquals("#ff6600>to>#3300cc", slide.gradient)
        // Image URL stripped from the caption.
        assertTrue(!slide.content.contains("cdn.example"))
        assertTrue(slide.content.contains("check"))
    }

    @Test
    fun rejectsExpiredFutureDatedAndNonStoryKinds() {
        // Expired.
        assertNull(Stories.parseSlide(
            storyEvent(tags = listOf(listOf("expiration", "${now - 1}"))), now,
        ))
        // Future-dated beyond the skew guard.
        assertNull(Stories.parseSlide(storyEvent(createdAt = now + 601), now))
        // Within skew: OK.
        assertNotNull(Stories.parseSlide(storyEvent(createdAt = now + 300), now))
        // Non-story kind.
        assertNull(Stories.parseSlide(storyEvent(kind = 1), now))
    }

    @Test
    fun sameIdNewerReplaces() {
        val old = Stories.parseSlide(storyEvent(createdAt = now), now)!!
        val newer = Stories.parseSlide(storyEvent(createdAt = now + 100), now)!!
        val result = Stories.insert(listOf(old), newer)
        assertEquals(1, result.size)
        assertEquals(newer.createdAt, result[0].createdAt)
    }

    @Test
    fun parameterizedReplaceableSameDReplaces() {
        val old = Stories.parseSlide(
            storyEvent(id = "22".repeat(32), createdAt = now, tags = listOf(listOf("d", "slide-1"))),
            now,
        )!!
        val newer = Stories.parseSlide(
            storyEvent(id = "33".repeat(32), createdAt = now + 200, tags = listOf(listOf("d", "slide-1"))),
            now,
        )!!
        val result = Stories.insert(listOf(old), newer)
        assertEquals(1, result.size)
        assertEquals(newer.id, result[0].id)
    }

    @Test
    fun perAuthorBoundCapsAtTwelve() {
        val slides = (0 until 15).map { index ->
            Stories.parseSlide(storyEvent(id = index.toString(16).padStart(64, '0'), createdAt = now + index.toLong()), now)!!
        }
        var acc = emptyList<StorySlide>()
        slides.forEach { acc = Stories.insert(acc, it) }
        assertEquals(Stories.MAX_SLIDES_PER_AUTHOR, acc.filter { it.pubkey == author }.size)
    }

    @Test
    fun authorsGroupByPubkeyNewestFirst() {
        val other = "bb".repeat(32)
        val s1 = Stories.parseSlide(storyEvent(pubkey = author, createdAt = now), now)!!
        val s2 = Stories.parseSlide(storyEvent(id = "44".repeat(32), pubkey = author, createdAt = now + 100), now)!!
        val s3 = Stories.parseSlide(storyEvent(id = "55".repeat(32), pubkey = other, createdAt = now + 500), now)!!
        val authors = Stories.authors(listOf(s1, s2, s3), now)
        assertEquals(2, authors.size)
        // Author with the latest slide first.
        assertEquals(other, authors[0].pubkey)
        assertEquals(author, authors[1].pubkey)
        // Slides newest-first within each author.
        assertEquals(listOf(s2.createdAt, s1.createdAt), authors[1].slides.map { it.createdAt })
    }
}
