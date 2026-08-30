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
            Button("Sign out", role: .destructive) { identity.signOut() }
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
    @Environment(AppEnvironment.self) private var environment

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
            Section {
                ForEach(Array(NotificationKind.allCases), id: \.name) { kind in
                    Toggle(notificationTypeLabel(kind), isOn: Binding(
                        get: { !environment.inboxStore.mutedKinds.contains(kind) },
                        set: { enabled in
                            var muted = environment.inboxStore.mutedKinds
                            if enabled {
                                muted.remove(kind)
                            } else {
                                muted.insert(kind)
                            }
                            environment.inboxStore.setMutedKinds(muted)
                        }))
                }
            } header: {
                Text("Notify me about")
            } footer: {
                Text("Muted types drop from the inbox, unread counts and the Activity badge (same mutes as the inbox header menu).")
            }
        }
        .tint(BitOSTheme.accent)
    }

    private func notificationTypeLabel(_ kind: NotificationKind) -> String {
        switch kind {
        case .reply: "Replies"
        case .mention: "Mentions"
        case .reaction: "Likes"
        case .repost: "Reposts"
        case .zap: "Zaps"
        case .follow: "New follows"
        }
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
    @Environment(AlgorithmStore.self) private var algorithm
    @State private var surface = "feed"

    private static let surfaceKeys = ["feed", "reels", "discover"]
    private static let signalKeys = ["recency", "engagement", "zaps", "affinity", "topics", "wot"]

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
                Text("Serialized channel records, such as channel:__roster, are hidden by default.")
            }

            Section {
                Picker("Recency half-life", selection: Binding(
                    get: { algorithm.freshnessHours },
                    set: { algorithm.setFreshness(hours: $0) })) {
                    Text("Live \u{00B7} 1h").tag(1)
                    Text("Balanced \u{00B7} 6h").tag(6)
                    Text("Relaxed \u{00B7} 24h").tag(24)
                    Text("Chill \u{00B7} 3d").tag(72)
                }
                .pickerStyle(.menu)
            } header: {
                Text("Freshness")
            } footer: {
                Text("Freshness retunes the recency signal everywhere \u{2014} older notes survive longer at higher steps.")
            }

            Section {
                Picker("Surface", selection: $surface) {
                    ForEach(Self.surfaceKeys, id: \.self) { key in
                        Text(key.capitalized).tag(key)
                    }
                }
                .pickerStyle(.segmented)
                Toggle("Ranked \(surface)", isOn: Binding(
                    get: { algorithm.surfaces[surface]?.enabled ?? false },
                    set: { algorithm.setEnabled(surface: surface, enabled: $0) }))
                if algorithm.surfaces[surface]?.enabled == true {
                    Toggle("Diverse authors", isOn: Binding(
                        get: { algorithm.surfaces[surface]?.diversityEnabled ?? true },
                        set: { algorithm.setDiversity(surface: surface, enabled: $0) }))
                }
                if !(algorithm.surfaces[surface]?.enabled ?? false) {
                    Text("Off = strict reverse-chronological \u{2014} never hidden.")
                        .font(.system(size: 12))
                        .foregroundStyle(BitOSTheme.textSecondary)
                }
            } header: {
                Text("Surfaces")
            }

            if algorithm.surfaces[surface]?.enabled == true {
                Section {
                    let preset = algorithm.detectPreset(surface: surface)
                    HStack(spacing: 8) {
                        ForEach([("LATEST", "Latest"), ("BALANCED", "Balanced"), ("TRENDING", "Trending"), ("TRUSTED", "Trusted")], id: \.0) { id, label in
                            presetPill(label, id: id, active: preset == id) {
                                algorithm.setPreset(surface: surface, preset: id)
                            }
                        }
                        if preset == "CUSTOM" {
                            Text("Custom")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(BitOSTheme.accent)
                                .padding(.horizontal, 12).padding(.vertical, 6)
                                .background(BitOSTheme.accent.opacity(0.15), in: Capsule())
                        }
                    }
                } header: {
                    Text("Preset")
                }
                Section {
                    AlgorithmMixBar(algorithm: algorithm, surface: surface)
                    ForEach(Self.signalKeys, id: \.self) { signal in
                        AlgorithmSignalRow(
                            signal: signal,
                            state: algorithm.signalState(surface: surface, signal: signal),
                            totalWeight: activeWeight(algorithm: algorithm, surface: surface)
                        ) { enabled, weight in
                            algorithm.setSignal(surface: surface, signal: signal, enabled: enabled, weight: weight)
                        }
                    }
                } header: {
                    Text("Signals")
                } footer: {
                    Text("Weights re-balance live \u{2014} turning a signal off re-normalizes the mix. Topics & Web-of-trust contribute once their data feeds land (W2).")
                }
                Section {
                    Button("Reset to preset") {
                        algorithm.resetToPreset(surface: surface)
                    }
                }
            }
        }
        .tint(BitOSTheme.accent)
    }

    private func presetPill(_ label: String, id: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 13, weight: active ? .bold : .medium))
                .foregroundStyle(active ? BitOSTheme.accent : BitOSTheme.textSecondary)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(
                    active ? BitOSTheme.accent.opacity(0.15) : BitOSTheme.surfaceOverlay.opacity(0.5),
                    in: Capsule()
                )
        }
        .buttonStyle(.plain)
    }

    private func activeWeight(algorithm: AlgorithmStore, surface: String) -> Double {
        (algorithm.surfaces[surface]?.signals ?? [:])
            .values.filter { $0.enabled && $0.weight > 0 }.reduce(0) { $0 + $1.weight }
    }
}

