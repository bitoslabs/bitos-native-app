import SwiftUI

/// Chats tab placeholder (APP-011 §3.11, Wave 2): honest empty state —
/// NIP-17 DMs are not implemented yet; nothing fake is shown.
struct ChatsView: View {
    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.md) {
            Spacer()
            Image(systemName: AppIcons.chat)
                .font(.system(size: 48, weight: .medium))
                .foregroundStyle(BitOSTheme.textTertiary)
            Text("Messages")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(BitOSTheme.textPrimary)
            Text("End-to-end encrypted DMs (NIP-17) arrive in Wave 2.\nNothing to show yet.")
                .font(.system(size: 13))
                .foregroundStyle(BitOSTheme.textSecondary)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(BitOSTheme.background)
        .accessibilityElement(children: .combine)
    }
}
