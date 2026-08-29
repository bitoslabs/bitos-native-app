package space.bitos.core.settings

/**
 * Versioned static app facts for the settings `help`/`about` sections
 * (APP-018 §3.18 + APP-020 content parity; legacy web/Flutter About card,
 * SupportSettings and `_HelpSection` data). One source so both platforms
 * render identical content; adapters never hardcode this copy.
 *
 * Honesty rules: [SUPPORTED_NIPS] lists only NIPs that are live in this
 * client (tracker-verified — the legacy mockup's list included NIPs this
 * platform does not implement yet); [LINK_SOURCE] and [SUPPORT_LUD16]
 * render their rows only when the team provides real values — placeholder
 * payment targets are never shown.
 */
object AppFacts {
    const val SCHEMA_VERSION = 2

    const val APP_NAME = "BitOS"
    const val TAGLINE =
        "Open-source Nostr client. MIT licensed. Built with respect for the protocol and the people on it."
    const val LICENSE = "MIT"
    const val BUILT_ON = "NIP-01"

    /** NIPs live in this client (sorted, deduped; see tracker). */
    val SUPPORTED_NIPS: List<Int> = listOf(
        1,   // events + verification gate
        2,   // contact list (kind 3)
        5,   // NIP-05 identifier display
        13,  // proof of work
        18,  // reposts (kind 6)
        19,  // bech32 entities (npub/nsec/note/…)
        21,  // nostr: URI scheme
        25,  // reactions (kind 7)
        27,  // text note references
        36,  // sensitive content warning
        51,  // bookmark lists (kind 30003)
        57,  // zaps (9734/9735 + LNURL)
        65,  // relay list metadata (kind 10002)
    )

    /** Canonical reference links (legacy About card buttons). */
    const val LINK_NIPS = "https://github.com/nostr-protocol/nips"
    const val LINK_NOSTR = "https://nostr.net"

    /** Project source repository; empty hides the Source row. */
    const val LINK_SOURCE = ""

    /**
     * Official support npub (legacy `SupportController.supportNpub` parity):
     * donate flow = fetch its kind-0 → lud16 → LNURL-pay invoice.
     */
    const val SUPPORT_NPUB =
        "npub12l8q8wph9ygk0hv00pf8g558pvftr0hav2r8npfq66nm04sswnwsylp57e"

    /** Web-parity donate tiers; `recommended` marks the highlighted tile. */
    data class SupportTier(val sats: Int, val recommended: Boolean = false)

    val SUPPORT_TIERS: List<SupportTier> = listOf(
        SupportTier(sats = 1_000),          // coffee
        SupportTier(sats = 5_000, recommended = true),  // expert
        SupportTier(sats = 21_000),         // production
        SupportTier(sats = 100_000),        // premium
    )

    /** Web-parity contributor list (kind-0 rows in the help section). */
    val CONTRIBUTOR_NPUBS: List<String> = listOf(
        "npub1ujh9lp7vw38yatm0vsxy7xuwxl3j98qvnyatyyg9xszufpyxn2fskqagph",
    )

    /** Contribute copy (legacy open-source card + ContributorsWidget spirit). */
    const val CONTRIBUTE_NOTE =
        "BitOS is open source (MIT). Contribute code, report issues or zap the team — every contribution keeps the relays humming."

    /** One FAQ entry (settings `help` section; single source). */
    data class FaqEntry(val question: String, val answer: String)

    val FAQ: List<FaqEntry> = listOf(
        FaqEntry(
            "What is BitOS?",
            "A Nostr client for short notes, Bitz clips and zaps. Your identity is a key pair you own — no email, no server account.",
        ),
        FaqEntry(
            "Where is my data stored?",
            "Notes are canonical signed events on relays. This device keeps a bounded cache; nothing is stored on a BitOS server.",
        ),
        FaqEntry(
            "How do I back up my account?",
            "Settings → Security → Reveal secret key. The nsec is the only recovery method — store it offline and never share it.",
        ),
        FaqEntry(
            "Why do some posts not load?",
            "Relays are independent servers. A post is only visible if at least one of your relays carries it.",
        ),
        FaqEntry(
            "How do zaps work?",
            "You can set a default amount and create an LNURL zap invoice. Wallet pairing and in-app settlement are not available yet, so pay the invoice in an external wallet.",
        ),
        FaqEntry(
            "What works today?",
            "Browsing verified relay notes, composing notes/replies/reposts/reactions, local bookmarks and follows, media upload verification, profile editing, relay management, and the listed settings preferences are available. Wallet pairing, biometric app lock, full theming, and translations are still pending.",
        ),
    )
}
