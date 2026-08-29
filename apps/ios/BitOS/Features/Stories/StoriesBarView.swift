import SwiftUI

/**
 * APP-006 Stories bar + viewer (iOS parity with Android).
 * Bar: gradient hex ring = unseen, muted = seen. Viewer: full-screen,
 * progress bars, auto-advance, tap left/right.
 */
struct StoriesBarView: View {
    let authors: [StoriesStore.StoryAuthorMirror]
    let seenIds: Set<String>
    let onOpen: (StoriesStore.StoryAuthorMirror) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: BitOSTheme.Spacing.md) {
                ForEach(authors) { author in
                    StoryAvatarView(author: author, seenIds: seenIds) {
                        onOpen(author)
                    }
                }
            }
            .padding(.horizontal, BitOSTheme.Spacing.screen)
        }
    }
}

private struct StoryAvatarView: View {
    let author: StoriesStore.StoryAuthorMirror
    let seenIds: Set<String>
    let onClick: () -> Void

    private var hasUnseen: Bool {
        author.slides.contains { !seenIds.contains($0.id) }
    }

    var body: some View {
        Button(action: onClick) {
            VStack(spacing: 4) {
                ZStack {
                    // Ring
                    Circle()
                        .strokeBorder(
                            hasUnseen ? AnyShapeStyle(LinearGradient(
                                colors: [BitOSTheme.accent, Color(red: 1.0, green: 0.42, blue: 0.62), BitOSTheme.accent],
                                startPoint: .topLeading, endPoint: .bottomTrailing
                            )) : AnyShapeStyle(BitOSTheme.divider),
                            lineWidth: 2.5
                        )
                    PubkeyAvatarView(pubkey: author.pubkey, size: 54)
                        .padding(3)
                }
                .frame(width: 64, height: 64)
                Text(FeedFormat.shortPubkey(author.pubkey))
                    .font(.system(size: 10))
                    .foregroundStyle(hasUnseen ? BitOSTheme.textPrimary : BitOSTheme.textTertiary)
                    .lineLimit(1)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("View \(FeedFormat.shortPubkey(author.pubkey))'s story")
    }
}

// MARK: - Viewer

struct StoryViewerView: View {
    let author: StoriesStore.StoryAuthorMirror
    let onSeen: (String) -> Void
    let onClose: () -> Void

    @State private var index = 0
    @State private var progress: Double = 0

    private var slide: StoriesStore.StorySlideMirror? {
        author.slides.indices.contains(index) ? author.slides[index] : nil
    }

    var body: some View {
        ZStack {
            // Background.
            if let gradient = slide?.gradient {
                let colors = parseGradient(gradient)
                LinearGradient(colors: colors, startPoint: .top, endPoint: .bottom)
                    .ignoresSafeArea()
            } else {
                BitOSTheme.background.ignoresSafeArea()
            }

            VStack(spacing: 0) {
                // Progress bars.
                HStack(spacing: 4) {
                    ForEach(Array(author.slides.enumerated()), id: \.element.id) { i, s in
                        GeometryReader { geo in
                            Capsule()
                                .fill(i < index ? BitOSTheme.accent : (i == index ? BitOSTheme.accent.opacity(max(0.05, progress)) : Color.white.opacity(0.2)))
                                .frame(height: 3)
                        }
                        .frame(height: 3)
                    }
                }
                .padding(.horizontal, BitOSTheme.Spacing.base)
                .padding(.top, BitOSTheme.Spacing.lg)

                // Header.
                HStack(spacing: BitOSTheme.Spacing.sm) {
                    PubkeyAvatarView(pubkey: author.pubkey, size: 32)
                    Text(FeedFormat.shortPubkey(author.pubkey))
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.white)
                    Spacer()
                    Button(action: onClose) {
                        Image(systemName: AppIcons.close)
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(.white)
                    }
                    .accessibilityLabel("Close story")
                }
                .padding(.horizontal, BitOSTheme.Spacing.base)
                .padding(.top, BitOSTheme.Spacing.md)

                Spacer()

                // Content.
                Text(slide?.content ?? "")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, BitOSTheme.Spacing.xl)

                Spacer()

                // Tap zones.
                HStack(spacing: 0) {
                    Color.clear
                        .contentShape(Rectangle())
                        .onTapGesture {
                            if index > 0 {
                                index -= 1
                                progress = 0
                            }
                        }
                        .accessibilityLabel("Previous slide")
                    Color.clear
                        .contentShape(Rectangle())
                        .onTapGesture {
                            if index < author.slides.count - 1 {
                                index += 1
                                progress = 0
                            } else {
                                onClose()
                            }
                        }
                        .accessibilityLabel("Next slide")
                }
                .frame(height: 200)
            }
        }
        .task(id: slide?.id) {
            guard let slide else { return }
            onSeen(slide.id)
            while progress < 1.0 {
                try? await Task.sleep(nanoseconds: 50_000_000)
                progress += 0.05 / 5.0
            }
            if index < author.slides.count - 1 {
                index += 1
                progress = 0
            } else {
                onClose()
            }
        }
    }

    private func parseGradient(_ token: String) -> [Color] {
        let match = token.range(of: #"#([0-9a-fA-F]{6})>to>#([0-9a-fA-F]{6})"#, options: .regularExpression)
        guard let match else { return [BitOSTheme.background, BitOSTheme.background] }
        let sub = token[match]
        let parts = sub.components(separatedBy: ">to>")
        guard parts.count == 2 else { return [BitOSTheme.background, BitOSTheme.background] }
        return [
            Color(hex: Int(parts[0].trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "#", with: ""), radix: 16) ?? 0),
            Color(hex: Int(parts[1].trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "#", with: ""), radix: 16) ?? 0),
        ]
    }
}

extension Color {
    init(hex: Int) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0
        )
    }
}
