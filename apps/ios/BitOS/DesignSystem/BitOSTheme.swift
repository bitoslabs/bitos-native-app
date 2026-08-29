import SwiftUI

/**
 * BitOS design tokens, ported 1:1 from the Flutter/web product tokens
 * (app_colors.dart / app_spacing.dart / app_radius.dart): bitcoin-orange
 * primary on near-black surfaces with semantic social-action colors.
 */
enum BitOSTheme {
    // Surfaces
    static let background = Color(hex: 0x0A0A0F)
    static let surface = Color(hex: 0x12121A)
    static let surfaceElevated = Color(hex: 0x1A1A26)
    static let surfaceOverlay = Color(hex: 0x22222E)

    // Brand
    static let accent = Color(hex: 0xF7931A)
    static let accentContainer = Color(hex: 0xF7931A).opacity(0.2)
    static let cyan = Color(hex: 0x06B6D4)

    // Text
    static let textPrimary = Color(hex: 0xF8F8FF)
    static let textSecondary = Color(hex: 0x9CA3AF)
    static let textTertiary = Color(hex: 0x6B7280)

    // Semantic
    static let success = Color(hex: 0x10B981)
    static let warning = Color(hex: 0xF59E0B)
    static let error = Color(hex: 0xEF4444)

    // Social actions
    static let like = Color(hex: 0xEC4899)
    static let repost = Color(hex: 0x10B981)
    static let zap = Color(hex: 0xF59E0B)
    static let reply = Color(hex: 0x3B82F6)
    static let bookmark = Color(hex: 0xF7931A)

    // Lines
    static let border = Color(hex: 0x2A2A3A)
    static let divider = Color(hex: 0x1F1F2E)

    // Spacing (app_spacing.dart)
    enum Spacing {
        static let xs: CGFloat = 4
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let base: CGFloat = 16
        static let lg: CGFloat = 20
        static let xl: CGFloat = 24
        static let xxl: CGFloat = 32
        static let screen: CGFloat = 24
        static let avatarGap: CGFloat = 12
    }

    // Radii (app_radius.dart)
    enum Radius {
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let lg: CGFloat = 16
        static let pill: CGFloat = 999
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

// MARK: - Formatters (presentation-only)

enum FeedFormat {
    /// Compact social counter: 1_234 -> "1.2K".
    static func count(_ value: Int) -> String {
        switch value {
        case 1_000_000...: return String(format: "%.1fM", Double(value) / 1_000_000)
        case 1_000...: return String(format: "%.1fK", Double(value) / 1_000)
        default: return "\(value)"
        }
    }

    /// Compact relative time: "12s", "4m", "2h", "6d", "3mo".
    static func timeAgo(createdAt: Int64, now: Date = .now) -> String {
        let age = max(0, Int64(now.timeIntervalSince1970) - createdAt)
        switch age {
        case ..<60: return "\(age)s"
        case ..<3_600: return "\(age / 60)m"
        case ..<86_400: return "\(age / 3_600)h"
        case ..<(30 * 86_400): return "\(age / 86_400)d"
        default: return "\(age / (30 * 86_400))mo"
        }
    }

    /// Short author label for unprofiled pubkeys: "abc123…9f".
    static func shortPubkey(_ pubkey: String) -> String {
        pubkey.count > 16 ? "\(pubkey.prefix(8))…\(pubkey.suffix(4))" : pubkey
    }
}

// MARK: - Shared components

/// Deterministic hex identicon avatar (APP-022 motif) derived from the
/// pubkey; the stable fallback before remote profile pictures load.
/// Delegates to the shared hex identity system in `HexIdentity.swift`.
struct PubkeyAvatarView: View {
    let pubkey: String
    var size: CGFloat = 40
    var picture: String? = nil
    var label: String? = nil
    var hasLightning = false

    var body: some View {
        HexAvatarView(
            pubkey: pubkey,
            size: size,
            imageURL: safeProfilePictureURL(picture),
            label: label,
            hasLightning: hasLightning
        )
    }
}
