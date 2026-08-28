import SwiftUI

/**
 * APP-017 More hub (legacy Flutter `MoreView` parity — the web NavRail
 * destination list collapsed into one page, opened from the feed apps-grid
 * action): profile hero + account switch row + stat tiles + tile groups +
 * meta rows. Tiles whose surfaces are later waves render disabled with
 * honest captions — never dead links.
 */
struct MoreView: View {
    let onOpenProfile: () -> Void
    let onOpenDiscover: () -> Void
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity
    @Environment(SettingsStore.self) private var settings
    @Environment(RelayManagerStore.self) private var relays
    @Environment(\.dismiss) private var dismiss

    @State private var showSettingsSection: String?
    @State private var showSwitcher = false
    @State private var showAddAccount = false
    @State private var importText = ""
    @State private var importError: String?
    @State private var connectionStates: [String: String] = [:]
    @State private var health = RelayHealth(connected: 0, total: 0)

    var body: some View {
        NavigationStack {
            List {
                if let account = identity.account {
                    let feed = environment.feedStore
                    let profile = feed.profiles[account.pubkeyHex]
                    Section {
                        HStack(spacing: 12) {
                            PubkeyAvatarView(pubkey: account.pubkeyHex, size: 48)
                            VStack(alignment: .leading, spacing: 2) {
                                Text((profile?.displayName ?? profile?.name).flatMap { $0.isEmpty ? nil : $0 } ?? "Your account")
                                    .font(.system(size: 16, weight: .bold))
                                Button {
                                    UIPasteboard.general.string = account.npub
                                } label: {
                                    Text(settings.shortNpub(account.npub))
                                        .font(.system(size: 11, design: .monospaced))
                                        .foregroundStyle(BitOSTheme.accent)
                                }
                                .buttonStyle(.plain)
                            }
                            Spacer()
                        }
                        .padding(.vertical, 2)
                    }
                    Section {
                        Button {
                            showSwitcher = true
                        } label: {
                            HStack {
                                Text("Switch account")
                                    .font(.system(size: 13))
                                    .foregroundStyle(BitOSTheme.textSecondary)
                                Spacer()
                                Text("\(identity.registeredAccounts.count) on this device")
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundStyle(BitOSTheme.accent)
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 12))
                                    .foregroundStyle(BitOSTheme.textTertiary)
                            }
                        }
                        .buttonStyle(.plain)
                    } footer: {
                        Text("Switching keeps every account sealed on this device.")
                    }
                    Section {
                        HStack(spacing: 20) {
                            stat("Following", "\(feed.following.count)")
                            stat("Relays", "\(health.connected)/\(health.total)")
                        }
                    }
                } else {
                    Section {
                        VStack(spacing: 8) {
                            Text("Browse freely")
                                .font(.system(size: 16, weight: .bold))
                            Text("Create or import a key on the You tab to publish, zap and follow.")
                                .font(.system(size: 13))
                                .foregroundStyle(BitOSTheme.textSecondary)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                    }
                }
                Section("Explore") {
                    tile("Discover", caption: "Search Nostr", symbol: AppIcons.search) {
                        onOpenDiscover()
                    }
                    tile("Lightning & Zaps", caption: "Default zap amount", symbol: AppIcons.zap) {
                        showSettingsSection = "lightning"
                    }
                }
                Section("Account") {
                    tile("Profile", caption: "Your identity", symbol: AppIcons.user) {
                        onOpenProfile()
                    }
                    tile("Settings", caption: "Preferences & relays", symbol: AppIcons.settings) {
                        showSettingsSection = "about"
                    }
                }
                Section("Coming soon") {
                    Label("Saved — bookmarks page (APP-015)", image: "SolarBookmarkLinear")
                        .foregroundStyle(BitOSTheme.textSecondary)
                    Label("Zap ledger — wallet page (APP-014)", image: "SolarBoltLinear")
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
            }
            .navigationTitle("More")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .navigationDestination(isPresented: Binding(
                get: { showSettingsSection != nil },
                set: { if !$0 { showSettingsSection = nil } }
            )) {
                SettingsSectionView(sectionKey: showSettingsSection ?? "about")
            }
            .sheet(isPresented: $showSwitcher) {
                AccountSwitcherSheet(
                    identity: identity,
                    settings: settings,
                    onAddAccount: { showSwitcher = false; showAddAccount = true },
                    onManage: { showSwitcher = false; onOpenProfile() }
                )
                .environment(environment)
                .environment(settings)
                .presentationDetents([.medium, .large])
            }
            .sheet(isPresented: $showAddAccount) {
                AddAccountSheet(
                    identity: identity,
                    importText: $importText,
                    importError: $importError
                )
                .environment(environment)
                .presentationDetents([.medium])
            }
            .task {
                // Live relay tiles while the hub is visible.
                while !Task.isCancelled {
                    health = await relays.health()
                    await relays.refreshConnectionStates()
                    try? await Task.sleep(for: .seconds(2))
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private func stat(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(value).font(.headline.monospaced())
            Text(label).font(.caption2).foregroundStyle(BitOSTheme.textTertiary)
        }
    }

    private func tile(_ label: String, caption: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                AppIcons.image(for: symbol)
                    .font(.system(size: 18))
                    .foregroundStyle(BitOSTheme.accent)
                    .frame(width: 36, height: 36)
                VStack(alignment: .leading, spacing: 1) {
                    Text(label)
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(BitOSTheme.textPrimary)
                    Text(caption)
                        .font(.system(size: 12))
                        .foregroundStyle(BitOSTheme.textTertiary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 12))
                    .foregroundStyle(BitOSTheme.textTertiary)
            }
        }
        .buttonStyle(.plain)
    }
}

/// One-tap account switcher (legacy `AccountSwitcherSheet` parity).
private struct AccountSwitcherSheet: View {
    let identity: IdentityStore
    let settings: SettingsStore
    let onAddAccount: () -> Void
    let onManage: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
            Capsule()
                .fill(BitOSTheme.surfaceOverlay)
                .frame(width: 36, height: 4)
                .frame(maxWidth: .infinity)
            HStack {
                Text("Switch account")
                    .font(.system(size: 20, weight: .bold))
                Spacer()
                Text("\(identity.registeredAccounts.count) saved on this device")
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.textTertiary)
            }
            if identity.registeredAccounts.isEmpty {
                Text("No saved accounts yet.")
                    .font(.system(size: 13))
                    .foregroundStyle(BitOSTheme.textSecondary)
            } else {
                ForEach(identity.registeredAccounts) { acct in
                    let isActive = acct.pubkeyHex == (identity.activeRegistryPubkey ?? identity.account?.pubkeyHex)
                    Button {
                        guard !isActive, !identity.busy else { return }
                        identity.switchTo(pubkeyHex: acct.pubkeyHex)
                    } label: {
                        HStack(spacing: 12) {
                            PubkeyAvatarView(pubkey: acct.pubkeyHex, size: 40)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(acct.displayName ?? "Account")
                                    .font(.system(size: 15, weight: isActive ? .bold : .medium))
                                    .foregroundStyle(BitOSTheme.textPrimary)
                                Text(settings.shortNpub(acct.npub))
                                    .font(.system(size: 11, design: .monospaced))
                                    .foregroundStyle(BitOSTheme.textSecondary)
                            }
                            Spacer()
                            if isActive {
                                Text("Active")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundStyle(BitOSTheme.accent)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            Divider()
            Button {
                onAddAccount()
            } label: {
                Text("+ Add account")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(BitOSTheme.accent)
            }
            .buttonStyle(.plain)
            Button {
                onManage()
            } label: {
                Text("Manage accounts (You tab)")
                    .font(.system(size: 13))
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            .buttonStyle(.plain)
            Spacer(minLength: 8)
        }
        .padding(BitOSTheme.Spacing.base)
        .background(BitOSTheme.background)
    }
}

/// Add-account sheet: import nsec or create a fresh key (legacy parity).
private struct AddAccountSheet: View {
    let identity: IdentityStore
    @Binding var importText: String
    @Binding var importError: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
            Text("Add account")
                .font(.system(size: 20, weight: .bold))
            Text("Import an nsec or create a fresh key. Every account already on this device stays sealed.")
                .font(.system(size: 12))
                .foregroundStyle(BitOSTheme.textSecondary)
            TextField("nsec1\u{2026}", text: $importText)
                .font(.system(size: 13, design: .monospaced))
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .textFieldStyle(.roundedBorder)
            if let importError {
                Text(importError)
                    .font(.system(size: 12))
                    .foregroundStyle(BitOSTheme.error)
            }
            HStack {
                Button {
                    identity.importKeyPreview(importText)
                    if identity.importError == nil, identity.preview != nil {
                        importError = nil
                    } else {
                        importError = identity.importError
                    }
                } label: {
                    Text("Review key")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(BitOSTheme.accent)
                }
                .disabled(importText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Button("Create new key") {
                    identity.createKeyPreview()
                }
                .foregroundStyle(BitOSTheme.accent)
                Spacer()
                Button("Cancel") { dismiss() }
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            Spacer(minLength: 8)
        }
        .padding(BitOSTheme.Spacing.base)
        .background(BitOSTheme.background)
        .sheet(item: Binding(
            get: { identity.preview },
            set: { _ in }
        )) { preview in
            VStack(spacing: BitOSTheme.Spacing.base) {
                Text("Confirm your identity")
                    .font(.system(size: 18, weight: .bold))
                Text(preview.npub)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(BitOSTheme.accent)
                    .lineLimit(2)
                    .truncationMode(.middle)
                Text("This account becomes the ACTIVE identity; every saved account stays sealed. Back up new keys now \u{2014} they cannot be recovered from this device.")
                    .font(.system(size: 12))
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .multilineTextAlignment(.center)
                HStack {
                    Button("Cancel") {
                        identity.cancelPreview()
                        dismiss()
                    }
                    Button("Use this identity") {
                        identity.confirmPreview()
                        dismiss()
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(BitOSTheme.accent)
                    .disabled(identity.busy)
                }
            }
            .padding(BitOSTheme.Spacing.base)
            .presentationDetents([.medium])
        }
    }
}
