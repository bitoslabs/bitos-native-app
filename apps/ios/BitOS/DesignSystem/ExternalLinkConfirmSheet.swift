import SwiftUI

/**
 * External-link confirm sheet: the browser only opens on an explicit Open
 * (user decision 2026-08-29). Display-only — the URL is shown truncated,
 * never pre-fetched.
 */
struct ExternalLinkConfirmSheet: View {
    let url: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
            Capsule()
                .fill(BitOSTheme.border)
                .frame(width: 36, height: 4)
                .frame(maxWidth: .infinity)
                .padding(.top, 8)
            Text("Open external link?")
                .font(.headline)
                .foregroundStyle(BitOSTheme.textPrimary)
                .padding(.top, 4)
            Text(url)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(BitOSTheme.textSecondary)
                .lineLimit(3)
                .truncationMode(.middle)
            Text("This link leaves BitOS.")
                .font(.caption2)
                .foregroundStyle(BitOSTheme.textTertiary)
            HStack(spacing: BitOSTheme.Spacing.sm) {
                Button {
                    if let target = URL(string: url) {
                        UIApplication.shared.open(target)
                    }
                    dismiss()
                } label: {
                    Text("Open")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color(red: 0.04, green: 0.04, blue: 0.06))
                        .padding(.horizontal, 20)
                        .padding(.vertical, 9)
                        .background(BitOSTheme.accent, in: Capsule())
                }
                .buttonStyle(.plain)
                Button {
                    dismiss()
                } label: {
                    Text("Cancel")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(BitOSTheme.accent)
                }
                .buttonStyle(.plain)
                Spacer()
            }
        }
        .padding(.horizontal, BitOSTheme.Spacing.base)
        .padding(.bottom, BitOSTheme.Spacing.lg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(BitOSTheme.surface)
        .presentationDetents([.medium])
    }
}
