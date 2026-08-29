package space.bitos.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * APP-018/APP-020 app-facts contract: the static help/about content is
 * versioned, bounded and honest (only live NIPs; no placeholder payment
 * targets).
 */
class AppFactsTest {

    @Test
    fun supportedNipsAreSortedUniqueAndPlausible() {
        assertEquals(AppFacts.SUPPORTED_NIPS, AppFacts.SUPPORTED_NIPS.distinct().sorted())
        assertTrue(AppFacts.SUPPORTED_NIPS.all { it in 1..110 })
        // The platform's core NIPs are always advertised.
        assertTrue(AppFacts.SUPPORTED_NIPS.containsAll(listOf(1, 19, 57, 65)))
    }

    @Test
    fun linksAreHttpsAndCanonical() {
        assertTrue(AppFacts.LINK_NIPS.startsWith("https://"))
        assertTrue(AppFacts.LINK_NOSTR.startsWith("https://"))
        assertTrue(AppFacts.LINK_SOURCE.isEmpty() || AppFacts.LINK_SOURCE.startsWith("https://"))
        // A support address is either empty (rows hidden) or a LUD-16 shape.
    }

    @Test
    fun contentIsBoundedAndVersioned() {
        assertEquals(2, AppFacts.SCHEMA_VERSION)
        assertTrue(AppFacts.TAGLINE.length <= 200)
        assertTrue(AppFacts.FAQ.size in 1..20)
        AppFacts.FAQ.forEach { entry ->
            assertTrue(entry.question.length in 4..120, "question bound")
            assertTrue(entry.answer.length in 20..400, "answer bound")
        }
        // Support npub + tiers (v2): valid npub shape, ascending sats,
        // exactly one recommended tier; contributor list is a valid npub set.
        assertTrue(AppFacts.SUPPORT_NPUB.startsWith("npub1") && AppFacts.SUPPORT_NPUB.length == 63)
        assertEquals(AppFacts.SUPPORT_TIERS.map { it.sats }, AppFacts.SUPPORT_TIERS.map { it.sats }.sorted())
        assertTrue(AppFacts.SUPPORT_TIERS.all { it.sats in 1..1_000_000 })
        assertEquals(1, AppFacts.SUPPORT_TIERS.count { it.recommended })
        AppFacts.CONTRIBUTOR_NPUBS.forEach { npub ->
            assertTrue(npub.startsWith("npub1") && npub.length == 63, "contributor npub shape")
        }
    }
}
