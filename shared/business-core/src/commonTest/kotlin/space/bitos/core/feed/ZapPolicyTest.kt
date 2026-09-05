package space.bitos.core.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Advisory zap permission (`ZapPolicy`): the off marker a publish stamps
 * when "Zap settings" is off, and the `FeedNote` projection both native
 * cards read to hide the zap action. Advisory-only like `license` —
 * unknown or absent tags never disable zaps.
 */
class ZapPolicyTest {

    @Test
    fun offTagIsTheAdvisoryMarker() {
        assertEquals(listOf("bitz:zaps", "off"), ZapPolicy.offTag())
    }

    @Test
    fun detectsOffMarkerAndIgnoresEverythingElse() {
        assertTrue(ZapPolicy.isDisabled(listOf(ZapPolicy.offTag())))
        assertTrue(ZapPolicy.isDisabled(listOf(listOf("t", "meme"), listOf("bitz:zaps", "off"))))
        // Absent, bare, or "on" markers stay zap-able; other clients' junk
        // never disables zaps either.
        assertFalse(ZapPolicy.isDisabled(emptyList()))
        assertFalse(ZapPolicy.isDisabled(listOf(listOf("bitz:zaps"))))
        assertFalse(ZapPolicy.isDisabled(listOf(listOf("bitz:zaps", "on"))))
        assertFalse(ZapPolicy.isDisabled(listOf(listOf("license", "CC0-1.0"))))
    }

    @Test
    fun feedNoteProjectsTheZapPermission() {
        val event = space.bitos.core.model.NostrEvent(
            id = space.bitos.core.model.EventId.parse("11".repeat(32))!!,
            pubkey = space.bitos.core.model.Pubkey.parse("22".repeat(32))!!,
            createdAt = 1_710_000_000,
            kind = space.bitos.core.model.NostrKinds.VIDEO,
            tags = listOf(
                listOf("t", "meme"),
                ZapPolicy.offTag(),
            ),
            content = "no zaps please",
            signature = null,
            receivedFromRelay = null,
        )
        assertTrue(FeedNote.from(event).zapsDisabled)
        // Notes without the marker stay zap-able.
        assertFalse(FeedNote.from(event.copy(tags = emptyList())).zapsDisabled)
    }
}
