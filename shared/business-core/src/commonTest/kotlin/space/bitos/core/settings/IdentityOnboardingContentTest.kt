package space.bitos.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Identity onboarding content contract (spec §4, docs/ui/app-01): shape and
 * copy vectors both platforms render — ids unique, one recommended create
 * path, NIP-46/55 marked not-yet-available, NIP-55 Android-only.
 */
class IdentityOnboardingContentTest {

    @Test
    fun valuePropsAreThreeWithUniqueIds() {
        assertEquals(3, IdentityOnboardingContent.VALUE_PROPS.size)
        assertEquals(
            IdentityOnboardingContent.VALUE_PROPS.map { it.id }.distinct(),
            IdentityOnboardingContent.VALUE_PROPS.map { it.id },
        )
        IdentityOnboardingContent.VALUE_PROPS.forEach { prop ->
            assertTrue(prop.title.isNotBlank())
            assertTrue(prop.body.isNotBlank())
            assertTrue(prop.iconToken.isNotBlank())
        }
    }

    @Test
    fun methodsCoverCreateImportAndFutureSigners() {
        val ids = IdentityOnboardingContent.METHODS.map { it.id }
        assertEquals(listOf("create", "import", "nip46", "nip55"), ids)
        assertEquals(1, IdentityOnboardingContent.METHODS.count { it.recommended })
        assertTrue(IdentityOnboardingContent.METHODS.first { it.id == "create" }.recommended)
        assertTrue(IdentityOnboardingContent.METHODS.first { it.id == "create" }.available)
        assertTrue(IdentityOnboardingContent.METHODS.first { it.id == "import" }.available)
    }

    @Test
    fun remoteSignersAreVisibleButNotYetAvailable() {
        val nip46 = IdentityOnboardingContent.METHODS.first { it.id == "nip46" }
        val nip55 = IdentityOnboardingContent.METHODS.first { it.id == "nip55" }
        assertEquals(false, nip46.available)
        assertEquals(false, nip55.available)
        assertEquals(false, nip46.androidOnly)
        assertEquals(true, nip55.androidOnly)
    }

    @Test
    fun backupGateCopyAnchorsTheOneTimeReveal() {
        val content = IdentityOnboardingContent
        assertTrue(content.BACKUP_WARNING.contains("shown once", ignoreCase = true))
        assertTrue(content.BACKUP_REVEAL_PROMPT.contains("never automatic"))
        assertTrue(content.BACKUP_ACK_LABEL.isNotBlank())
        assertEquals(3, content.BACKUP_NEXT_STEPS.size)
        assertTrue(content.BACKUP_CONFIRM_LABEL.equals("I saved my key", ignoreCase = true))
    }

    @Test
    fun welcomeCopyMatchesTheMockupContract() {
        val content = IdentityOnboardingContent
        assertEquals("BitOS", content.APP_NAME)
        assertTrue(content.TAGLINE.startsWith("Social that pays you back"))
        assertTrue(content.PRIVACY_FOOTNOTE.contains("Nothing leaves this device"))
        assertTrue(content.GUEST_TOAST.contains("Browsing as guest"))
    }
}
