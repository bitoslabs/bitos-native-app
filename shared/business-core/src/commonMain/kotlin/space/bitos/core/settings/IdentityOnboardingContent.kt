package space.bitos.core.settings

/**
 * Identity onboarding content (docs/ui/app-01-onboarding-identity.html,
 * spec §4): the launch flow Welcome → Add identity → Import/Backup → npub
 * confirmation. Versioned shared copy so Compose and SwiftUI render the
 * identical flow; icon tokens are semantic names the native icon facades
 * resolve. Replaces the APP-002 carousel contract.
 */
data class IdentityValueProp(
    val id: String,
    val iconToken: String,
    val title: String,
    val body: String,
)

data class IdentityMethodOption(
    val id: String,
    val iconToken: String,
    val title: String,
    val subtitle: String,
    val recommended: Boolean = false,
    /** false = visible-but-inert "coming soon" card (behind the same signer port later). */
    val available: Boolean = true,
    /** NIP-55 is an Android platform capability only. */
    val androidOnly: Boolean = false,
)

object IdentityOnboardingContent {

    // v2: More-hub add-account sheet copy + nsec help items joined the contract.
    const val SCHEMA_VERSION = 2

    // ── Welcome / value proposition ────────────────────────────────────
    const val APP_NAME = "BitOS"
    const val TAGLINE =
        "Social that pays you back. Short video, notes and zaps on Nostr — no algorithm cage, no platform lock."
    const val GUEST_TOAST =
        "Browsing as guest — any zap, post or follow will ask for an identity first"
    const val PRIVACY_FOOTNOTE =
        "No email · No tracking · Nothing leaves this device until you sign it."

    val VALUE_PROPS = listOf(
        IdentityValueProp(
            id = "keys",
            iconToken = "key",
            title = "Your keys, your audience",
            body = "Identity is a keypair you own. Accounts can't be deplatformed.",
        ),
        IdentityValueProp(
            id = "zaps",
            iconToken = "zap",
            title = "Zaps over Lightning",
            body = "Send and receive sats on any note or video. Value, not likes.",
        ),
        IdentityValueProp(
            id = "bitz",
            iconToken = "video",
            title = "Bitz — video-first Nostr",
            body = "Watch immediately. The protocol explains itself as you go.",
        ),
    )

    // ── Identity method ────────────────────────────────────────────────
    const val METHOD_TITLE = "Your identity is a key"
    const val METHOD_BODY =
        "Signing an event proves it came from you — no password, no server. " +
            "The key never leaves this device; remote signers sign without ever sending it to us either."
    const val METHOD_INFO =
        "Identities are never created silently — you choose, and you can hold several and switch before any sign or payment."
    const val NIP46_COMING_SOON = "NIP-46 remote signers arrive in a future build — rejection returns here, nothing is lost."
    const val NIP55_COMING_SOON = "NIP-55 system signing arrives in a future build."

    val METHODS = listOf(
        IdentityMethodOption(
            id = "create",
            iconToken = "sparkle",
            title = "Create a new key",
            subtitle = "Generated on-device. One-time backup, then it's yours forever.",
            recommended = true,
        ),
        IdentityMethodOption(
            id = "import",
            iconToken = "download",
            title = "Import a secret key",
            subtitle = "Paste an nsec… or 64-char hex key you already hold.",
        ),
        IdentityMethodOption(
            id = "nip46",
            iconToken = "link",
            title = "Connect a remote signer",
            subtitle = "NIP-46 — keep keys in Alby, a hardware device or a bunker.",
            available = false,
        ),
        IdentityMethodOption(
            id = "nip55",
            iconToken = "android",
            title = "Use Android system signer",
            subtitle = "NIP-55 — sign with an app like Amber without sharing keys.",
            available = false,
            androidOnly = true,
        ),
    )

    // ── Import secret key ──────────────────────────────────────────────
    const val IMPORT_FIELD_LABEL = "Secret key"
    const val IMPORT_REVIEW_NOTE =
        "Enabled only when the input resolves to a usable secret (nsec or 64-hex)."
    const val DERIVED_IDENTITY_LABEL = "Derived identity"

    // ── More-hub add-account sheet (ID-004 surface; rendered by the
    // SwiftUI sheet and the Compose bottom sheet with verbatim parity) ──
    const val ADD_ACCOUNT_TITLE = "Add account"
    const val ADD_ACCOUNT_SUBTITLE =
        "Log in with an nsec or create a fresh key. Every account already on this device stays sealed."
    const val ADD_ACCOUNT_REVIEW_LABEL = "Review key"
    const val ADD_ACCOUNT_CREATE_LABEL = "Create new key"
    const val ADD_ACCOUNT_CANCEL_LABEL = "Cancel"
    const val NSEC_HELP_TITLE = "What's an nsec?"
    val NSEC_HELP_ITEMS = listOf(
        "Your secret key — it starts with nsec1. Whoever holds it can post and spend as you, so never share it.",
        "Export it from your current Nostr app (usually Settings → Back up or Export private key), then paste it above.",
        "npub1… is your public ID — safe to share, but it can't log you in.",
        "BitOS seals the key in device storage and never uploads it. Only paste an nsec into apps you trust.",
    )

    // ── Backup gate (freshly generated key) ────────────────────────────
    const val BACKUP_WARNING =
        "This is shown once. If you lose the key and this device, the identity is unrecoverable — by anyone."
    const val BACKUP_FIELD_LABEL = "Secret key (backup)"
    const val BACKUP_REVEAL_PROMPT = "Tap to reveal — never automatic"
    const val BACKUP_ADVICE =
        "Write it on paper or steel. Screenshots and cloud notes leak. BitOS never logs, persists or uploads this value."
    const val BACKUP_NEXT_STEPS_TITLE = "What happens next"
    val BACKUP_NEXT_STEPS = listOf(
        "Confirm the npub this key derives to",
        "Return to the action you started — nothing re-typed",
        "Imported keys skip this reveal — you already hold them",
    )
    const val BACKUP_ACK_LABEL =
        "I saved my key somewhere safe and understand it can't be recovered."
    const val BACKUP_CONFIRM_LABEL = "I saved my key"

    // ── Verification (imported key) ────────────────────────────────────
    const val VERIFY_TITLE = "Confirm your identity"
    const val VERIFY_BODY =
        "This is the public identity derived from your key. Check that it matches the account you expect."
    const val VERIFY_CONFIRM_LABEL = "Use this identity"

    // ── Identity ready ─────────────────────────────────────────────────
    const val SUCCESS_TITLE = "Identity ready"
    const val SUCCESS_BODY =
        "This is your public ID — the part you share. It's how people find and zap you."
    const val SUCCESS_PROFILE_NOTE = "Default profile — edit anytime from Profile"
    const val SUCCESS_INFO = "Heading to your feed — any pending action resumes right where you left it."
    const val SUCCESS_DONE_LABEL = "Done"
    const val COPY_NPUB_LABEL = "Copy npub"
}
