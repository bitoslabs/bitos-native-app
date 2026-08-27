import SwiftUI

enum BitOSTheme {
    static let accent = Color(red: 1.0, green: 0.8, blue: 0.0)
    static let background = Color(red: 0.035, green: 0.035, blue: 0.043)
    static let surface = Color(red: 0.094, green: 0.094, blue: 0.106)
}

struct FeaturePlaceholder: View {
    let title: LocalizedStringKey
    let subtitle: LocalizedStringKey
    let symbol: String

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: symbol)
                .font(.system(size: 44, weight: .semibold))
                .foregroundStyle(BitOSTheme.accent)
                .accessibilityHidden(true)
            Text(title)
                .font(.title2.bold())
            Text(subtitle)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(BitOSTheme.background)
    }
}
