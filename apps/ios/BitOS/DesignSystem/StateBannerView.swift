import SwiftUI

/**
 * Inline state banner (docs/ui `state-banner`): tone-tinted background and
 * border plus a leading symbol — state is never communicated by color alone.
 */
enum StateBannerTone {
    case info
    case warn
    case error
    case ok

    var color: Color {
        switch self {
        case .info: Color(red: 0.23, green: 0.51, blue: 0.96)   // #3B82F6
        case .warn: BitOSTheme.warning
        case .error: BitOSTheme.error
        case .ok: BitOSTheme.success
        }
    }

    var systemImage: String {
        switch self {
        case .info: "info.circle"
        case .warn: "exclamationmark.triangle"
        case .error: "xmark.circle"
        case .ok: "checkmark.circle"
        }
    }
}

struct StateBannerView: View {
    let tone: StateBannerTone
    let text: String

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Image(systemName: tone.systemImage)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(tone.color)
            Text(text)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(tone.color)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(tone.color.opacity(0.10))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(tone.color.opacity(0.35), lineWidth: 1)
        )
        .accessibilityElement(children: .combine)
    }
}

/** npub/nsec display truncation (CB-1): keep both bech32 ends visible. */
func middleEllipsized(_ value: String, head: Int = 10, tail: Int = 8) -> String {
    guard value.count > head + tail + 1 else { return value }
    return "\(value.prefix(head))…\(value.suffix(tail))"
}
