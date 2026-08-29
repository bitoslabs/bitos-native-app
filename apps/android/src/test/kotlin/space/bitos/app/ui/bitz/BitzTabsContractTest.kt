package space.bitos.app.ui.bitz

import org.junit.Test
import space.bitos.core.feed.BitzTimelinePolicy
import space.bitos.core.model.MediaMetadata
import space.bitos.core.model.MediaRendition
import space.bitos.core.settings.BitzModeSetting
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bitz tab + pagination + rendition adapter contract (APP-007 / FED-004).
 *
 * Locks the seams the surfaces rely on: the persisted bitz-mode wires
 * (SettingsContract ↔ shared BitzModeSetting — THREE tabs, legacy
 * Flutter parity), the walk policy the load-more loop executes (pages
 * that only re-send known ids must not strand the surface), and the
 * rendition pick the player chain leads with.
 */
class BitzTabsContractTest {

    @Test
    fun bitzModeWiresCoverExactlyTheThreeLegacyTabs() {
        // Wire strings are the persisted contract; both platforms and the
        // shared settings normalizer must agree on exactly these.
        val wires = BitzModeSetting.entries.associate { it.wire to it }
        assertEquals(3, wires.size)
        assertEquals("for_you", BitzModeSetting.FOR_YOU.wire)
        assertEquals("following", BitzModeSetting.FOLLOWING.wire)
        assertEquals("explore", BitzModeSetting.EXPLORE.wire)
        // Removed W2 wires migrate to the default, never crash old installs.
        assertEquals(BitzModeSetting.DEFAULT, BitzModeSetting.parse("trending"))
        assertEquals(BitzModeSetting.DEFAULT, BitzModeSetting.parse("zapped"))
        assertEquals(BitzModeSetting.DEFAULT, BitzModeSetting.parse("bogus"))
    }

    @Test
    fun walkContinuesWhileFreshMatchesRemainUnderBudget() {
        // Root cause of "load more does nothing": a page re-sending known
        // ids (budget-fresh = 0 but the cursor still moves) must CONTINUE
        // the walk, not exhaust the feed.
        val cursor0 = BitzTimelinePolicy.cursor(oldestCreatedAt = 10_000)
        assertTrue(BitzTimelinePolicy.shouldContinue(foundFreshMedia = 0, batchesIssued = 0))
        // Batch 1 returned only duplicates but moved the cursor → walk on.
        assertFalse(BitzTimelinePolicy.relayStalled(oldestInBatch = 8_000, previousCursor = cursor0, freshCount = 0))
        assertTrue(BitzTimelinePolicy.shouldContinue(foundFreshMedia = 4, batchesIssued = 1))
        // Batch 2 reaches the budget → stop; six batches is the hard bound.
        assertFalse(BitzTimelinePolicy.shouldContinue(foundFreshMedia = 18, batchesIssued = 2))
        assertFalse(BitzTimelinePolicy.shouldContinue(foundFreshMedia = 0, batchesIssued = 6))
        // Stalled relay (no advance, nothing fresh) terminates instead of looping.
        assertTrue(BitzTimelinePolicy.relayStalled(oldestInBatch = cursor0, previousCursor = cursor0, freshCount = 0))
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
