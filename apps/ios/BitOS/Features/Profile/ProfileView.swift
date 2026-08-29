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
                if let account = store.account {
                    // The signed-in profile owns the full scroll width so the
                    // cover reaches the screen edges like the legacy hero.
                    accountPanel(account)
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
                    .padding(.horizontal, BitOSTheme.Spacing.screen)
                    .padding(.top, BitOSTheme.Spacing.md)
                } else {
                    VStack(spacing: BitOSTheme.Spacing.md) {
                        browsePanel
                        importPanel
                    }
                    .padding(BitOSTheme.Spacing.screen)
                }
            }
            .background(BitOSTheme.background)
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
                initialProfile: environment.feedStore.profiles[store.account?.pubkeyHex ?? ""],
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
            // ── Full-bleed cover + glass controls (legacy _ProfileHeader) ─
            ZStack(alignment: .top) {
                bannerCover(profile)
                    .frame(height: 160)
                    .clipShape(RoundedRectangle(cornerRadius: 0))
                HStack(spacing: 8) {
                    Spacer()
                    glassPill("📷 Edit cover") { showEdit = true }
                    glassIcon("square.and.arrow.up", "Share profile") {
                        UIPasteboard.general.string = "nostr:\(account.npub)"
                    }
                    glassIcon(AppIcons.settings, "Settings") { showSettings = true }
                }
                .padding(.horizontal, 10)
                .padding(.top, 8)
            }
            // ── Avatar hero band (lifted onto the cover edge) ───────────
            ZStack(alignment: .top) {
                Color.clear.frame(height: 46)
                HexAvatarView(
                    pubkey: account.pubkeyHex,
                    size: 92,
                    imageURL: safeProfilePictureURL(profile?.picture),
                    label: profile?.bestDisplayName,
                    hasLightning: !(profile?.lud16 ?? "").isEmpty
                )
                    .offset(y: -46)
                    .shadow(color: .black.opacity(0.18), radius: 12, y: 6)
            }
            // ── Identity block (centered) ───────────────────────────────
            VStack(spacing: 4) {
                HStack(spacing: 4) {
                    Text((profile?.displayName ?? profile?.name).flatMap { $0.isEmpty ? nil : $0 } ?? "Your account")
                        .font(.system(size: 24, weight: .bold))
                    if let nip05 = profile?.nip05, !nip05.isEmpty {
                        Image(systemName: AppIcons.check)
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(BitOSTheme.success)
                    }
                }
                if let username = profile?.name, !username.isEmpty {
                    Text("@\(username)")
                        .font(.system(size: 15))
                        .foregroundStyle(BitOSTheme.accent)
                }
                Button {
                    UIPasteboard.general.string = account.npub
                } label: {
                    HStack(spacing: 4) {
                        Text(settings.shortNpub(account.npub))
                            .font(.system(size: 11, design: .monospaced))
                        Image(systemName: AppIcons.copy)
                            .font(.system(size: 10))
                    }
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Capsule().fill(BitOSTheme.surfaceOverlay.opacity(0.5)))
                }
                .buttonStyle(.plain)
                if !(profile?.lud16 ?? "").isEmpty {
                    Text("\u{26A1} Lightning")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(BitOSTheme.zap)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Capsule().fill(BitOSTheme.zap.opacity(0.10)))
                }
            }
            .frame(maxWidth: .infinity)
            // Own-profile actions are live: metadata editor + canonical QR.
            HStack(spacing: BitOSTheme.Spacing.sm) {
                Button {
                    showEdit = true
                } label: {
                    Label { Text("Edit profile") } icon: { AppIcons.image(for: AppIcons.pen) }
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(BitOSTheme.accent)
                .accessibilityLabel("Edit profile")

                Button {
                    showQr = true
                } label: {
                    AppIcons.image(for: AppIcons.qrCode)
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.bordered)
                .accessibilityLabel("Show your profile QR code")
            }
            .padding(.horizontal, BitOSTheme.Spacing.screen)
            profileCompletionCard(profile)
            // ── Stats row (evenly spaced) ───────────────────────────────
            HStack {
                Spacer()
                stat("Posts", "\(tabs[0].1.count)")
                Spacer()
                stat("Following", "\(feed.following.count)")
                Spacer()
                stat("Bitz", "\(tabs[2].1.count)")
                Spacer()
            }
            // ── About (collapsible) ─────────────────────────────────────
            if let bio = profile?.about, !bio.isEmpty {
                AboutBlock(bio: bio)
            }
            profileDetails(profile)
            // ── Tab bar (Notes · Replies · Bitz · Reposts) ──────────────
            HStack {
                ForEach(Array(tabs.enumerated()), id: \.offset) { index, entry in
                    let selected = ownTab == index
                    Button {
                        ownTab = index
                    } label: {
                        Text(entry.0)
                            .font(.system(size: 14, weight: selected ? .bold : .medium))
                            .foregroundStyle(selected ? BitOSTheme.accent : BitOSTheme.textSecondary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                    }
                    .buttonStyle(.plain)
                }
            }
            .background(RoundedRectangle(cornerRadius: 12).fill(BitOSTheme.surface.opacity(0.5)))
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

    private func bannerCover(_ profile: ProfileMetadata?) -> some View {
        ZStack {
            if let banner = profile?.banner, !banner.isEmpty, let url = URL(string: banner) {
                AsyncImage(url: url) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    defaultCover
                }
            } else {
                defaultCover
            }
            LinearGradient(
                colors: [.black.opacity(0.25), .clear, .black.opacity(0.35)],
                startPoint: .top, endPoint: .bottom
            )
        }
    }

    @ViewBuilder
    private func profileDetails(_ profile: ProfileMetadata?) -> some View {
        let nip05 = profile?.nip05?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let website = profile?.website?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !nip05.isEmpty || !website.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                if !nip05.isEmpty {
                    Label(nip05, systemImage: "checkmark.seal")
                }
                if let url = URL(string: website),
                   let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme) {
                    Link(destination: url) {
                        Label(website, systemImage: "link")
                            .lineLimit(1)
                    }
                }
            }
            .font(.system(size: 12))
            .foregroundStyle(BitOSTheme.textSecondary)
            .padding(.horizontal, BitOSTheme.Spacing.screen)
        }
    }

    @ViewBuilder
    private func profileCompletionCard(_ profile: ProfileMetadata?) -> some View {
        let missing = profileCompletionFields(profile)
        if !missing.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: "sparkles")
                        .foregroundStyle(BitOSTheme.accent)
                        .frame(width: 26, height: 26)
                        .background(RoundedRectangle(cornerRadius: 8).fill(BitOSTheme.accent.opacity(0.12)))
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Complete your profile")
                            .font(.system(size: 13, weight: .bold))
                        Text("\(7 - missing.count) of 7 complete · \(missing.count) steps to go")
                            .font(.system(size: 10))
                            .foregroundStyle(BitOSTheme.textTertiary)
                    }
                    Spacer()
                    Button("Finish") { showEdit = true }
                        .font(.system(size: 12, weight: .bold))
                        .buttonStyle(.borderedProminent)
                        .tint(BitOSTheme.accent)
                }
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 8) {
                    ForEach(missing, id: \.self) { field in
                        Label(field, systemImage: "circle")
                            .font(.system(size: 10))
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                }
            }
            .padding(BitOSTheme.Spacing.md)
            .background(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).fill(BitOSTheme.surface))
            .overlay(RoundedRectangle(cornerRadius: BitOSTheme.Radius.md).strokeBorder(BitOSTheme.divider))
            .padding(.horizontal, BitOSTheme.Spacing.screen)
        }
    }

    private func profileCompletionFields(_ profile: ProfileMetadata?) -> [String] {
        let displayName = profile?.displayName ?? profile?.name ?? ""
        [
            ("Display name", !displayName.isEmpty),
            ("Bio", !(profile?.about ?? "").isEmpty),
            ("Profile picture", !(profile?.picture ?? "").isEmpty),
            ("Cover photo", !(profile?.banner ?? "").isEmpty),
            ("Verified NIP-05", !(profile?.nip05 ?? "").isEmpty),
            ("Lightning address", !(profile?.lud16 ?? "").isEmpty),
            ("Website", !(profile?.website ?? "").isEmpty),
        ].compactMap { $0.1 ? nil : $0.0 }
    }

    private var defaultCover: some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 0.96, green: 0.48, blue: 0.10), Color(red: 0.63, green: 0.18, blue: 0.04)],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
            DefaultCoverHexPattern()
        }
    }

    private func glassIcon(_ symbol: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 16))
                .foregroundStyle(.white)
                .frame(width: 36, height: 36)
                .background(Circle().fill(.black.opacity(0.30)))
        }
        .accessibilityLabel(label)
    }

    private func glassPill(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(.black.opacity(0.30)))
        }
        .buttonStyle(.plain)
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

/// Collapsible bio (legacy _AboutSection parity — Show more/less at 120 chars).
private struct AboutBlock: View {
    let bio: String
    @State private var expanded = false

    init(bio: String) {
        self.bio = bio
        _expanded = State(initialValue: bio.count <= 120)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(bio)
                .font(.system(size: 13))
                .foregroundStyle(BitOSTheme.textSecondary)
                .lineLimit(expanded ? nil : 2)
            if bio.count > 120 {
                Button(expanded ? "Show less" : "Show more") {
                    expanded.toggle()
                }
                .font(.system(size: 12))
                .foregroundStyle(BitOSTheme.accent)
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, BitOSTheme.Spacing.base)
    }
}

extension ProfileView {
    fileprivate func stat(_ label: String, _ value: String) -> some View {
        VStack(spacing: 1) {
            Text(value).font(.headline.monospaced())
            Text(label).font(.caption2).foregroundStyle(BitOSTheme.textTertiary)
        }
    }

    fileprivate func ownNoteRow(_ note: FeedNote) -> some View {
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
}
