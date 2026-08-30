package space.bitos.core.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Content-classification contract (web `content-classification.test.ts`
 * parity, version 2026-08-22): explainable detection for kind-1 notes
 * that carry a protocol payload rather than a message for a person, and
 * for bot coordination tags that are syntactically valid hashtags but
 * carry no human topic.
 */
class ContentClassificationTest {

    @Test
    fun recognizesSerializedChannelRosters() {
        assertTrue(ContentClassification.isProtocolPayload("channel:__roster\n${"ab".repeat(80)}"))
        assertTrue(ContentClassification.isProtocolPayload("channel: __roster\n${"ab".repeat(48)}"))
    }

    @Test
    fun doesNotHideProseThatMentionsAChannelOrHash() {
        // Web fixtures verbatim.
        assertFalse(ContentClassification.isProtocolPayload("channel: __roster\nWelcome to the group."))
        assertFalse(ContentClassification.isProtocolPayload("The build hash is ${"ab".repeat(32)}"))
        // Roster with a too-short body is not machine traffic.
        assertFalse(ContentClassification.isProtocolPayload("channel:__roster\nabcdef"))
        // Ordinary notes (incl. JSON-ish prose) stay readable.
        assertFalse(ContentClassification.isProtocolPayload("""{"kind":"token","amount":1}"""))
        assertFalse(ContentClassification.isProtocolPayload("gm nostr"))
    }

    @Test
    fun recognizesBotCoordinationTags() {
        assertTrue(ContentClassification.isMachineTag("udal-friend-aede0a98e7fd3ffef77db169c0ccaaa1"))
        assertTrue(ContentClassification.isMachineTag("udal-peer-2348e984dab2c63dfbdab100aa1a3974"))
        assertTrue(ContentClassification.isMachineTag("UDAL-NODE-aede0a98e7fd3ffe"))
    }

    @Test
    fun keepsHumanHashtags() {
        assertFalse(ContentClassification.isMachineTag("nostr"))
        assertFalse(ContentClassification.isMachineTag("bitcoin-price"))
        assertFalse(ContentClassification.isMachineTag("udal"))
        assertFalse(ContentClassification.isMachineTag("udal-friend-abc")) // too short → not a bot id
    }

    @Test
    fun filtersMachineTagsOutOfAMixedList() {
        assertEquals(
            listOf("nostr", "asknostr"),
            ContentClassification.humanTags(
                listOf("nostr", "udal-friend-aede0a98e7fd3ffef77db169c0ccaaa1", "asknostr"),
            ),
        )
    }

    @Test
    fun emptyAndBlankTagsAreNotMachineTags() {
        assertFalse(ContentClassification.isMachineTag(""))
        assertFalse(ContentClassification.isMachineTag("   "))
    }
}
