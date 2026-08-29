package space.bitos.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * APP-020 static pages: the legacy copy is preserved verbatim — full
 * section counts, key phrases, and structure.
 */
class StaticPagesContentTest {

    @Test
    fun privacyHasExactlyElevenLegacySections() {
        assertEquals(11, StaticPagesContent.privacySections.size)
        assertEquals("1. BitOS is software, not a hosted network", StaticPagesContent.privacySections[0].title)
        assertEquals("11. Contact", StaticPagesContent.privacySections.last().title)
    }

    @Test
    fun termsHasExactlyTenLegacySections() {
        assertEquals(10, StaticPagesContent.termsSections.size)
        assertEquals("1. What BitOS is", StaticPagesContent.termsSections[0].title)
        assertEquals("10. Contact", StaticPagesContent.termsSections.last().title)
    }

    @Test
    fun keyLegacyPhrasesArePreserved() {
        assertTrue(StaticPagesContent.ABOUT_HERO_BODY.contains("no central server"))
        assertTrue(StaticPagesContent.WHAT_IS_NOSTR_BODY.contains("Notes and Other Stuff Transmitted by Relays"))
        assertTrue(StaticPagesContent.privacySections[1].body.contains("BitOS cannot recover your key"))
        assertTrue(StaticPagesContent.termsSections[4].body.contains("as is"))
        assertTrue(StaticPagesContent.PRIVACY_SUMMARY_BODY.contains("does not include trackers"))
    }

    @Test
    fun everySectionCarriesSubstantialCopy() {
        (StaticPagesContent.aboutFeatures + StaticPagesContent.privacySections + StaticPagesContent.termsSections)
            .forEach { section ->
                assertTrue(section.title.length in 3..120, "title: ${section.title.take(30)}")
                assertTrue(section.body.length in 40..2000, "body: ${section.title.take(30)}")
            }
    }
}
