package space.bitos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * APP-014 zap sheet presentation rules (legacy Flutter `zap_dialog.dart` /
 * web `NoteZapDialog.svelte` parity): YakiHonne-style amount tiers, the
 * quick-pick presets and the K/M sats formatter.
 */
class ZapFormatTest {

    @Test
    fun emojiTiersFollowTheLegacyBounds() {
        assertEquals("⚡", ZapFormat.emoji(21))
        assertEquals("⚡", ZapFormat.emoji(50))
        assertEquals("💜", ZapFormat.emoji(51))
        assertEquals("💜", ZapFormat.emoji(250))
        assertEquals("🔥", ZapFormat.emoji(251))
        assertEquals("🔥", ZapFormat.emoji(750))
        assertEquals("🚀", ZapFormat.emoji(751))
        assertEquals("🚀", ZapFormat.emoji(100_000))
    }

    @Test
    fun presetsAreTheLegacyQuickPicks() {
        assertEquals(listOf(21, 100, 500, 1000), ZapFormat.PRESETS)
    }

    @Test
    fun formatsSatsWithCompactUnits() {
        assertEquals("21", ZapFormat.sats(21))
        assertEquals("999", ZapFormat.sats(999))
        assertEquals("1K", ZapFormat.sats(1_000))
        assertEquals("1.2K", ZapFormat.sats(1_234))
        assertEquals("1M", ZapFormat.sats(1_000_000))
        assertEquals("1.5M", ZapFormat.sats(1_500_000))
    }
}
