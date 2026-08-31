import BusinessCore
import SwiftUI
import UIKit


/**
 * Secret-key login field (ID-004), shared by the onboarding import step, the
 * You-tab import panel and the More-hub add-account sheet: masked by default
 * with reveal + one-tap paste, keyboard flags that never mangle a pasted
 * key, and live feedback from the shared `KeyImportForm` rule (verbatim copy
 * parity with Compose) rendered as an icon+text state banner.
 */
struct SecretKeyField: View {
    @Binding var text: String
    var error: String?
    var onSubmit: () -> Void = {}

    @State private var revealed = false

    private var check: BusinessCoreBridge.KeyImportCheckWire {
        BusinessCoreBridge().keyImportCheck(raw: text)
    }

    private var ready: Bool { check.verdict == "READY" }
    /// A submit error outranks the live hint; both clear on the next edit.
    private var feedback: String? { error ?? check.message }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 0) {
                Group {
                    if revealed {
                        TextField("nsec1…", text: $text)
                    } else {
                        SecureField("nsec1…", text: $text)
                    }
                }
                .font(.system(size: 14, design: .monospaced))
                .foregroundStyle(BitOSTheme.textPrimary)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .keyboardType(.asciiCapable)
                .submitLabel(.continue)
                .onSubmit(onSubmit)
                .accessibilityLabel("Secret key")
                if text.isEmpty {
                    pasteButton
                }
                revealButton
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(BitOSTheme.surfaceElevated)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(borderColor, lineWidth: 1)
            )
            if let feedback {
                StateBannerView(
                    tone: ready ? .ok : .error,
                    text: feedback
                )
            }
        }
        .animation(.easeOut(duration: 0.15), value: feedback)
    }

    private var borderColor: Color {
        if ready { return BitOSTheme.success }
        if feedback != nil { return BitOSTheme.error }
        return BitOSTheme.divider
    }

    private var pasteButton: some View {
        Button {
            if let pasted = UIPasteboard.general.string {
                text = pasted
            }
        } label: {
            Text("Paste")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(BitOSTheme.textSecondary)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Capsule().fill(BitOSTheme.surfaceOverlay))
                .overlay(Capsule().strokeBorder(BitOSTheme.border, lineWidth: 1))
                .padding(.trailing, 6)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Paste key from clipboard")
    }

    private var revealButton: some View {
        Button {
            revealed.toggle()
        } label: {
            Image(systemName: revealed ? "eye.slash" : "eye")
                .font(.system(size: 15))
                .foregroundStyle(BitOSTheme.textSecondary)
                .frame(width: 34, height: 34)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(revealed ? "Hide key" : "Show key")
    }
}

/**
 * Live derived-identity preview (KF-6): once the shared rule resolves the
 * input to a usable secret, this shows the account the key controls — hex
 * avatar over the derived pubkey, monospace npub and a copy chip — before
 * anything is stored.
 */
struct DerivedIdentityCard: View {
    let check: BusinessCoreBridge.KeyImportCheckWire

    var body: some View {
        HStack(spacing: 12) {
            if let pubkey = check.pubkeyHex {
                HexAvatarView(pubkey: pubkey, size: 40)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("Derived identity")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                if let npub = check.npub {
                    Text(middleEllipsized(npub))
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let npub = check.npub {
                Button {
                    UIPasteboard.general.string = npub
                } label: {
                    Label("Copy npub", systemImage: "doc.on.doc")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(BitOSTheme.accent)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Capsule().fill(BitOSTheme.accent.opacity(0.14)))
                        .overlay(Capsule().strokeBorder(BitOSTheme.accent.opacity(0.4), lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Copy npub")
            }
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(BitOSTheme.surfaceElevated)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(BitOSTheme.border, lineWidth: 1)
        )
    }
}

/** True when the field text resolves to a usable secret key. */
func secretKeyReady(_ text: String) -> Bool {
    BusinessCoreBridge().keyImportCheck(raw: text).verdict == "READY"
}
