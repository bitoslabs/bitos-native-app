import SwiftUI

/**
 * APP-006 Stories bar + viewer (web `StoriesBar`/`StoryViewer` parity).
 * Bar: gradient hex ring = unseen, muted = seen. Viewer: full-screen 9:16
 * canvas, white progress bars, header with hex-ringed avatar, auto-advance
 * (5 s image / 7 s text), left-third / right-two-thirds tap zones.
 */

/// Signature story-ring gradient (web `from-primary-500 via-accent-500
/// to-warm-500`, app palette): unseen stories / own story. Computed so the
/// in-app accent override stays live.
private var unseenRing: AnyShapeStyle {
    AnyShapeStyle(LinearGradient(
        colors: [BitOSTheme.accent, Color(red: 1.0, green: 0.42, blue: 0.62), BitOSTheme.accent],
        startPoint: .topLeading, endPoint: .bottomTrailing
    ))
}

/// Layered hex story ring (web `story-ring-frame hex-clip` parity): a 3 pt
/// gradient (unseen) or muted (seen) hex ring, a 2 pt inner gap, then the hex
/// avatar — the ring shape always matches the avatar clip.
private struct StoryHexRingAvatar: View {
    let pubkey: String
    let avatarSize: CGFloat
    let ring: AnyShapeStyle
    var inner: Color = BitOSTheme.surface

