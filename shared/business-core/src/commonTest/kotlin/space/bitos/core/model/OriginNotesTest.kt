package space.bitos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OriginNotesTest {

    private fun event(content: String, kind: Int = 1, createdAt: Long = 1_000, tags: List<List<String>> = emptyList()) = NostrEvent(
        id = EventId.parse("11".repeat(32))!!,
        pubkey = Pubkey.parse("e93fbf1000405bc8bb536a8ae37eebe349ebde8ecae3779ad3786def739aa301")!!,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        signature = null,
        receivedFromRelay = null,
    )

    @Test
    fun excerptStripsLinksKeepsTextAndHashtags() {
        val npub = "npub194667yy2sqh4h4vlwssg7g5smhmqx4x9hgtfdjun8e46l30kxqqselzc9y"
        val note = OriginNotes.project(
            event("gm nostr:$npub check https://example.com/pic.png #bitcoin and http://vid.example/x.mp4 end"),
        )
        assertEquals("gm check #bitcoin and end", note.excerpt)
        assertEquals("https://example.com/pic.png", note.thumbUrl) // first URL wins
    }

    @Test
    fun excerptCollapsesWhitespaceAndIsBounded() {
        val long = ("word " ).repeat(80)
        val note = OriginNotes.project(event("  $long\n\t tail  "))
        assertEquals(OriginNotes.EXCERPT_MAX, note.excerpt.length)
        assertEquals("…", note.excerpt.last().toString())
    }

    @Test
    fun mediaOnlyContentYieldsEmptyExcerptWithThumb() {
        val note = OriginNotes.project(event("https://cdn.example/v.mp4"))
        assertEquals("", note.excerpt)
        assertEquals("https://cdn.example/v.mp4", note.thumbUrl)
        assertEquals(1, note.kind)
        assertNull(OriginNotes.project(event("plain text only")).thumbUrl)
    }

    @Test
    fun fullContentIsPreservedForThreadRoots() {
        val content = "full note body with https://cdn.example/i.png and #tag kept verbatim"
        assertEquals(content, OriginNotes.project(event(content)).content)
    }

    /** APP-012 media strip: ≤4 distinct media URLs, same deterministic rule
     * as the feed card (image matches first, then video matches — FeedNote
     * parity — distinct, strip-bounded). */
    @Test
    fun mediaStripCollectsUpToFourDistinctMediaUrls() {
        val note = OriginNotes.project(
            event(
                "pics https://a.example/1.png https://a.example/1.png " +
                    "https://a.example/2.jpg?w=8 https://a.example/3.webm " +
                    "https://a.example/4.gif https://a.example/5.mov " +
                    "https://a.example/page not-media",
            ),
        )
        assertEquals(
            listOf(
                "https://a.example/1.png",
                "https://a.example/2.jpg?w=8",
                "https://a.example/4.gif",
                "https://a.example/3.webm",
            ),
            note.mediaUrls,
        )
    }

    /** APP-012 sensitive cover: NIP-36 tag + label forms both flag. */
    @Test
    fun contentWarningFollowsNip36TagAndLabelForms() {
        assertTrue(OriginNotes.project(event("nsfw", tags = listOf(listOf("content-warning", "why")))).contentWarning)
        assertTrue(OriginNotes.project(event("nsfw", tags = listOf(listOf("L", "content warning")))).contentWarning)
        assertTrue(OriginNotes.project(event("nsfw", tags = listOf(listOf("l", "content warning")))).contentWarning)
        assertFalse(OriginNotes.project(event("safe")).contentWarning)
        assertFalse(OriginNotes.project(event("safe", tags = listOf(listOf("L", "other label")))).contentWarning)
    }
}
