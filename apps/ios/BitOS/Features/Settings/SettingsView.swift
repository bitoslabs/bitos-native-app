import BusinessCore
import SwiftUI

/**
 * APP-018 Settings hub (unified feature spec §3.18, legacy Flutter
 * `SettingsView`/`settings_section_page` parity): account hero + the shared
 * section catalog (web mobile index grouping: hero · preferences · content ·
 * support) with hex icon tiles, per-section detail screens executing the
 * shared settings contract through `SettingsStore`. Sections whose features
 * are later waves show honest pending rows, never fake controls.
 */
struct SettingsView: View {
    @Environment(IdentityStore.self) private var identity
    @Environment(SettingsStore.self) private var settings
    @State private var npubCopied = false
    @State private var showSignOutConfirm = false

    /// Native presentation spec keyed by the shared section keys (icons and
    /// tints stay native; ordering/grouping comes from business-core).
    private struct SectionSpec {
        let label: String
        let icon: String
        let tint: Color
    }

    private static let specs: [String: SectionSpec] = [
        "account": SectionSpec(label: "Account", icon: "person.fill", tint: Color(hex: 0x2F95F6)),
        "lightning": SectionSpec(label: "Lightning & Zaps", icon: AppIcons.zap, tint: Color(hex: 0xFF9500)),
        "privacy": SectionSpec(label: "Privacy", icon: "hand.raised.fill", tint: Color(hex: 0x5856D6)),
        "notifications": SectionSpec(label: "Notifications", icon: "bell.fill", tint: Color(hex: 0xFF3B30)),
        "appearance": SectionSpec(label: "Appearance", icon: "paintpalette.fill", tint: Color(hex: 0xFF2D92)),
        "algorithm": SectionSpec(label: "Algorithm & Feed", icon: "bolt.horizontal.fill", tint: Color(hex: 0xBF5AF2)),
        "security": SectionSpec(label: "Security", icon: "shield.lefthalf.filled", tint: Color(hex: 0xFF9500)),
        "media": SectionSpec(label: "Media", icon: "photo.fill", tint: Color(hex: 0x34C759)),
        "language": SectionSpec(label: "Language & Region", icon: "globe", tint: Color(hex: 0x5AC8FA)),
        "relays": SectionSpec(label: "Relays", icon: "antenna.radiowaves.left.and.right", tint: Color(hex: 0x5AC8FA)),
        "help": SectionSpec(label: "Help & FAQ", icon: "questionmark.circle.fill", tint: Color(hex: 0x32ADE6)),
        "about": SectionSpec(label: "About", icon: "doc.text.fill", tint: Color(hex: 0x8E8E93)),
    ]