private func signalColor(_ signal: String) -> Color {
    switch signal {
    case "recency": BitOSTheme.accent
    case "engagement": BitOSTheme.like
    case "zaps": BitOSTheme.zap
    case "affinity": BitOSTheme.reply
    case "topics": Color(hex: 0x06B6D4)
    case "wot": BitOSTheme.success
    default: BitOSTheme.textTertiary
    }
}

/// Live weight-mix bar (origin parity): stacked shares of the enabled mix.
private struct AlgorithmMixBar: View {
    let algorithm: AlgorithmStore
    let surface: String

    var body: some View {
        let active = (algorithm.surfaces[surface]?.signals ?? [:])
            .filter { $0.value.enabled && $0.value.weight > 0 }
            .sorted { $0.key < $1.key }
        if !active.isEmpty {
            let total = active.reduce(0.0) { $0 + $1.value.weight }
            GeometryReader { proxy in
                HStack(spacing: 0) {
                    ForEach(active, id: \.key) { key, state in
                        Rectangle()
                            .fill(signalColor(key))
                            .frame(width: proxy.size.width * (state.weight / total))
                    }
                }
            }
            .frame(height: 8)
            .clipShape(Capsule())
            .accessibilityLabel("Weight mix")
        }
    }
}

private struct AlgorithmSignalRow: View {
    let signal: String
    let state: AlgorithmStore.SignalState
    let totalWeight: Double
    let onChange: (Bool, Double) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Circle().fill(signalColor(signal)).frame(width: 10, height: 10)
                Text(signal.capitalized)
                    .font(.system(size: 14))
                    .foregroundStyle(state.enabled ? BitOSTheme.textPrimary : BitOSTheme.textTertiary)
                Spacer()
                Text(state.enabled && totalWeight > 0
                     ? "\(Int(state.weight / totalWeight * 100))%"
                     : "\u{2014}")
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(BitOSTheme.textSecondary)
                Toggle("", isOn: Binding(
                    get: { state.enabled },
                    set: { onChange($0, state.weight) }))
                    .labelsHidden()
            }
            if state.enabled {
                Slider(
                    value: Binding(
                        get: { state.weight },
                        set: { onChange(true, $0) }),
                    in: 0.05...1,
                    step: 0.05
                )
            }
        }
        .padding(.vertical, 2)
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
            Section {
                LabeledRow(label: "Provider", value: "Blossom (default)")
            } header: {
                Text("Uploads")
            } footer: {
                Text("Hash-verified Blossom uploads before signing; S3/Cloudinary fallbacks arrive with the media wave.")
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
    // APP-018a row 1: switches ride the branded overlay (MoreView parity).
    @State private var switchTarget: RegisteredAccountRow?

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
                Section {
                    if identity.registeredAccounts.isEmpty {
                        Text("No saved accounts \u{2014} create or import a key on the You tab.")
                            .font(.system(size: 13))
                            .foregroundStyle(BitOSTheme.textSecondary)
                    } else {
                        ForEach(identity.registeredAccounts) { acct in
                            let isActive = acct.pubkeyHex == (identity.activeRegistryPubkey ?? identity.account?.pubkeyHex)
                            HStack(spacing: 10) {
                                PubkeyAvatarView(pubkey: acct.pubkeyHex, size: 36)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(acct.displayName ?? "Account")
                                        .font(.system(size: 14, weight: isActive ? .bold : .medium))
                                        .foregroundStyle(BitOSTheme.textPrimary)
                                    Text(settings.shortNpub(acct.npub))
                                        .font(.system(size: 11, design: .monospaced))
                                        .foregroundStyle(BitOSTheme.textSecondary)
                                }
                                Spacer()
                                if isActive {
                                    Image(systemName: AppIcons.check)
                                        .font(.system(size: 13, weight: .bold))
                                        .foregroundStyle(BitOSTheme.accent)
                                }
                                Button("Remove", role: .destructive) {
                                    identity.removeRegisteredAccount(pubkeyHex: acct.pubkeyHex)
                                }
                                .font(.system(size: 13))
                            }
                            .contentShape(Rectangle())
                            .onTapGesture { if !isActive { switchTarget = acct } }
                        }
                    }
                } header: {
                    Text("Accounts on this device")
                } footer: {
                    Text("Switching keeps every account sealed on this device \u{2014} one tap back. Remove wipes that account's key (back it up first).")
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
                        // APP-018a row 5 (legacy parity): also wipe the
                        // session's derived feed state — the persisted
                        // event cache arrives with DAT-003 on iOS.
                        environment.feedStore.clearDerivedState()
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
                initialProfile: environment.feedStore.profiles[identity.account?.pubkeyHex ?? ""],
                onClose: { showEdit = false }
            )
            .presentationDetents([.medium, .large])
        }
        .fullScreenCover(item: $switchTarget) { target in
            AccountSwitchOverlayView(
                fromPubkey: identity.activeRegistryPubkey ?? identity.account?.pubkeyHex,
                toPubkey: target.pubkeyHex,
                toName: target.displayName ?? (String(target.npub.prefix(10)) + "\u{2026}"),
                switchAction: { identity.switchTo(pubkeyHex: target.pubkeyHex) },
                onFinished: { switchTarget = nil }
            )
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
                                let secret = identity.activeRegistryPubkey.flatMap { IdentityKeychain.loadSecret(slotPubkey: $0) } ?? IdentityKeychain.loadSecret()
                                if let secret {
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
    @Environment(PrivacyPrefsStore.self) private var privacy
    @Environment(AppEnvironment.self) private var environment
    @Environment(IdentityStore.self) private var identity
    @Environment(RelayManagerStore.self) private var relays

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
                Picker("Sensitive media", selection: Binding(
                    get: { settings.state.sensitiveMedia },
                    set: { settings.set($0) })) {
                    ForEach(SettingsSensitiveMedia.allCases) { m in
                        Text(m.label).tag(m)
                    }
                }
            } header: {
                Text("Content")
            } footer: {
                Text("Cover keeps NIP-36 flagged notes behind a tap-to-reveal; Show renders them directly. Device-local choice.")
            }
            Section {
                Toggle("Private account", isOn: Binding(
                    get: { privacy.state.privateAccount },
                    set: { newValue in privacy.update { $0.privateAccount = newValue } }))
                Toggle("Include client tag", isOn: Binding(
                    get: { privacy.state.includeClientTag },
                    set: { newValue in privacy.update { $0.includeClientTag = newValue } }))
                Toggle("Activity visible to others", isOn: Binding(
                    get: { privacy.state.activityVisible },
                    set: { newValue in privacy.update { $0.activityVisible = newValue } }))
                Toggle("Read receipts", isOn: Binding(
                    get: { privacy.state.readReceipts },
                    set: { newValue in privacy.update { $0.readReceipts = newValue } }))
                Toggle("Show sensitive-content reason", isOn: Binding(
                    get: { privacy.state.sensitiveReason },
                    set: { newValue in privacy.update { $0.sensitiveReason = newValue } }))
                Toggle("Allow story sharing", isOn: Binding(
                    get: { privacy.state.storyShare },
                    set: { newValue in privacy.update { $0.storyShare = newValue } }))
            } header: {
                Text("Account privacy")
            } footer: {
                Text("Read receipts and story sharing take effect when DMs and stories ship (W2); the choices persist now \u{2014} legacy parity.")
            }
            Section {
                Picker("Who can message me", selection: Binding(
                    get: { privacy.state.messagePermission },
                    set: { newValue in privacy.update { $0.messagePermission = newValue } })) {
                    Text("Everyone").tag("everyone")
                    Text("Followers").tag("followers")
                    Text("No one").tag("none")
                }
                Picker("Who can comment", selection: Binding(
                    get: { privacy.state.commentPermission },
                    set: { newValue in privacy.update { $0.commentPermission = newValue } })) {
                    Text("Everyone").tag("everyone")
                    Text("Followers").tag("followers")
                    Text("Friends").tag("friends")
                }
            } header: {
                Text("Interactions")
            } footer: {
                Text("Gates apply to incoming interactions (W2 DMs/comments enforcement; persisted now \u{2014} legacy parity).")
            }
            Section {
                let blocked = environment.feedStore.blocked
                if blocked.isEmpty {
                    Text("No blocked authors on this account's kind-10004 list.")
                        .font(.system(size: 13))
                        .foregroundStyle(BitOSTheme.textSecondary)
                } else {
                    ForEach(Array(blocked.sorted().prefix(50)), id: \.self) { pubkey in
                        HStack {
                            PubkeyAvatarView(pubkey: pubkey, size: 28)
                            Text(shortBlocked(pubkey))
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(BitOSTheme.textSecondary)
                            Spacer()
                            Button("Unblock") {
                                Task {
                                    environment.notePublisher.dismiss()
                                    await environment.notePublisher.publishBlockList(
                                        blocked: blocked.filter { $0 != pubkey },
                                        writeUrls: relays.writeUrls()
                                    )
                                }
                            }
                            .font(.system(size: 13, weight: .semibold))
                        }
                    }
                    if blocked.count > 50 {
                        Text("Showing first 50 of \(blocked.count) blocked authors.")
                            .font(.system(size: 11))
                            .foregroundStyle(BitOSTheme.textTertiary)
                    }
                    PublishStatusLine(publisher: environment.notePublisher)
                }
            } header: {
                Text("Blocked users")
            } footer: {
                Text("Blocked authors are filtered from feeds and the inbox (NIP-51). Unblock publishes a new kind-10004 head to your write relays. DM/mention gates and read receipts arrive with the DM wave.")
            }
        }
        .tint(BitOSTheme.accent)
    }

    private func shortBlocked(_ pubkey: String) -> String {
        BusinessCoreBridge().npubEncode(pubkeyHex: pubkey).map(settings.shortNpub) ?? pubkey
    }
}

