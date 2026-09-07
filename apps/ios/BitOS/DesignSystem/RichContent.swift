import AVKit
import ImageIO
import SwiftUI
import UIKit

/**
 * APP-005 rich content components (unified feature spec §3.5):
 * `RichTextView` renders NIP-27 token JSON from the shared `Nip27`
 * tokenizer (via the store seam), `MediaGrid` + `MediaLightbox` show
 * image tiles with a zoomable fullscreen viewer, and `SensitiveCover`
 * gates NIP-36 content behind a per-session reveal.
 */

// MARK: - Token model (bridge JSON: {"k":"t|l|h|n","v":…,"e":…,"x":…})

struct RichTokenModel: Codable, Equatable {
    let k: String
    let v: String
    let d: String?
    let e: String?
    let x: String?
}

enum RichTextDecoder {
    static func decode(_ json: String) -> [RichTokenModel] {
        (try? JSONDecoder().decode([RichTokenModel].self, from: Data(json.utf8))) ?? []
    }
}

// MARK: - Rich text

struct RichTextView: View {
    let json: String
    var onOpenProfile: ((String) -> Void)?
    var onOpenHashtag: ((String) -> Void)?
    /** Body color override (video captions render white). */
    var color: Color? = nil
    var lineLimit: Int? = nil
    /** Media link URLs already rendered as tiles — hidden from the body. */
    var hiddenMediaUrls: Set<String> = []
    /** Mention display-name resolver (APP-009 threads parity). */
    var resolveMentionName: ((String) -> String?)? = nil
    /** Note-reference tap handler (APP-009 entity routing). */
    var onOpenNoteRef: ((String) -> Void)? = nil
    /** External-link tap interception (confirm sheet); nil = open directly. */
    var onOpenLink: ((String) -> Void)? = nil

    private var tokens: [RichTokenModel] { RichTextDecoder.decode(json) }

    /// Web parity: profile mentions resolve to @display-name once metadata
    /// lands; note refs stay shortened ids.
    private func display(_ token: RichTokenModel) -> String {
        switch token.k {
        case "h": return "#\(token.v)"
        case "n":
            if token.e == "profile", let hex = token.x,
               let name = resolveMentionName?(hex) {
                return "@\(name)"
            }
            return token.v.count > 14 ? "\(token.v.prefix(10))…" : token.v
        case "l": return token.d ?? token.v
        default: return token.v
        }
    }

    private var bodyText: AttributedString {
        var out = AttributedString()
        out.font = .body
        for (index, token) in tokens.enumerated() {
            // Bare media links render as tiles below — the raw URL
            // disappears from the body (web parity).
            if token.k == "l", hiddenMediaUrls.contains(token.v) { continue }
            var run = AttributedString(display(token))
            switch token.k {
            case "l":
                if onOpenLink != nil {
                    run.link = URL(string: "bitos://link/\(index)")
                } else {
                    run.link = URL(string: token.v)
                }
                run.foregroundColor = BitOSTheme.accent
                run.underlineStyle = .single
            case "h", "n":
                run.link = URL(string: "bitos://tap/\(index)")
                run.foregroundColor = BitOSTheme.accent
                run.font = .body.weight(.medium)
            default:
                run.foregroundColor = color ?? BitOSTheme.textPrimary
            }
            out += run
        }
        return out
    }

    var body: some View {
        Text(bodyText)
            .lineLimit(lineLimit)
            .environment(\.openURL, OpenURLAction { url in
                guard url.scheme == "bitos",
                      let index = Int(url.lastPathComponent), tokens.indices.contains(index) else {
                    return .systemAction
                }
                let token = tokens[index]
                switch token.k {
                case "l":
                    onOpenLink?(token.v)
                case "n":
                    if token.e == "profile" {
                        if let hex = token.x { onOpenProfile?(hex) }
                    } else {
                        // note1/nevent1/naddr1 → thread (web parity).
                        onOpenNoteRef?(token.v)
                    }
                case "h":
                    onOpenHashtag?(token.v)
                default:
                    break
                }
                return .handled
            })
    }
}

// MARK: - Remote image

