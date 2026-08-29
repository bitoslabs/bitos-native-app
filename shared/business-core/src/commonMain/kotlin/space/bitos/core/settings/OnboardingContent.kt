package space.bitos.core.settings

/**
 * APP-002 onboarding carousel content (legacy Flutter `onboarding_view`
 * parity): the four pages' copy as a versioned, shared contract so both
 * platforms render identical onboarding. Icons are semantic token names
 * the native AppIcons facades resolve.
 */
data class OnboardingPage(
    val id: String,
    val iconToken: String,
    val title: String,
    val body: String,
)

object OnboardingContent {

    const val SCHEMA_VERSION = 1
    const val TOTAL_PAGES = 4

    val pages: List<OnboardingPage> = listOf(
        OnboardingPage(
            id = "welcome",
            iconToken = "globe",
            title = "Welcome to bitos.space",
            body = "The decentralized social network powered by Nostr protocol and Bitcoin. No algorithms, no censorship — just pure social connection.",
        ),
        OnboardingPage(
            id = "keys",
            iconToken = "qrCode",
            title = "Your Keys, Your Identity",
            body = "No phone number. No email. Just your cryptographic keys. You truly own your account — no one can take it from you.",
        ),
        OnboardingPage(
            id = "decentralized",
            iconToken = "globe",
            title = "No Algorithms, No Censorship",
            body = "Your feed, your rules. Connect to relays worldwide. No corporation controls what you see or can delete your posts.",
        ),
        OnboardingPage(
            id = "zap",
            iconToken = "zap",
            title = "Zap, Post & Connect",
            body = "Support creators with Bitcoin Lightning zaps. Share your thoughts, join communities, and connect with people around the world.",
        ),
    )
}
