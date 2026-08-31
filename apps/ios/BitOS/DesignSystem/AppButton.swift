import SwiftUI

/**
 * The app button (DESIGN_SYSTEM §8 + mockup `btn` family): filled brand /
 * inverted / outlined / danger-tinted, pill by default, fast pressed
 * scale-down, optional leading SF Symbol, loading state that swaps the
 * icon for a spinner, 45% disabled alpha. Heights clear the 44 pt target.
 */
struct AppButton: View {
    enum Variant {
        case primary
        case onSurface
        case ghost
        case danger
    }

    enum Size {
        case sm
        case md
        case lg

        var height: CGFloat {
            switch self {
            case .sm: return 36
            case .md: return 44
            case .lg: return 52
            }
        }

        var font: Font {
            switch self {
            case .sm: return BitOSType.labelMedium
            case .md, .lg: return BitOSType.labelLarge
            }
        }

        var horizontalPadding: CGFloat {
            switch self {
            case .sm: return BitOSTheme.Spacing.base
            case .md: return BitOSTheme.Spacing.lg
            case .lg: return BitOSTheme.Spacing.xl
            }
        }
    }

    let title: String
    var icon: String? = nil
    var variant: Variant = .primary
    var size: Size = .md
    var pill = true
    var enabled = true
    var loading = false
    var action: () -> Void

    private var container: Color {
        switch variant {
        case .primary: return BitOSTheme.accent
        case .onSurface: return BitOSTheme.textPrimary
        case .ghost: return BitOSTheme.surface
        case .danger: return BitOSTheme.error.opacity(0.12)
        }
    }

    private var content: Color {
        switch variant {
        case .primary: return Color(hex: 0x1A1000)
        case .onSurface: return BitOSTheme.background
        case .ghost: return BitOSTheme.textPrimary
        case .danger: return BitOSTheme.errorText
        }
    }

    private var stroke: Color? {
        switch variant {
        case .ghost: return BitOSTheme.border
        case .danger: return BitOSTheme.error.opacity(0.4)
        default: return nil
        }
    }

    var body: some View {
        Button {
            guard enabled, !loading else { return }
            action()
        } label: {
            HStack(spacing: BitOSTheme.Spacing.sm) {
                if loading {
                    ProgressView()
                        .tint(content)
                        .controlSize(.small)
                } else if let icon {
                    Image(systemName: icon)
                        .font(.system(size: size == .sm ? 13 : 15, weight: .semibold))
                }
                Text(title)
                    .font(size.font)
            }
            .frame(maxWidth: .infinity)
            .frame(minHeight: size.height)
            .padding(.horizontal, size.horizontalPadding)
            .foregroundStyle(content)
            .background(
                ZStack {
                    let shape = RoundedRectangle(
                        cornerRadius: pill ? size.height / 2 : BitOSTheme.Radius.md,
                        style: .continuous
                    )
                    shape.fill(container)
                    if let stroke {
                        shape.strokeBorder(stroke, lineWidth: 1)
                    }
                }
            )
            .opacity(enabled || loading ? 1 : 0.45)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressedScaleStyle())
        .disabled(!enabled || loading)
        .accessibilityLabel(Text(title))
    }
}

/// Fast pressed scale-down shared by tappable brand controls (§2.4).
struct PressedScaleStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(BitOSMotion.standard(BitOSMotion.fast), value: configuration.isPressed)
    }
}

/// Compact icon-only action target (48-equivalent square, same variants).
struct AppIconButton: View {
    let systemIcon: String
    let contentDescription: String
    var variant: AppButton.Variant = .ghost
    var enabled = true
    var action: () -> Void

    private var container: Color {
        switch variant {
        case .primary: return BitOSTheme.accent
        case .onSurface: return BitOSTheme.textPrimary
        case .ghost: return BitOSTheme.surface
        case .danger: return BitOSTheme.error.opacity(0.12)
        }
    }

    private var content: Color {
        switch variant {
        case .primary: return Color(hex: 0x1A1000)
        case .onSurface: return BitOSTheme.background
        case .ghost: return BitOSTheme.textSecondary
        case .danger: return BitOSTheme.errorText
        }
    }

    var body: some View {
        Button {
            guard enabled else { return }
            action()
        } label: {
            Image(systemName: systemIcon)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(content)
                .frame(
                    width: max(BitOSTheme.minTouchTarget, 40),
                    height: max(BitOSTheme.minTouchTarget, 40)
                )
                .background(Circle().fill(container))
                .contentShape(Circle())
        }
        .buttonStyle(PressedScaleStyle())
        .disabled(!enabled)
        .accessibilityLabel(Text(contentDescription))
    }
}

#Preview("App buttons — both modes") {
    VStack(spacing: BitOSTheme.Spacing.base) {
        AppButton(title: "Zap", icon: "bolt.fill", action: {})
        AppButton(title: "Follow", variant: .onSurface, action: {})
        AppButton(title: "Share", variant: .ghost, action: {})
        AppButton(title: "Delete", variant: .danger, action: {})
        AppButton(title: "Publishing…", loading: true, action: {})
        AppButton(title: "Disabled", enabled: false, action: {})
        HStack(spacing: BitOSTheme.Spacing.md) {
            AppIconButton(systemIcon: "xmark", contentDescription: "Close", action: {})
            AppIconButton(systemIcon: "bolt.fill", contentDescription: "Zap", variant: .primary, action: {})
        }
    }
    .padding(BitOSTheme.Spacing.screen)
    .background(BitOSTheme.background)
    .environment(\.colorScheme, .dark)
}