/// Multi-frame GIF decoding (ImageIO): SwiftUI `Image` renders only the
/// first frame of a GIF payload. Single-frame and non-GIF data return
/// empty — callers keep the static image path. Shared by remote media
/// tiles (`RemoteImageView`) and story slides (`StoryGifView`).
enum GifDecoder {
    static func decode(_ data: Data) -> (frames: [UIImage], duration: Double) {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return ([], 0) }
        let count = CGImageSourceGetCount(source)
        guard count > 1 else { return ([], 0) }
        var frames: [UIImage] = []
        var total: Double = 0
        for index in 0..<count {
            guard let cgImage = CGImageSourceCreateImageAtIndex(source, index, nil) else { continue }
            var delay: Double = 0.1
            if let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [String: Any],
               let raw = properties[kCGImagePropertyGIFDictionary as String] as? [String: Any],
               let unclamped = raw[kCGImagePropertyGIFUnclampedDelayTime as String] as? Double {
                delay = max(unclamped, 0.02)
            }
            frames.append(UIImage(cgImage: cgImage))
            total += delay
        }
        return (frames, total)
    }
}

/// Plays decoded GIF frames in a UIImageView (`animationImages`).
struct GifFramePlayer: UIViewRepresentable {
    let frames: [UIImage]
    let duration: Double
    var contentMode: UIView.ContentMode = .scaleAspectFill

    func makeUIView(context: Context) -> UIImageView {
        let view = UIImageView()
        view.contentMode = contentMode
        view.clipsToBounds = true
        view.animationImages = frames
        view.animationDuration = duration
        view.animationRepeatCount = 0
        view.startAnimating()
        return view
    }

    func updateUIView(_ uiView: UIImageView, context: Context) {}
}

struct RemoteImageView: View {
    let url: String
    @State private var image: UIImage?
    /// Animated GIF payloads: decoded frames + summed delay. Empty while
    /// static, so the `image` path keeps rendering everything else.
    @State private var gifFrames: [UIImage] = []
    @State private var gifDuration: Double = 0

    var body: some View {
        GeometryReader { geo in
            Group {
                if gifFrames.count > 1 {
                    GifFramePlayer(frames: gifFrames, duration: gifDuration, contentMode: .scaleAspectFit)
                } else if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                } else {
                    LinearGradient(
                        colors: [BitOSTheme.surface, BitOSTheme.surfaceElevated],
                        startPoint: .topLeading, endPoint: .bottomTrailing
                    )
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .clipped()
        }
        .task(id: url) {
            guard image == nil, gifFrames.isEmpty, let target = URL(string: url) else { return }
            let decoded: (frames: [UIImage], duration: Double, staticImage: UIImage?) =
                await Task.detached(priority: .utility) {
                    guard let (data, _) = try? await URLSession.shared.data(from: target) else { return ([], 0, nil) }
                    let gif = GifDecoder.decode(data)
                    if gif.frames.count > 1 { return (gif.frames, gif.duration, nil) }
                    return ([], 0, UIImage(data: data)?.normalized())
                }.value
            gifFrames = decoded.frames
            gifDuration = decoded.duration
            image = decoded.staticImage
        }
    }
}

private extension UIImage {
    /// Scale very large remote images down before display (memory bound).
    func normalized(maxDimension: CGFloat = 2048) -> UIImage {
        let largest = max(size.width, size.height)
        guard largest > maxDimension else { return self }
        let scale = maxDimension / largest
        let newSize = CGSize(width: size.width * scale, height: size.height * scale)
        let renderer = UIGraphicsImageRenderer(size: newSize)
        return renderer.image { _ in draw(in: CGRect(origin: .zero, size: newSize)) }
    }
}

// MARK: - Media grid (≤9 image tiles, spec §3.5)

struct MediaGrid: View {
    let urls: [String]
    var onOpen: (String) -> Void

    private static let imageExtensions: Set<String> = ["png", "jpg", "jpeg", "gif", "webp", "avif", "apng"]
    private static let videoExtensions: Set<String> = ["mp4", "webm", "mov", "m4v"]

