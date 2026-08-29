package space.bitos.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * APP-002 onboarding contract: four pages, populated copy, icon tokens
 * resolve against the AppIcons set.
 */
class OnboardingContentTest {

    @Test
    fun hasExactlyFourPagesInLegacyOrder() {
        assertEquals(OnboardingContent.TOTAL_PAGES, OnboardingContent.pages.size)
        assertEquals(
            listOf("welcome", "keys", "decentralized", "zap"),
            OnboardingContent.pages.map { it.id },
        )
    }

    @Test
    fun everyPageCarriesSubstantialCopy() {
        OnboardingContent.pages.forEach { page ->
            assertTrue(page.title.length in 4..80, "title length: ${page.id}")
            assertTrue(page.body.length in 40..400, "body length: ${page.id}")
            assertTrue(!page.iconToken.isBlank(), "icon: ${page.id}")
        }
    }

    @Test
    fun legacyCopyIsPreservedVerbatim() {
        assertEquals("Welcome to bitos.space", OnboardingContent.pages[0].title)
        assertTrue(OnboardingContent.pages[1].body.contains("no one can take it from you"))
        assertTrue(OnboardingContent.pages[3].title.startsWith("Zap"))
    }
}
