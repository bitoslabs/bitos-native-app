package space.bitos.core.model

import space.bitos.core.feed.FeedNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Kind-22 / NIP-92 `imeta` / legacy kind-1 media parsing with hostile-input
 * bounds (FED foundation, PRO-003 subset).
 */
class MediaMetadataTest {

    private fun event(kind: Int, tags: List<List<String>>, content: String = "caption #bitcoin") = NostrEvent(
        id = EventId.parse("11".repeat(32))!!,
        pubkey = Pubkey.parse("22".repeat(32))!!,
        createdAt = 1_710_000_000,
        kind = kind,
        tags = tags,
        content = content,
        signature = null,
        receivedFromRelay = null,
    )

    @Test
    fun parsesKind22ImetaVideoWithPoster() {
        val media = MediaMetadata.fromEvent(
            event(
                kind = NostrKinds.VIDEO,
                tags = listOf(
                    listOf("imeta", "url https://cdn.example/v.mp4", "m video/mp4", "dim 1080x1920", "duration 4200"),
                    listOf("imeta", "url https://cdn.example/poster.jpg", "m image/jpeg", "dim 1080x1920"),
                    listOf("alt", "a bitcoin video"),
                ),
            ),
        )
        assertNotNull(media)
        assertEquals("https://cdn.example/v.mp4", media.url)
        assertEquals("video/mp4", media.mimeType)
        assertEquals("https://cdn.example/poster.jpg", media.posterUrl)
        assertEquals(1080, media.width)
        assertEquals(1920, media.height)
    }

    @Test
    fun parsesDirectUrlAndMTagStyle() {
        val media = MediaMetadata.fromEvent(
            event(
                kind = NostrKinds.VIDEO,
                tags = listOf(
                    listOf("url", "https://mirror.example/clip.webm"),
                    listOf("m", "video/webm"),
                    listOf("url", "https://mirror.example/thumb.png"),
                    listOf("m", "image/png"),
                ),
            ),
        )
        assertNotNull(media)
        assertEquals("https://mirror.example/clip.webm", media.url)
        assertEquals("video/webm", media.mimeType)
        assertEquals("https://mirror.example/thumb.png", media.posterUrl)
    }

    @Test
    fun parsesLegacyKind1VideoUrlInContent() {
        val media = MediaMetadata.fromEvent(
            event(
                kind = NostrKinds.SHORT_TEXT_NOTE,
                tags = emptyList(),
                content = "watch this https://files.example/intro.mp4 now",
            ),
        )
        assertNotNull(media)
        assertEquals("https://files.example/intro.mp4", media.url)
        assertNull(media.mimeType)
        assertNull(media.posterUrl)
    }

    @Test
    fun rejectsHostileMetadata() {
        // Non-http scheme.
        assertNull(
            MediaMetadata.fromEvent(
                event(NostrKinds.VIDEO, listOf(listOf("imeta", "url javascript:alert(1)", "m video/mp4"))),
            ),
        )
        // Oversized URL is dropped.
        assertNull(
            MediaMetadata.fromEvent(
                event(NostrKinds.VIDEO, listOf(listOf("imeta", "url https://x.example/" + "a".repeat(2100)))),
            ),
        )
        // Garbage dim is ignored; the video still parses.
        val media = MediaMetadata.fromEvent(
            event(
                NostrKinds.VIDEO,
                listOf(listOf("imeta", "url https://x/v.mp4", "m video/mp4", "dim not-a-dim")),
            ),
        )
        assertNotNull(media)
        assertNull(media.width)
        // Absurd dimensions are rejected.
        assertNull(
            MediaMetadata.fromEvent(
                event(NostrKinds.VIDEO, listOf(listOf("imeta", "url https://x/v.mp4", "dim 99999999x1"))),
            )?.width,
        )
    }

    @Test
    fun imageOnlyEventsYieldNoVideo() {
        assertNull(
            MediaMetadata.fromEvent(
                event(NostrKinds.VIDEO, listOf(listOf("imeta", "url https://x/pic.jpg", "m image/jpeg"))),
            ),
        )
    }

    @Test
    fun feedNoteCarriesVideo() {
        val note = FeedNote.from(
            event(
                kind = NostrKinds.VIDEO,
                tags = listOf(listOf("imeta", "url https://cdn.example/v.mp4", "m video/mp4")),
            ),
        )
        assertEquals("https://cdn.example/v.mp4", note.video?.url)
    }
}
