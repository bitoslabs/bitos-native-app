import BusinessCore
import SwiftUI

enum AppDestination: Hashable {
    case home
    case bitz
    case chats
    case activity
    case you
}

/// Six-tab product shell (user decision 2026-08-28, legacy-app parity):
/// Home · Bitz · Discover · Chats · Activity · You. Studio/Create entry
/// points stay on the Home composer FAB, the Bitz header and the future
/// You hub (APP-017); Settings pushes from You (APP-018).
struct RootView: View {
    /// T16: pending inbound deep link (consumed once by the router below).
    var deepLinkUri: String? = nil
    var onDeepLinkConsumed: () -> Void = {}

    @State private var destination: AppDestination = .home
    // APP-002: first launch gates on the onboarding carousel.
    @State private var showOnboarding = !OnboardingPrefs.hasOnboarded
    @State private var showDiscover = false
    @State private var showMore = false
    /** T16 deep-link surfaces. */
    @State private var deepLinkAuthor: String?
    @State private var deepLinkInvoice: String?
    /** APP-011 chat-header ⚡ chip target (author zap pipeline). */
    @State private var chatZapPeer: String?
    /** UX-010: full profile page target from chat headers. */
    @State private var chatProfilePeer: String?
    /// APP-003: bumped when the user re-taps the active Home/Bitz tab —
    /// the surface scrolls to top, or refreshes when already at top.
    @State private var feedRetapTick = 0
    @Environment(AppEnvironment.self) private var environment
    @Environment(SettingsStore.self) private var settings

    /// Persisted font size → DynamicTypeSize (applied app-wide).
    private var fontTypeSize: DynamicTypeSize {
        switch settings.state.fontSize {
        case .small: .xSmall
        case .large: .xxLarge
        case .extraLarge: .accessibility2
        default: .large
        }
    }

    var body: some View {
        Group {
            if showOnboarding {
                // Spec §4: first launch gates on the identity onboarding
                // flow (welcome → add identity → import/backup → npub
                // confirmation); Browse now exits into the feed as guest.
                OnboardingScreen(store: environment.identityStore) {
                    OnboardingPrefs.markOnboarded()
                    showOnboarding = false
                }
            } else {
                appTabs
            }
        }
    }

