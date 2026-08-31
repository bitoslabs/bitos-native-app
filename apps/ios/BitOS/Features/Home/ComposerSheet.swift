import SwiftUI

/// Note composer sheet (PUB-001 note path): identity-gated publish with
/// live relay receipts. Signer refusal and relay rejections are shown,
/// never swallowed.
struct ComposerSheet: View {
    @Bindable var publisher: NotePublisher
    let onClose: () -> Void
    @Environment(IdentityStore.self) private var identity
    @State private var text = ""
    @State private var powTarget = 0
    @State private var powOutcome: PowOutcome?

    private static let maxNoteLength = 2000

    var body: some View {
        NavigationStack {
            content
                .padding(BitOSTheme.Spacing.screen)
                .background(BitOSTheme.background)
                .navigationTitle("New note")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        SheetCloseButton(action: onClose)
                    }
                }
        }
        .preferredColorScheme(BitOSTheme.preferredScheme)
    }

    @ViewBuilder
    private var content: some View {
        if publisher.inFlightId != nil || publisher.result != nil {
            PublishStatus(publisher: publisher, onClose: onClose)
        } else if identity.account == nil {
            IdentityGateNotice(onClose: onClose)
        } else {
            VStack(spacing: BitOSTheme.Spacing.md) {
                TextEditor(text: $text)
                    .frame(minHeight: 120)
                    .scrollContentBackground(.hidden)
                    .padding(BitOSTheme.Spacing.sm)
                    .background(
                        RoundedRectangle(cornerRadius: BitOSTheme.Radius.md)
                            .fill(BitOSTheme.surface)
                    )
                    .onChange(of: text) { _, newValue in
                        if newValue.count > Self.maxNoteLength {
                            text = String(newValue.prefix(Self.maxNoteLength))
                        }
                    }
                HStack {
                    Text("\(text.count)/\(Self.maxNoteLength)")
                        .font(.caption2)
                        .foregroundStyle(text.count >= Self.maxNoteLength ? BitOSTheme.error : BitOSTheme.textTertiary)
                    Spacer()
                    Button("Publish") {
                        if let pow = powOutcome {
                            Task {
                                await publisher.publishPow(
                                    content: text,
                                    nonce: pow.nonce,
                                    targetDifficulty: Int32(pow.targetDifficulty),
                                    createdAt: pow.createdAt
                                )
                            }
                        } else {
                            Task { await publisher.publish(content: text) }
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(BitOSTheme.accent)
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || publisher.busy)
                }
                // APP-008: optional NIP-13 proof-of-work. Card identity
                // follows content + target — a mined nonce is valid for
                // exactly one template, so any edit resets the session.
                PowCard(
                    target: $powTarget,
                    mineChunk: { createdAt, startNonce, attempts in
                        await publisher.minePowChunk(
                            content: text,
                            targetDifficulty: Int32(powTarget),
                            createdAt: createdAt,
                            startNonce: startNonce,
                            attempts: attempts
                        )
                    },
                    onMined: { powOutcome = $0 }
                )
                .id("\(powTarget)-\(text)")
            }
        }
    }
}

private struct IdentityGateNotice: View {
    let onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            Text("Signing needs an identity")
                .font(.subheadline.weight(.semibold))
            Text("Open the Profile tab to create or import a key, then come back to publish. Nothing is created silently.")
                .font(.caption)
                .foregroundStyle(BitOSTheme.textSecondary)
            SheetCloseButton(action: onClose)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(BitOSTheme.Spacing.base)
        .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.lg).fill(BitOSTheme.surface))
    }
}

private struct PublishStatus: View {
    let publisher: NotePublisher
    let onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            switch publisher.result {
            case nil:
                HStack(spacing: BitOSTheme.Spacing.sm) {
                    ProgressView().tint(BitOSTheme.accent)
                    Text("Publishing…").font(.subheadline.weight(.semibold))
                }
            case .published:
                Label("Published", systemImage: "checkmark.circle.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(BitOSTheme.success)
            case .signingRefused, .invalid:
                Label("The note was not sent.", systemImage: "xmark.octagon.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(BitOSTheme.error)
            case .rejected(let detail):
                Label(detail ?? "Relays rejected the note.", systemImage: "xmark.octagon.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(BitOSTheme.error)
            case .timeout:
                Label("No relay acknowledged in time.", systemImage: "clock.badge.exclamationmark")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(BitOSTheme.warning)
            }

            ForEach(publisher.receipts) { receipt in
                HStack {
                    Text(receipt.relayHost)
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                    Spacer()
                    Text(receipt.accepted == true ? "accepted" : receipt.accepted == false ? "rejected" : "waiting…")
                        .font(.caption)
                        .foregroundStyle(
                            receipt.accepted == true ? BitOSTheme.success
                                : receipt.accepted == false ? BitOSTheme.error
                                : BitOSTheme.textTertiary
                        )
                }
            }

            if publisher.result != nil {
                Button("Done", action: onClose)
                    .buttonStyle(.borderedProminent)
                    .tint(BitOSTheme.accent)
            }
        }
        .padding(BitOSTheme.Spacing.base)
        .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.lg).fill(BitOSTheme.surface))
    }
}
