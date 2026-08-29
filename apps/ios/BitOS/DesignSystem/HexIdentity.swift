import SwiftUI

/**
 * Hex identity system (unified feature spec §2.5/§4, APP-022): the BitOS
 * sovereign-identity motif. `HexShape` is the flat-top hexagon clip shared
 * by avatars, badges and brand tiles (web `.hex-clip` / Flutter `HexShape`
 * parity); `HexAvatarView` is the deterministic pubkey avatar used by every
 * surface until remote profile pictures load.
 */

/// Pure geometry + identity-color derivation (testable without UI).
enum HexIdentity {
    /// Flat-top hexagon vertices inscribed in `rect`
    /// (CSS `polygon(25% 6.7%, 75% 6.7%, 100% 50%, 75% 93.3%, 25% 93.3%, 0% 50%)`
    /// parity with the web `.hex-clip` / Flutter `hexPoints`: a *regular*
    /// flat-top hexagon touching the left/right edges with a 6.7% vertical
    /// inset — not one stretched to fill the square).
    static func vertices(in rect: CGRect) -> [CGPoint] {
        [
            CGPoint(x: rect.minX + rect.width * 0.25, y: rect.minY + rect.height * 0.067),
            CGPoint(x: rect.minX + rect.width * 0.75, y: rect.minY + rect.height * 0.067),
            CGPoint(x: rect.maxX, y: rect.midY),
            CGPoint(x: rect.minX + rect.width * 0.75, y: rect.maxY - rect.height * 0.067),
            CGPoint(x: rect.minX + rect.width * 0.25, y: rect.maxY - rect.height * 0.067),
            CGPoint(x: rect.minX, y: rect.midY),
        ]
    }

    /// Deterministic pubkey → avatar gradient. Stable for the same pubkey
    /// across launches, distinct between keys.
    static func avatarGradient(for pubkey: String) -> (start: Color, end: Color) {
        let seed = UInt32(pubkey.prefix(8), radix: 16) ?? 0
        let hue = Double(seed % 360) / 360
        let hueEnd = (hue + 40.0 / 360.0).truncatingRemainder(dividingBy: 1)
        return (
            Color(hue: hue, saturation: 0.65, brightness: 0.85),
            Color(hue: hueEnd, saturation: 0.7, brightness: 0.55)
        )
    }
}

/// The hexagon clip used for avatars, identity chips and brand tiles.
struct HexShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.addLines(HexIdentity.vertices(in: rect))
        path.closeSubpath()
        return path
    }
}

/// Hexagonal identicon avatar: deterministic gradient + initials, with an
/// optional remote picture that replaces the fallback once it loads.
struct HexAvatarView: View {
    let pubkey: String
    var size: CGFloat = 40
    var imageURL: URL?
    var label: String?
    var hasLightning: Bool = false

    init(pubkey: String, size: CGFloat = 40, imageURL: URL? = nil, label: String? = nil, hasLightning: Bool = false) {
        self.pubkey = pubkey
        self.size = size
        self.imageURL = imageURL
        self.label = label
        self.hasLightning = hasLightning
    }

    private var initials: some View {
        Text(avatarInitials(label ?? pubkey))
            .font(.system(size: size / 3, weight: .bold))
            .foregroundStyle(.white)
    }

    var body: some View {
        let colors = HexIdentity.avatarGradient(for: pubkey)
        ZStack(alignment: .bottomTrailing) {
            ZStack {
                LinearGradient(
                    colors: [colors.start, colors.end],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                if let imageURL {
                    AsyncImage(url: imageURL) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        initials
                    }
                } else {
                    initials
                }
            }
            .frame(width: size, height: size)
            .clipShape(HexShape())
            if hasLightning {
                AppIcons.image(for: AppIcons.zap)
                    .font(.system(size: min(max(size * 0.18, 6), 10), weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: min(max(size * 0.36, 10), 18), height: min(max(size * 0.36, 10), 18))
                    .background(Circle().fill(LinearGradient(colors: [Color(red: 1, green: 0.71, blue: 0.11), Color(red: 0.97, green: 0.58, blue: 0.10)], startPoint: .topLeading, endPoint: .bottomTrailing)))
                    .overlay(Circle().stroke(.white, lineWidth: 1))
            }
        }
        .frame(width: size, height: size)
        .accessibilityLabel("Avatar \(avatarInitials(label ?? pubkey))")
    }
}

/// Profile metadata is untrusted. Avatar fetches use only bounded HTTPS URLs
/// and never load remote SVG documents; callers retain the hex fallback.
func safeProfilePictureURL(_ raw: String?) -> URL? {
    guard let raw = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
          raw.count <= 512,
          let url = URL(string: raw),
          url.scheme?.lowercased() == "https",
          url.host != nil else { return nil }
    guard !url.path.lowercased().hasSuffix(".svg") else { return nil }
    return url
}

private func avatarInitials(_ label: String) -> String {
    let words = label.split(whereSeparator: \.isWhitespace)
    switch words.count {
    case 0: return "?"
    case 1: return String(words[0].prefix(2)).uppercased()
    default: return "\(words[0].prefix(1))\(words[words.count - 1].prefix(1))".uppercased()
    }
}

/// Small hexagonal tile with a symbol (settings tiles, brand marks).
/// Decorative: label at the usage site.
struct HexIcon: View {
    let systemName: String
    var size: CGFloat = 40
    var background: Color = BitOSTheme.surfaceElevated
    var foreground: Color = BitOSTheme.accent

    var body: some View {
        ZStack {
            HexShape().fill(background)
            AppIcons.image(for: systemName)
                .font(.system(size: size * 0.42, weight: .semibold))
                .foregroundStyle(foreground)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
