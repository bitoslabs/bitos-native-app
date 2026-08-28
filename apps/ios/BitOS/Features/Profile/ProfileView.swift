import SwiftUI

/// Profile surface with the account flow (ID-004): browse-first by default —
/// identity creation/import is always explicit and visibly confirms the
/// derived npub before anything is stored or replaced.
struct ProfileView: View {
    @State private var store: IdentityStore
    @State private var importText = ""
    @State private var confirmRemove = false
    @State private var showEdit = false
    @State private var showSettings = false
    @Environment(AppEnvironment.self) private var environment

    init(store: IdentityStore) {
        _store = State(initialValue: store)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: BitOSTheme.Spacing.md) {
                    if let account = store.account {
                        accountPanel(account)
                    } else {
                        browsePanel
                        importPanel
                    }
                }
                .padding(BitOSTheme.Spacing.screen)
            }
            .background(BitOSTheme.background)
            .navigationTitle("You")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showSettings = true
                    } label: {
                        AppIcons.image(for: AppIcons.settings)
                    }
                    .accessibilityLabel("Settings")
                }
            }
            .sheet(isPresented: $showSettings) {
                NavigationStack { SettingsView() }
                    .environment(environment)
            }
        }
        .sheet(isPresented: $showEdit) {
            ProfileEditSheet(
                publisher: environment.notePublisher,
                onClose: { showEdit = false }
            )
            .presentationDetents([.medium, .large])
        }
        .sheet(item: $store.preview) { preview in
            ConfirmIdentitySheet(
                preview: preview,
                busy: store.busy,
                onConfirm: { store.confirmPreview() },
                onCancel: { store.cancelPreview() }
            )
            .presentationDetents([.medium])
        }
    }

    private func accountPanel(_ account: AccountIdentity) -> some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            HStack(spacing: BitOSTheme.Spacing.md) {
                PubkeyAvatarView(pubkey: account.pubkeyHex, size: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Local identity active")
                        .font(.subheadline.weight(.semibold))
                    Text("Secret sealed in the Keychain")
                        .font(.caption)
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
            }
            Text(account.npub)
                .font(.caption.monospaced())
                .foregroundStyle(BitOSTheme.textSecondary)
                .textSelection(.enabled)
            HStack(spacing: BitOSTheme.Spacing.sm) {
                Button { showEdit = true } label: {
                    Label { Text("Edit profile") } icon: { AppIcons.image(for: AppIcons.pen) }
                }
                    .buttonStyle(.borderedProminent)
                    .tint(BitOSTheme.accent)
                Button("Copy npub") {
                    UIPasteboard.general.string = account.npub
                }
                .buttonStyle(.bordered)
                Button("Remove", role: .destructive) {
                    confirmRemove = true
                }
                .buttonStyle(.bordered)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(BitOSTheme.Spacing.base)
        .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.lg).fill(BitOSTheme.surface))
        .confirmationDialog(
            "Remove identity?",
            isPresented: $confirmRemove,
            titleVisibility: .visible
        ) {
            Button("Remove identity permanently", role: .destructive) {
                store.removeAccount()
            }
            Button("Keep", role: .cancel) {}
        } message: {
            Text("The sealed secret is deleted from this device. Without a backup you lose the account. This cannot be undone.")
        }
    }

    private var browsePanel: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            Text("Browsing without an identity")
                .font(.subheadline.weight(.semibold))
            Text("You can watch and explore anonymously. Actions that need a signature offer key creation or import below — nothing is created silently.")
                .font(.caption)
                .foregroundStyle(BitOSTheme.textSecondary)
            HStack(spacing: BitOSTheme.Spacing.sm) {
                Button {
                    store.createKeyPreview()
                } label: {
                    Label { Text("Create identity") } icon: { AppIcons.image(for: AppIcons.user) }
                }
                .buttonStyle(.borderedProminent)
                .tint(BitOSTheme.accent)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(BitOSTheme.Spacing.base)
        .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.lg).fill(BitOSTheme.surface))
    }

    private var importPanel: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
            Text("Import a secret key")
                .font(.subheadline.weight(.semibold))
            SecureField("nsec1…", text: $importText)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            if let error = store.importError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.error)
            }
            Button {
                store.importKeyPreview(importText)
            } label: {
                Label { Text("Review key") } icon: { AppIcons.image(for: AppIcons.qrCode) }
            }
            .buttonStyle(.bordered)
            .disabled(importText.trimmingCharacters(in: .whitespaces).isEmpty)
            Text("The key stays on this device, sealed in the Keychain. Never share an nsec.")
                .font(.caption2)
                .foregroundStyle(BitOSTheme.textTertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(BitOSTheme.Spacing.base)
        .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.lg).fill(BitOSTheme.surfaceElevated))
    }
}

extension IdentityPreview: Identifiable {
    var id: String { npub }
}

private struct ConfirmIdentitySheet: View {
    let preview: IdentityPreview
    let busy: Bool
    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: BitOSTheme.Spacing.base) {
            Capsule()
                .fill(BitOSTheme.surfaceOverlay)
                .frame(width: 36, height: 4)
                .padding(.top, BitOSTheme.Spacing.sm)
            Text(preview.replacesExisting ? "Replace identity?" : "Confirm your identity")
                .font(.headline)
            Text(
                preview.replacesExisting
                    ? "This REPLACES the identity currently stored on this device. Its secret is overwritten."
                    : "This is the public identity derived from your key. Verify it before continuing."
            )
            .font(.footnote)
            .foregroundStyle(BitOSTheme.textSecondary)
            .multilineTextAlignment(.center)
            Text(preview.npub)
                .font(.caption.monospaced())
                .foregroundStyle(BitOSTheme.accent)
                .padding(BitOSTheme.Spacing.sm)
                .frame(maxWidth: .infinity)
                .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.surfaceElevated))
            Text("Backup warning: if this is a new key, write the secret down now — it cannot be recovered from this device.")
                .font(.caption2)
                .foregroundStyle(BitOSTheme.warning)
                .multilineTextAlignment(.center)
            HStack(spacing: BitOSTheme.Spacing.base) {
                Button("Cancel", role: .cancel) { onCancel() }
                    .buttonStyle(.bordered)
                Button(preview.replacesExisting ? "Replace" : "Use this identity") { onConfirm() }
                    .buttonStyle(.borderedProminent)
                    .tint(BitOSTheme.accent)
                    .disabled(busy)
            }
            Spacer()
        }
        .padding(BitOSTheme.Spacing.base)
        .background(BitOSTheme.background)
    }
}
