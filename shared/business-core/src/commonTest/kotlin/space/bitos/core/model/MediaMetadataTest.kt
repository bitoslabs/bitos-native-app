package space.bitos.core.model

import space.bitos.core.feed.FeedNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        assertEquals(4200L, media.durationSeconds)
    }

    @Test
    fun parsesAndBoundsImetaDuration() {
        // NIP-92 imeta `duration` (whole seconds) rides along with the video.
        fun videoWithDuration(duration: String): Long? =
            MediaMetadata.fromEvent(
                event(
                    NostrKinds.VIDEO,
                    listOf(listOf("imeta", "url https://x/v.mp4", "m video/mp4", "duration $duration")),
                ),
            )?.durationSeconds

        assertEquals(59L, videoWithDuration("59"))
        assertEquals(14_400L, videoWithDuration("14400"))
        // Web publishers (and this app since M4b) write `duration 4.861`
        // (NIP-71 fractional seconds) — parsed, rounded, still bounded.
        assertEquals(5L, videoWithDuration("4.861"))
        assertEquals(59L, videoWithDuration("58.500"))
        // Malformed / zero / negative / beyond the 4 h bound → unknown.
        assertNull(videoWithDuration("soon"))
        assertNull(videoWithDuration("0"))
        assertNull(videoWithDuration("-3"))
        assertNull(videoDurationUnknownLegacyTag())
        assertNull(videoWithDuration("14401"))
    }

    /** Positional url/m pairs carry no duration; the value stays unknown. */
    private fun videoDurationUnknownLegacyTag(): Long? =
        MediaMetadata.fromEvent(
            event(NostrKinds.VIDEO, listOf(listOf("url", "https://x/clip.mp4"))),
        )?.durationSeconds

    @Test
    fun formatsDurationLocaleFree() {
        assertEquals("0:00", MediaMetadata.formatDuration(0))
        assertEquals("0:59", MediaMetadata.formatDuration(59))
        assertEquals("1:00", MediaMetadata.formatDuration(60))
        assertEquals("12:05", MediaMetadata.formatDuration(725))
        assertEquals("1:02:03", MediaMetadata.formatDuration(3723))
        // Negative input clamps instead of printing a minus.
        assertEquals("0:00", MediaMetadata.formatDuration(-1))
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

    // MARK: - Poster hint parity with Flutter posterUrlFor (issue: bitz
    // cover thumbnails blank despite explicit publisher hints)

    @Test
    fun topLevelPreviewTagWinsOverDerivedPoster() {
        val media = MediaMetadata.fromEvent(
            event(
                kind = NostrKinds.VIDEO,
                tags = listOf(
                    listOf("preview", "https://cdn.example/hint.jpg"),
                    listOf("imeta", "url https://x/v.mp4", "m video/mp4"),
                    listOf("imeta", "url https://x/derived.jpg", "m image/jpeg"),
                ),
            ),
        )
        assertNotNull(media)
        assertEquals("https://cdn.example/hint.jpg", media.posterUrl)
    }

    @Test
    fun topLevelImageTagHintIsHonored() {
        val media = MediaMetadata.fromEvent(
            event(
                kind = NostrKinds.VIDEO,
                tags = listOf(
                    listOf("imeta", "url https://x/v.mp4", "m video/mp4"),
                    listOf("image", "https://cdn.example/cover.webp"),
                ),
            ),
        )
        assertNotNull(media)
        assertEquals("https://cdn.example/cover.webp", media.posterUrl)
    }

    @Test
    fun imetaPreviewFieldHintBeatsImageBlockUrl() {
        val media = MediaMetadata.fromEvent(
            event(
                kind = NostrKinds.VIDEO,
                tags = listOf(
                    listOf("imeta", "url https://x/v.mp4", "m video/mp4", "preview https://x/hint.png"),
                    listOf("imeta", "url https://x/derived.jpg", "m image/jpeg"),
                ),
            ),
        )
        assertNotNull(media)
        assertEquals("https://x/hint.png", media.posterUrl)
    }

    @Test
    fun imetaImageFieldHintIsHonored() {
        val media = MediaMetadata.fromEvent(
            event(
                kind = NostrKinds.VIDEO,
                tags = listOf(
                    listOf("imeta", "url https://x/v.mp4", "m video/mp4", "image https://x/hint.jpg"),
                ),
            ),
        )
        assertNotNull(media)
        assertEquals("https://x/hint.jpg", media.posterUrl)
    }

    @Test
    fun imetaThumbFieldIsTheStandardCoverHint() {
        // Real bitz publish shape (web BitzComposer parity): a single video
        // imeta block whose `thumb` is the only cover — no separate image
        // attachment exists, so without parsing it the grid has no poster.
        val media = MediaMetadata.fromEvent(
            event(
                kind = NostrKinds.VIDEO,
                tags = listOf(
                    listOf(
                        "imeta",
                        "url https://blossom.example/20b0.mp4",
                        "m video/mp4",
                        "x 20b0c984eabaed394aada0bc6b2b0514d8b2f19476a87412e28b427ba943b4c7",
                        "size 1734198",
                        "dim 720x1280",
                        "duration 3",
                        "thumb https://blossom.example/7fba.jpg",
                    ),
                ),
            ),
        )
        assertNotNull(media)
        assertEquals("https://blossom.example/20b0.mp4", media.url)
        assertEquals("https://blossom.example/7fba.jpg", media.posterUrl)
        assertEquals(720, media.width)
        assertEquals(1280, media.height)
        assertEquals(3L, media.durationSeconds)
    }

    @Test
    fun imetaThumbBeatsPreviewHintAndDerivedPoster() {
        // The standard `thumb` field outranks the non-standard preview hint
        // and any poster derived from a separate image attachment.
        val media = MediaMetadata.fromEvent(
            event(
                kind = NostrKinds.VIDEO,
                tags = listOf(
                    listOf("imeta", "url https://x/v.mp4", "m video/mp4", "preview https://x/hint.png", "thumb https://x/cover.jpg"),
                    listOf("imeta", "url https://x/derived.jpg", "m image/jpeg"),
                ),
            ),
        )
        assertNotNull(media)
        assertEquals("https://x/cover.jpg", media.posterUrl)
    }

    @Test
    fun hostileThumbHintFallsBackToDerivedPoster() {
        // Non-http thumb is dropped; the derived poster still resolves.
        val media = MediaMetadata.fromEvent(
            event(
                kind = NostrKinds.VIDEO,
                tags = listOf(
                    listOf("imeta", "url https://x/v.mp4", "m video/mp4", "thumb javascript:alert(1)"),
                    listOf("imeta", "url https://x/derived.jpg", "m image/jpeg"),
                ),
            ),
        )
        assertNotNull(media)
        assertEquals("https://x/derived.jpg", media.posterUrl)
    }

    @Test
    fun hintOverSizeOrNonHttpIsIgnored() {
        // Non-http hint falls back to the derived poster.
        val nonHttp = MediaMetadata.fromEvent(
            event(
                kind = NostrKinds.VIDEO,
                tags = listOf(
                    listOf("preview", "javascript:alert(1)"),
                    listOf("imeta", "url https://x/v.mp4", "m video/mp4"),
                    listOf("imeta", "url https://x/derived.jpg", "m image/jpeg"),
                ),
            ),
        )
        assertNotNull(nonHttp)
        assertEquals("https://x/derived.jpg", nonHttp.posterUrl)
        // Oversized hint (> 2048 chars) is dropped entirely.
        val overSize = MediaMetadata.fromEvent(
            event(
                kind = NostrKinds.VIDEO,
                tags = listOf(
                    listOf("preview", "https://x.example/" + "a".repeat(2100)),
                    listOf("imeta", "url https://x/v.mp4", "m video/mp4"),
                ),
            ),
        )
        assertNotNull(overSize)
        assertNull(overSize.posterUrl)
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

    // MARK: - Rendition ladder + mirrors (FED-004)

    @Test
    fun parsesFallbackMirrorsAndRenditionLadder() {
        val media = MediaMetadata.fromEvent(
            event(
                kind = NostrKinds.VIDEO,
                tags = listOf(
                    listOf("imeta", "url https://cdn.example/v.mp4", "m video/mp4", "dim 1080x1920"),
                    listOf("imeta", "fallback https://mirror.example/v.mp4"),
                    listOf("imeta", "fallback https://cdn.example/v.mp4"), // dup of primary → kept as mirror, dropped from ladder
                    listOf("imeta", "fallbackrendition variant https://cdn.example/v-720.mp4 720x1280 2500000"),
                    // Same-URL lower rung — a mirror of the same rendition,
                    // not a separate ladder entry.
                    listOf("imeta", "fallbackrendition variant https://cdn.example/v-720.mp4 480x854 1200000"),
                ),
            ),
        )
        assertNotNull(media)
        assertEquals("https://cdn.example/v.mp4", media.url)
        // Mirrors: order preserved, bounded, primary URL may appear once.
        assertEquals(
            listOf("https://mirror.example/v.mp4", "https://cdn.example/v.mp4"),
            media.fallbackUrls,
        )
        // Ladder tall→short, same-URL variants excluded (mirror, not rendition):
        // the second block reuses the v-720 URL at a lower rung and must
        // collapse into the taller one instead of adding a ladder entry.
        assertEquals(1, media.renditions.size)
        assertEquals("https://cdn.example/v-720.mp4", media.renditions.first().url)
        assertEquals(720, media.renditions.first().height)
        assertEquals(2_500_000L, media.renditions.first().bitrate)
    }

    @Test
    fun selectRenditionPicksTallestFittingWithHeadroom() {
        fun media(vararg heights: Int): MediaMetadata = MediaMetadata(
            url = "https://x/primary.mp4",
            mimeType = "video/mp4",
            posterUrl = null,
            width = null,
            height = null,
            durationSeconds = null,
            fallbackUrls = emptyList(),
            renditions = heights.map { MediaRendition("https://x/$it.mp4", it, 0L) },
        )

        // 1080 × 1.25 = 1350 cap → 1080 (tallest fitting).
        assertEquals("https://x/1080.mp4", media(2160, 1080, 720).selectRendition(1080))
        // Everything overshoots → smallest available (client downscales).
        assertEquals("https://x/1440.mp4", media(2160, 1440).selectRendition(1080))
        // DPR headroom: 1350 fits under a 1080 × 1.25 cap.
        assertEquals("https://x/1350.mp4", media(1350, 1080).selectRendition(1080))
        // No ladder → primary URL, untouched.
        assertEquals("https://x/primary.mp4", media().selectRendition(1080))
    }

    @Test
    fun highQualityAlwaysPicksTheTallestRung() {
        fun media(vararg heights: Int): MediaMetadata = MediaMetadata(
            url = "https://x/primary.mp4",
            mimeType = "video/mp4",
            posterUrl = null,
            width = null,
            height = null,
            durationSeconds = null,
            fallbackUrls = emptyList(),
            renditions = heights.map { MediaRendition("https://x/$it.mp4", it, 0L) },
        )

        // Tallest wins even when it grossly overshoots the display.
        assertEquals("https://x/2160.mp4", media(2160, 1080, 720).selectTallestRendition())
        // Height ties resolve deterministically (first of the tall→short ladder).
        assertEquals("https://x/1080.mp4", media(1080, 1080, 720).selectTallestRendition())
        // No ladder → primary.
        assertEquals("https://x/primary.mp4", media().selectTallestRendition())
    }

    @Test
    fun dataSaverPicksShortestWatchableRung() {
        fun media(vararg heights: Int): MediaMetadata = MediaMetadata(
            url = "https://x/primary.mp4",
            mimeType = "video/mp4",
            posterUrl = null,
            width = null,
            height = null,
            durationSeconds = null,
            fallbackUrls = emptyList(),
            renditions = heights.map { MediaRendition("https://x/$it.mp4", it, 0L) },
        )

        // Shortest rung at/above the 360p floor (UX U9 / APP-018 `low`).
        assertEquals("https://x/480.mp4", media(2160, 1080, 480).selectDataSaverRendition())
        // Exactly at the floor counts.
        assertEquals("https://x/360.mp4", media(2160, 360, 240).selectDataSaverRendition())
        // Every rung below the floor → shortest available anyway.
        assertEquals("https://x/144.mp4", media(240, 144).selectDataSaverRendition())
        // No ladder → primary.
        assertEquals("https://x/primary.mp4", media().selectDataSaverRendition())
        // The floor is the documented constant.
        assertEquals(360, MediaMetadata.DATA_SAVER_MIN_HEIGHT)
    }

    @Test
    fun hostileRenditionFieldsAreDropped() {
        // Bad scheme, missing dim, absurd bitrate → nothing enters the ladder.
        val media = MediaMetadata.fromEvent(
            event(
                kind = NostrKinds.VIDEO,
                tags = listOf(
                    listOf("imeta", "url https://x/v.mp4", "m video/mp4"),
                    listOf("imeta", "fallbackrendition variant javascript:alert(1) 720x1280"),
                    listOf("imeta", "fallbackrendition variant https://x/nodim.mp4"),
                    listOf("imeta", "fallbackrendition variant https://x/ok.mp4 480x854 9999999999"),
                    listOf("fallback", "ftp://not-a-mirror.mp4"),
                ),
            ),
        )
        assertNotNull(media)
        assertTrue(media.renditions.isEmpty())
        assertTrue(media.fallbackUrls.isEmpty())
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
