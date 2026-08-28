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
        assertTrue(AppFacts.SUPPORT_LUD16.isEmpty() || Regex("^[\\w.-]+@[\\w.-]+$").matches(AppFacts.SUPPORT_LUD16))
    }

    @Test
    fun contentIsBoundedAndVersioned() {
        assertEquals(1, AppFacts.SCHEMA_VERSION)
        assertTrue(AppFacts.TAGLINE.length <= 200)
        assertTrue(AppFacts.FAQ.size in 1..20)
        AppFacts.FAQ.forEach { entry ->
            assertTrue(entry.question.length in 4..120, "question bound")
            assertTrue(entry.answer.length in 20..400, "answer bound")
        }
        // Legacy support tiers: ascending sats, sane bounds.
        assertEquals(AppFacts.SUPPORT_TIERS_SATS, AppFacts.SUPPORT_TIERS_SATS.sorted())
        assertTrue(AppFacts.SUPPORT_TIERS_SATS.all { it in 1..100_000 })
    }
}