    /// Media tiles (≤9): images inline; bare video links get a play-glyph
    /// tile that opens a fullscreen player.
    var mediaUrls: [String] {
        urls.prefix(9).map { $0 }
    }

    private static func isVideo(_ url: String) -> Bool {
        let lower = url.lowercased()
        return videoExtensions.contains { fileExtension in
            lower.hasSuffix(".\(fileExtension)") ||
                lower.contains(".\(fileExtension)?") ||
                lower.contains(".\(fileExtension)#") ||
                lower.contains(".\(fileExtension)&") ||
                lower.contains("format=\(fileExtension)&") ||
                lower.hasSuffix("format=\(fileExtension)") ||
                lower.contains("fm=\(fileExtension)&") ||
                lower.hasSuffix("fm=\(fileExtension)") ||
                lower.contains("ext=\(fileExtension)&") ||
                lower.hasSuffix("ext=\(fileExtension)")
        }
    }

    private let columns = [GridItem(.flexible(), spacing: 4), GridItem(.flexible(), spacing: 4), GridItem(.flexible(), spacing: 4)]

    @State private var playUrl: String?

    var body: some View {
        if !mediaUrls.isEmpty {
            LazyVGrid(columns: columns, spacing: 4) {
                ForEach(mediaUrls, id: \.self) { url in
                    Button {
                        if Self.isVideo(url) {
                            playUrl = url
                        } else {
                            onOpen(url)
                        }
                    } label: {
                        Group {
                            if Self.isVideo(url) {
                                ZStack {
                                    Rectangle().fill(BitOSTheme.surfaceElevated)
                                    AppIcons.image(for: AppIcons.play)
                                        .font(.title2)
                                        .foregroundStyle(BitOSTheme.textSecondary)
                                }
                            } else {
                                RemoteImageView(url: url)
                            }
                        }
                        .frame(height: 104)
                        .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.sm, style: .continuous))
                    }
                    .accessibilityLabel(Self.isVideo(url) ? "Play video" : "Open media")
                }
            }
            .fullScreenCover(item: Binding(
                get: { playUrl.map { MediaPlayTarget(url: $0) } },
                set: { playUrl = $0?.url }
            )) { target in
                BareVideoPlayerScreen(url: target.url, onDismiss: { playUrl = nil })
            }
        }
    }

    private struct MediaPlayTarget: Identifiable {
        let url: String
        var id: String { url }
    }
}

/// Single-file fullscreen player for bare video links (released on close).
struct BareVideoPlayerScreen: View {
    let url: String
    let onDismiss: () -> Void

    @State private var player: AVPlayer?

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            if let player {
                VideoPlayer(player: player)
                    .ignoresSafeArea()
            }
            Button(action: onDismiss) {
                AppIcons.image(for: AppIcons.close)
                    .font(.title3)
                    .foregroundStyle(.white)
                    .padding(12)
            }
            .accessibilityLabel("Close video")
        }
        .onAppear {
            guard let target = URL(string: url) else { return }
            let avPlayer = AVPlayer(url: target)
            avPlayer.isMuted = false
            player = avPlayer
            avPlayer.play()
        }
        .onDisappear {
            player?.pause()
            player = nil
        }
    }
}

/// imeta video preview tile (comment-sheet origin cards): poster art (or an
/// elevated tile) with a play glyph; tap opens the fullscreen player. The
/// URL lives on `FeedNote.video`, not in content links, so `MediaGrid`
/// never sees it — without this tile a Bitz origin card renders an empty
/// body.
struct VideoPreviewTile: View {
    let url: String
    let posterUrl: String?
    var aspectRatio: CGFloat = 9.0 / 16.0
    @State private var play = false

