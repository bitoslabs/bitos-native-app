import SwiftUI

/**
 * APP-006 Stories bar + viewer (iOS parity with Android).
 * Bar: gradient hex ring = unseen, muted = seen. Viewer: full-screen,
 * progress bars, auto-advance, tap left/right.
 */
struct StoriesBarView: View {
    let authors: [StoriesStore.StoryAuthorMirror]
    let publicAuthors: [StoriesStore.StoryAuthorMirror]
    let seenIds: Set<String>
    let onOpen: (StoriesStore.StoryAuthorMirror) -> Void
    let onCreateStory: () -> Void
    let onOpenPublicStories: () -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: BitOSTheme.Spacing.md) {
                CreateStoryCard(onClick: onCreateStory)
                ForEach(authors) { author in
                    StoryCardView(author: author, seenIds: seenIds) {
                        onOpen(author)
                    }
                }
                PublicStoriesButton(onClick: onOpenPublicStories)
                ForEach(publicAuthors) { author in
                    StoryCardView(author: author, seenIds: seenIds) {
                        onOpen(author)
                    }
                }
            }
            .padding(.horizontal, BitOSTheme.Spacing.screen)
        }
    }
}

private struct CreateStoryCard: View {
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            VStack(spacing: 0) {
                ZStack {
                    LinearGradient(
                        colors: [Color(hex: 0x39485A), Color(hex: 0x17202B)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    Circle()
                        .fill(BitOSTheme.accent)
                        .frame(width: 50, height: 50)
                        .overlay {
                            Circle().stroke(BitOSTheme.surface, lineWidth: 3)
                            Image(systemName: "plus")
                                .font(.system(size: 21, weight: .bold))
                                .foregroundStyle(BitOSTheme.surface)
                        }
                }
                .frame(height: 118)
                Text("Create story")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, BitOSTheme.Spacing.sm)
                    .padding(.vertical, BitOSTheme.Spacing.base)
            }
            .frame(width: 108, height: 154)
            .background(BitOSTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Create story")
    }
}

private struct StoryCardView: View {
    let author: StoriesStore.StoryAuthorMirror
    let seenIds: Set<String>
    let onClick: () -> Void

    private var hasUnseen: Bool {
        author.slides.contains { !seenIds.contains($0.id) }
    }

    var body: some View {
        Button(action: onClick) {
            ZStack(alignment: .topLeading) {
                if let imageUrl = author.slides.first?.imageUrl, let url = URL(string: imageUrl) {
                    AsyncImage(url: url) { phase in
                        if let image = phase.image {
                            image.resizable().scaledToFill()
                        } else {
                            BitOSTheme.surfaceElevated
                        }
                    }
                } else {
                    LinearGradient(
                        colors: storyCardGradient(author.slides.first?.gradient),
                        startPoint: .top,
                        endPoint: .bottom
                    )
                }
                LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .center, endPoint: .bottom)
                VStack {
                    Spacer()
                    Text(FeedFormat.shortPubkey(author.pubkey))
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(BitOSTheme.Spacing.sm)
                }
                Circle()
                    .strokeBorder(
                        hasUnseen ? AnyShapeStyle(LinearGradient(
                            colors: [BitOSTheme.accent, Color(red: 1.0, green: 0.42, blue: 0.62), BitOSTheme.accent],
                            startPoint: .topLeading, endPoint: .bottomTrailing
                        )) : AnyShapeStyle(BitOSTheme.divider),
                        lineWidth: 2.5
                    )
                    .frame(width: 36, height: 36)
                    .overlay { PubkeyAvatarView(pubkey: author.pubkey, size: 30).padding(2) }
                    .padding(BitOSTheme.Spacing.sm)
                if author.isPublicDiscovery {
                    Text("Public")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(.black.opacity(0.55), in: Capsule())
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                        .padding(BitOSTheme.Spacing.sm)
                }
            }
            .frame(width: 108, height: 154)
            .background(BitOSTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("View \(FeedFormat.shortPubkey(author.pubkey))'s story")
    }
}

private struct PublicStoriesButton: View {
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            VStack(spacing: BitOSTheme.Spacing.sm) {
                Image(systemName: "safari")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Color(hex: 0x24DFA0))
                    .frame(width: 30, height: 30)
                    .background(Color(hex: 0x24DFA0).opacity(0.12), in: Circle())
                Text("Public stories")
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .multilineTextAlignment(.center)
            }
            .frame(width: 88, height: 154)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Browse public stories")
    }
}

private func storyCardGradient(_ token: String?) -> [Color] {
    guard let token,
          let match = token.range(of: #"#([0-9a-fA-F]{6})>to>#([0-9a-fA-F]{6})"#, options: .regularExpression) else {
        return [BitOSTheme.surfaceElevated, BitOSTheme.background]
    }
    let parts = token[match].components(separatedBy: ">to>")
    guard parts.count == 2 else { return [BitOSTheme.surfaceElevated, BitOSTheme.background] }
    return parts.map {
        Color(hex: Int($0.replacingOccurrences(of: "#", with: ""), radix: 16) ?? 0)
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
