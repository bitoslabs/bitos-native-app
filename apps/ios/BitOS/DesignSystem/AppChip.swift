import SwiftUI

/**
 * Filter/tag chip (mockup `chip` family): 32 pt tall, surface + hairline
 * border; `active` inverts; `brand` renders the zap/amount tint. Optional
 * remove ✕ for editable tag rows.
 */
struct AppChip: View {
    let title: String
    var active = false
    var brand = false
    var enabled = true
    /// Text tint override for semantic chips (e.g. accent for hashtags);
    /// nil keeps the role default.
    var tint: Color? = nil
    var onClick: (() -> Void)? = nil
    var onRemove: (() -> Void)? = nil

    private var container: Color {
        if active { return BitOSTheme.textPrimary }
        if brand { return BitOSTheme.accent.opacity(0.14) }
        return BitOSTheme.surface
    }

    private var content: Color {
        if let tint { return tint }
        if active { return BitOSTheme.background }
        if brand { return BitOSTheme.textLink }
        return BitOSTheme.textSecondary
    }

    private var stroke: Color? {
        if active { return nil }
        if brand { return BitOSTheme.accent.opacity(0.4) }
        return BitOSTheme.border
    }

    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.xs) {
            Text(title)
                .font(BitOSType.labelMedium)
                .lineLimit(1)
            if let onRemove {
                Button {
                    onRemove()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 8, weight: .bold))
                        .frame(width: 20, height: 20)
                        .contentShape(Circle())
                }
                .buttonStyle(PressedScaleStyle())
                .accessibilityLabel(Text("Remove \(title)"))
            }
        }
        .foregroundStyle(content)
        .padding(.leading, BitOSTheme.Spacing.md)
        .padding(.trailing, onRemove != nil ? BitOSTheme.Spacing.xs : BitOSTheme.Spacing.md)
        .frame(height: 32)
        .background(
            ZStack {
                let shape = Capsule(style: .continuous)
                shape.fill(container)
                if let stroke {
                    shape.strokeBorder(stroke, lineWidth: 1)
                }
            }
        )
        .opacity(enabled ? 1 : 0.45)
        .contentShape(Capsule())
        .onTapGesture {
            guard let onClick, enabled else { return }
            onClick()
        }
        .accessibilityAddTraits(onClick != nil ? .isButton : [])
        .accessibilityLabel(Text(title))
    }
}

/**
 * Card container (mockup `card` family): surface fill, hairline border,
 * md radius, 16 pt padding by default (§2.3 semantic spacing).
 */
struct AppCard<Content: View>: View {
    var elevated = false
    var contentPadding: EdgeInsets = EdgeInsets(
        top: BitOSTheme.Spacing.base,
        leading: BitOSTheme.Spacing.base,
        bottom: BitOSTheme.Spacing.base,
        trailing: BitOSTheme.Spacing.base
    )
    var onClick: (() -> Void)? = nil
    @ViewBuilder var content: () -> Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous)
        content()
            .padding(contentPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(shape.fill(elevated ? BitOSTheme.surfaceElevated : BitOSTheme.surface))
            .overlay(shape.strokeBorder(BitOSTheme.border, lineWidth: 1))
            .contentShape(shape)
            .onTapGesture {
                guard let onClick else { return }
                onClick()
            }
            .accessibilityAddTraits(onClick != nil ? .isButton : [])
    }
}

#Preview("Chips + cards — both modes") {
    VStack(alignment: .leading, spacing: BitOSTheme.Spacing.base) {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            AppChip(title: "#lightning", active: true, onClick: {})
            AppChip(title: "⚡ 21 sats", brand: true)
            AppChip(title: "#node", onRemove: {})
        }
        AppCard {
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.xs) {
                Text("Latest Note").font(BitOSType.labelSmall)
                Text("Verifying every block since genesis…")
                    .font(BitOSType.bodySmall)
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
        }
        AppCard(elevated: true, onClick: {}) {
            Text("Tappable elevated card").font(BitOSType.bodyMedium)
        }
    }
    .padding(BitOSTheme.Spacing.screen)
    .background(BitOSTheme.background)
    .environment(\.colorScheme, .dark)
}
