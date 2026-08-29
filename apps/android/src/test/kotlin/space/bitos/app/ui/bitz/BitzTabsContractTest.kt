package space.bitos.app.ui.bitz

import org.junit.Test
import space.bitos.core.feed.BitzSort
import space.bitos.core.model.MediaMetadata
import space.bitos.core.model.MediaRendition
import space.bitos.core.settings.BitzModeSetting
import kotlin.test.assertEquals

/**
 * Bitz tab + rendition adapter contract (APP-007 W2 / FED-004).
 *
 * Locks the seams the surfaces rely on: the persisted bitz-mode wires
 * (SettingsContract ↔ shared BitzModeSetting, schema v5), the shared tab
 * sort ordering feeding the Trending / Most-zapped pills, and the
 * rendition pick the player chain leads with.
 */
class BitzTabsContractTest {

    @Test
    fun bitzModeWiresCoverAllFiveTabs() {
        // Wire strings are the persisted contract; both platforms and the
        // shared settings normalizer must agree on exactly these.
        val wires = BitzModeSetting.entries.associate { it.wire to it }
        assertEquals(5, wires.size)
        assertEquals("for_you", BitzModeSetting.FOR_YOU.wire)
        assertEquals("following", BitzModeSetting.FOLLOWING.wire)
        assertEquals("explore", BitzModeSetting.EXPLORE.wire)
        assertEquals("trending", BitzModeSetting.TRENDING.wire)
        assertEquals("zapped", BitzModeSetting.ZAPPED.wire)
    }

    @Test
    fun trendingDecayRanksFreshEngagementAboveStale() {
        // Stale is 3 half-lives old (216 h, decay 0.125 → 600×.125 = 75);
        // fresh is 1 h old (decay ≈ 0.99 → 100×.99 ≈ 99) and wins despite
        // six times fewer reactions.
        val now = 1_000_000L
        val ordered = BitzSort.trending(
            listOf(
                BitzSort.Entry(id = "stale", createdAt = now - 216L * 3_600L, reactions = 600),
                BitzSort.Entry(id = "fresh", createdAt = now - 1L * 3_600L, reactions = 100),
            ),
            nowSeconds = now,
        )
        assertEquals(listOf("fresh", "stale"), ordered)
    }

    @Test
    fun zappedRanksSatsThenNewest() {
        val ordered = BitzSort.zapped(
            listOf(
                BitzSort.Entry(id = "small", createdAt = 5, zapCount = 1, zapSats = 100),
                BitzSort.Entry(id = "big", createdAt = 2, zapCount = 9, zapSats = 9_000),
            ),
        )
        assertEquals(listOf("big", "small"), ordered)
    }

    @Test
    fun renditionPickMatchesPlayerChainHead() {
        val media = MediaMetadata(
            url = "https://x/primary.mp4",
            mimeType = "video/mp4",
            posterUrl = null,
            width = null,
            height = null,
            durationSeconds = null,
            fallbackUrls = listOf("https://mirror.example/v.mp4"),
            renditions = listOf(
                MediaRendition("https://x/2160.mp4", 2160, 8_000_000L),
                MediaRendition("https://x/1080.mp4", 1080, 4_000_000L),
                MediaRendition("https://x/720.mp4", 720, 2_500_000L),
            ),
        )
        // Screen-height bucket parity with VideoPlayerPool.TARGET_HEIGHT
        // semantics: tallest fitting under ×1.25 headroom; overshoot →
        // smallest; no ladder → primary.
        assertEquals("https://x/1080.mp4", media.selectRendition(1080))
        assertEquals("https://x/720.mp4", media.selectRendition(480))
        assertEquals(
            "https://x/primary.mp4",
            MediaMetadata("https://x/primary.mp4", "video/mp4", null, null, null, null).selectRendition(1080),
        )
    }
}
