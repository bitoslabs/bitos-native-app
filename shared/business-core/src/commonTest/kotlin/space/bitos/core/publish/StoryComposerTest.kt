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

    @Test
    fun powStoryDerivesExpirationFromTheMiningTimestampAndAppendsNonceLast() {
        val minedAt = now + 45 // mine, then publish later — the template is fixed
        val note = NoteComposer(clock = { now }).composeStoryWithPow(
            author, "gm #nostr", listOf("https://cdn.example/a.png"),
            null, "pic", false, dTag, 4242L, 16, minedAt,
        )!!
        assertEquals(minedAt, note.createdAtSeconds)
        // Expiration rides the mining timestamp, never the publish clock.
        assertEquals(minedAt + Stories.STORY_TTL_SECONDS, tag(note, "expiration").first()[1].toLong())
        // The nonce tag is APPENDED (mineChunk commits it last) with its target.
        assertEquals(
            listOf("nonce", "4242", "16"),
            note.tags.last(),
        )
        // The committed id covers the appended nonce tag.
        assertEquals(
            space.bitos.core.nostr.NostrEventCodec.computeId(
                space.bitos.core.nostr.Sha256EventHasher, author, minedAt,
                Stories.STORY_KIND, note.tags, note.content,
            ),
            note.idHex,
        )
        // Non-pow composition stays clock-driven and nonce-free.
        val plain = clock.composeStory(author, "gm #nostr", listOf("https://cdn.example/a.png"), null, "pic", false, dTag)!!
        assertTrue(plain.tags.none { it.firstOrNull() == "nonce" })
        assertEquals(now + Stories.STORY_TTL_SECONDS, tag(plain, "expiration").first()[1].toLong())
    }

    @Test
    fun powStoryMinedIdIsReproducedOnPublish() {
        // The story pow publish contract: mining hashes the kind-30315
        // template (mineChunk appends the nonce tag); composing with the
        // mined nonce must reproduce that exact id at the target difficulty.
        val minedAt = now
        val base = NoteComposer(clock = { minedAt }).composeStory(
            author, "mined story", emptyList(),
            "linear-gradient(135deg, #2f95f6, #55d69a)", null, false, dTag,
        )!!
        val target = 10
        val mined = space.bitos.core.nostr.Pow.mineChunk(
            space.bitos.core.nostr.Sha256EventHasher,
            base.pubkeyHex, base.createdAtSeconds, base.kind, base.tags, base.content,
            target, 0, 500_000,
        )!!
        val published = NoteComposer(clock = { now }).composeStoryWithPow(
            author, "mined story", emptyList(),
            "linear-gradient(135deg, #2f95f6, #55d69a)", null, false, dTag,
            mined.nonce, target, minedAt,
        )!!
        assertEquals(mined.idHex, published.idHex)
        assertTrue(space.bitos.core.nostr.Pow.difficulty(published.idHex) >= target)
    }

    @Test
    fun videoStoryPublishesVideoImetaAndParsesBackThroughTheViewer() {
        val note = clock.composeStory(
            author, "watch this", emptyList(), null, null, false, dTag,
            videoUrl = "https://cdn.example/clip.mp4",
            videoMime = "video/mp4",
            videoDurationMs = 12_500,
            videoPoster = "https://cdn.example/clip.jpg",
        )!!
        // Video imeta: url + m + thumb + duration (integer-seconds form,
        // never locale-formatted).
        assertEquals(
            listOf(
                "url https://cdn.example/clip.mp4", "m video/mp4",
                "thumb https://cdn.example/clip.jpg", "duration 12.5s",
            ),
            tag(note, "imeta").single().drop(1),
        )
        // The URL mirrors into the content for link-only clients…
        assertEquals("watch this\nhttps://cdn.example/clip.mp4", note.content)
        // …and parses back as the slide's video with poster + duration.
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
        assertEquals("https://cdn.example/clip.mp4", slide.videoUrl)
        assertEquals("https://cdn.example/clip.jpg", slide.videoPoster)
        assertEquals(12_500L, slide.videoDurationMs)
        assertTrue(slide.imageUrls.isEmpty())
    }

    @Test
    fun videoAndImagesCoexistWithoutPollutingTheCarousel() {
        val note = clock.composeStory(
            author, "clip + stills",
            listOf("https://cdn.example/a.png"),
            null, "cover", true, dTag,
            videoUrl = "https://cdn.example/clip.mov",
            videoMime = "video/quicktime",
        )!!
        // Image imeta keeps alt; the video imeta rides after with its mime;
        // extractImageUrls skips the video imeta on parse.
        assertEquals(
            listOf("url https://cdn.example/a.png", "alt cover"),
            tag(note, "imeta")[0].drop(1),
        )
        assertEquals(
            listOf("url https://cdn.example/clip.mov", "m video/quicktime"),
            tag(note, "imeta")[1].drop(1),
        )
        // Sensitive covers video slides too.
        assertEquals(1, tag(note, "content-warning").size)
    }

    @Test
    fun videoStoryBoundsHostileInputAndDefaultsTheMime() {
        // Non-https and oversized URLs drop; a missing/invalid mime falls
        // back to video/mp4; an unknown duration omits the segment; a
        // non-https poster drops its thumb segment.
        val note = clock.composeStory(
            author, "", emptyList(), null, null, false, dTag,
            videoUrl = "http://insecure.example/clip.mp4",
        )
        assertNull(note)
        val fallback = clock.composeStory(
            author, "", emptyList(), null, null, false, dTag,
            videoUrl = "https://cdn.example/clip",
            videoMime = "text/html",
            videoPoster = "http://insecure.example/clip.jpg",
        )!!
        assertEquals(
            listOf("url https://cdn.example/clip", "m video/mp4"),
            tag(fallback, "imeta").single().drop(1),
        )
    }
}
