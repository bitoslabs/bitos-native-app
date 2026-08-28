package space.bitos.core.publish

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * APP-008 draft persistence: versioned v1 wire, bounds at encode, lenient
 * decode (corrupt/oversized → null), round-trip.
 */
class ComposerDraftTest {

    @Test
    fun roundTripsEveryField() {
        val draft = ComposerDraft(
            text = "gm nostr",
            remoteUrls = listOf("https://cdn.example/a.png"),
            contentWarningReason = "nudity",
            trackedMentions = listOf("Satoshi Nakamoto" to "npub1example", "alice" to "npub1other"),
            powTarget = 20,
        )
        assertEquals(draft, ComposerDraftContract.decode(ComposerDraftContract.encode(draft)))
    }

    @Test
    fun emptyDraftEncodesAndDecodes() {
        val draft = ComposerDraft(text = "")
        assertEquals(draft, ComposerDraftContract.decode(ComposerDraftContract.encode(draft)))
        assertTrue(draft.isEmpty)
        assertTrue(!ComposerDraft(text = "x").isEmpty)
        assertTrue(!ComposerDraft(text = "", remoteUrls = listOf("https://a.example/x.png")).isEmpty)
    }

    @Test
    fun encodeBoundsClampHostileValues() {
        val hostile = ComposerDraft(
            text = "x".repeat(20_000),
            remoteUrls = (1..8).map { "https://a.example/$it.png" },
            contentWarningReason = "y".repeat(400),
            trackedMentions = (1..12).map { "name$it" to "npub$it" },
            powTarget = 99,
        )
        val decoded = ComposerDraftContract.decode(ComposerDraftContract.encode(hostile))!!
        assertEquals(ComposerRules.HARD_LIMIT, decoded.text.length)
        assertEquals(ComposerRules.MAX_IMAGES, decoded.remoteUrls.size)
        assertEquals(120, decoded.contentWarningReason!!.length)
        assertEquals(8, decoded.trackedMentions.size)
        assertEquals(30, decoded.powTarget)
    }

    @Test
    fun corruptOversizedOrWrongVersionWireYieldsNull() {
        assertNull(ComposerDraftContract.decode("{not json"))
        assertNull(ComposerDraftContract.decode("""{"v":2,"text":"x"}"""))
        assertNull(ComposerDraftContract.decode("""["array"]"""))
        assertNull(ComposerDraftContract.decode("x".repeat(ComposerDraftContract.MAX_WIRE_LENGTH + 1)))
        // Missing fields fall back to defaults instead of failing.
        assertEquals(
            ComposerDraft(text = "gm"),
            ComposerDraftContract.decode("""{"v":1,"text":"gm"}"""),
        )
    }
}