    var body: some View {
        List {
            accountHero
            sectionGroup("PREFERENCES", group: "PREFERENCES")
            sectionGroup("CONTENT", group: "CONTENT")
            sectionGroup("SUPPORT", group: "SUPPORT")
            signOutSection
            Section {
                Text("BitOS \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0") · settings v\(settingsSchemaVersion)")
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.textTertiary)
                    .frame(maxWidth: .infinity)
            }
        }
        .scrollContentBackground(.hidden)
        .background(BitOSTheme.background)
        .navigationDestination(for: String.self) { key in
            SettingsSectionView(sectionKey: key)
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var settingsSchemaVersion: Int {
        Int(BusinessCoreBridge().settingsSchemaVersion())
    }

    // MARK: - Account hero (web hero group parity)

    private var accountHero: some View {
        Section {
            if let account = identity.account {
                NavigationLink(value: "account") {
                    HStack(spacing: BitOSTheme.Spacing.md) {
                        PubkeyAvatarView(pubkey: account.pubkeyHex, size: 48)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Your account")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundStyle(BitOSTheme.textPrimary)
                            Text(settings.shortNpub(account.npub))
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundStyle(BitOSTheme.textSecondary)
                        }
                    }
                    .padding(.vertical, 4)
                }
                .buttonStyle(.plain)
            } else {
                VStack(spacing: 8) {
                    Text("No account")
                        .font(.system(size: 16, weight: .bold))
                    Text("Create or import a key from the You tab to start publishing.")
                        .font(.system(size: 13))
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
            }
        }
    }

    // MARK: - Grouped section rows (shared catalog ordering)

    private func sectionGroup(_ title: String, group: String) -> some View {
        Section {
            ForEach(settings.sections.filter { $0.group == group }, id: \.key) { section in
                if let spec = Self.specs[section.key] {
                    NavigationLink(value: section.key) {
                        sectionRow(spec)
                    }
                    .buttonStyle(.plain)
                }
            }
        } header: {
            Text(title)
        }
    }

    private func sectionRow(_ spec: SectionSpec) -> some View {
        HStack(spacing: 12) {
            HexIcon(
                systemName: spec.icon,
                size: 38,
                background: spec.tint.opacity(0.15),
                foreground: spec.tint
            )
            Text(spec.label)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(BitOSTheme.textPrimary)
            Spacer()
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(BitOSTheme.textTertiary)
        }
        .padding(.vertical, 2)
    }

    private var signOutSection: some View {
        Section {
            Button(role: .destructive) {
                showSignOutConfirm = true
            } label: {
                Text("Sign out")
            }
            .disabled(identity.account == nil)
        }
        .confirmationDialog(
            "Remove this account's key from this device?",
            isPresented: $showSignOutConfirm,
            titleVisibility: .visible
        ) {
            Button("Sign out", role: .destructive) { identity.removeAccount() }
            Button("Cancel", role: .cancel) {}
        }
    }
}

/// Per-section detail screens. Keys come from the shared catalog; content
/// is native and only implements what is live — pending features show
/// honest rows.
struct SettingsSectionView: View {
    let sectionKey: String
    @Environment(SettingsStore.self) private var settings
    @Environment(IdentityStore.self) private var identity
    @State private var npubCopied = false

    var body: some View {
        Group {
            switch sectionKey {
            case "notifications": NotificationsSection()
            case "appearance": AppearanceSection()
            case "algorithm": AlgorithmSection()
            case "media": MediaSection()
            case "language": LanguageSection()
            case "account": AccountSection(npubCopied: $npubCopied)
            case "about": AboutSection()
            case "security": SecuritySection()
            case "lightning": LightningSection()
            case "privacy": PrivacySection()
            case "relays": RelaysSection()
            case "help": HelpSection()
            default: PendingSection(sectionKey: sectionKey)
            }
        }
        .scrollContentBackground(.hidden)
        .background(BitOSTheme.background)
        .navigationTitle(Self.title(for: sectionKey))
        .navigationBarTitleDisplayMode(.inline)
    }

    static func title(for key: String) -> String {
        switch key {
        case "notifications": "Notifications"
        case "appearance": "Appearance"
        case "algorithm": "Algorithm & Feed"
        case "media": "Media"
        case "language": "Language & Region"
        case "account": "Account"
        case "about": "About"
        case "lightning": "Lightning & Zaps"
        case "privacy": "Privacy"
        case "security": "Security"
        case "relays": "Relays"
        case "help": "Help & FAQ"
        default: key.capitalized
        }
    }
}

// MARK: - Live sections

private struct NotificationsSection: View {
    @Environment(SettingsStore.self) private var settings

    var body: some View {
        List {
            Section {
                Toggle("Notifications", isOn: Binding(
                    get: { settings.state.notificationsEnabled },
                    set: { settings.setNotifications($0) }))
                Toggle("Sound", isOn: Binding(
                    get: { settings.state.soundEnabled },
                    set: { settings.setSound($0) }))
                    .disabled(!settings.state.notificationsEnabled)
                Toggle("Haptics", isOn: Binding(
                    get: { settings.state.hapticEnabled },
                    set: { settings.setHaptic($0) }))
            } footer: {
                Text("Push delivery arrives with the notification service (APP-012).")
            }
        }
        .tint(BitOSTheme.accent)
    }
}

private struct AppearanceSection: View {
    @Environment(SettingsStore.self) private var settings

