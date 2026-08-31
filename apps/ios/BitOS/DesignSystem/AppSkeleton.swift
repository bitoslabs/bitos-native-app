import SwiftUI

/**
 * Skeleton block (§2.5 "Skeletons for every list/grid"): elevated→overlay
 * shimmer sweep. The animation honors `accessibilityReduceMotion` by
 * rendering the static base instead of sweeping.
 */
struct AppSkeleton: View {
    var height: CGFloat? = nil
    var cornerRadius: CGFloat = BitOSTheme.Radius.sm
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var phase: CGFloat = -1

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        shape
            .fill(BitOSTheme.surfaceElevated)
            .frame(height: height)
            .overlay(
                // Sweep highlight: clipped to the block, translating left→right.
                GeometryReader { geo in
                    LinearGradient(
                        colors: [
                            BitOSTheme.surfaceElevated,
                            BitOSTheme.surfaceOverlay,
                            BitOSTheme.surfaceElevated,
                        ],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    .frame(width: geo.size.width * 1.6)
                    .offset(x: phase * geo.size.width * 1.6)
                }
                .clipShape(shape)
            )
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(
                    .linear(duration: 1.4)
                    .repeatForever(autoreverses: false)
                ) {
                    phase = 0.6
                }
            }
            .accessibilityHidden(true)
    }
}

/// Two-line list-row skeleton with a hex leading glyph.
struct AppSkeletonRow: View {
    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.md) {
            AppSkeleton(height: 40, cornerRadius: BitOSTheme.Radius.md)
                .frame(width: 40)
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
                AppSkeleton(height: 12)
                    .frame(width: 140)
                AppSkeleton(height: 10)
                    .frame(width: 210)
            }
        }
        .padding(.horizontal, BitOSTheme.Spacing.base)
        .padding(.vertical, BitOSTheme.Spacing.md)
    }
}

/**
 * Matched empty state (§2.5): hex icon plate + title + message + one
 * primary CTA — the "explain why and offer one primary next action"
 * rule from ux-ui-flows §3.
 */
struct AppEmptyState: View {
    let title: String
    var message: String? = nil
    var actionTitle: String? = nil
    var onAction: (() -> Void)? = nil
    var icon: () -> AnyView = {
        AnyView(
            Text("⬡")
                .font(BitOSType.headlineMedium)
                .foregroundStyle(BitOSTheme.textLink)
        )
    }

    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.md) {
            ZStack {
                HexShape()
                    .fill(BitOSTheme.accent.opacity(0.16))
                    .frame(width: 56, height: 56)
                icon()
            }
            .frame(width: 56, height: 56)
            Text(title)
                .font(BitOSType.headlineSmall)
                .multilineTextAlignment(.center)
            if let message {
                Text(message)
                    .font(BitOSType.bodyMedium)
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .multilineTextAlignment(.center)
            }
            if let actionTitle, let onAction {
                AppButton(title: actionTitle, action: onAction)
                    .padding(.top, BitOSTheme.Spacing.xs)
            }
        }
        .padding(.horizontal, BitOSTheme.Spacing.xxxl)
        .padding(.vertical, BitOSTheme.Spacing.xxxl)
        .frame(maxWidth: .infinity)
    }
}

#Preview("Skeletons + empty state") {
    ScrollView {
        VStack(spacing: BitOSTheme.Spacing.base) {
            AppSkeletonRow()
            AppSkeletonRow()
            AppSkeleton(height: 180, cornerRadius: BitOSTheme.Radius.md)
            AppEmptyState(
                title: "Nothing saved yet",
                message: "Bookmarks live on your relays (kind 10000 set) — private to you, portable across apps.",
                actionTitle: "Browse the feed",
                onAction: {}
            )
        }
        .padding(BitOSTheme.Spacing.screen)
    }
    .background(BitOSTheme.background)
    .environment(\.colorScheme, .dark)
}