    private var appTabs: some View {
        TabView(selection: tabSelection) {
            HomeView(store: environment.feedStore,
                     onOpenDiscover: { showDiscover = true },
                     onOpenProfile: { destination = .you },
                     onOpenHub: { showMore = true },
                     retapTick: feedRetapTick)
                .tag(AppDestination.home)
                .tabItem { Label { Text("Home") } icon: { AppIcons.image(for: AppIcons.home) } }

            BitzView(retapTick: feedRetapTick)
                .tag(AppDestination.bitz)
                .tabItem { Label { Text("Bitz") } icon: { AppIcons.image(for: AppIcons.bitz) } }

            DmScreen(
                onZapPeer: { chatZapPeer = $0 },
                onOpenProfile: { chatProfilePeer = $0 }
            )
            .tag(AppDestination.chats)
            .tabItem { Label { Text("Chats") } icon: { AppIcons.image(for: AppIcons.chat) } }
            .badge(chatsBadge)

            InboxView(store: environment.inboxStore)
                .tag(AppDestination.activity)
                .tabItem { Label { Text("Activity") } icon: { AppIcons.image(for: AppIcons.inbox) } }
                .badge(activityBadge)

            ProfileView(store: environment.identityStore)
                .tag(AppDestination.you)
                .tabItem { Label { Text("You") } icon: { AppIcons.image(for: AppIcons.userProfile) } }
        }
        .tint(BitOSTheme.accent)
        // APP-018 functional setting: font size applies app-wide.
        .environment(\.dynamicTypeSize, fontTypeSize)
        // Web feedPreferences parity: the persisted protocol-notes opt-in
        // drives the feed gate live (Settings → Feed → Protocol notes).
        .onChange(of: settings.state.showProtocolNotes, initial: true) { _, enabled in
            environment.feedStore.setShowProtocolNotes(enabled)
        }
        // Tokens are dark-only until APP-023; the persisted theme preference
        // (SettingsStore) will drive this once light surfaces exist.
        .preferredColorScheme(.dark)
        .environment(environment.identityStore)
        // One shared feed store backs Home and Bitz. Own its relay lifecycle
        // at the shell so switching tabs never closes and reopens the same
        // subscription.
        .task {
            await environment.feedStore.start()
        }
        // Shell-level account wiring: the Activity badge needs the inbox
        // subscription alive from app start, not only while the tab is open;
        // the Chats badge rides the DM store's own subscription.
        .task(id: environment.identityStore.account?.pubkeyHex) {
            environment.feedStore.setAccount(environment.identityStore.account?.pubkeyHex)
            environment.inboxStore.setAccount(environment.identityStore.account?.pubkeyHex)
            environment.dmStore.setAccount(environment.identityStore.account?.pubkeyHex)
            environment.hashtagFollows.setAccount(environment.identityStore.account?.pubkeyHex)
        }
        // APP-011 chat ⚡ chip → profile zap sheet (mock chip-orange parity).
        .sheet(item: Binding(
            get: { chatZapPeer.map { ZapPeerTarget(pubkey: $0) } },
            set: { chatZapPeer = $0?.pubkey }
        )) { target in
            ZapSheet(
                authorPubkey: target.pubkey,
                profile: environment.feedStore.profiles[target.pubkey],
                onClose: { chatZapPeer = nil }
            )
            .environment(environment.identityStore)
            .preferredColorScheme(.dark)
        }
        // UX-010: chat header → full in-app profile page.
        .sheet(item: Binding(
            get: { chatProfilePeer.map { ZapPeerTarget(pubkey: $0) } },
            set: { chatProfilePeer = $0?.pubkey }
        )) { target in
            AuthorProfileSheet(authorPubkey: target.pubkey) {
                chatProfilePeer = nil
            }
            .environment(environment)
            .environment(environment.identityStore)
        }
        .sheet(isPresented: $showMore) {
            MoreView(
                onOpenProfile: { showMore = false; destination = .you },
                onOpenDiscover: { showMore = false; showDiscover = true }
            )
            .environment(environment)
            .environment(settings)
            .environment(environment.relayManager)
            .environment(environment.privacyPrefs)
        }
        .sheet(isPresented: $showDiscover) {
            NavigationStack {
                DiscoverView()
            }
        }
        // ── T16 deep-link routing (spec §1.2) ───────────────────────────
        .onChange(of: deepLinkUri) { _, uri in
            guard let uri, let json = (BusinessCoreBridge().deepLinkJson(uri: uri) as String?),
                  let data = json.data(using: .utf8),
                  let target = try? JSONSerialization.jsonObject(with: data) as? [String: String],
                  let kind = target["kind"], let value = target["value"] else { return }
            switch kind {
            case "author":
                environment.authorStore.open(authorPubkey: value)
                deepLinkAuthor = value
            case "note":
                // Discover's ref-search fetches the head and its result
                // card opens the thread sheet.
                showDiscover = true
                environment.searchStore.search(value)
            case "lightning":
                deepLinkInvoice = value
            default: break
            }
            onDeepLinkConsumed()
        }
        .sheet(item: Binding(
            get: { deepLinkAuthor.map { DeepLinkAuthorTarget(pubkey: $0) } },
            set: { deepLinkAuthor = $0?.pubkey }
        )) { target in
            AuthorProfileSheet(authorPubkey: target.pubkey, onClose: { deepLinkAuthor = nil })
                .environment(environment.identityStore)
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: Binding(
            get: { deepLinkInvoice != nil },
            set: { if !$0 { deepLinkInvoice = nil } }
        )) {
            if let deepLinkInvoice {
                LightningInvoiceSheet(invoiceUri: deepLinkInvoice)
                    .preferredColorScheme(.dark)
            }
        }
    }

    private struct DeepLinkAuthorTarget: Identifiable {
        let pubkey: String
        var id: String { pubkey }
    }

    private struct ZapPeerTarget: Identifiable {
        let pubkey: String
        var id: String { pubkey }
    }

    /** Unread badge for the Activity tab; "9+" cap keeps the bar tidy. */
    private var activityBadge: Text? {
        let count = environment.inboxStore.unreadCount
        guard count > 0 else { return nil }
        return Text(count > 9 ? "9+" : "\(count)")
    }

    /** APP-011: Chats badge = DM unread + pending requests (mock parity). */
    private var chatsBadge: Text? {
        let count = environment.dmStore.unreadCount + environment.dmStore.requestCount
        guard count > 0 else { return nil }
        return Text(count > 9 ? "9+" : "\(count)")
    }

    /**
     * APP-003 re-tap routing: re-selecting the active Home/Bitz tab bumps
     * the shared tick (scroll-to-top; at top → refresh). SwiftUI only
     * delivers the same-value set on newer iOS releases — where it does
     * not, the behavior is inert rather than wrong.
     */
    private var tabSelection: Binding<AppDestination> {
        Binding(
            get: { destination },
            set: { next in
                if next == destination, next == .home || next == .bitz {
                    feedRetapTick += 1
                }
                destination = next
            }
        )
    }
}
