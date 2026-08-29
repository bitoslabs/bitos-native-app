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
    @State private var showZaps = false
    @State private var showQr = false
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity
    @Environment(SettingsStore.self) private var settings

    init(store: IdentityStore) {
        _store = State(initialValue: store)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: BitOSTheme.Spacing.md) {
                    if let account = store.account {
                        accountPanel(account)
                        // APP-014: zap wallet entry.
                        Button {
                            showZaps = true
                        } label: {
                            HStack {
                                Text("⚡ Zap wallet")
                                    .font(.system(size: 14, weight: .bold))
                                Spacer()
                                Text("Ledger")
                                    .font(.system(size: 11))
                                    .foregroundStyle(BitOSTheme.textTertiary)
                            }
                            .padding(BitOSTheme.Spacing.md)
                            .background(
                                RoundedRectangle(cornerRadius: BitOSTheme.Radius.md, style: .continuous)
                                    .strokeBorder(BitOSTheme.divider)
                            )
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Open zap wallet")
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
            .fullScreenCover(isPresented: $showZaps) {
            ZapsView { showZaps = false }
                .environment(identity)
        }
        .sheet(isPresented: $showSettings) {
                NavigationStack { SettingsView() }
                    .environment(environment)
            }
        }
        .sheet(isPresented: $showQr) {
            VStack(spacing: BitOSTheme.Spacing.base) {
                Text("Your identity QR")
                    .font(.system(size: 18, weight: .bold))
                if let account = store.account {
                    BrandQrCodeView(value: account.npub, size: 224)
                    Text("Scan with any Nostr app to follow \(settings.shortNpub(account.npub))")
                        .font(.system(size: 12))
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .multilineTextAlignment(.center)
                    Button("Copy npub") {
                        UIPasteboard.general.string = account.npub
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(BitOSTheme.accent)
                }
            }
            .padding(BitOSTheme.Spacing.base)
            .presentationDetents([.medium])
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

    @State private var ownTab = 0

    private func accountPanel(_ account: AccountIdentity) -> some View {
        let feed = environment.feedStore
        let profile = feed.profiles[account.pubkeyHex]
        let own = feed.notes.filter { $0.pubkey == account.pubkeyHex || $0.repostedBy == account.pubkeyHex }
        let tabs: [(String, [FeedNote])] = [
            ("Notes", own.filter { $0.replyTo == nil && $0.repostedBy == nil }),
            ("Replies", own.filter { $0.replyTo != nil && $0.repostedBy == nil }),
            ("Bitz", own.filter { $0.video != nil }),
            ("Reposts", own.filter { $0.repostedBy == account.pubkeyHex }),
        ]
        return VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
            // Hero: gradient cover + overlapping hex avatar (web 160/104 parity).
            ZStack(alignment: .bottomLeading) {
                LinearGradient(
                    colors: [BitOSTheme.accent.opacity(0.35), BitOSTheme.reply.opacity(0.25)],
                    startPoint: .topLeading, endPoint: .bottomTrailing
                )
                .frame(height: 96)
                .clipShape(RoundedRectangle(cornerRadius: 20))
                PubkeyAvatarView(pubkey: account.pubkeyHex, size: 84)
                    .offset(y: 42)
            }
            .padding(.bottom, 42)
            VStack(alignment: .leading, spacing: 4) {
                Text((profile?.displayName ?? profile?.name).flatMap { $0.isEmpty ? nil : $0 } ?? "Your account")
                    .font(.title2.weight(.bold))
                HStack(spacing: 8) {
                    Button {
                        UIPasteboard.general.string = account.npub
                    } label: {
                        Text(settings.shortNpub(account.npub))
                            .font(.caption.monospaced())
                            .foregroundStyle(BitOSTheme.accent)
                    }
                    .buttonStyle(.plain)
                    Button {
                        showQr = true
                    } label: {
                        Text("QR")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(BitOSTheme.accent)
                    }
                    .buttonStyle(.plain)
                }
                if let nip05 = profile?.nip05, !nip05.isEmpty {
                    Text("\u{2713} \(nip05)").font(.caption).foregroundStyle(BitOSTheme.success)
                }
                if let about = profile?.about, !about.isEmpty {
                    Text(about).font(.footnote).foregroundStyle(BitOSTheme.textSecondary).lineLimit(4)
                }
            }
            HStack(spacing: 20) {
                stat("Following", "\(feed.following.count)")
                stat("Notes", "\(tabs[0].1.count)")
                stat("Bitz", "\(tabs[2].1.count)")
            }
            HStack(spacing: BitOSTheme.Spacing.sm) {
                Button { showEdit = true } label: {
                    Label { Text("Edit profile") } icon: { AppIcons.image(for: AppIcons.pen) }
                }
                .buttonStyle(.borderedProminent)
                .tint(BitOSTheme.accent)
                Button { showSettings = true } label: {
                    Label { Text("Settings") } icon: { AppIcons.image(for: AppIcons.settings) }
                }
                .buttonStyle(.bordered)
            }
            HStack(spacing: 8) {
                ForEach(Array(tabs.enumerated()), id: \.offset) { index, entry in
                    let selected = ownTab == index
                    Button {
                        ownTab = index
                    } label: {
                        Text(entry.0)
                            .font(.system(size: 13, weight: selected ? .bold : .medium))
                            .foregroundStyle(selected ? BitOSTheme.accent : BitOSTheme.textSecondary)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(
                                selected ? BitOSTheme.accent.opacity(0.15) : BitOSTheme.surfaceOverlay.opacity(0.5),
                                in: Capsule()
                            )
                    }
                    .buttonStyle(.plain)
                }
            }
            let content = tabs[ownTab].1
            if content.isEmpty {
                Text("Nothing here yet \u{2014} this tab shows your notes currently in the live feed window.")
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textTertiary)
            } else {
                ForEach(content.prefix(20), id: \.id) { note in
                    ownNoteRow(note)
                }
            }
        }
    }

    private func stat(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(value).font(.headline.monospaced())
            Text(label).font(.caption2).foregroundStyle(BitOSTheme.textTertiary)
        }
    }

    private func ownNoteRow(_ note: FeedNote) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Circle()
                .fill(note.video != nil ? BitOSTheme.reply : BitOSTheme.accent)
                .frame(width: 8, height: 8)
                .padding(.top, 5)
            Text(note.content.isEmpty ? "(media)" : String(note.content.prefix(120)))
                .font(.footnote)
                .lineLimit(2)
                .foregroundStyle(BitOSTheme.textPrimary)
        }
        .padding(.vertical, 2)
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