    var body: some View {
        List {
            Section {
                Picker("Theme", selection: Binding(
                    get: { settings.state.themeMode },
                    set: { settings.set($0) })) {
                    ForEach(SettingsThemeMode.allCases) { mode in
                        Label(mode.label, systemImage: mode.symbol).tag(mode)
                    }
                }
                .pickerStyle(.inline)
                .labelsHidden()
            } header: {
                Text("Theme")
            } footer: {
                Text("Light surfaces land with the theming wave (APP-023); the preference is already persisted.")
            }
            Section {
                Picker("Text Size", selection: Binding(
                    get: { settings.state.fontSize },
                    set: { settings.set($0) })) {
                    ForEach(SettingsFontSize.allCases) { size in
                        Text(size.label).tag(size)
                    }
                }
                .pickerStyle(.segmented)
            } header: {
                Text("Text Size")
            } footer: {
                Text("Text scaling applies app-wide with the theming wave (APP-023).")
            }
            Section("Layout") {
                Toggle("Compact mode", isOn: Binding(
                    get: { settings.state.compactMode },
                    set: { settings.setCompactMode($0) }))
            }
            Section {
                AccentPaletteRow(selectedHex: settings.state.accentColorHex) { hex in
                    settings.setAccentColor(hex: hex)
                }
            } header: {
                Text("Accent Color")
            } footer: {
                Text("Accent applies app-wide with the theming wave (APP-023); the choice is already persisted.")
            }
        }
        .tint(BitOSTheme.accent)
    }
}

private struct AlgorithmSection: View {
    @Environment(SettingsStore.self) private var settings

    var body: some View {
        List {
            Section {
                Picker("Timeline", selection: Binding(
                    get: { settings.state.feedTimeline },
                    set: { settings.set($0) })) {
                    ForEach(SettingsFeedTimeline.allCases) { t in
                        Text(t.label).tag(t)
                    }
                }
                Toggle("Media previews", isOn: Binding(
                    get: { settings.state.mediaPreview },
                    set: { settings.setMediaPreview($0) }))
                Toggle("Show reactions", isOn: Binding(
                    get: { settings.state.showReactions },
                    set: { settings.setShowReactions($0) }))
                Toggle("Protocol notes", isOn: Binding(
                    get: { settings.state.showProtocolNotes },
                    set: { settings.setShowProtocolNotes($0) }))
            } header: {
                Text("Feed")
            } footer: {
                Text("Protocol notes show raw kind events (reposts, reactions) in the timeline — web feedPreferences parity.")
            }
        }
        .tint(BitOSTheme.accent)
    }
}

/// Legacy `accentColorOptions` palette (web/Flutter parity).
private struct AccentPaletteRow: View {
    let selectedHex: String
    let onPick: (String) -> Void

    private static let palette = [
        "#F7931A", "#8B5CF6", "#7C3AED", "#6366F1", "#3B82F6",
        "#0EA5E9", "#06B6D4", "#14B8A6", "#10B981", "#84CC16",
        "#F59E0B", "#EF4444", "#E11D48", "#EC4899", "#D946EF",
    ]

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 5), spacing: 10) {
            ForEach(Self.palette, id: \.self) { hex in
                let color = Color(hex: UInt32(hex.dropFirst(), radix: 16) ?? 0xF7931A)
                Button {
                    onPick(hex)
                } label: {
                    ZStack {
                        Circle().fill(color)
                            .frame(width: 34, height: 34)
                        if hex == selectedHex {
                            Circle().strokeBorder(Color.white, lineWidth: 2)
                                .frame(width: 34, height: 34)
                            AppIcons.image(for: AppIcons.check)
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(.white)
                        }
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Accent \(hex)")
            }
        }
        .padding(.vertical, 6)
    }
}

private struct MediaSection: View {
    @Environment(SettingsStore.self) private var settings