// MARK: - Relays manager (APP-018 §3.18: CRUD, roles, status dots, NIP-65)

private struct RelaysSection: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(RelayManagerStore.self) private var relays
    @Environment(IdentityStore.self) private var identity
    @State private var health = RelayHealth(connected: 0, total: DefaultRelays.urls.count)
    @State private var addInput = ""
    @State private var addError: String?
    @FocusState private var addFocused: Bool

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
                Text("Edits connect and disconnect sockets immediately and persist on this device.")
            }
            Section {
                ForEach(relays.relays) { relay in
                    RelayManagerRow(
                        relay: relay,
                        state: relays.connectionStates[relay.url],
                        onRemove: { relays.remove(url: relay.url) },
                        onToggleRead: { relays.setRoles(url: relay.url, read: !relay.read, write: relay.write) },
                        onToggleWrite: { relays.setRoles(url: relay.url, read: relay.read, write: !relay.write) },
                        onTogglePrimary: { relays.setPrimary(url: relay.url, primary: !relay.primary) }
                    )
                }
            } header: {
                Text("Configured relays")
            } footer: {
                Text("Roles follow NIP-65: read relays serve your feeds; write relays receive your events. A relay needs at least one role.")
            }
            Section {
                let suggestions = ["wss://nostr-01.yakihonne.com", "wss://relay.primal.net", "wss://relay.damus.io", "wss://nos.lol"]
                    .filter { url in !relays.relays.contains { $0.url == url } }
                if !suggestions.isEmpty {
                    HStack(spacing: 6) {
                        ForEach(suggestions, id: \.self) { url in
                            Button {
                                addInput = url
                                addError = nil
                            } label: {
                                Text(url.replacingOccurrences(of: "wss://", with: "").replacingOccurrences(of: "relay.", with: ""))
                                    .font(.system(size: 11))
                                    .foregroundStyle(BitOSTheme.accent)
                                    .padding(.horizontal, 8).padding(.vertical, 4)
                                    .background(BitOSTheme.accent.opacity(0.15), in: Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                HStack {
                    TextField("wss://relay.example.com", text: $addInput)
                        .font(.system(size: 13, design: .monospaced))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        .focused($addFocused)
                        .onSubmit(addRelay)
                    Button(action: addRelay) {
                        AppIcons.image(for: AppIcons.add)
                            .font(.system(size: 16, weight: .medium))
                    }
                    .disabled(addInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityLabel("Add relay")
                }
                if let addError {
                    Text(addError)
                        .font(.system(size: 12))
                        .foregroundStyle(BitOSTheme.error)
                }
            } header: {
                Text("Add relay")
            }
            Section {
                if identity.account == nil {
                    Text("Publishing the relay list (kind 10002) needs an account — create or import a key first.")
                        .font(.system(size: 13))
                        .foregroundStyle(BitOSTheme.textSecondary)
                } else {
                    Button {
                        Task {
                            environment.notePublisher.dismiss()
                            await environment.notePublisher.publishRelayList(
                                relayListJson: relays.encode(),
                                writeUrls: relays.writeUrls()
                            )
                        }
                    } label: {
                        Label("Publish relay list (NIP-65)", image: "SolarBoltLinear")
                    }
                    PublishStatusLine(publisher: environment.notePublisher)
                }
            } header: {
                Text("Publish")
            } footer: {
                Text("The published kind-10002 event advertises your relays to other clients (NIP-65).")
            }
        }
        .tint(BitOSTheme.accent)
        .task {
            // Status dots + header count poll while the section is visible.
            while !Task.isCancelled {
                await relays.refreshConnectionStates()
                health = await relays.health()
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }

    private func addRelay() {
        if relays.add(rawUrl: addInput) {
            addInput = ""
            addError = nil
            addFocused = false
        } else {
            addError = relays.relays.contains { $0.url == addInput.trimmingCharacters(in: .whitespacesAndNewlines) }
                ? "Already in your relay set."
                : "Enter a valid wss:// relay URL."
        }
    }
}

private struct RelayManagerRow: View {
    let relay: ManagedRelay
    let state: String?
    let onRemove: () -> Void
    let onToggleRead: () -> Void
    let onToggleWrite: () -> Void
    let onTogglePrimary: () -> Void

    private var dotColor: Color {
        switch state {
        case "connected": BitOSTheme.success
        case "connecting": BitOSTheme.warning
        default: BitOSTheme.textTertiary
        }
    }

    var body: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(dotColor)
                .frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 4) {
                Text(relay.url)
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(BitOSTheme.textPrimary)
                HStack(spacing: 6) {
                    roleChip("read", active: relay.read, action: onToggleRead)
                    roleChip("write", active: relay.write, action: onToggleWrite)
                }
            }
            Spacer()
            if relay.write {
                Button(action: onTogglePrimary) {
                    Text(relay.primary ? "\u{2605}" : "\u{2606}")
                        .font(.system(size: 16))
                        .foregroundStyle(relay.primary ? BitOSTheme.warning : BitOSTheme.textTertiary)
                }
                .accessibilityLabel("Toggle primary relay")
            }
            Button(role: .destructive, action: onRemove) {
                AppIcons.image(for: AppIcons.delete)
                    .font(.system(size: 15, weight: .medium))
            }
            .accessibilityLabel("Remove relay")
        }
        .padding(.vertical, 2)
    }

    private func roleChip(_ label: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 10, weight: .semibold))
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(
                    (active ? BitOSTheme.accent : BitOSTheme.textTertiary).opacity(0.15),
                    in: Capsule()
                )
                .foregroundStyle(active ? BitOSTheme.accent : BitOSTheme.textTertiary)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Toggle \(label) role")
    }
}

