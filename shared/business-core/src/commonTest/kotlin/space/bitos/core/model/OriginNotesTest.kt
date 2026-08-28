package space.bitos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OriginNotesTest {

    private fun event(content: String, kind: Int = 1, createdAt: Long = 1_000) = NostrEvent(
        id = EventId.parse("11".repeat(32))!!,
        pubkey = Pubkey.parse("e93fbf1000405bc8bb536a8ae37eebe349ebde8ecae3779ad3786def739aa301")!!,
        createdAt = createdAt,
        kind = kind,
        tags = emptyList(),
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
}
