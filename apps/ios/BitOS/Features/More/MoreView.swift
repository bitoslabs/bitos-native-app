import BusinessCore
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
    @State private var showStatic: String?
    @State private var showSwitcher = false
    @State private var switchTarget: RegisteredAccountRow?
    @State private var showQr = false
    @State private var showAddAccount = false
    @State private var importText = ""
    @State private var connectionStates: [String: String] = [:]
    @State private var health = RelayHealth(connected: 0, total: 0)
    /** APP-015: the Saved (bookmarks) page. */
    @State private var showSaved = false
    /** APP-014: the zap wallet (sent ledger + received receipts). */
    @State private var showZaps = false

    var body: some View {
        NavigationStack {
            List {
                if let account = identity.account {
                    let feed = environment.feedStore
                    let profile = feed.profiles[account.pubkeyHex]
                    Section {
                        HStack(spacing: 12) {
                            PubkeyAvatarView(pubkey: account.pubkeyHex, size: 48, picture: profile?.picture, label: profile?.bestDisplayName)
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
                            Button {
                                showQr = true
                            } label: {
                                Text("QR")
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundStyle(BitOSTheme.accent)
                            }
                            .buttonStyle(.plain)
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
                    // APP-014: same wallet surface the You page opens (menu
                    // item + "Sats zapped" pill) — one entry point per
                    // identity, no fork.
                    tile("Zap wallet", caption: "Sent & received sats", symbol: AppIcons.zap) {
                        showZaps = true
                    }
                    tile("Settings", caption: "Preferences & relays", symbol: AppIcons.settings) {
                        showSettingsSection = "about"
                    }
                }
                Section("Library") {
                    tile("Saved", caption: "Your bookmarked notes", symbol: AppIcons.bookmark) {
                        showSaved = true
                    }
                }
                Section("About") {
                    Button("About BitOS") { showStatic = "about" }
                        .foregroundStyle(BitOSTheme.textPrimary)
                    Button("Privacy Policy") { showStatic = "privacy" }
                        .foregroundStyle(BitOSTheme.textPrimary)
                    Button("Terms of Service") { showStatic = "terms" }
                        .foregroundStyle(BitOSTheme.textPrimary)
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
            // APP-020: static pages cover.
            .fullScreenCover(item: Binding(
                get: { showStatic.map { StaticPageTarget(page: $0) } },
                set: { showStatic = $0?.page }
            )) { target in
                StaticPagesScreen(initialPage: target.page) { showStatic = nil }
            }
            // APP-015: the Saved page is a full-screen cover over the hub.
            .fullScreenCover(isPresented: $showSaved) {
                BookmarksView()
                    .environment(environment)
            }
            // APP-014: the zap wallet is a full-screen cover over the hub
            // (same surface the You page presents).
            .fullScreenCover(isPresented: $showZaps) {
                ZapsView { showZaps = false }
                    .environment(environment)
                    .environment(environment.identityStore)
            }
            .fullScreenCover(item: $switchTarget) { target in
                AccountSwitchOverlayView(
                    fromPubkey: identity.activeRegistryPubkey ?? identity.account?.pubkeyHex,
                    toPubkey: target.pubkeyHex,
                    toName: target.displayName ?? (String(target.npub.prefix(10)) + "\u{2026}"),
                    switchAction: { identity.switchTo(pubkeyHex: target.pubkeyHex) },
                    onFinished: {
                        switchTarget = nil
                    }
                )
            }
            .sheet(isPresented: $showQr) {
                VStack(spacing: BitOSTheme.Spacing.base) {
                    Text("Your identity QR")
                        .font(.system(size: 18, weight: .bold))
                    if let account = identity.account {
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
            .sheet(isPresented: $showSwitcher) {
                AccountSwitcherSheet(
                    identity: identity,
                    settings: settings,
                    onSwitchTarget: { showSwitcher = false; switchTarget = $0 },
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
                    importText: $importText
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
        .preferredColorScheme(BitOSTheme.preferredScheme)
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
    let onSwitchTarget: (RegisteredAccountRow) -> Void
    @Environment(AppEnvironment.self) private var environment
    let onAddAccount: () -> Void
    let onManage: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
            dragHandle
            header
            if identity.registeredAccounts.isEmpty {
                Text("No saved accounts yet.")
                    .font(.system(size: 13))
                    .foregroundStyle(BitOSTheme.textSecondary)
            } else {
                accountList
            }
            Divider()
            footer
            Spacer(minLength: 8)
        }
        .padding(BitOSTheme.Spacing.base)
        .background(BitOSTheme.background)
    }

    private var dragHandle: some View {
        Capsule()
            .fill(BitOSTheme.surfaceOverlay)
            .frame(width: 36, height: 4)
            .frame(maxWidth: .infinity)
    }

    private var header: some View {
        HStack {
            Text("Switch account")
                .font(.system(size: 20, weight: .bold))
            Spacer()
            Text("\(identity.registeredAccounts.count) saved on this device")
                .font(.system(size: 11))
                .foregroundStyle(BitOSTheme.textTertiary)
        }
    }

    private var accountList: some View {
        ForEach(Array(identity.registeredAccounts), id: \.pubkeyHex) { acct in
            SwitcherAccountRow(
                acct: acct,
                isActive: acct.pubkeyHex == (identity.activeRegistryPubkey ?? identity.account?.pubkeyHex),
                busy: identity.busy,
                profile: environment.feedStore.profiles[acct.pubkeyHex],
                shortNpub: settings.shortNpub(acct.npub),
                onTap: { onSwitchTarget(acct) }
            )
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.xs) {
            Button {
                onAddAccount()
            } label: {
                Label("+ Add account", image: "SolarUserLinear")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(BitOSTheme.accent)
            }
            .buttonStyle(.plain)
            Button {
                onManage()
            } label: {
                Label("Manage accounts (You tab)", image: "SolarSettingsLinear")
                    .font(.system(size: 13))
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
            .buttonStyle(.plain)
        }
    }
}

/// Add-account sheet: import nsec or create a fresh key (legacy parity).
private struct AddAccountSheet: View {
    let identity: IdentityStore
    @Binding var importText: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
            Text("Add account")
                .font(.system(size: 20, weight: .bold))
            Text("Log in with an nsec or create a fresh key. Every account already on this device stays sealed.")
                .font(.system(size: 12))
                .foregroundStyle(BitOSTheme.textSecondary)
            SecretKeyField(
                text: $importText,
                error: identity.importError,
                onSubmit: { identity.importKeyPreview(importText) }
            )
            .onChange(of: importText) { _, _ in identity.clearImportError() }
            if secretKeyReady(importText) {
                DerivedIdentityCard(
                    check: BusinessCoreBridge().keyImportCheck(raw: importText)
                )
            }
            HStack {
                Button {
                    identity.importKeyPreview(importText)
                } label: {
                    Text("Review key")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(BitOSTheme.accent)
                }
                .disabled(!secretKeyReady(importText))
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
            ConfirmIdentitySheet(
                preview: preview,
                secretNsec: preview.isNewKey ? identity.previewSecretNsec : nil,
                busy: identity.busy,
                onConfirm: { identity.confirmPreview(); dismiss() },
                onCancel: { identity.cancelPreview(); dismiss() }
            )
            .presentationDetents([.medium])
        }
    }
}


/// Legacy `_AccountRow` parity: rounded card, kind-0 badges (NIP-05 ✓,
/// ⚡ lud16 chip), spinner-while-switching / check-if-active / chevron.
private struct SwitcherAccountRow: View {
    let acct: RegisteredAccountRow
    let isActive: Bool
    let busy: Bool
    let profile: ProfileMetadata?
    let shortNpub: String
    let onTap: () -> Void

    var body: some View {
        let name = acct.displayName
            ?? profile.map(\.bestDisplayName).flatMap { $0.isEmpty ? nil : $0 }
            ?? "Account"
        let nip05 = profile?.nip05.flatMap { $0.isEmpty ? nil : $0 }
        let hasLightning = !(profile?.lud16 ?? "").isEmpty
        Button(action: onTap) {
            rowContent(name: name, nip05: nip05, hasLightning: hasLightning)
        }
        .buttonStyle(.plain)
        .disabled(isActive || busy)
    }

    private func rowContent(name: String, nip05: String?, hasLightning: Bool) -> some View {
        HStack(spacing: 12) {
            avatar(hasLightning: hasLightning)
            identityBlock(name: name, nip05: nip05)
            Spacer()
            trailing
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(isActive ? BitOSTheme.accent.opacity(0.06) : BitOSTheme.surface.opacity(0.4))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .strokeBorder(
                    isActive ? BitOSTheme.accent.opacity(0.25) : BitOSTheme.border.opacity(0.35),
                    lineWidth: 1
                )
        )
    }

    private func avatar(hasLightning: Bool) -> some View {
        ZStack(alignment: .bottomTrailing) {
            PubkeyAvatarView(pubkey: acct.pubkeyHex, size: 40)
            if hasLightning {
                Text("\u{26A1}")
                    .font(.system(size: 7))
                    .frame(width: 14, height: 14)
                    .background(Circle().fill(BitOSTheme.zap))
                    .overlay(Circle().strokeBorder(BitOSTheme.background, lineWidth: 1))
            }
        }
    }

    private func identityBlock(name: String, nip05: String?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Text(name)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                    .lineLimit(1)
                if nip05 != nil {
                    Image(systemName: AppIcons.check)
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(BitOSTheme.success)
                }
            }
            Text(shortNpub)
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(BitOSTheme.textSecondary)
            if let nip05 {
                Text(nip05)
                    .font(.system(size: 10))
                    .foregroundStyle(BitOSTheme.success)
                    .lineLimit(1)
            }
        }
    }

    @ViewBuilder private var trailing: some View {
        if busy && !isActive {
            ProgressView().controlSize(.small)
        } else if isActive {
            Image(systemName: AppIcons.check)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(BitOSTheme.accent)
        } else {
            Image(systemName: "chevron.right")
                .font(.system(size: 12))
                .foregroundStyle(BitOSTheme.textTertiary)
        }
    }
}

private struct StaticPageTarget: Identifiable {
    let page: String
    var id: String { page }
}
