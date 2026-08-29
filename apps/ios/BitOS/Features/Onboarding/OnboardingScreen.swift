import BusinessCore
import SwiftUI

/// `hasOnboarded` persistence (UserDefaults, device-local).
enum OnboardingPrefs {
    private static let key = "bitos_has_onboarded"

    static var hasOnboarded: Bool {
        UserDefaults.standard.bool(forKey: key)
    }

    static func markOnboarded() {
        UserDefaults.standard.set(true, forKey: key)
    }
}

/// One onboarding page from the shared contract.
private struct OnboardingPageCard: View {
    let page: SharedOnboardingPage
    @State private var appeared = false

    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.xl) {
            ZStack {
                Circle().fill(BitOSTheme.accentContainer).frame(width: 88, height: 88)
                AppIcons.image(for: iconToken)
                    .font(.system(size: 34))
                    .foregroundStyle(BitOSTheme.accent)
            }
            VStack(spacing: BitOSTheme.Spacing.md) {
                Text(page.title)
                    .font(.system(size: 24, weight: .heavy))
                    .foregroundStyle(BitOSTheme.textPrimary)
                    .multilineTextAlignment(.center)
                Text(page.body)
                    .font(.system(size: 14))
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, BitOSTheme.Spacing.xl)
            }
        }
        .opacity(appeared ? 1 : 0)
        .offset(y: appeared ? 0 : 12)
        .onAppear {
            withAnimation(.easeOut(duration: 0.3).delay(0.1)) { appeared = true }
        }
    }

    private var iconToken: String {
        switch page.iconToken {
        case "qrCode": return AppIcons.qrCode
        case "zap": return AppIcons.zap
        default: return AppIcons.globe
        }
    }
}

/// Bridge mirror of the shared onboarding page.
struct SharedOnboardingPage: Identifiable, Equatable {
    let id: String
    let iconToken: String
    let title: String
    let body: String

    static func pages(bridge: BusinessCoreBridge) -> [SharedOnboardingPage] {
        bridge.onboardingPages().map { entry in
            SharedOnboardingPage(
                id: entry.id,
                iconToken: entry.iconToken,
                title: entry.title,
                body: entry.body
            )
        }
    }
}

/// Local mirror type for the bridge list-of-map return.
private extension BusinessCoreBridge {
    func onboardingPages() -> [SharedOnboardingPage] {
        let raw: [Any] = onboardingContent()
        return raw.compactMap { element in
            guard let map = element as? [String: Any],
                  let id = map["id"] as? String,
                  let icon = map["icon"] as? String,
                  let title = map["title"] as? String,
                  let body = map["body"] as? String else { return nil }
            return SharedOnboardingPage(id: id, iconToken: icon, title: title, body: body)
        }
    }
}

/**
 * APP-002 onboarding carousel (legacy Flutter `onboarding_view` parity):
 * 4 pages from the shared `OnboardingContent` — dot indicator, Next/Back,
 * Skip → auth, Get Started on the last page.
 */
struct OnboardingScreen: View {
    let onDone: () -> Void
    @State private var pageIndex = 0
    private let pages = SharedOnboardingPage.pages(bridge: BusinessCoreBridge())

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                if pageIndex < pages.count - 1 {
                    Button("Skip", action: onDone)
                        .font(.system(size: 14))
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .padding(BitOSTheme.Spacing.base)
                }
            }
            TabView(selection: $pageIndex) {
                ForEach(Array(pages.enumerated()), id: \.element.id) { index, page in
                    OnboardingPageCard(page: page)
                        .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            VStack(spacing: BitOSTheme.Spacing.md) {
                // Dots.
                HStack(spacing: 8) {
                    ForEach(pages.indices, id: \.self) { index in
                        Capsule()
                            .fill(index == pageIndex ? BitOSTheme.accent : BitOSTheme.textTertiary.opacity(0.4))
                            .frame(width: index == pageIndex ? 24 : 8, height: 8)
                            .animation(.spring(response: 0.3, dampingFraction: 0.7), value: pageIndex)
                    }
                }
                Text("\(pageIndex + 1) of \(pages.count)")
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.textTertiary)
                Button {
                    if pageIndex < pages.count - 1 {
                        withAnimation(.easeInOut(duration: 0.35)) { pageIndex += 1 }
                    } else {
                        onDone()
                    }
                } label: {
                    Text(pageIndex < pages.count - 1 ? "Next" : "Get Started")
                        .font(.system(size: 15, weight: .heavy))
                        .foregroundStyle(BitOSTheme.background)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Capsule().fill(BitOSTheme.accent))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(pageIndex < pages.count - 1 ? "Next page" : "Get started")
            }
            .padding(.horizontal, BitOSTheme.Spacing.screen)
            .padding(.bottom, BitOSTheme.Spacing.xl)
        }
        .background(BitOSTheme.background)
        .preferredColorScheme(.dark)
    }
}
