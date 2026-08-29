import SwiftUI
import UIKit

/// Profile surface with the account flow (ID-004): browse-first by default —
/// identity creation/import is always explicit and visibly confirms the
/// derived npub before anything is stored or replaced.
///
/// Signed-in layout is legacy-Flutter `profile_view` parity: full-bleed
/// cover under the status bar with floating glass controls, hex avatar hero,
/// centered identity block, "Edit profile" pill + ⋯ actions menu,
/// completion card, stats, collapsible about with info chips, and a pinned
/// Notes · Replies · Bitz · Reposts tab rail.
struct ProfileView: View {
    @State private var store: IdentityStore
    @State private var importText = ""
    @State private var confirmRemove = false
    @State private var showEdit = false
    @State private var showSettings = false
    @State private var showZaps = false
    @State private var showQr = false
    @State private var npubCopied = false
    @State private var ownTab = 0
    @State private var moreMenu: AppMenuPresentation?
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity
    @Environment(SettingsStore.self) private var settings

    init(store: IdentityStore) {
        _store = State(initialValue: store)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                if let account = store.account {
                    // The signed-in profile owns the full scroll width so the
                    // cover reaches the screen edges like the legacy hero.
                    accountPanel(account)
                } else {
                    VStack(spacing: BitOSTheme.Spacing.md) {
                        browsePanel
                        importPanel
                    }
                    .padding(BitOSTheme.Spacing.screen)
                }
            }
            .background(BitOSTheme.background)
            .ignoresSafeArea(edges: store.account == nil ? [] : .top)
            .navigationTitle(store.account == nil ? "You" : "")
            .toolbar {
                if store.account == nil {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            showSettings = true
                        } label: {
                            AppIcons.image(for: AppIcons.settings)
                        }
                        .accessibilityLabel("Settings")
                    }
                }
            }
            .toolbar(store.account == nil ? .visible : .hidden, for: .navigationBar)
            .appMenuHost($moreMenu)
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
                    BrandQrCodeView(value: "https://njump.me/\(account.npub)", size: 224)
                    Text("Scan with any Nostr app to follow \(settings.shortNpub(account.npub))")
                        .font(.system(size: 12))
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .multilineTextAlignment(.center)
                    Button("Copy profile link") {
                        UIPasteboard.general.string = "https://njump.me/\(account.npub)"
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(BitOSTheme.accent)
                }
            }
            .padding(BitOSTheme.Spacing.base)
            .presentationDetents([.medium])
        }
        .fullScreenCover(isPresented: $showEdit) {
            ProfileEditSheet(
                publisher: environment.notePublisher,
                initialProfile: environment.feedStore.profiles[store.account?.pubkeyHex ?? ""],
                onClose: { showEdit = false }
            )
        }
        .sheet(item: $store.preview) { preview in
            ConfirmIdentitySheet(
                preview: preview,
                secretNsec: preview.isNewKey ? store.previewSecretNsec : nil,
                busy: store.busy,
                onConfirm: { store.confirmPreview() },
                onCancel: { store.cancelPreview() }
            )
            .presentationDetents([.medium])
        }
    }

    // MARK: - Own profile

    private func accountPanel(_ account: AccountIdentity) -> some View {
        let feed = environment.feedStore
        let profile = feed.profiles[account.pubkeyHex]
        let own = feed.notes.filter { $0.pubkey == account.pubkeyHex || $0.repostedBy == account.pubkeyHex }
        let tabs: [(String, [FeedNote])] = [
            ("Notes", own.filter { $0.replyTo == nil && $0.repostedBy == nil }),
            ("Replies", own.filter { $0.replyTo != nil && $0.repostedBy == nil }),
            ("Bitz", own.filter { $0.video != nil || !$0.mediaUrls.isEmpty }),
            ("Reposts", own.filter { $0.repostedBy == account.pubkeyHex }),
        ]
        return LazyVStack(spacing: BitOSTheme.Spacing.md, pinnedViews: [.sectionHeaders]) {
            // ── Full-bleed cover + avatar hero (legacy _ProfileHeader). The
            // zero-gap band keeps the hex locked onto the banner edge.
            VStack(spacing: 0) {
                ZStack(alignment: .top) {
                    bannerCover(profile)
                        .frame(height: 160 + statusBarInset)
                    HStack(spacing: BitOSTheme.Spacing.sm) {
                        Spacer()
                        glassPill("Edit cover") { showEdit = true }
                        glassIcon(AppIcons.share, "Share profile") {
                            UIPasteboard.general.string = "https://njump.me/\(account.npub)"
                        }
                        glassIcon(AppIcons.settings, "Settings") { showSettings = true }
                    }
                    .padding(.horizontal, 10)
                    .padding(.top, statusBarInset + 4)
                }
                ZStack(alignment: .top) {
                    Color.clear.frame(height: 64)
                    HexAvatarView(
                        pubkey: account.pubkeyHex,
                        size: 92,
                        imageURL: safeProfilePictureURL(profile?.picture),
                        label: profile?.bestDisplayName,
                        hasLightning: !(profile?.lud16 ?? "").isEmpty
                    )
                    .offset(y: -46)
                    // Legacy _ProfileAvatarHero: drop shadow + primary glow.
                    .shadow(color: .black.opacity(0.18), radius: 12, y: 5)
                    .shadow(color: BitOSTheme.accent.opacity(0.14), radius: 8)
                }
            }
            // ── Identity block (centered, legacy _ProfileInfo) ───────────
            identityBlock(account, profile)
            // ── Actions: Edit profile pill + ⋯ menu (legacy parity) ──────
            HStack(spacing: BitOSTheme.Spacing.sm) {
                Button {
                    showEdit = true
                } label: {
                    HStack(spacing: 8) {
                        AppIcons.image(for: AppIcons.pen)
                            .font(.system(size: 16, weight: .medium))
                        Text("Edit profile")
                            .font(.system(size: 14, weight: .bold))
                    }
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(Capsule(style: .continuous).fill(BitOSTheme.accent))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Edit profile")

                moreMenuButton(account: account, profile: profile)
            }
            .padding(.horizontal, BitOSTheme.Spacing.base)
            profileCompletionCard(profile)
            // ── Stats row (evenly spaced) ───────────────────────────────
            HStack {
                Spacer()
                stat("Posts", FeedFormat.count(tabs[0].1.count))
                Spacer()
                stat("Following", FeedFormat.count(feed.following.count))
                Spacer()
                stat("Bitz", FeedFormat.count(tabs[2].1.count))
                Spacer()
            }
            .padding(.horizontal, BitOSTheme.Spacing.base)
            // ── About + info chips (legacy _AboutSection) ───────────────
            ProfileAboutSection(profile: profile)
            // ── Pinned tab rail (Notes · Replies · Bitz · Reposts) ──────
            Section(header: pinnedTabBar(tabs: tabs.map(\.0))) {
                tabContent(tabs[ownTab].1, profile: profile)
            }
        }
    }

    private func identityBlock(_ account: AccountIdentity, _ profile: ProfileMetadata?) -> some View {
        VStack(spacing: 4) {
            HStack(spacing: 4) {
                Text(profile?.bestDisplayName ?? "Anonymous")
                    .font(.system(size: 24, weight: .heavy))
                    .lineLimit(1)
                if let nip05 = profile?.nip05, !nip05.isEmpty {
                    Image(systemName: AppIcons.checkCircle)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(BitOSTheme.cyan)
                        .accessibilityLabel("NIP-05 verified")
                }
            }
            if let username = profile?.name, !username.isEmpty {
                Text("@\(username)")
                    .font(.system(size: 14))
                    .foregroundStyle(BitOSTheme.accent)
            }
            Button {
                UIPasteboard.general.string = account.npub
                UISelectionFeedbackGenerator().selectionChanged()
                npubCopied = true
                Task {
                    try? await Task.sleep(for: .seconds(1.8))
                    npubCopied = false
                }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: npubCopied ? AppIcons.check : AppIcons.copy)
                        .font(.system(size: 13))
                        .foregroundStyle(npubCopied ? BitOSTheme.success : BitOSTheme.textSecondary)
                    Text(settings.shortNpub(account.npub))
                        .font(.system(size: 11.5, design: .monospaced))
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(Capsule().fill(BitOSTheme.surfaceOverlay.opacity(0.5)))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Copy npub")
            if !(profile?.lud16 ?? "").isEmpty {
                chip(icon: AppIcons.zap, label: "Lightning", foreground: BitOSTheme.zap)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, BitOSTheme.Spacing.base)
    }

    private func chip(icon: String, label: String, foreground: Color) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 12))
            Text(label)
                .font(.system(size: 11, weight: .bold))
        }
        .foregroundStyle(foreground)
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Capsule().fill(foreground.opacity(0.10)))
    }

    // MARK: - Actions menu (legacy _ProfileMoreMenu parity)

    private func moreMenuButton(account: AccountIdentity, profile: ProfileMetadata?) -> some View {
        let entries: [AppMenuEntry] = {
            var items: [AppMenuEntry] = [
                .item(AppMenuItem(id: "settings", label: "Settings", systemImage: AppIcons.settings)),
                .item(AppMenuItem(id: "zap-wallet", label: "Zap wallet", systemImage: AppIcons.zap)),
                .divider,
                .item(AppMenuItem(id: "copy-link", label: "Copy profile link", systemImage: "link")),
                .item(AppMenuItem(id: "copy-npub", label: "Copy npub", systemImage: AppIcons.copy)),
                .item(AppMenuItem(id: "show-qr", label: "Show profile QR", systemImage: AppIcons.qrCode)),
            ]
            if let lud16 = profile?.lud16, !lud16.isEmpty {
                items.append(.divider)
                items.append(.item(AppMenuItem(id: "copy-lightning", label: "Copy lightning address", systemImage: AppIcons.zap)))
            }
            return items
        }()
        return Button {
            // no-op — the overlay tap gesture below reports the anchor
        } label: {
            AppIcons.image(for: AppIcons.more)
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(BitOSTheme.textSecondary)
                .frame(width: 44, height: 44)
                .background(Circle().stroke(BitOSTheme.border.opacity(0.3), lineWidth: 1))
        }
        .overlay {
            GeometryReader { geo in
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture {
                        let frame = geo.frame(in: .global)
                        moreMenu = AppMenuPresentation(
                            anchor: CGPoint(x: frame.maxX, y: frame.minY),
                            entries: entries
                        ) { id in
                            handleMoreMenu(id, account: account, profile: profile)
                        }
                    }
            }
        }
        .accessibilityLabel("More profile actions")
        .accessibilityAddTraits(.isButton)
    }

    private func handleMoreMenu(_ id: String, account: AccountIdentity, profile: ProfileMetadata?) {
        switch id {
        case "settings": showSettings = true
        case "zap-wallet": showZaps = true
        case "copy-link": UIPasteboard.general.string = "https://njump.me/\(account.npub)"
        case "copy-npub": UIPasteboard.general.string = account.npub
        case "show-qr": showQr = true
        case "copy-lightning":
            if let lud16 = profile?.lud16 { UIPasteboard.general.string = lud16 }
        default: break
        }
    }

    // MARK: - Cover

    private func bannerCover(_ profile: ProfileMetadata?) -> some View {
        ZStack {
            if let banner = profile?.banner, !banner.isEmpty, let url = URL(string: banner) {
                AsyncImage(url: url) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    defaultCover
                }
                .overlay(DefaultCoverHexPattern())
            } else {
                defaultCover
            }
            // Web hero scrim: from-black/25 via-transparent to-black/35.
            LinearGradient(
                stops: [
                    .init(color: .black.opacity(0.25), location: 0),
                    .init(color: .clear, location: 0.45),
                    .init(color: .black.opacity(0.35), location: 1),
                ],
                startPoint: .top, endPoint: .bottom
            )
        }
    }

    private var defaultCover: some View {
        ZStack {
            // Web: linear-gradient(115deg, primary-400 → primary-700).
            LinearGradient(
                colors: [Color(red: 0.976, green: 0.659, blue: 0.294), Color(red: 0.831, green: 0.475, blue: 0.059)],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
            DefaultCoverHexPattern()
        }
    }

    private var statusBarInset: CGFloat {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.first(where: { $0.keyWindow != nil })?.keyWindow?.safeAreaInsets.top ?? 0
    }

    // MARK: - Glass controls (legacy _GlassIconButton/_GlassPillButton)

    private func glassIcon(_ symbol: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 16))
                .foregroundStyle(.white)
                .frame(width: 36, height: 36)
                .background {
                    Circle()
                        .fill(.black.opacity(0.30))
                        .background(.ultraThinMaterial)
                }
        }
        .accessibilityLabel(label)
    }

    private func glassPill(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                AppIcons.image(for: AppIcons.camera)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(.white)
                Text(label)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background {
                Capsule()
                    .fill(.black.opacity(0.30))
                    .background(.ultraThinMaterial)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    // MARK: - Completion card (legacy _ProfileCompletionCard parity)

    @ViewBuilder
    private func profileCompletionCard(_ profile: ProfileMetadata?) -> some View {
        let missing = profileCompletionFields(profile)
        if !missing.isEmpty {
            let score = ((7 - missing.count) * 100) / 7
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.md) {
                HStack(spacing: 10) {
                    Image(systemName: AppIcons.sparkles)
                        .font(.system(size: 16))
                        .foregroundStyle(BitOSTheme.zap)
                        .frame(width: 28, height: 28)
                        .background(RoundedRectangle(cornerRadius: 8).fill(BitOSTheme.zap.opacity(0.10)))
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Complete your profile")
                            .font(.system(size: 14, weight: .heavy))
                        Text("\(score)% complete · \(missing.count) steps to go")
                            .font(.system(size: 11.5))
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                    Spacer()
                    Button("Finish") { showEdit = true }
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Capsule().fill(BitOSTheme.accent))
                        .buttonStyle(.plain)
                }
                // Progress — primary → cyan gradient fill (web parity).
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(BitOSTheme.surfaceOverlay.opacity(0.6))
                        Capsule()
                            .fill(LinearGradient(colors: [BitOSTheme.accent, BitOSTheme.cyan], startPoint: .leading, endPoint: .trailing))
                            .frame(width: geo.size.width * CGFloat(score) / 100)
                    }
                }
                .frame(height: 8)
                FlowLayout(spacing: 6) {
                    ForEach(missing, id: \.self) { field in
                        HStack(spacing: 4) {
                            Image(systemName: "record.circle")
                                .font(.system(size: 12))
                            Text(field)
                                .font(.system(size: 11, weight: .semibold))
                        }
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(Capsule().fill(BitOSTheme.surfaceOverlay.opacity(0.5)))
                        .overlay(Capsule().strokeBorder(BitOSTheme.border.opacity(0.15)))
                    }
                }
            }
            .padding(BitOSTheme.Spacing.base)
            .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.lg).fill(BitOSTheme.surfaceOverlay.opacity(0.3)))
            .overlay(RoundedRectangle(cornerRadius: BitOSTheme.Radius.lg).strokeBorder(BitOSTheme.border.opacity(0.12)))
            .padding(.horizontal, BitOSTheme.Spacing.base)
        }
    }

    private func profileCompletionFields(_ profile: ProfileMetadata?) -> [String] {
        let displayName = profile?.displayName ?? profile?.name ?? ""
        return [
            ("Display name", !displayName.isEmpty),
            ("Bio", !(profile?.about ?? "").isEmpty),
            ("Profile picture", !(profile?.picture ?? "").isEmpty),
            ("Cover photo", !(profile?.banner ?? "").isEmpty),
            ("Verified NIP-05", !(profile?.nip05 ?? "").isEmpty),
            ("Lightning address", !(profile?.lud16 ?? "").isEmpty),
            ("Website", !(profile?.website ?? "").isEmpty),
        ].compactMap { $0.1 ? nil : $0.0 }
    }

    // MARK: - Tab rail (legacy _ProfileTabBar parity: pinned, 48, underline)

    private func pinnedTabBar(tabs: [String]) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                ForEach(Array(tabs.enumerated()), id: \.offset) { index, label in
                    let selected = ownTab == index
                    Button {
                        withAnimation(.easeInOut(duration: 0.18)) { ownTab = index }
                    } label: {
                        VStack(spacing: 8) {
                            Text(label)
                                .font(.system(size: 14, weight: selected ? .bold : .medium))
                                .foregroundStyle(selected ? BitOSTheme.accent : BitOSTheme.textSecondary)
                            Capsule()
                                .fill(selected ? BitOSTheme.accent : .clear)
                                .frame(height: 3)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected ? [.isSelected] : [])
                }
            }
            Rectangle()
                .fill(BitOSTheme.border.opacity(0.15))
                .frame(height: 1)
        }
        // The status-bar filler keeps the pinned rail below the notch. UX:
        // no surface block behind the rail — page background blends in while
        // still masking content scrolled under it when pinned.
        .padding(.top, statusBarInset)
        .background(BitOSTheme.background)
    }

    // MARK: - Tab content (legacy Notes/Replies/Bitz/Reposts parity)

    @ViewBuilder
    private func tabContent(_ notes: [FeedNote], profile: ProfileMetadata?) -> some View {
        if notes.isEmpty {
            tabEmptyState
        } else if ownTab == 2 {
            // Bitz — 3-column media grid, 2px gutters.
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 3), spacing: 2) {
                ForEach(notes.prefix(60), id: \.id) { note in
                    BitzGridTile(note: note)
                }
            }
            .padding(.horizontal, 2)
        } else {
            ForEach(Array(notes.prefix(50)), id: \.id) { note in
                if ownTab == 1 {
                    replyContextStrip(note)
                }
                if ownTab == 3 {
                    repostHeader(note)
                }
                ProfileNoteCard(note: note, profile: profile)
            }
        }
    }

    @ViewBuilder
    private var tabEmptyState: some View {
        let (symbol, message): (String, String) = {
            switch ownTab {
            case 1: return (AppIcons.comment, "No replies yet")
            case 2: return (AppIcons.photo, "No bitz yet")
            case 3: return (AppIcons.repost, "No reposts yet")
            default: return (AppIcons.pen, "No posts yet")
            }
        }()
        VStack(spacing: BitOSTheme.Spacing.md) {
            AppIcons.image(for: symbol)
                .font(.system(size: 48, weight: .medium))
                .foregroundStyle(BitOSTheme.textSecondary.opacity(0.5))
            Text(message)
                .font(.system(size: 14))
                .foregroundStyle(BitOSTheme.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, BitOSTheme.Spacing.xxl)
    }

    /// Slim “replying to …” affordance above each reply card.
    private func replyContextStrip(_ note: FeedNote) -> some View {
        HStack(spacing: 4) {
            Image(systemName: "arrow.turn.down.right")
                .font(.system(size: 14))
                .foregroundStyle(BitOSTheme.textSecondary)
            Text("Replying to")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(BitOSTheme.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, BitOSTheme.Spacing.base)
        .padding(.top, BitOSTheme.Spacing.sm)
    }

    /// “🔁 name reposted” header above each embedded original note.
    private func repostHeader(_ note: FeedNote) -> some View {
        let who = environment.feedStore.profiles[note.pubkey]?.bestDisplayName ?? "Anonymous"
        return HStack(spacing: 4) {
            AppIcons.image(for: AppIcons.repost)
                .font(.system(size: 14))
                .foregroundStyle(BitOSTheme.textSecondary)
            Text("\(who) reposted")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(BitOSTheme.textSecondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, BitOSTheme.Spacing.base)
        .padding(.top, BitOSTheme.Spacing.sm)
    }

    // MARK: - Browse / import (signed-out)

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
            Text("Log in with a secret key")
                .font(.subheadline.weight(.semibold))
            SecretKeyField(
                text: $importText,
                error: store.importError,
                onSubmit: { store.importKeyPreview(importText) }
            )
            .onChange(of: importText) { _, _ in store.clearImportError() }
            Button {
                store.importKeyPreview(importText)
            } label: {
                Label { Text("Review key") } icon: { AppIcons.image(for: AppIcons.qrCode) }
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(BitOSTheme.accent)
            .disabled(!secretKeyReady(importText))
            Text("The key stays on this device, sealed in the Keychain. Never share an nsec.")
                .font(.caption2)
                .foregroundStyle(BitOSTheme.textTertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(BitOSTheme.Spacing.base)
        .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.lg).fill(BitOSTheme.surfaceElevated))
    }
}

// MARK: - About section (legacy _AboutSection parity)

/// Bio (5-line clamp, Show more past 240 chars) + info chips (NIP-05,
/// website, lightning) + hairline divider.
private struct ProfileAboutSection: View {
    let profile: ProfileMetadata?
    @State private var expanded = false

    private static let bioLongThreshold = 240

    private var bio: String { (profile?.about ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
    private var nip05: String { (profile?.nip05 ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
    private var website: String { (profile?.website ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
    private var lud16: String { (profile?.lud16 ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        if !bio.isEmpty || !nip05.isEmpty || !website.isEmpty || !lud16.isEmpty {
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.sm) {
                if !bio.isEmpty {
                    let long = bio.count > Self.bioLongThreshold
                    Text(bio)
                        .font(.system(size: 14))
                        .lineSpacing(7)
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .lineLimit(expanded || !long ? nil : 5)
                    if long {
                        Button(expanded ? "Show less" : "Show more") {
                            expanded.toggle()
                        }
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(BitOSTheme.accent)
                        .buttonStyle(.plain)
                    }
                }
                if !nip05.isEmpty || !website.isEmpty || !lud16.isEmpty {
                    FlowLayout(spacing: BitOSTheme.Spacing.sm) {
                        if !nip05.isEmpty {
                            infoChip(symbol: AppIcons.checkCircle, text: nip05, tint: BitOSTheme.cyan)
                        }
                        if !website.isEmpty {
                            infoChip(symbol: "globe", text: website, tint: BitOSTheme.textSecondary)
                        }
                        if !lud16.isEmpty {
                            infoChip(symbol: AppIcons.zap, text: lud16, tint: BitOSTheme.zap)
                        }
                    }
                }
                Rectangle()
                    .fill(BitOSTheme.border.opacity(0.65))
                    .frame(height: 1)
            }
            .padding(.horizontal, BitOSTheme.Spacing.base)
        }
    }

    private func infoChip(symbol: String, text: String, tint: Color) -> some View {
        HStack(spacing: 4) {
            Image(systemName: symbol)
                .font(.system(size: 14))
            Text(text)
                .font(.system(size: 11))
                .lineLimit(1)
        }
        .foregroundStyle(tint)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: 8).fill(tint.opacity(0.10)))
    }
}

// MARK: - Note card (profile tabs)

/// Compact profile note card: author row, expandable content, media preview.
private struct ProfileNoteCard: View {
    let note: FeedNote
    let profile: ProfileMetadata?
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                PubkeyAvatarView(
                    pubkey: note.pubkey,
                    size: 36,
                    picture: profile?.picture,
                    label: profile?.bestDisplayName
                )
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 4) {
                        Text(profile?.bestDisplayName ?? FeedFormat.shortPubkey(note.pubkey))
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(BitOSTheme.textPrimary)
                            .lineLimit(1)
                        if !(profile?.nip05?.isEmpty ?? true) {
                            Image(systemName: AppIcons.checkCircle)
                                .font(.system(size: 12))
                                .foregroundStyle(BitOSTheme.cyan)
                        }
                    }
                    Text(FeedFormat.timeAgo(createdAt: note.createdAt))
                        .font(.system(size: 11))
                        .foregroundStyle(BitOSTheme.textTertiary)
                }
                Spacer()
            }
            if !note.content.isEmpty {
                Text(note.content)
                    .font(.system(size: 14))
                    .foregroundStyle(BitOSTheme.textPrimary)
                    .lineLimit(expanded ? nil : 6)
                if note.content.count > 280 {
                    Button(expanded ? "Show less" : "Show more") {
                        expanded.toggle()
                    }
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(BitOSTheme.accent)
                    .buttonStyle(.plain)
                }
            }
            mediaPreview
        }
        .padding(.horizontal, BitOSTheme.Spacing.screen)
        .padding(.vertical, BitOSTheme.Spacing.md)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(BitOSTheme.border.opacity(0.65))
                .frame(height: 1)
                .padding(.leading, BitOSTheme.Spacing.screen)
        }
    }

    @ViewBuilder
    private var mediaPreview: some View {
        let images = note.mediaUrls.filter { !$0.hasVideoExtension }
        if let video = note.video {
            BitzVideoTile(url: URL(string: video.posterUrl ?? video.url), wide: true)
        } else if !images.isEmpty {
            HStack(spacing: 2) {
                ForEach(images.prefix(3), id: \.self) { urlString in
                    AsyncImage(url: URL(string: urlString)) { image in
                        image.resizable().aspectRatio(contentMode: .fill)
                    } placeholder: {
                        BitzTilePlaceholder()
                    }
                    .frame(width: 104, height: 104)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                }
            }
        }
    }
}

