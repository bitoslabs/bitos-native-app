import SwiftUI

/// Video extensions the preview row renders with a play affordance.
func isVideoMediaUrl(_ url: String) -> Bool {
    let ext = url
        .components(separatedBy: "#").first?
        .components(separatedBy: "?").first?
        .components(separatedBy: ".").last?
        .lowercased() ?? ""
    return ["mp4", "webm", "mov", "m4v"].contains(ext)
}

private func isGifUrl(_ url: String) -> Bool {
    (url.components(separatedBy: "#").first?
        .components(separatedBy: "?").first ?? url).hasSuffix(".gif")
}

/**
 * Pending-attachment preview row (legacy Flutter `AttachmentPreviewRow` /
 * web ReplyComposer parity, spec §4): 64 pt rounded tiles with a hairline
 * border — images/GIFs render a cover thumbnail, videos a play affordance
 * over a dark surface, GIFs carry a bottom-left badge; every tile keeps an
 * always-visible ✕ (touch has no hover). Used by the thread reply bar and
 * later the bitz comments composer.
 */
struct AttachmentPreviewRow: View {
    let urls: [String]
    let onRemove: (Int) -> Void

    var body: some View {
        if urls.isEmpty { EmptyView() } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: BitOSTheme.Spacing.sm) {
                    ForEach(Array(urls.enumerated()), id: \.offset) { index, url in
                        AttachmentTile(url: url) { onRemove(index) }
                    }
                }
                .padding(.vertical, 2)
            }
            .frame(height: 68)
        }
    }
}

private struct AttachmentTile: View {
    let url: String
    let onRemove: () -> Void

    private var video: Bool { isVideoMediaUrl(url) }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous)
                .fill(BitOSTheme.surfaceElevated)
                .overlay {
                    Group {
                        if video {
                            AppIcons.image(for: AppIcons.play)
                                .font(.system(size: 24))
                                .foregroundStyle(.white)
                        } else {
                            AsyncImage(url: URL(string: url)) { phase in
                                switch phase {
                                case .success(let image):
                                    image.resizable().scaledToFill()
                                default:
                                    EmptyView()
                                }
                            }
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous))
                }
                .overlay(
                    RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous)
                        .strokeBorder(BitOSTheme.border.opacity(0.25), lineWidth: 0.5)
                )
            if isGifUrl(url) && !video {
                Text("GIF")
                    .font(.system(size: 8, weight: .bold))
                    .tracking(0.5)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 3)
                    .padding(.vertical, 1)
                    .background(RoundedRectangle(cornerRadius: 3).fill(.black.opacity(0.65)))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                    .padding(3)
            }
            Button(action: onRemove) {
                Image(systemName: AppIcons.close)
                    .font(.system(size: 8, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 20, height: 20)
                    .background(Circle().fill(.black.opacity(0.65)))
            }
            .accessibilityLabel("Remove attachment")
        }
        .frame(width: 64, height: 64)
    }
}