    var body: some View {
        List {
            Section {
                Picker("Autoplay", selection: Binding(
                    get: { settings.state.mediaAutoPlay },
                    set: { settings.set($0) })) {
                    ForEach(SettingsMediaAutoPlay.allCases) { m in
                        Text(m.label).tag(m)
                    }
                }
                Picker("Video quality", selection: Binding(
                    get: { settings.state.videoQuality },
                    set: { settings.set($0) })) {
                    ForEach(SettingsVideoQuality.allCases) { q in
                        Text(q.label).tag(q)
                    }
                }
                Picker("Playback rate", selection: Binding(
                    get: { settings.state.playbackRate },
                    set: { settings.set($0) })) {
                    ForEach(SettingsPlaybackRate.allCases) { r in
                        Text(r.label).tag(r)
                    }
                }
            } header: {
                Text("Playback")
            } footer: {
                Text("Autoplay honors the network policy above; downloads stay hash-verified (Blossom).")
            }
        }
        .tint(BitOSTheme.accent)
    }
}

private struct LanguageSection: View {
    @Environment(SettingsStore.self) private var settings

    var body: some View {
        List {
            Section {
                Picker("Language", selection: Binding(
                    get: { settings.state.language },
                    set: { settings.set($0) })) {
                    ForEach(SettingsLanguage.allCases) { l in
                        Text(l.label).tag(l)
                    }
                }
                .pickerStyle(.inline)
                .labelsHidden()
            } header: {
                Text("Language")
            } footer: {
                Text("Lao translation strings arrive with APP-024.")
            }
            Section("Region") {
                Picker("Date format", selection: Binding(
                    get: { settings.state.dateFormat },
                    set: { settings.set($0) })) {
                    ForEach(SettingsDateFormat.allCases) { f in
                        Text(f.label).tag(f)
                    }
                }
                HStack {
                    Text("Time zone")
                    Spacer()
                    Text(settings.state.timeZone == "auto" ? "Automatic" : settings.state.timeZone)
                        .font(.system(size: 13))
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
            }
        }
        .tint(BitOSTheme.accent)
    }
}

private struct AccountSection: View {
    @Environment(IdentityStore.self) private var identity
    @Environment(AppEnvironment.self) private var environment
    @Environment(SettingsStore.self) private var settings
    @Binding var npubCopied: Bool
    @State private var showEdit = false

