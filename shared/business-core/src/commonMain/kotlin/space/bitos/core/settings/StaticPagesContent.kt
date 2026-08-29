package space.bitos.core.settings

/**
 * APP-020 static pages (spec §3.20): About · Privacy (11 sections) ·
 * Terms (10 sections) — the complete legacy copy ported verbatim from the
 * Flutter app's i18n (`en_US.dart`). Shared so both platforms render
 * identical legal/brand content; versioned for future revisions.
 */
data class StaticSection(val title: String, val body: String)

object StaticPagesContent {

    const val SCHEMA_VERSION = 1

    // ── About ───────────────────────────────────────────────────────

    const val ABOUT_HEADLINE = "BitOS"
    const val ABOUT_SUBTITLE = "Launches August 8, 2026"
    const val ABOUT_HERO_BODY =
        "A local-first social client for the Nostr protocol. Your keys never leave your device — there is no central server, no company holding your account, and no lock-in."

    val aboutFeatures: List<StaticSection> = listOf(
        StaticSection(
            "Local-first identity",
            "Your private key is generated and stored on your device. There is no signup, no email, and no password to forget — and no server that can lose or leak it.",
        ),
        StaticSection(
            "Open relays",
            "BitOS speaks the Nostr protocol and connects to relays you choose. Add, remove, or switch relays any time — your social graph belongs to you, not a silo.",
        ),
        StaticSection(
            "Encrypted messages",
            "Direct messages are end-to-end encrypted on your device before they ever touch a relay.",
        ),
        StaticSection(
            "Stories & media",
            "Ephemeral 24-hour stories, image and video posts, and a discover grid — all published as standard Nostr events.",
        ),
        StaticSection(
            "Value, peer-to-peer",
            "Send sats over the Lightning Network natively. Nostr makes money a first-class interaction, not an afterthought.",
        ),
        StaticSection(
            "Premium, private UX",
            "A calm, Apple-faithful interface with light/dark themes, no trackers, and no ads — ever.",
        ),
    )

    const val WHAT_IS_NOSTR_TITLE = "What is Nostr?"
    const val WHAT_IS_NOSTR_BODY =
        "Nostr (\"Notes and Other Stuff Transmitted by Relays\") is a simple, open protocol for censorship-resistant social networking. Instead of one company's servers, notes are signed by your cryptographic key and passed around by relays — independent servers that anyone can run."

    const val OPEN_SOURCE_TITLE = "Open source & self-hostable"
    const val OPEN_SOURCE_BODY =
        "BitOS is open source. Read the code, run it yourself, or contribute on GitHub."
    const val OPEN_SOURCE_LAUNCH = "Public launch: August 8, 2026"

    // ── Privacy (11 sections) ───────────────────────────────────────

    const val PRIVACY_TITLE = "Privacy Policy"
    const val PRIVACY_UPDATED = "Last updated: August 8, 2026"
    const val PRIVACY_INTRO =
        "BitOS is a local-first client for the open Nostr protocol. This policy explains what data BitOS stores, what data is published to the network, and where third-party services are involved."
    const val PRIVACY_SUMMARY_TITLE = "Short version"
    const val PRIVACY_SUMMARY_BODY =
        "BitOS does not run a central backend, does not require a traditional account, and does not include trackers. Your private key and local app data stay on your device unless you choose to publish content or use third-party services such as relays, media hosts, or wallets."

    val privacySections: List<StaticSection> = listOf(
        StaticSection(
            "1. BitOS is software, not a hosted network",
            "BitOS runs on your device and connects to the Nostr protocol. It does not operate the relays your content travels through, the media providers you may use, or the wallets you may connect. Those third parties may collect or process data under their own policies.",
        ),
        StaticSection(
            "2. Your identity and keys",
            "Your BitOS identity is a Nostr keypair. Your private key (your nsec) is generated or imported locally and stored on this device. BitOS does not send your private key to a BitOS server because BitOS does not run one for account storage. BitOS cannot recover your key if it is lost.",
        ),
        StaticSection(
            "3. Data stored on your device",
            "BitOS stores app data locally. Depending on how you use the app, this may include your private key, profile data, preferences, relay settings, bookmarks, drafts, cached notes, cached direct messages, media metadata, and other app state needed to make the client work. Clearing app data removes this local data, but not data already published to relays or uploaded to third-party media hosts.",
        ),
        StaticSection(
            "4. What gets published to relays",
            "When you post, react, reply, edit your profile, follow someone, send a direct message, or publish other Nostr events, BitOS signs those events with your key and sends them to the relays you have configured. Most Nostr events are public and may be copied, indexed, cached, and re-broadcast by relays, clients, and other third parties. In practice, public network activity should be treated as widely visible and potentially permanent.",
        ),
        StaticSection(
            "5. Direct messages",
            "Direct messages are encrypted on your device before publication. The encrypted payload may pass through relays, but relay operators can still observe some metadata, such as timing, sender, recipient, relay usage patterns, and message volume depending on the protocol and relay behavior.",
        ),
        StaticSection(
            "6. Media uploads",
            "BitOS does not permanently host your media. When you upload images or video, the files are sent to a media provider that you configure or use. That provider may collect file contents, IP address information, access logs, and related metadata under its own privacy policy and retention rules.",
        ),
        StaticSection(
            "7. Relays",
            "Relays are independent servers run by third parties. They receive the events you publish, may store them, and may send you events published by others. Each relay may maintain logs, moderation rules, retention practices, access controls, and jurisdiction-specific policies of its own.",
        ),
        StaticSection(
            "8. Analytics and tracking",
            "BitOS does not include advertising, analytics, telemetry, or third-party tracking scripts by default. It does not operate a backend that profiles users or sells personal data.",
        ),
        StaticSection(
            "9. Lightning payments and wallets",
            "When you send or receive zaps, your device talks to the recipient's Lightning service and your wallet app directly. BitOS never sees your wallet credentials and does not process payments itself.",
        ),
        StaticSection(
            "10. Your choices",
            "Stop using BitOS at any time; clear app data to remove local state; add, remove, or change relays in Settings; choose which media providers and wallet tools you use; and publish a Nostr deletion request where supported, understanding that relays and other clients may ignore it.",
        ),
        StaticSection(
            "11. Contact",
            "Questions about privacy? Reach the project at bitos.space.",
        ),
    )

