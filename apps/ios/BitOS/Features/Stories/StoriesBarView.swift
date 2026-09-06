import BusinessCore
import SwiftUI

/**
 * APP-006 Stories bar + viewer (web `StoriesBar`/`StoryViewer` parity).
 * Bar: gradient hex ring = unseen, muted = seen. Viewer: full-screen 9:16
 * canvas, white progress bars, header with hex-ringed avatar, auto-advance
 * (5 s image / 7 s text), left-third / right-two-thirds tap zones.
 */

/// Display name (web `nameFor` parity): profile name over the raw key.
private func storyDisplayName(
    _ pubkey: String,
    _ profileFor: (String) -> ProfileMetadata?
) -> String {
    guard let profile = profileFor(pubkey) else { return FeedFormat.shortPubkey(pubkey) }
    let candidate = [profile.displayName, profile.name]
        .compactMap { $0 }
        .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        .first { !$0.isEmpty }
    return candidate ?? FeedFormat.shortPubkey(pubkey)
}

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
    var picture: String? = nil
    var hasLightning: Bool = false

    var body: some View {
        HexShape()
            .fill(ring)
            .frame(width: avatarSize + 10, height: avatarSize + 10)
            .overlay(
                HexShape()
                    .fill(inner)
                    .frame(width: avatarSize + 4, height: avatarSize + 4)
                    .overlay(PubkeyAvatarView(pubkey: pubkey, size: avatarSize, picture: picture, hasLightning: hasLightning))
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
    /// Kind-0 metadata lookup for display names + avatar pictures.
    var profileFor: (String) -> ProfileMetadata? = { _ in nil }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: BitOSTheme.Spacing.md) {
                CreateStoryCard(onClick: onCreateStory)
                ForEach(authors) { author in
                    StoryCardView(author: author, seenIds: seenIds, profileFor: profileFor) {
                        onOpen(author)
                    }
                }
                PublicStoriesButton(onClick: onOpenPublicStories)
                ForEach(publicAuthors) { author in
                    StoryCardView(author: author, seenIds: seenIds, profileFor: profileFor) {
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
    let profileFor: (String) -> ProfileMetadata?
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
                    HStack(spacing: 3) {
                        Text(storyDisplayName(author.pubkey, profileFor))
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                        if !(profileFor(author.pubkey)?.nip05?.isEmpty ?? true) {
                            AppIcons.image(for: AppIcons.checkCircle)
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(.white)
                                .accessibilityLabel("NIP-05 identity claim")
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(BitOSTheme.Spacing.sm)
                }
                // Hex ring (web `hex-clip` parity): gradient = unseen, muted = seen.
                StoryHexRingAvatar(
                    pubkey: author.pubkey,
                    avatarSize: 34,
                    ring: hasUnseen ? unseenRing : AnyShapeStyle(BitOSTheme.divider),
                    picture: profileFor(author.pubkey)?.picture,
                    hasLightning: !(profileFor(author.pubkey)?.lud16 ?? "").isEmpty
                )
                .padding(BitOSTheme.Spacing.sm)
                if author.isPublicDiscovery || author.slides.first?.videoUrl != nil {
                    VStack(alignment: .trailing, spacing: 6) {
                        if author.slides.first?.videoUrl != nil {
                            AppIcons.image(for: AppIcons.play)
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
                AppIcons.image(for: AppIcons.globe)
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
    /// Engagement lookup for the CURRENT slide (viewer-side, per-slide ids).
    var interactionFor: (String) -> StoriesStore.StoryInteractionMirror? = { _ in nil }
    /// Kind-0 metadata lookup for display names + avatar pictures.
    var profileFor: (String) -> ProfileMetadata? = { _ in nil }
    /// True for the signed-in account's own slides (delete + view count).
    var isMine = false
    /// Signed-in state gates the reply input (web "Sign in to reply").
    var hasIdentity = false
    var onLike: (StoriesStore.StorySlideMirror) -> Void = { _ in }
    /// Unlike publishes a kind-5 delete of MY like event id.
    var onUnlike: (String) -> Void = { _ in }
    var onReply: (StoriesStore.StorySlideMirror, String) -> Void = { _, _ in }
    var onDm: (String) -> Void = { _ in }
    /// Zap request carrying the CURRENT slide (its id is the zap target).
    var onZap: (StoriesStore.StorySlideMirror) -> Void = { _ in }
    var onDelete: (StoriesStore.StorySlideMirror) -> Void = { _ in }
    let onSeen: (String) -> Void
    let onClose: () -> Void

    @State private var index = 0
    @State private var imageIndex = 0
    @State private var progress: Double = 0
    @State private var paused = false
    @State private var imageFailed = false
    @State private var revealed = false
    @State private var measuredVideoSeconds: Double?
    // Engagement UI state (web parity): reply/DM modes, activity sheet,
    // delete confirm, double-tap heart burst.
    @State private var replyMode = "reply"
    @State private var replyText = ""
    @State private var activityOpen = false
    @State private var confirmDeleteOpen = false
    @State private var burstAt: CGPoint?
    @State private var burstScale: Double = 0.55

    private var slide: StoriesStore.StorySlideMirror? {
        author.slides.indices.contains(index) ? author.slides[index] : nil
    }

    private var interaction: StoriesStore.StoryInteractionMirror? {
        slide.flatMap { interactionFor($0.id) }
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

    /// Like the current slide (double-tap + heart button path).
    private func likeCurrent() {
        guard hasIdentity, let slide else { return }
        if let interaction, interaction.likedByMe, let eventId = interaction.myLikeEventId {
            onUnlike(eventId)
        } else {
            onLike(slide)
        }
    }

    private var canvas: some View {
        ZStack {
            slideBackground

            // Tap zones (web: left third = previous, right two thirds = next;
            // double-tap = like burst) sit above the media but below the
            // header chrome and the interactions bar.
            GeometryReader { geo in
                HStack(spacing: 0) {
                    Color.clear
                        .frame(width: geo.size.width / 3)
                        .contentShape(Rectangle())
                        .onTapGesture(count: 2) { location in
                            burstAt = location
                            likeCurrent()
                        }
                        .onTapGesture { back() }
                        .accessibilityLabel("Previous slide")
                    Color.clear
                        .frame(maxWidth: .infinity)
                        .contentShape(Rectangle())
                        .onTapGesture(count: 2) { location in
                            burstAt = location
                            likeCurrent()
                        }
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
                interactionsBar
            }

            caption

            // Double-tap heart burst (web like-burst, simplified).
            if let burstAt {
                AppIcons.image(for: AppIcons.heartFill)
                    .font(.system(size: 84))
                    .foregroundStyle(Color(red: 1.0, green: 0.30, blue: 0.42))
                    .position(burstAt)
                    .scaleEffect(burstScale)
                    .shadow(color: .black.opacity(0.4), radius: 12, y: 4)
                    .task(id: burstAt) {
                        burstScale = 0.55
                        withAnimation(.easeOut(duration: 0.16)) { burstScale = 1.15 }
                        try? await Task.sleep(nanoseconds: 90_000_000)
                        withAnimation(.easeInOut(duration: 0.09)) { burstScale = 1.0 }
                        try? await Task.sleep(nanoseconds: 430_000_000)
                        self.burstAt = nil
                    }
                    .allowsHitTesting(false)
            }
        }
        .clipped()
        .sheet(isPresented: $activityOpen) {
            StoryActivitySheet(interaction: interaction, profileFor: profileFor)
                .presentationDetents([.medium, .large])
        }
        .alert("Delete story", isPresented: $confirmDeleteOpen) {
            Button("Delete", role: .destructive) {
                if let slide {
                    onDelete(slide)
                    confirmDeleteOpen = false
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Delete this story from your profile? BitOS will publish a delete event to your relays and remove this story from your device.")
        }
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
                inner: Color.black.opacity(0.45),
                picture: profileFor(author.pubkey)?.picture,
                hasLightning: !(profileFor(author.pubkey)?.lud16 ?? "").isEmpty
            )
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 3) {
                    Text(storyDisplayName(author.pubkey, profileFor))
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    if !(profileFor(author.pubkey)?.nip05?.isEmpty ?? true) {
                        AppIcons.image(for: AppIcons.checkCircle)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(BitOSTheme.accent)
                            .accessibilityLabel("NIP-05 identity claim")
                    }
                }
                if let slide {
                    Text(FeedFormat.timeAgo(createdAt: slide.createdAt))
                        .font(.system(size: 11))
                        .foregroundStyle(.white.opacity(0.7))
                }
            }
            Spacer()
            if isMine {
                Button {
                    confirmDeleteOpen = true
                } label: {
                    AppIcons.image(for: AppIcons.delete)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.white.opacity(0.8))
                        .frame(width: 32, height: 32)
                        .contentShape(Circle())
                }
                .accessibilityLabel("Delete story")
            }
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
                AppIcons.image(for: AppIcons.close)
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

    // MARK: Interactions (web parity)

    /// Bottom bar: slide counter, Reply/DM modes + privacy hint, input with
    /// send/like/zap/activity actions, and the counts row.
    @ViewBuilder
    private var interactionsBar: some View {
        VStack(spacing: 6) {
            if author.slides.count > 1 {
                let carousel = images.count > 1 ? " · \(imageIndex + 1)/\(images.count)" : ""
                Text("\(index + 1) / \(author.slides.count)\(carousel)")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.8))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(Color.black.opacity(0.4), in: Capsule())
            }
            HStack {
                HStack(spacing: 0) {
                    modeChip("Reply", active: replyMode == "reply") {
                        replyMode = "reply"
                        paused = true
                    }
                    modeChip("DM", active: replyMode == "dm") {
                        replyMode = "dm"
                        paused = true
                    }
                }
                .background(Color.white.opacity(0.1), in: Capsule())
                Spacer()
                Text(replyMode == "reply" ? "Visible in story activity" : "Only sent privately")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.75))
            }
            HStack(spacing: 2) {
                TextField(
                    "",
                    text: $replyText,
                    prompt: Text(hasIdentity
                        ? (replyMode == "reply" ? "Reply to \(storyDisplayName(author.pubkey, profileFor))…" : "Message \(storyDisplayName(author.pubkey, profileFor)) privately…")
                        : "Sign in to reply")
                        .font(.system(size: 13))
                        .foregroundStyle(.white.opacity(0.6))
                )
                .font(.system(size: 13))
                .foregroundStyle(.white)
                .textFieldStyle(.plain)
                .disabled(!hasIdentity)
                .submitLabel(.send)
                .onSubmit(sendReply)
                .padding(.horizontal, BitOSTheme.Spacing.base)
                .frame(height: 40)
                .background(Color.white.opacity(0.1), in: Capsule())
                .overlay(Capsule().stroke(Color.white.opacity(0.15)))
                .onChange(of: replyText) { _, text in
                    // Web parity: typing pauses the auto-advance.
                    paused = hasIdentity && !text.isEmpty ? true : paused
                }
                actionButton(
                    icon: replyMode == "reply" ? AppIcons.comment : AppIcons.send,
                    label: replyMode == "reply" ? "Reply to story" : "Message privately",
                    action: sendReply
                )
                actionButton(
                    icon: interaction?.likedByMe == true ? AppIcons.heartFill : AppIcons.heart,
                    label: interaction?.likedByMe == true ? "Unlike story" : "Like story",
                    tint: interaction?.likedByMe == true ? Color(red: 1.0, green: 0.30, blue: 0.42) : .white,
                    action: likeCurrent
                )
                actionButton(
                    icon: AppIcons.zap,
                    label: "Zap sats to this story",
                    tint: Color(red: 1.0, green: 0.76, blue: 0.29),
                    action: { if let slide { onZap(slide) } }
                )
                actionButton(
                    icon: AppIcons.arrowUp,
                    label: "View activity",
                    action: { activityOpen = true }
                )
            }
            HStack(spacing: BitOSTheme.Spacing.md) {
                countButton(icon: AppIcons.heartFill, label: "\(interaction?.likeCount ?? 0)") { activityOpen = true }
                if (interaction?.zapSats ?? 0) > 0 || (interaction?.zapCount ?? 0) > 0 {
                    countButton(
                        icon: AppIcons.zap,
                        label: (interaction?.zapSats ?? 0) > 0
                            ? "\(FeedFormat.count(Int(interaction?.zapSats ?? 0))) sats"
                            : "\(interaction?.zapCount ?? 0)",
                        tint: Color(red: 1.0, green: 0.76, blue: 0.29)
                    ) {
                        if let slide { onZap(slide) }
                    }
                }
                if isMine {
                    countButton(icon: AppIcons.eye, label: "\(interaction?.viewCount ?? 0)") { activityOpen = true }
                }
                countButton(icon: AppIcons.comment, label: "\(interaction?.replyCount ?? 0)") { activityOpen = true }
            }
        }
        .padding(.horizontal, BitOSTheme.Spacing.md)
        .padding(.top, BitOSTheme.Spacing.lg)
        .padding(.bottom, BitOSTheme.Spacing.sm)
        .background(
            LinearGradient(colors: [.black.opacity(0.75), .black.opacity(0.25), .clear], startPoint: .top, endPoint: .bottom),
            alignment: .bottom
        )
        .ignoresSafeArea(edges: .bottom)
    }

    private func sendReply() {
        guard hasIdentity, let slide else { return }
        let text = replyText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        if replyMode == "dm" {
            onDm(text)
        } else {
            onReply(slide, text)
        }
        replyText = ""
    }

    private func modeChip(_ label: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(active ? .black : .white.opacity(0.85))
                .padding(.horizontal, 12)
                .padding(.vertical, 4)
                .background(active ? Color.white : .clear, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private func actionButton(icon: String, label: String, tint: Color = .white, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(tint.opacity(0.85))
                .frame(width: 40, height: 40)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private func countButton(icon: String, label: String, tint: Color = .white.opacity(0.85), action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 12, weight: .semibold))
                Text(label)
                    .font(.system(size: 11, weight: .semibold))
            }
            .foregroundStyle(tint)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(.white.opacity(0.1), in: Capsule())
        }
        .buttonStyle(.plain)
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

/// Story activity sheet (web `StoryActivity` parity): likes + replies.
private struct StoryActivitySheet: View {
    let interaction: StoriesStore.StoryInteractionMirror?
    var profileFor: (String) -> ProfileMetadata? = { _ in nil }

    var body: some View {
        NavigationStack {
            List {
                let likes = interaction?.likes ?? []
                let replies = interaction?.replies ?? []
                if likes.isEmpty && replies.isEmpty {
                    Text("No activity yet")
                        .font(.system(size: 14))
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                Section("Reactions") {
                    ForEach(likes, id: \.eventId) { like in
                        HStack(spacing: BitOSTheme.Spacing.sm) {
                            PubkeyAvatarView(pubkey: like.pubkey, size: 28, picture: profileFor(like.pubkey)?.picture, hasLightning: !(profileFor(like.pubkey)?.lud16 ?? "").isEmpty)
                            HStack(spacing: 4) {
                                Text(storyDisplayName(like.pubkey, profileFor))
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundStyle(BitOSTheme.textPrimary)
                                if !(profileFor(like.pubkey)?.nip05?.isEmpty ?? true) {
                                    AppIcons.image(for: AppIcons.checkCircle)
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundStyle(BitOSTheme.accent)
                                        .accessibilityLabel("NIP-05 identity claim")
                                }
                            }
                            Spacer()
                            Text(like.emoji)
                        }
                    }
                }
                Section("Replies") {
                    ForEach(replies, id: \.eventId) { reply in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: BitOSTheme.Spacing.sm) {
                                PubkeyAvatarView(pubkey: reply.pubkey, size: 28, picture: profileFor(reply.pubkey)?.picture, hasLightning: !(profileFor(reply.pubkey)?.lud16 ?? "").isEmpty)
                                HStack(spacing: 4) {
                                    Text(storyDisplayName(reply.pubkey, profileFor))
                                        .font(.system(size: 13, weight: .semibold))
                                        .foregroundStyle(BitOSTheme.textPrimary)
                                    if !(profileFor(reply.pubkey)?.nip05?.isEmpty ?? true) {
                                        AppIcons.image(for: AppIcons.checkCircle)
                                            .font(.system(size: 12, weight: .semibold))
                                            .foregroundStyle(BitOSTheme.accent)
                                            .accessibilityLabel("NIP-05 identity claim")
                                    }
                                }
                                Spacer()
                                Text(FeedFormat.timeAgo(createdAt: reply.at))
                                    .font(.system(size: 11))
                                    .foregroundStyle(BitOSTheme.textSecondary)
                            }
                            Text(reply.text)
                                .font(.system(size: 14))
                                .foregroundStyle(BitOSTheme.textPrimary)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
            .navigationTitle("Story activity")
            .navigationBarTitleDisplayMode(.inline)
        }
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