    var body: some View {
        List {
            if let account = identity.account {
                Section("Identity") {
                    HStack(spacing: BitOSTheme.Spacing.md) {
                        PubkeyAvatarView(pubkey: account.pubkeyHex, size: 48)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Your account")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(BitOSTheme.textPrimary)
                            Button {
                                UIPasteboard.general.string = account.npub
                                npubCopied = true
                            } label: {
                                HStack(spacing: 4) {
                                    Text(settings.shortNpub(account.npub))
                                        .font(.system(size: 11, design: .monospaced))
                                    AppIcons.image(for: npubCopied ? AppIcons.check : AppIcons.copy)
                                        .font(.system(size: 10))
                                }
                                .foregroundStyle(BitOSTheme.accent)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 4)
                    Button {
                        showEdit = true
                    } label: {
                        Label("Edit profile", systemImage: AppIcons.pen)
                    }
                }
                Section("Storage") {
                    HStack {
                        Text("Settings cache")
                        Spacer()
                        Text(settings.cacheSizeLabel)
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                    Button(role: .destructive) {
                        settings.clearCache()
                    } label: {
                        Text("Clear cache (keeps theme & language)")
                    }
                }
            } else {
                Section {
                    Text("No account — create or import a key on the You tab.")
                        .font(.system(size: 13))
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
            }
        }
        .sheet(isPresented: $showEdit) {
            ProfileEditSheet(
                publisher: environment.notePublisher,
                onClose: { showEdit = false }
            )
            .presentationDetents([.medium, .large])
        }
    }
}

// MARK: - Security (legacy SecurityPage parity: keys + danger zone)

private struct SecuritySection: View {
    @Environment(IdentityStore.self) private var identity
    @State private var nsec: String?
    @State private var revealRequested = false
    @State private var copied: String?
    @State private var confirmSignOut = false

    var body: some View {
        List {
            if let account = identity.account {
                Section {
                    keyRow(label: "npub (public)", value: account.npub, token: "npub")
                    if let nsec {
                        keyRow(label: "nsec (secret)", value: nsec, token: "nsec")
                    } else {
                        Button {
                            revealRequested = true
                        } label: {
                            Label("Reveal secret key (nsec)", systemImage: "eye")
                        }
                        .confirmationDialog(
                            "Anyone with your nsec controls your identity. Never share or screenshot it.",
                            isPresented: $revealRequested,
                            titleVisibility: .visible
                        ) {
                            Button("Reveal", role: .destructive) {
                                if let secret = IdentityKeychain.loadSecret() {
                                    nsec = BusinessCoreBridge().nsecEncode(secretHex: secret)
                                }
                            }
                            Button("Cancel", role: .cancel) {}
                        }
                    }
                } header: {
                    Text("Keys")
                } footer: {
                    Text("Back up your nsec somewhere safe — it is the only way to recover this account.")
                }
            }
            Section {
                Button(role: .destructive) {
                    confirmSignOut = true
                } label: {
                    Label("Remove key from this device", systemImage: "trash")
                }
                .disabled(identity.account == nil)
            } header: {
                Text("Danger zone")
            } footer: {
                Text("Keeps every saved account, theme and feed preference — only the active key is removed.")
            }
            .confirmationDialog(
                "Remove the active key from this device?",
                isPresented: $confirmSignOut,
                titleVisibility: .visible
            ) {
                Button("Remove key", role: .destructive) { identity.removeAccount() }
                Button("Cancel", role: .cancel) {}
            }
        }
        .tint(BitOSTheme.accent)
    }

    private func keyRow(label: String, value: String, token: String) -> some View {
        Button {
            UIPasteboard.general.string = value
            copied = token
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(label)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                Text(value)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .lineLimit(2)
                    .truncationMode(.middle)
                if copied == token {
                    Label("Copied", systemImage: AppIcons.check)
                        .font(.system(size: 11))
                        .foregroundStyle(BitOSTheme.success)
                }
            }
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Lightning (legacy parity: default zap amount)

private struct LightningSection: View {
    @Environment(SettingsStore.self) private var settings

    var body: some View {
        List {
            Section {
                Stepper("Default zap: \(settings.state.defaultZapAmount) sats",
                        value: Binding(
                            get: { settings.state.defaultZapAmount },
                            set: { settings.setDefaultZapAmount($0) }),
                        in: 1...100_000, step: 1)
            } header: {
                Text("Zaps")
            } footer: {
                Text("Wallet pairing (LNURL/NWC) and the sats ledger arrive with the wallet wave (APP-014).")
            }
        }
        .tint(BitOSTheme.accent)
    }
}

// MARK: - Privacy (live rows + honest pending gates)

private struct PrivacySection: View {
    @Environment(SettingsStore.self) private var settings

    var body: some View {
        List {
            Section {
                Picker("Media auto-load", selection: Binding(
                    get: { settings.state.mediaAutoPlay },
                    set: { settings.set($0) })) {
                    ForEach(SettingsMediaAutoPlay.allCases) { m in
                        Text(m.label).tag(m)
                    }
                }
                Toggle("Protocol notes in feed", isOn: Binding(
                    get: { settings.state.showProtocolNotes },
                    set: { settings.setShowProtocolNotes($0) }))
            } header: {
                Text("Content")
            } footer: {
                Text("DM/mention gates, read receipts and blocked-user management arrive with the trust wave.")
            }
        }
        .tint(BitOSTheme.accent)
    }
}

// MARK: - Relays (configured set + live health; CRUD pending)

private struct RelaysSection: View {
    @Environment(AppEnvironment.self) private var environment
    @State private var health = RelayHealth(connected: 0, total: DefaultRelays.urls.count)

    var body: some View {
        List {
            Section {
                HStack {
                    Text("Connected")
                    Spacer()
                    Text("\(health.connected)/\(health.total)")
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(health.isLive ? BitOSTheme.success : BitOSTheme.textTertiary)
                }
            } footer: {
                Text("Adding, removing and per-relay read/write toggles arrive with the relay wave (NIP-65 publish).")
            }
            Section("Configured relays") {
                ForEach(DefaultRelays.urls, id: \.self) { url in
                    relayRow(url: url.value)
                }
            }
        }
        .tint(BitOSTheme.accent)
        .task {
            health = await environment.relayPool.health()
        }
    }

    private func relayRow(url: String) -> some View {
        let writeRelay = DefaultRelays.writeUrls.contains { $0.value == url }
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(url)
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(BitOSTheme.textPrimary)
                HStack(spacing: 6) {
                    roleChip("read", color: BitOSTheme.accent)
                    if writeRelay { roleChip("write", color: BitOSTheme.success) }
                }
            }
            Spacer()
        }
    }

    private func roleChip(_ label: String, color: Color) -> some View {
        Text(label)
            .font(.system(size: 10, weight: .semibold))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.15), in: Capsule())
            .foregroundStyle(color)
    }
}

// MARK: - Help (static FAQ, legacy parity)

private struct HelpSection: View {
    @State private var expanded: Set<String> = []

