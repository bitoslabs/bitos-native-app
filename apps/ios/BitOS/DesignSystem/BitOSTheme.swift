import SwiftUI
import UIKit

/**
 * BitOS design tokens — SwiftUI side of the shared token contract
 * (`space.bitos.core.design.DesignTokens`, DESIGN_SYSTEM.md §2).
 *
 * Values mirror the contract's two palettes 1:1 (parity locked by
 * `DesignTokensTest` in the shared module). Every color is a *dynamic*
 * asset: it resolves dark or light at draw time from the active trait,
 * overridden by [modeOverride] (APP-023). Existing call sites read the
 * same names (`BitOSTheme.surface`, …) and pick up light mode for free —
 * extend, don't fork.
 */
enum BitOSTheme {
    /// App-level appearance override: nil = follow the system. The
    /// settings adapter sets this at launch and on every theme change from
    /// `ThemeModeSetting` (product default: dark). Views never mutate it.
    /// Written on the main actor before colors resolve; `nonisolated(unsafe)`
    /// mirrors the set-once launch pattern with live re-sync from the
    /// settings store.
    nonisolated(unsafe) static var modeOverride: UIUserInterfaceStyle? = .dark

    /// App-level accent override: nil = brand bitcoin orange. The settings
    /// adapter keeps this in sync with the persisted `#RRGGBB` choice
    /// (APP-023). Views never mutate it.
    nonisolated(unsafe) static var accentOverride: UInt32? = nil

    /// SwiftUI scheme matching [modeOverride]; nil = follow the system.
    /// Sheets/presentations re-apply this per surface so the whole
    /// presentation honors the persisted theme.
    static var preferredScheme: ColorScheme? {
        switch modeOverride {
        case .dark: .dark
        case .light: .light
        case .unspecified: nil
        case nil: nil
        @unknown default: nil
        }
    }

    /// Dynamic brand color from the contract's two palettes (light, dark).
    private static func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { traits in
            let mode = modeOverride ?? traits.userInterfaceStyle
            return mode == .dark ? UIColor(rgb: dark) : UIColor(rgb: light)
        })
    }

    // Surfaces
    static let background = dynamic(0xFAFAFC, 0x0A0A0F)
    static let surface = dynamic(0xFFFFFF, 0x12121A)
    static let surfaceElevated = dynamic(0xF5F5F7, 0x1A1A26)
    static let surfaceOverlay = dynamic(0xEEEEF0, 0x22222E)

    // Brand — the accent palette choice (Settings → Appearance) overrides
    // the bitcoin orange at every read site.
    private static let brandAccent = dynamic(0xF7931A, 0xF7931A)
    static var accent: Color { accentOverride.map(Color.init(hex:)) ?? brandAccent }
    static var accentContainer: Color { accent.opacity(0.2) }
    static let accentLight = Color(hex: 0xF9A84B)
    static let accentDark = Color(hex: 0xD4790F)
    static let cyan = dynamic(0x0E7490, 0x06B6D4)

    // Text (light-mode roles are the AA-tuned variants from the contract)
    static let textPrimary = dynamic(0x111827, 0xF8F8FF)
    static let textSecondary = dynamic(0x4B5563, 0x9CA3AF)
    static let textTertiary = dynamic(0x717684, 0x6B7280)
    static let textLink = dynamic(0xB45309, 0xF7931A)

    // Semantic (fills) + their readable-as-text variants
    static let success = dynamic(0x10B981, 0x10B981)
    static let successText = dynamic(0x047857, 0x10B981)
    static let warning = dynamic(0xF59E0B, 0xF59E0B)
    static let warningText = dynamic(0xB45309, 0xF59E0B)
    static let error = dynamic(0xEF4444, 0xEF4444)
    static let errorText = dynamic(0xB91C1C, 0xEF4444)
    static let info = dynamic(0x3B82F6, 0x3B82F6)
    static let infoText = dynamic(0x1D4ED8, 0x3B82F6)

    // Social actions
    static let like = dynamic(0xBE185D, 0xEC4899)
    static let repost = dynamic(0x047857, 0x10B981)
    static let zap = dynamic(0xB45309, 0xF59E0B)
    static let reply = dynamic(0x1D4ED8, 0x3B82F6)
    static let bookmark = dynamic(0xB45309, 0xF7931A)

    // Lines
    static let border = dynamic(0xE5E7EB, 0x2A2A3A)
    static let divider = dynamic(0xF3F4F6, 0x1F1F2E)

    // Spacing (app_spacing.dart / DesignTokens.Spacing)
    enum Spacing {
        static let xs: CGFloat = 4
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let base: CGFloat = 16
        static let lg: CGFloat = 20
        static let xl: CGFloat = 24
        static let xxl: CGFloat = 32
        static let xxxl: CGFloat = 48
        static let screen: CGFloat = 24
        static let card: CGFloat = 16
        static let avatarGap: CGFloat = 12
    }

    // Radii (DesignTokens.Radius)
    enum Radius {
        static let xs: CGFloat = 4
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let lg: CGFloat = 16
        static let xl: CGFloat = 20
        static let pill: CGFloat = 999
    }

    /// Avatar diameters (DESIGN_SYSTEM §2.3).
    enum AvatarSize {
        static let xs: CGFloat = 24
        static let sm: CGFloat = 32
        static let md: CGFloat = 40
        static let lg: CGFloat = 56
        static let xl: CGFloat = 80
        static let xxl: CGFloat = 120
    }

    /// Minimum touch target (§2.6): 44 pt on iOS.
    static let minTouchTarget: CGFloat = 44

    /// Number formatter for social counters (unchanged behavior).
    static func counter(_ value: Int) -> String {
        switch value {
        case 1_000_000...: return String(format: "%.1fM", Double(value) / 1_000_000)
        case 1_000...: return String(format: "%.1fK", Double(value) / 1_000)
        default: return "\(value)"
        }
    }
}