    var body: some View {
        HexShape()
            .fill(ring)
            .frame(width: avatarSize + 10, height: avatarSize + 10)
            .overlay(
                HexShape()
                    .fill(inner)
                    .frame(width: avatarSize + 4, height: avatarSize + 4)
                    .overlay(PubkeyAvatarView(pubkey: pubkey, size: avatarSize))
            )
            .accessibilityHidden(true)
    }
}

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
                // Web parity: video tiles preview the poster, image tiles the
                // first image; a play badge marks video slides.
                if let latest = author.slides.first,
                   let preview = latest.videoPoster ?? latest.imageUrls.first ?? latest.imageUrl,
                   let url = URL(string: preview) {
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
                    // Web parity: gradient tiles preview the note text.
                    Text(author.slides.first { !$0.content.isEmpty }?.content ?? FeedFormat.shortPubkey(author.pubkey))
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(.white)
                        .shadow(color: .black.opacity(0.45), radius: 1, y: 1)
                        .lineLimit(4)
                        .multilineTextAlignment(.center)
                        .padding(BitOSTheme.Spacing.md)
                        .padding(.bottom, 20)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
                // Hex ring (web `hex-clip` parity): gradient = unseen, muted = seen.
                StoryHexRingAvatar(
                    pubkey: author.pubkey,
                    avatarSize: 34,
                    ring: hasUnseen ? unseenRing : AnyShapeStyle(BitOSTheme.divider)
                )
                .padding(BitOSTheme.Spacing.sm)
                if author.isPublicDiscovery || author.slides.first?.videoUrl != nil {
                    VStack(alignment: .trailing, spacing: 6) {
                        if author.slides.first?.videoUrl != nil {
                            Image(systemName: AppIcons.play)
                                .font(.system(size: 9, weight: .bold))
                                .foregroundStyle(.white)
                                .frame(width: 24, height: 24)
                                .background(.black.opacity(0.55), in: Circle())
                                .accessibilityLabel("Video story")
                        }
                        if author.isPublicDiscovery {
                            Text("Public")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 3)
                                .background(.black.opacity(0.55), in: Capsule())
                        }
                    }
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
    @State private var imageIndex = 0
    @State private var progress: Double = 0
    @State private var paused = false
    @State private var imageFailed = false
    @State private var revealed = false
    @State private var measuredVideoSeconds: Double?

    private var slide: StoriesStore.StorySlideMirror? {
        author.slides.indices.contains(index) ? author.slides[index] : nil
    }

    /// All images on the current slide — carousels get one timer per image.
    private var images: [String] { slide?.imageUrls ?? [] }

    /// Video slides replace the image carousel entirely (web parity).
    private var isVideo: Bool { slide?.videoUrl != nil && images.isEmpty }

    /// Sensitive image slides stay blurred until the viewer taps to reveal.
    private var hidden: Bool { slide?.sensitive == true && !images.isEmpty && !revealed }

    /// Web parity: images 5 s per carousel frame, text-only 7 s, video its
    /// measured (or imeta-declared) duration capped at 60 s / 15 s fallback.
    private var segmentSeconds: Double {
        if isVideo {
            let seconds = measuredVideoSeconds ?? slide.map { Double($0.videoDurationMs ?? 0) / 1000 } ?? 0
            return min(seconds > 0 ? seconds : 15, 60)
        }
        return images.isEmpty ? 7 : 5
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            // Phone-portrait fills the safe area; wider screens (iPad)
            // letterbox a 9:16 canvas (web `aspect-[9/16]` parity).
            GeometryReader { geo in
                let wide = geo.size.width / geo.size.height > 9.0 / 16.0
                Group {
                    if wide {
                        canvas.aspectRatio(9.0 / 16.0, contentMode: .fit)
                    } else {
                        canvas
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
        }
        // Restart per carousel frame too — every image gets a full segment.
        .task(id: "\(slide?.id ?? "")#\(imageIndex)") {
            guard let slide else { return }
            imageFailed = false
            onSeen(slide.id)
            while progress < 1.0 {
                try? await Task.sleep(nanoseconds: 50_000_000)
                if paused { continue }
                progress += 0.05 / segmentSeconds
            }
            advance()
        }
        .onChange(of: index) { _ in
            // New slide: restart the carousel and re-hide sensitive media.
            imageIndex = 0
            revealed = false
            measuredVideoSeconds = nil
        }
    }

    /// Carousel-first navigation (web `advance`/`back` parity).
    private func advance() {
        if imageIndex < images.count - 1 {
            progress = 0
            imageIndex += 1
        } else if index < author.slides.count - 1 {
            progress = 0
            index += 1
        } else {
            onClose()
        }
    }

    private func back() {
        if imageIndex > 0 {
            progress = 0
            imageIndex -= 1
        } else if index > 0 {
            progress = 0
            index -= 1
        }
    }

    private var canvas: some View {
        ZStack {
            slideBackground

            // Tap zones (web: left third = previous, right two thirds = next)
            // sit above the media but below the header chrome.
            GeometryReader { geo in
                HStack(spacing: 0) {
                    Color.clear
                        .frame(width: geo.size.width / 3)
                        .contentShape(Rectangle())
                        .onTapGesture { back() }
                        .accessibilityLabel("Previous slide")
                    Color.clear
                        .frame(maxWidth: .infinity)
                        .contentShape(Rectangle())
                        .onTapGesture { advance() }
                        .accessibilityLabel("Next slide")
                }
            }

            // Sensitive reveal sits above the tap zones so its tap wins.
            if hidden {
                sensitiveReveal
            }

            VStack(spacing: 0) {
                progressBars
                carouselDots
                header
                Spacer()
            }

            caption
            slideCounter
        }
        .clipped()
    }

    // MARK: Slide backdrop

    @ViewBuilder
    private var slideBackground: some View {
        if isVideo, let videoUrl = slide?.videoUrl.flatMap(URL.init(string:)) {
            ZStack {
                if let poster = slide?.videoPoster.flatMap(URL.init(string:)) {
                    AsyncImage(url: poster) { phase in
                        if let image = phase.image {
                            image.resizable().scaledToFill()
                        }
                    }
                }
                StoryVideoLayer(
                    url: videoUrl,
                    paused: paused || hidden,
                    onEnded: { advance() },
                    onDurationMeasured: { measuredVideoSeconds = $0 }
                )
            }
        } else if images.indices.contains(imageIndex),
                  let url = URL(string: images[imageIndex]), !imageFailed {
            slideImage(url)
                .blur(radius: hidden ? 30 : 0)
                .overlay(Color.black.opacity(hidden ? 0.5 : 0))
                .clipped()
        } else {
            gradientBackdrop
        }
    }

    /// GIF attachments animate; still images load through AsyncImage.
    @ViewBuilder
    private func slideImage(_ url: URL) -> some View {
        let isGif = url.path.lowercased().hasSuffix(".gif")
        if isGif {
            StoryGifView(url: url)
        } else {
            AsyncImage(url: url) { phase in
                if let image = phase.image {
                    image.resizable().scaledToFill()
                } else if phase.error != nil {
                    LinearGradient(colors: gradientColors, startPoint: .top, endPoint: .bottom)
                        .onAppear { imageFailed = true }
                } else {
                    // Loading: gradient only, no text (web shows the bare pane).
                    LinearGradient(colors: gradientColors, startPoint: .top, endPoint: .bottom)
                }
            }
        }
    }

    private var gradientColors: [Color] {
        slide?.gradient.map { parseGradient($0) } ?? [BitOSTheme.background, BitOSTheme.background]
    }

    /// Text-only slides render their content centered on the slide gradient;
    /// a broken image falls back to the same text (web `Image unavailable`).
    @ViewBuilder
    private var gradientBackdrop: some View {
        let text = slide?.content ?? ""
        ZStack {
            LinearGradient(colors: gradientColors, startPoint: .top, endPoint: .bottom)
            Text(text.isEmpty && imageFailed ? "Image unavailable" : text)
                .font(.system(size: 24, weight: .bold))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .padding(.horizontal, BitOSTheme.Spacing.xl)
        }
    }

    /// Sensitive media gate (web parity): blur + tap-to-reveal.
    private var sensitiveReveal: some View {
        Button {
            revealed = true
        } label: {
            VStack(spacing: BitOSTheme.Spacing.base) {
                AppIcons.image(for: AppIcons.eyeClosed)
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 56, height: 56)
                    .background(Circle().fill(Color.black.opacity(0.6)))
                    .overlay(Circle().stroke(Color.white.opacity(0.2)))
                Text("Sensitive content · tap to view")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black.opacity(0.3))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Sensitive content. Tap to view")
    }

    // MARK: Chrome

    /// White-on-white/30 progress bars with an animated fill (web parity).
    private var progressBars: some View {
        HStack(spacing: 4) {
            ForEach(Array(author.slides.enumerated()), id: \.element.id) { i, _ in
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Rectangle().fill(Color.white.opacity(0.3))
                        Rectangle()
                            .fill(Color.white)
                            .frame(width: geo.size.width * (i < index ? 1 : (i == index ? min(max(progress, 0), 1) : 0)))
                    }
                }
                .clipShape(Capsule())
                .frame(height: 3)
            }
        }
        .padding(.horizontal, BitOSTheme.Spacing.base)
        .padding(.top, BitOSTheme.Spacing.sm)
    }

    /// Carousel dots for multi-image slides (web parity, tappable).
    @ViewBuilder
    private var carouselDots: some View {
        if images.count > 1 {
            HStack(spacing: 6) {
                ForEach(images.indices, id: \.self) { i in
                    Circle()
                        .fill(Color.white.opacity(i == imageIndex ? 1 : 0.4))
                        .frame(width: i == imageIndex ? 16 : 6, height: 6)
                        .onTapGesture { imageIndex = i }
                        .accessibilityLabel("Image \(i + 1) of \(images.count)")
                }
            }
            .padding(.top, 6)
        }
    }

    private var header: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            StoryHexRingAvatar(
                pubkey: author.pubkey,
                avatarSize: 32,
                ring: unseenRing,
                inner: Color.black.opacity(0.45)
            )
            VStack(alignment: .leading, spacing: 1) {
                Text(FeedFormat.shortPubkey(author.pubkey))
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white)
                if let slide {
                    Text(FeedFormat.timeAgo(createdAt: slide.createdAt))
                        .font(.system(size: 11))
                        .foregroundStyle(.white.opacity(0.7))
                }
            }
            Spacer()
            Button {
                paused.toggle()
            } label: {
                Image(systemName: paused ? AppIcons.play : AppIcons.pause)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white.opacity(0.8))
                    .frame(width: 32, height: 32)
                    .contentShape(Circle())
            }
            .accessibilityLabel(paused ? "Play" : "Pause")
            Button(action: onClose) {
                Image(systemName: AppIcons.close)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(.white.opacity(0.8))
                    .frame(width: 32, height: 32)
                    .contentShape(Circle())
            }
            .accessibilityLabel("Close story")
        }
        .padding(.horizontal, BitOSTheme.Spacing.md)
        .padding(.top, BitOSTheme.Spacing.sm)
    }

    /// Image slides keep their caption over a bottom scrim (web parity).
    @ViewBuilder
    private var caption: some View {
        if let text = slide?.content, !text.isEmpty, !images.isEmpty, !imageFailed, !hidden {
            VStack {
                Spacer()
                Text(text)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, BitOSTheme.Spacing.lg)
                    .padding(.top, BitOSTheme.Spacing.xl)
                    .padding(.bottom, BitOSTheme.Spacing.lg)
                    .background(
                        LinearGradient(colors: [.clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom),
                        alignment: .bottom
                    )
            }
            .allowsHitTesting(false)
        }
    }

    /// "1 / 3 · 1/2" pill (web parity), multi-slide authors only.
    @ViewBuilder
    private var slideCounter: some View {
        if author.slides.count > 1 {
            let carousel = images.count > 1 ? " · \(imageIndex + 1)/\(images.count)" : ""
            VStack {
                Spacer()
                Text("\(index + 1) / \(author.slides.count)\(carousel)")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.8))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(Color.black.opacity(0.4), in: Capsule())
                    .padding(.bottom, 8)
            }
            .allowsHitTesting(false)
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