private struct PublishStatusLine: View {
    private let publisher: NotePublisher

    init(publisher: NotePublisher) {
        self.publisher = publisher
    }

    private var statusText: String? {
        if publisher.result == nil, publisher.inFlightId != nil { return "Publishing…" }
        switch publisher.result {
        case .published: return "Published ✓"
        case .rejected(let detail): return "Rejected: \(detail ?? "relay declined")"
        case .timeout: return "No relay receipt before timeout."
        case .signingRefused: return "No account key available."
        case .invalid: return "Relay list invalid — nothing sent."
        case nil: return nil
        }
    }

    var body: some View {
        if let text = statusText {
            Text(text)
                .font(.system(size: 12))
                .foregroundStyle(publisher.result == .published ? BitOSTheme.success : BitOSTheme.textSecondary)
        }
    }
}

// MARK: - Help (static FAQ, legacy parity)

private struct HelpSection: View {
    @Environment(AppEnvironment.self) private var environment
    @State private var expanded: Set<String> = []
    @State private var showDonate = false
    private let bridge = BusinessCoreBridge()

    private var faq: [(String, String)] {
        bridge.appFactsFaq().map { ($0.question, $0.answer) }
    }

    var body: some View {
        let facts = bridge.appFacts()
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text(facts.contributeNote)
                        .font(.system(size: 13))
                        .foregroundStyle(BitOSTheme.textSecondary)
                    Button {
                        showDonate = true
                    } label: {
                        Label("Donate sats", image: "SolarBoltLinear")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(BitOSTheme.accent)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.vertical, 4)
            } header: {
                Text("Support the project")
            } footer: {
                Text("Donations go straight to the project\u{2019}s Lightning address \u{2014} no custodian.")
            }
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
            Section("Contributors") {
                let bridge = BusinessCoreBridge()
                let contributorHexes = facts.contributorNpubs.map { bridge.parseNpub(encoded: $0) }
                ForEach(Array(zip(facts.contributorNpubs, contributorHexes)), id: \.0) { npub, hex in
                    contributorRow(npub: npub, hex: hex)
                }
            }
            Section("Links") {
                factLink("Nostr Improvement Possibilities", url: facts.linkNips)
                factLink("What is Nostr?", url: facts.linkNostr)
                if !facts.linkSource.isEmpty {
                    factLink("Source code", url: facts.linkSource)
                }
            }
        }
        .tint(BitOSTheme.accent)
        .sheet(isPresented: $showDonate) {
            SupportDonateSheet(
                supportNpub: facts.supportNpub,
                tiers: facts.supportTiersSats.map { (sats: Int($0), recommended: Int($0) == Int(facts.recommendedTierSats)) }
                    .map { (sats: Int($0.0), recommended: $0.1) },
                lookup: environment.profileLookup,
                onDismiss: { showDonate = false }
            )
            .presentationDetents([.medium, .large])
        }
    }

    private var facts: BusinessCoreBridge.AppFactsWire { BusinessCoreBridge().appFacts() }

    private func contributorRow(npub: String, hex: String?) -> some View {
        let profile = hex.flatMap { environment.profileLookup.profiles[$0] }
        let name = profile.map(\.bestDisplayName).flatMap { $0.isEmpty ? nil : $0 }
            ?? (String(npub.prefix(14)) + "\u{2026}" + String(npub.suffix(10)))
        return HStack(spacing: 10) {
            if let hex {
                PubkeyAvatarView(pubkey: hex, size: 36)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(name)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                    .lineLimit(1)
                Text("Contributor")
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.textTertiary)
            }
            Spacer()
            Button("Copy npub") {
                UIPasteboard.general.string = npub
            }
            .font(.system(size: 12))
            .foregroundStyle(BitOSTheme.accent)
        }
    }

    private func factLink(_ label: String, url: String) -> some View {
        Link(destination: URL(string: url)!) {
            HStack {
                Text(label)
                    .font(.system(size: 15))
                    .foregroundStyle(BitOSTheme.textPrimary)
                Spacer()
                Text(url.replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "www.", with: ""))
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.textTertiary)
                    .lineLimit(1)
                Image(systemName: "arrow.up.right")
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.textTertiary)
            }
        }
    }
}