// MARK: - Typography (DESIGN_SYSTEM §2.2)

/**
 * Type roles from `DesignTokens.Type`. The app currently maps them onto
 * the system font; bundling Inter/JetBrains Mono later changes only this
 * file (same rule as the AppIcons token swap).
 */
enum BitOSType {
    static let displayLarge = Font.system(size: 32, weight: .bold).leading(.tight)
    static let headlineLarge = Font.system(size: 24, weight: .bold)
    static let headlineMedium = Font.system(size: 20, weight: .semibold)
    static let headlineSmall = Font.system(size: 18, weight: .semibold)
    static let bodyLarge = Font.system(size: 16)
    static let bodyMedium = Font.system(size: 14)
    static let bodySmall = Font.system(size: 12)
    static let labelLarge = Font.system(size: 14, weight: .semibold)
    static let labelMedium = Font.system(size: 12, weight: .medium)
    static let labelSmall = Font.system(size: 10, weight: .medium)
    static let menuItem = Font.system(size: 13, weight: .semibold)
    /// npub/nsec/ids/sats (JetBrains Mono when bundled).
    static let mono = Font.system(size: 13, weight: .regular, design: .monospaced)
}

// MARK: - Motion (DESIGN_SYSTEM §2.4)

/// Duration/curve ladder from `DesignTokens.Duration`/`Curve`.
enum BitOSMotion {
    static let instant: TimeInterval = 0.10
    static let fast: TimeInterval = 0.20
    static let normal: TimeInterval = 0.30
    static let slow: TimeInterval = 0.50
    static let emphasis: TimeInterval = 0.80

    /// easeInOutCubic — default.
    static func standard(_ duration: TimeInterval = normal) -> Animation {
        .easeInOut(duration: duration)
    }
    /// easeOutCubic — entering.
    static func enter(_ duration: TimeInterval = normal) -> Animation {
        .easeOut(duration: duration)
    }
    /// easeInCubic — exiting.
    static func exit(_ duration: TimeInterval = normal) -> Animation {
        .easeIn(duration: duration)
    }
    /// elasticOut — the like scale-bounce (1 → 1.3 → 1, §2.4).
    static func bounce(_ duration: TimeInterval = normal) -> Animation {
        .spring(response: duration, dampingFraction: 0.55)
    }
    static let likeScalePeak: CGFloat = 1.3
}

// MARK: - Elevation (dark uses brand glow, not gray shadows)

extension View {
    /// cardGlow: primary 5%, blur 20, y 4 (§DESIGN_SYSTEM shadows).
    func cardGlow() -> some View {
        shadow(color: BitOSTheme.accent.opacity(0.05), radius: 20, y: 4)
    }

    /// elevatedGlow: primary 10%, blur 30, y 8.
    func elevatedGlow() -> some View {
        shadow(color: BitOSTheme.accent.opacity(0.10), radius: 30, y: 8)
    }
}

// MARK: - Color helpers

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
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
