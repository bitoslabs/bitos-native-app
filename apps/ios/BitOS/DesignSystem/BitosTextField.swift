import SwiftUI

/**
 * BitOS text field (legacy Flutter `InputDecorationTheme` parity —
 * app_theme.dart): filled surfaceElevated, 12 pt radius, 1 pt divider
 * border, tertiary hint. One view wrapper so every surface inherits the
 * brand look; SwiftUI TextFieldStyle cannot observe focus state across
 * its protocol boundary, so this is a plain wrapper composition instead.
 */

/// Field size ladder (§2.6): `medium` is the default and honors the 44 pt
/// minimum touch target; `small` is for dense rows, `large` for hero forms.
enum BitosFieldSize {
    case small
    case medium
    case large

    var font: Font {
        switch self {
        case .small: .system(size: 13)
        case .medium: .system(size: 14)
        case .large: .system(size: 16)
        }
    }

    var minHeight: CGFloat {
        switch self {
        case .small: 36
        case .medium: BitOSTheme.minTouchTarget
        case .large: 52
        }
    }

    var horizontalPadding: CGFloat {
        switch self {
        case .small: 12
        case .medium: 14
        case .large: 16
        }
    }

    var verticalPadding: CGFloat {
        switch self {
        case .small: 7
        case .medium: 10
        case .large: 13
        }
    }
}

struct BitosField<Label: View>: View {
    @ViewBuilder let label: () -> Label
    @Binding var text: String
    var axis: Axis? = nil
    var size: BitosFieldSize = .medium

    var body: some View {
        field
            .font(size.font)
            .foregroundStyle(BitOSTheme.textPrimary)
            .padding(.horizontal, size.horizontalPadding)
            .padding(.vertical, size.verticalPadding)
            .frame(minHeight: size.minHeight)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(BitOSTheme.surfaceElevated)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(BitOSTheme.divider)
            )
    }

    @ViewBuilder
    private var field: some View {
        if let axis {
            TextField(text: $text, axis: axis, label: label)
        } else {
            TextField(text: $text, label: label)
        }
    }
}

extension BitosField where Label == Text {
    init(_ placeholder: String, text: Binding<String>, axis: Axis? = nil, size: BitosFieldSize = .medium) {
        self.label = { Text(placeholder).foregroundStyle(BitOSTheme.textTertiary) }
        self._text = text
        self.axis = axis
        self.size = size
    }
}

/**
 * Shared search input (Discover + Bitz search overlay): the brand field
 * chrome from `BitosField` plus the affordances every search surface
 * shares — magnifier lead, inline clear, focused accent border, and the
 * search keyboard (no autocorrect/autocapitals, search submit). Size
 * defaults to `.medium`; the call site owns the focus binding so overlays
 * can auto-focus and resign it.
 */
struct BitosSearchField: View {
    let placeholder: String
    @Binding var text: String
    var focus: FocusState<Bool>.Binding?
    var size: BitosFieldSize = .medium

    init(_ placeholder: String, text: Binding<String>, focus: FocusState<Bool>.Binding? = nil, size: BitosFieldSize = .medium) {
        self.placeholder = placeholder
        self._text = text
        self.focus = focus
        self.size = size
    }

    var body: some View {
        HStack(spacing: BitOSTheme.Spacing.sm) {
            AppIcons.image(for: AppIcons.search)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(BitOSTheme.textSecondary)
                .accessibilityHidden(true)
            TextField("Search", text: $text, prompt: Text(placeholder).foregroundStyle(BitOSTheme.textTertiary))
                .bitosFocused(focus)
                .font(size.font)
                .foregroundStyle(BitOSTheme.textPrimary)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .submitLabel(.search)
            if !text.isEmpty {
                Button {
                    text = ""
                } label: {
                    AppIcons.image(for: AppIcons.close)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(BitOSTheme.textTertiary)
                        .frame(width: 24, height: 24)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.horizontal, size.horizontalPadding)
        .padding(.vertical, size.verticalPadding)
        .frame(minHeight: size.minHeight)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(BitOSTheme.surfaceElevated)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(focus?.wrappedValue == true ? BitOSTheme.accent : BitOSTheme.divider)
        )
    }
}

/// Optional-focus bridge: surfaces that own focus (search overlay) pass
/// their `FocusState` binding; plain call sites pass nil and keep default
/// focus behavior.
private extension View {
    @ViewBuilder
    func bitosFocused(_ binding: FocusState<Bool>.Binding?) -> some View {
        if let binding {
            focused(binding)
        } else {
            self
        }
    }
}
