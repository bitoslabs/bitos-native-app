import BusinessCore
import SwiftUI

/**
 * APP-020 static pages (spec §3.20): About · Privacy · Terms — the
 * complete legacy copy from shared `StaticPagesContent` through the
 * bridge, rendered as section cards (legacy Flutter static_pages parity).
 */
struct StaticPagesScreen: View {
    @State var page: String
    let onClose: () -> Void
    private let bridge = BusinessCoreBridge()

    init(initialPage: String = "about", onClose: @escaping () -> Void) {
        _page = State(initialValue: initialPage)
        self.onClose = onClose
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: BitOSTheme.Spacing.md) {
                ForEach(["about", "privacy", "terms"], id: \.self) { key in
                    Button(label(for: key)) { page = key }
                        .font(.system(size: 13, weight: page == key ? .heavy : .semibold))
                        .foregroundStyle(page == key ? BitOSTheme.accent : BitOSTheme.textSecondary)
                }
                Spacer()
                SheetCloseButton(action: onClose)
            }
            .padding(.horizontal, BitOSTheme.Spacing.screen)
            .padding(.vertical, BitOSTheme.Spacing.sm)

            ScrollView {
                VStack(spacing: BitOSTheme.Spacing.md) {
                    switch page {
                    case "privacy": privacyPage
                    case "terms": termsPage
                    default: aboutPage
                    }
                }
                .padding(.horizontal, BitOSTheme.Spacing.screen)
                .padding(.bottom, BitOSTheme.Spacing.xl)
            }
        }
        .background(BitOSTheme.background)
        .preferredColorScheme(BitOSTheme.preferredScheme)
    }

    private func label(for key: String) -> String {
        switch key {
        case "privacy": return "Privacy"
        case "terms": return "Terms"
        default: return "About"
        }
    }

    // ── About ───────────────────────────────────────────────────

    @ViewBuilder
    private var aboutPage: some View {
            HeroCard {
                VStack(spacing: BitOSTheme.Spacing.sm) {
                    Text(bridge.staticAboutHeadline())
                        .font(.system(size: 32, weight: .black))
                        .foregroundStyle(BitOSTheme.accent)
                    Text(bridge.staticAboutSubtitle())
                        .font(.system(size: 12))
                        .foregroundStyle(BitOSTheme.textTertiary)
                    Text(bridge.staticAboutHeroBody())
                        .font(.system(size: 14))
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .multilineTextAlignment(.center)
                }
            }
            ForEach(Array(bridge.staticAboutFeatures().enumerated()), id: \.offset) { _, entry in
                SectionCard(title: entry["title"] as? String ?? "", body: entry["body"] as? String ?? "")
            }
    }

    // ── Privacy ─────────────────────────────────────────────────

    @ViewBuilder
    private var privacyPage: some View {
            HeaderCard(title: bridge.staticPrivacyTitle(), updated: bridge.staticPrivacyUpdated(), intro: bridge.staticPrivacyIntro())
            SectionCard(title: bridge.staticPrivacySummaryTitle(), body: bridge.staticPrivacySummaryBody(), highlight: true)
            ForEach(Array(bridge.staticPrivacySections().enumerated()), id: \.offset) { _, entry in
                SectionCard(title: entry["title"] as? String ?? "", body: entry["body"] as? String ?? "")
            }
    }

    // ── Terms ───────────────────────────────────────────────────

    @ViewBuilder
    private var termsPage: some View {
            HeaderCard(title: bridge.staticTermsTitle(), updated: bridge.staticTermsUpdated(), intro: bridge.staticTermsIntro())
            SectionCard(title: bridge.staticTermsSummaryTitle(), body: bridge.staticTermsSummaryBody(), highlight: true)
            ForEach(Array(bridge.staticTermsSections().enumerated()), id: \.offset) { _, entry in
                SectionCard(title: entry["title"] as? String ?? "", body: entry["body"] as? String ?? "")
            }
    }

    // ── Pieces ──────────────────────────────────────────────────

    private struct HeaderCard: View {
        let title: String
        let updated: String
        let intro: String

        var body: some View {
            HeroCard {
                VStack(alignment: .leading, spacing: BitOSTheme.Spacing.xs) {
                    Text(title).font(.system(size: 22, weight: .heavy))
                    Text(updated).font(.system(size: 11)).foregroundStyle(BitOSTheme.textTertiary)
                    Text(intro).font(.system(size: 13)).foregroundStyle(BitOSTheme.textSecondary)
                }
            }
        }
    }

    private struct SectionCard: View {
        var title: String? = nil
        let bodyText: String
        var highlight = false

        var body: some View {
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.xs) {
                if let title {
                    Text(title).font(.system(size: 15, weight: .bold))
                }
                Text(bodyText)
                    .font(.system(size: 13))
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(BitOSTheme.Spacing.base)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(highlight ? BitOSTheme.accentContainer : BitOSTheme.surface)
            )
        }

        init(title: String? = nil, body text: String, highlight: Bool = false) {
            self.title = title
            self.bodyText = text
            self.highlight = highlight
        }
    }

    private struct HeroCard<Content: View>: View {
        @ViewBuilder let content: () -> Content

        var body: some View {
            content()
                .frame(maxWidth: .infinity)
                .padding(BitOSTheme.Spacing.base)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(BitOSTheme.surface)
                )
        }
    }
}
