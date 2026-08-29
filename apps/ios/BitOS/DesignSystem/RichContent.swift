import AVKit
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

struct RemoteImageView: View {
    let url: String
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { geo in
            Group {
                if let image {
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
            guard image == nil, let target = URL(string: url) else { return }
            image = await Task.detached(priority: .utility) { () -> UIImage? in
                guard let (data, _) = try? await URLSession.shared.data(from: target) else { return nil }
                return UIImage(data: data)?.normalized()
            }.value
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
        videoExtensions.contains((url as NSString).pathExtension.lowercased())
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

/// Fullscreen zoomable media viewer (lightbox).
struct MediaLightbox: View {
    let url: String
    let onClose: () -> Void
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1

    var body: some View {
        ZStack(alignment: .topTrailing) {
            BitOSTheme.background.ignoresSafeArea()
            RemoteImageView(url: url)
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