// MARK: - About (legacy AboutPage hero parity)

private struct AboutSection: View {
    private let bridge = BusinessCoreBridge()

    var body: some View {
        let facts = bridge.appFacts()
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        List {
            Section {
                VStack(spacing: 8) {
                    HexIcon(
                        systemName: AppIcons.zap,
                        size: 56,
                        background: BitOSTheme.accent.opacity(0.15),
                        foreground: BitOSTheme.accent
                    )
                    Text(facts.appName)
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(BitOSTheme.textPrimary)
                    Text("version \(version) \u{00B7} built on \(facts.builtOn) \u{00B7} \(facts.license)")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(BitOSTheme.textTertiary)
                    Text(facts.tagline)
                        .font(.system(size: 13))
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
            }
            Section {
                FlowChips(items: facts.supportedNips.map { String(format: "NIP-%02lld", $0) })
            } header: {
                Text("Supported NIPs")
            } footer: {
                Text("Only NIPs live in this client are listed \u{2014} the list grows with each shipped wave.")
            }
            Section {
                LabeledRow(label: "Version", value: version)
                LabeledRow(label: "Settings schema", value: "v\(bridge.settingsSchemaVersion())")
                LabeledRow(label: "Event store schema", value: "v\(bridge.eventStoreSchemaVersion())")
            }
            Section {
                Text("BitOS \u{2014} sovereign identity on Nostr. Notes are canonical signed events; this app is a projection of them.")
                    .font(.system(size: 13))
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
        }
    }
}

/// Wrapping monospace chips (legacy NIP badge parity).
private struct FlowChips: View {
    let items: [String]

    private let rows: [[String]]
    init(items: [String]) {
        var built: [[String]] = []
        var current: [String] = []
        for item in items {
            current.append(item)
            if current.count == 5 {
                built.append(current)
                current = []
            }
        }
        if !current.isEmpty { built.append(current) }
        rows = built
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(spacing: 6) {
                    ForEach(row, id: \.self) { item in
                        Text(item)
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(BitOSTheme.textSecondary)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(BitOSTheme.surfaceOverlay.opacity(0.5), in: Capsule())
                    }
                }
            }
        }
        .padding(.vertical, 4)
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
