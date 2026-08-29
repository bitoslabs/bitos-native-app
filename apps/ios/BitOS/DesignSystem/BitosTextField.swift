import SwiftUI

/**
 * BitOS text field (legacy Flutter `InputDecorationTheme` parity —
 * app_theme.dart): filled surfaceElevated, 12 pt radius, 1 pt divider
 * border, tertiary hint. One view wrapper so every surface inherits the
 * brand look; SwiftUI TextFieldStyle cannot observe focus state across
 * its protocol boundary, so this is a plain wrapper composition instead.
 */
struct BitosField<Label: View>: View {
    @ViewBuilder let label: () -> Label
    @Binding var text: String
    var axis: Axis? = nil

    var body: some View {
        field
            .font(.system(size: 14))
            .foregroundStyle(BitOSTheme.textPrimary)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
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
    init(_ placeholder: String, text: Binding<String>, axis: Axis? = nil) {
        self.label = { Text(placeholder).foregroundStyle(BitOSTheme.textTertiary) }
        self._text = text
        self.axis = axis
    }
}
