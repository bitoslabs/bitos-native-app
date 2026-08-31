import SwiftUI
import UIKit

/**
 * Identity confirmation gate (ID-004), shared by the You tab and the
 * More-hub add-account sheet. For a freshly generated key this is also the
 * one-time backup moment: the secret can be revealed and copied here —
 * deliberately, never automatically — and the confirm action stays disabled
 * until the user acknowledges the backup (mockup scr-backup gate).
 */
struct ConfirmIdentitySheet: View {
    let preview: IdentityPreview
    /// nsec of the pending key; passed only for generated keys (backup reveal).
    let secretNsec: String?
    let busy: Bool
    let onConfirm: () -> Void
    let onCancel: () -> Void

    @State private var revealed = false
    @State private var savedAcknowledged = false

    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.base) {
            Capsule()
                .fill(BitOSTheme.surfaceOverlay)
                .frame(width: 36, height: 4)
                .padding(.top, BitOSTheme.Spacing.sm)
            Text(title)
                .font(.headline)
            Text(bodyCopy)
                .font(.footnote)
                .foregroundStyle(BitOSTheme.textSecondary)
                .multilineTextAlignment(.center)
            npubBlock
            if preview.isNewKey, let secretNsec {
                BackupRevealBlock(
                    secretNsec: secretNsec,
                    revealed: $revealed
                )
                BackupAcknowledgementRow(
                    title: "I saved my key somewhere safe and understand it can't be recovered.",
                    checked: $savedAcknowledged
                )            } else if !preview.isNewKey {
                Text("Imported keys are already in your possession; no backup is needed here.")
                    .font(.caption2)
                    .foregroundStyle(BitOSTheme.textTertiary)
                    .multilineTextAlignment(.center)
            }
            HStack(spacing: BitOSTheme.Spacing.base) {
                Button("Cancel", role: .cancel) { onCancel() }
                    .buttonStyle(.bordered)
                Button(confirmLabel) { onConfirm() }
                    .buttonStyle(.borderedProminent)
                    .tint(BitOSTheme.accent)
                    .disabled(busy || (preview.isNewKey && !savedAcknowledged))
            }
            Spacer()
        }
        .padding(BitOSTheme.Spacing.base)
        .background(BitOSTheme.background)
    }

    private var title: String {
        switch (preview.replacesExisting, preview.isNewKey) {
        case (true, _): return "Switch identity?"
        case (false, true): return "Your new identity"
        default: return "Confirm your identity"
        }
    }

    private var bodyCopy: String {
        if preview.replacesExisting {
            return "This account becomes the ACTIVE identity; every saved account stays sealed on this device."
        }
        if preview.isNewKey {
            return "This identity was just generated on this device. Write the secret down now — it cannot be recovered later."
        }
        return "This is the public identity derived from your key. Check that it matches the account you expect."
    }

    private var confirmLabel: String {
        if busy { return "Storing…" }
        return preview.isNewKey ? "I saved my key" : "Use this identity"
    }

    private var npubBlock: some View {
        HStack(spacing: BitOSTheme.Spacing.xs) {
            Text(preview.npub)
                .font(.caption.monospaced())
                .foregroundStyle(BitOSTheme.accent)
                .lineLimit(2)
                .truncationMode(.middle)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                UIPasteboard.general.string = preview.npub
            } label: {
                Image(systemName: "doc.on.doc")
                    .font(.system(size: 14))
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .frame(width: 30, height: 30)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Copy public key (npub)")
        }
        .padding(BitOSTheme.Spacing.sm)
        .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.surfaceElevated))
    }
}

/// One-time backup reveal for a generated key: explicit show + copy.
private struct BackupRevealBlock: View {
    let secretNsec: String
    @Binding var revealed: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.xs) {
            HStack(spacing: BitOSTheme.Spacing.xs) {
                Image(systemName: "key")
                    .font(.system(size: 13))
                    .foregroundStyle(BitOSTheme.warning)
                Text("Secret key (backup)")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(BitOSTheme.textSecondary)
                Spacer()
                Button(revealed ? "Hide" : "Show") { revealed.toggle() }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(BitOSTheme.accent)
                    .buttonStyle(.plain)
                    .accessibilityLabel(revealed ? "Hide secret key" : "Show secret key")
            }
            if revealed {
                HStack(spacing: BitOSTheme.Spacing.xs) {
                    Text(secretNsec)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(BitOSTheme.textPrimary)
                        .lineLimit(2)
                        .truncationMode(.middle)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                    Button {
                        UIPasteboard.general.string = secretNsec
                    } label: {
                        Image(systemName: "doc.on.doc")
                            .font(.system(size: 14))
                            .foregroundStyle(BitOSTheme.textSecondary)
                            .frame(width: 30, height: 30)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Copy secret key (nsec)")
                }
                Text("Write it down and store it somewhere safe. Never share it — anyone holding it controls the account.")
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.warning)
            } else {
                Text("Shown once here while creating the account; after this it stays sealed.")
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.textTertiary)
            }
        }
        .padding(BitOSTheme.Spacing.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.surfaceElevated))
    }
}

/// CB-2: the backup gate only opens once the user owns the consequence.
struct BackupAcknowledgementRow: View {
    let title: String
    @Binding var checked: Bool

    var body: some View {
        Button {
            checked.toggle()
        } label: {
            HStack(alignment: .top, spacing: BitOSTheme.Spacing.sm) {
                ZStack {
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(BitOSTheme.surfaceElevated)
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .strokeBorder(BitOSTheme.border, lineWidth: 1)
                    if checked {
                        Image(systemName: "checkmark")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(BitOSTheme.accent)
                    }
                }
                .frame(width: 24, height: 24)
                Text(title)
                    .font(.system(size: 12))
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .multilineTextAlignment(.leading)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityAddTraits(checked ? [.isSelected] : [])
    }
}