    var body: some View {
        Button {
            play = true
        } label: {
            ZStack {
                Rectangle().fill(BitOSTheme.surfaceElevated)
                if let poster = URL(string: posterUrl ?? "") {
                    AsyncImage(url: poster) { phase in
                        if let image = phase.image {
                            image
                                .resizable()
                                .scaledToFill()
                        }
                    }
                }
                Image(systemName: AppIcons.play)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 52, height: 52)
                    .background(Circle().fill(.black.opacity(0.6)))
            }
            .aspectRatio(aspectRatio, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Play video")
        .fullScreenCover(isPresented: $play) {
            BareVideoPlayerScreen(url: url, onDismiss: { play = false })
        }
    }
}

/// Fullscreen zoomable media viewer (lightbox).
struct MediaLightbox: View {
    let urls: [String]
    let onClose: () -> Void
    @State private var currentIndex: Int
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1

    init(urls: [String], initialUrl: String, onClose: @escaping () -> Void) {
        let media = Array(NSOrderedSet(array: urls)) as? [String] ?? urls
        self.urls = media.isEmpty ? [initialUrl] : media
        self.onClose = onClose
        _currentIndex = State(initialValue: max(0, media.firstIndex(of: initialUrl) ?? 0))
    }

    init(url: String, onClose: @escaping () -> Void) {
        self.init(urls: [url], initialUrl: url, onClose: onClose)
    }

    private var currentUrl: String { urls[currentIndex] }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            BitOSTheme.background.ignoresSafeArea()
            RemoteImageView(url: currentUrl)
                .id(currentUrl)
                .scaleEffect(scale)
                .gesture(
                    MagnificationGesture()
                        .onChanged { scale = min(5, max(1, lastScale * $0)) }
                        .onEnded { _ in lastScale = scale }
                )
                .onTapGesture(count: 2) {
                    withAnimation(.easeOut(duration: 0.2)) {
                        scale = scale > 1 ? 1 : 2.5
                        lastScale = scale
                    }
                }
                .simultaneousGesture(
                    DragGesture(minimumDistance: 24)
                        .onEnded { value in
                            guard abs(value.translation.width) > abs(value.translation.height),
                                  scale == 1 else { return }
                            if value.translation.width < 0, currentIndex < urls.count - 1 {
                                currentIndex += 1
                            } else if value.translation.width > 0, currentIndex > 0 {
                                currentIndex -= 1
                            }
                        }
                )
            if urls.count > 1 {
                lightboxNavigation
            }
            Button(action: onClose) {
                Image(systemName: AppIcons.close)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(10)
                    .background(Circle().fill(.black.opacity(0.5)))
            }
            .padding(BitOSTheme.Spacing.base)
            .accessibilityLabel("Close media")
        }
        .onChange(of: currentIndex) { _, _ in
            scale = 1
            lastScale = 1
        }
    }

    @ViewBuilder
    private var lightboxNavigation: some View {
        HStack {
            Button { currentIndex -= 1 } label: {
                Image(systemName: "chevron.left")
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(.black.opacity(0.5)))
            }
            .disabled(currentIndex == 0)
            .accessibilityLabel("Previous image")
            Spacer()
            Button { currentIndex += 1 } label: {
                Image(systemName: "chevron.right")
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(.black.opacity(0.5)))
            }
            .disabled(currentIndex == urls.count - 1)
            .accessibilityLabel("Next image")
        }
        .font(.system(size: 18, weight: .semibold))
        .foregroundStyle(.white)
        .padding(.horizontal, 12)
        .accessibilityElement(children: .contain)
        .overlay(alignment: .top) {
            Text("\(currentIndex + 1) of \(urls.count)")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Capsule().fill(.black.opacity(0.5)))
                .padding(.top, BitOSTheme.Spacing.base)
        }
    }
}

// MARK: - Sensitive cover (NIP-36, per-session reveal)

struct SensitiveCover: View {
    let onReveal: () -> Void

    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.sm) {
            Image(systemName: AppIcons.reportIllicit)
                .font(.system(size: 28, weight: .medium))
                .foregroundStyle(BitOSTheme.textTertiary)
            Text("Sensitive content")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(BitOSTheme.textSecondary)
            Button("Show", action: onReveal)
                .font(.system(size: 13, weight: .semibold))
                .buttonStyle(.bordered)
                .tint(BitOSTheme.accent)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 180)
        .background(
            RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous)
                .fill(BitOSTheme.surfaceElevated)
        )
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Sensitive content hidden. Show button.")
    }
}