    // ── Terms (10 sections) ─────────────────────────────────────────

    const val TERMS_TITLE = "Terms of Service"
    const val TERMS_UPDATED = "Last updated: August 8, 2026"
    const val TERMS_INTRO =
        "These Terms of Service govern your use of BitOS, an open-source client for the Nostr protocol. By using BitOS, you agree to these Terms. If you do not agree, do not use the app."
    const val TERMS_SUMMARY_TITLE = "Important"
    const val TERMS_SUMMARY_BODY =
        "BitOS is software, not a hosted social network. Your keys are controlled by you, and your content is distributed through third-party relays and media providers that you choose."

    val termsSections: List<StaticSection> = listOf(
        StaticSection(
            "1. What BitOS is",
            "BitOS is free, open-source software that connects to the decentralized Nostr network. BitOS does not operate a central social platform, custody your account, or guarantee delivery or storage of content. Content you publish may pass through relays, media hosts, wallet providers, and other third-party services. Those services are separate from BitOS and may have their own terms, fees, moderation policies, and retention practices.",
        ),
        StaticSection(
            "2. Your identity and keys",
            "There are no BitOS accounts. Your identity is a Nostr keypair that you create and control. You are responsible for keeping your private key (nsec) secure, backed up, and private. BitOS cannot recover a lost key, reset access, reverse actions signed with your key, or protect you if your key is shared, stolen, or exposed.",
        ),
        StaticSection(
            "3. Your content and conduct",
            "You are responsible for the content you publish and for how you use BitOS. You agree not to use BitOS to break the law or violate the rights of others; publish unlawful, abusive, fraudulent, or infringing content; send spam, malware, or deceptive or automated abuse; or interfere with relays, wallets, media hosts, or other third-party systems. Because Nostr is decentralized, BitOS cannot guarantee that content can be edited, removed, hidden, or fully deleted once it has been published to relays.",
        ),
        StaticSection(
            "4. Third-party services",
            "BitOS may connect you to relays, media providers, wallet software, Lightning services, and other third-party tools. Those services are not controlled by BitOS. Their own terms, privacy policies, availability, moderation, and fees apply. BitOS is not responsible for outages, data loss, content policies, payment issues, or security failures caused by third-party services.",
        ),
        StaticSection(
            "5. Availability and no warranty",
            "BitOS is provided \"as is\" and \"as available,\" without warranties of any kind, express or implied, including warranties of merchantability, fitness for a particular purpose, and non-infringement. You use BitOS at your own risk.",
        ),
        StaticSection(
            "6. Limitation of liability",
            "To the maximum extent permitted by law, the authors of BitOS are not liable for any damages arising from the use of, or inability to use, this software — including data loss, lost profits, or business interruption.",
        ),
        StaticSection(
            "7. Content and intellectual property",
            "You keep all rights to the content you create. By publishing to Nostr you make your events available to relays and other clients as the protocol requires. You are responsible for securing the rights needed to publish what you post.",
        ),
        StaticSection(
            "8. Stopping use",
            "You may stop using BitOS at any time. Because your identity is your key and your content lives on relays, leaving BitOS does not delete anything you have published.",
        ),
        StaticSection(
            "9. Changes to these Terms",
            "These Terms may be updated as the software evolves. Continued use after an update means you accept the revised Terms.",
        ),
        StaticSection(
            "10. Contact",
            "Questions about these Terms? Reach the project at bitos.space.",
        ),
    )
}
