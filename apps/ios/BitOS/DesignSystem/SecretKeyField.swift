import BusinessCore
import SwiftUI
import UIKit


/**
 * Secret-key login field (ID-004), shared by the You-tab import panel and
 * the More-hub add-account sheet: masked by default with reveal + one-tap
 * paste, keyboard flags that never mangle a pasted key, and live feedback
 * from the shared `KeyImportForm` rule (verbatim copy parity with Compose).
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
        VStack(alignment: .leading, spacing: 6) {
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
                    .strokeBorder(
                        feedback != nil && !ready ? BitOSTheme.error : BitOSTheme.divider
                    )
            )
            if let feedback {
                Text(feedback)
                    .font(.system(size: 12))
                    .foregroundStyle(ready ? BitOSTheme.success : BitOSTheme.error)
                    .accessibilityLabel(ready ? "Valid key" : feedback)
            }
        }
        .animation(.easeOut(duration: 0.15), value: feedback)
    }

    private var pasteButton: some View {
        Button {
            if let pasted = UIPasteboard.general.string {
                text = pasted
            }
        } label: {
            Image(systemName: "doc.on.clipboard")
                .font(.system(size: 15))
                .foregroundStyle(BitOSTheme.textSecondary)
                .frame(width: 34, height: 34)
                .contentShape(Rectangle())
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

/** True when the field text resolves to a usable secret key. */
func secretKeyReady(_ text: String) -> Bool {
    BusinessCoreBridge().keyImportCheck(raw: text).verdict == "READY"
}