    private let faq: [(String, String)] = [
        ("What is BitOS?",
         "A Nostr client for short notes, Bitz clips and zaps. Your identity is a key pair you own — no email, no server account."),
        ("Where is my data stored?",
         "Notes are canonical signed events on relays. This device keeps a bounded cache; nothing is stored on a BitOS server."),
        ("How do I back up my account?",
         "Settings → Security → Reveal secret key. The nsec is the only recovery method — store it offline and never share it."),
        ("Why do some posts not load?",
         "Relays are independent servers. A post is only visible if at least one of your relays carries it."),
        ("How do zaps work?",
         "You can set a default amount and create an LNURL zap invoice. Wallet pairing and in-app settlement are not available yet, so pay the invoice in an external wallet."),
        ("What works today?",
         "Browsing verified relay notes, composing notes/replies/reposts/reactions, local bookmarks and follows, media upload verification, profile editing, relay status, and the listed settings preferences are available. Wallet pairing, relay editing, biometric app lock, full theming, and translations are still pending."),
    ]

    var body: some View {
        List {
            Section("FAQ") {
                ForEach(faq, id: \.0) { question, answer in
                    VStack(alignment: .leading, spacing: 6) {
                        Button {
                            if expanded.contains(question) {
                                expanded.remove(question)
                            } else {
                                expanded.insert(question)
                            }
                        } label: {
                            HStack {
                                Text(question)
                                    .font(.system(size: 15, weight: .medium))
                                    .foregroundStyle(BitOSTheme.textPrimary)
                                Spacer()
                                Image(systemName: expanded.contains(question) ? "chevron.up" : "chevron.down")
                                    .font(.system(size: 11))
                                    .foregroundStyle(BitOSTheme.textTertiary)
                            }
                        }
                        .buttonStyle(.plain)
                        if expanded.contains(question) {
                            Text(answer)
                                .font(.system(size: 13))
                                .foregroundStyle(BitOSTheme.textSecondary)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
        }
        .tint(BitOSTheme.accent)
    }
}

private struct AboutSection: View {
    var body: some View {
        List {
            Section {
                LabeledRow(label: "Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                LabeledRow(label: "Settings schema", value: "v\(BusinessCoreBridge().settingsSchemaVersion())")
                LabeledRow(label: "Event store schema", value: "v\(BusinessCoreBridge().eventStoreSchemaVersion())")
            }
            Section {
                Text("BitOS — sovereign identity on Nostr. Notes are canonical signed events; this app is a projection of them.")
                    .font(.system(size: 13))
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
        }
    }
}

private struct LabeledRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
            Spacer()
            Text(value)
                .font(.system(size: 13))
                .foregroundStyle(BitOSTheme.textSecondary)
        }
    }
}

/// Honest placeholder for sections whose features are later waves.
private struct PendingSection: View {
    let sectionKey: String

    private var note: String {
        switch sectionKey {
        case "lightning": "Zap tiers, LNURL/NWC wallet pairing and the ledger arrive with the wallet wave (APP-014)."
        case "privacy": "Mute/block lists and content filters arrive with the trust wave."
        case "security": "nsec backup, biometric lock and key rotation arrive with the security wave."
        case "relays": "Relay management (add/remove/read-write) arrives with the relay wave."
        case "help": "FAQ and support pages arrive with static content (APP-020)."
        default: "Coming in a later wave."
        }
    }

    var body: some View {
        List {
            Section {
                Text(note)
                    .font(.system(size: 13))
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
        }
    }
}