// MARK: - Bitz grid tiles (legacy _PostGrid/_MediaTile parity)

private struct BitzGridTile: View {
    let note: FeedNote

    private var imageURL: URL? {
        let images = note.mediaUrls.filter { !$0.hasVideoExtension }
        if let first = images.first { return URL(string: first) }
        if let poster = note.video?.posterUrl ?? note.video?.url { return URL(string: poster) }
        return nil
    }

    var body: some View {
        ZStack {
            if let url = imageURL {
                AsyncImage(url: url) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    BitzTilePlaceholder()
                }
            } else {
                BitzTilePlaceholder()
            }
            if note.video != nil {
                Color.black.opacity(0.30)
                Image(systemName: AppIcons.bitz)
                    .font(.system(size: 28))
                    .foregroundStyle(.white)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipped()
    }
}

/// 16:9 video preview used inside note cards.
private struct BitzVideoTile: View {
    let url: URL?
    var wide = false

    var body: some View {
        ZStack {
            if let url {
                AsyncImage(url: url) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    BitzTilePlaceholder()
                }
            } else {
                BitzTilePlaceholder()
            }
            Color.black.opacity(0.30)
            Image(systemName: AppIcons.bitz)
                .font(.system(size: 28))
                .foregroundStyle(.white)
        }
        .frame(height: 180)
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

private struct BitzTilePlaceholder: View {
    var body: some View {
        LinearGradient(
            colors: [BitOSTheme.surfaceElevated, BitOSTheme.surfaceOverlay],
            startPoint: .topLeading, endPoint: .bottomTrailing
        )
        .overlay {
            Image(systemName: AppIcons.bitz)
                .font(.system(size: 20))
                .foregroundStyle(BitOSTheme.textTertiary.opacity(0.6))
        }
    }
}

private extension String {
    var hasVideoExtension: Bool {
        let lower = lowercased()
        return [".mp4", ".webm", ".mov", ".m4v"].contains { lower.hasSuffix($0) }
    }
}

// MARK: - Flow layout (wrapping chip rows)

/// Simple wrapping layout for chip rows (legacy Flutter `Wrap` parity).
private struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 0
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: width, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

extension IdentityPreview: Identifiable {
    var id: String { npub }
}

/// The legacy fallback-banner tile: decorative geometry only, never remote SVG.
private struct DefaultCoverHexPattern: View {
    var body: some View {
        Canvas { context, size in
            let tile: CGFloat = 120
            let hexHeight: CGFloat = 104
            for x in stride(from: -tile / 2, through: size.width, by: tile) {
                for y in stride(from: -hexHeight / 2, through: size.height, by: hexHeight) {
                    var path = Path()
                    path.move(to: CGPoint(x: x + 30, y: y))
                    path.addLine(to: CGPoint(x: x + 90, y: y))
                    path.addLine(to: CGPoint(x: x + 120, y: y + 52))
                    path.addLine(to: CGPoint(x: x + 90, y: y + 104))
                    path.addLine(to: CGPoint(x: x + 30, y: y + 104))
                    path.addLine(to: CGPoint(x: x, y: y + 52))
                    path.closeSubpath()
                    context.fill(path, with: .color(.white.opacity(0.08)))
                }
            }
        }
        .allowsHitTesting(false)
    }
}

extension ProfileView {
    fileprivate func stat(_ label: String, _ value: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(BitOSTheme.textPrimary)
            Text(label)
                .font(.system(size: 12))
                .foregroundStyle(BitOSTheme.textSecondary)
        }
    }
}
