import BusinessCore
import SwiftUI

enum AppDestination: Hashable {
    case home
    case bitz
    /// Prototype tabdock center slot: never selects — the selection binding
    /// intercepts it and opens the Create sheet instead.
    case createSlot
    case activity
    case you
}

/// Five-slot product shell (prototype parity): Home · Bitz · ＋ · Activity ·
/// You. The center ＋ opens the Create sheet (New note / New Bitz / New story)
/// and replaces the removed Home composer FAB, which conflicted with the tab
/// bar. Chats lives INSIDE Activity as a chip. Discover stays on the Home
/// header search and the More hub (APP-017); Settings pushes from You.
struct RootView: View {
    /// T16: pending inbound deep link (consumed once by the router below).
    var deepLinkUri: String? = nil
    var onDeepLinkConsumed: () -> Void = {}

    @State private var destination: AppDestination = .home
    // R10 (performance-audit): tabs compose on FIRST selection, then stay
    // alive. Cold launch pays only the initial tab's body cost — the other
    // four heavy surfaces (Bitz pager, chats, activity, you) render
    // `Color.clear` until visited. State preservation is exact: a visited
    // tab is never disposed, and expensive hidden resources are already
    // released by each surface's disappear handlers (players, monitors).
    @State private var visitedDestinations: Set<AppDestination> = [.home]
    // APP-002: first launch gates on the onboarding carousel.
    @State private var showOnboarding = !OnboardingPrefs.hasOnboarded
    @State private var showDiscover = false
    @State private var showMore = false
    /** Prototype `openCreateSheet`: the center ＋ picker. */
    @State private var showCreateSheet = false
    /** The Create sheet's New note row opens the full-page composer. */
    @State private var showCreateNote = false
    /** The Create sheet's New Bitz row opens the capture hub. */
    @State private var showCreateBitz = false
    /** The Create sheet's New story row (prototype `story-compose`). */
    @State private var showStoryComposer = false
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

    /// Prototype tabdock center ＋ — an action slot, not a destination.
    /// Center action slot: invisible (width-keeping); the visible button
    /// is the ring-hex overlay. tabSelection still intercepts taps on the
    /// slot itself and opens the Create sheet — it never selects.
    private var createSlotItem: some View {
        Color.clear
            .tag(AppDestination.createSlot)
            .tabItem { Label { Text("") } icon: { EmptyView() } }
            .accessibilityLabel("Create")
    }

    /// Center ＋ in the AVATAR hex idiom (RingHexAvatarView parity): an
    /// accent hex ring around a surface inner hex, ＋ glyph in accent.
    private var hexCreateButton: some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            showCreateSheet = true
        } label: {
            ZStack {
                HexShape().fill(BitOSTheme.accent)
                HexShape()
                    .fill(BitOSTheme.surface)
                    .padding(2)
                Image(systemName: "plus")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(BitOSTheme.accent)
            }
            .frame(width: 46, height: 46)
            .shadow(color: BitOSTheme.accent.opacity(0.25), radius: 8, y: 2)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Create")
    }

    /// Activity hosts BOTH inbox chips (prototype parity): notifications
    /// and — since the tab merge — Chats (NIP-17 DMs).
    private var activityItem: some View {
        deferred(.activity) {
            InboxView(
                store: environment.inboxStore,
                dmUnreadCount: dmUnreadCount,
                chats: AnyView(DmScreen(
                    onZapPeer: { chatZapPeer = $0 },
                    onOpenProfile: { chatProfilePeer = $0 }
                ))
            )
        }
        .tag(AppDestination.activity)
        .tabItem { Label { Text("Activity") } icon: { AppIcons.image(for: AppIcons.inbox) } }
    }

    private var appTabs: some View {
        TabView(selection: tabSelection) {
            deferred(.home) {
                HomeView(store: environment.feedStore,
                         onOpenDiscover: { showDiscover = true },
                         onOpenProfile: { destination = .you },
                         onOpenHub: { showMore = true },
                         retapTick: feedRetapTick)
            }
            .tag(AppDestination.home)
            .tabItem { Label { Text("Home") } icon: { AppIcons.image(for: AppIcons.home) } }

            deferred(.bitz) {
                BitzView(retapTick: feedRetapTick)
            }
            .tag(AppDestination.bitz)
            .tabItem { Label { Text("Bitz") } icon: { AppIcons.image(for: AppIcons.bitz) } }

            // Prototype tabdock center: the ＋ is intercepted by
            // tabSelection (opens the Create sheet); the slot composes
            // nothing and never selects.
            createSlotItem

            activityItem
                .badge(activityBadge)

            deferred(.you) {
                ProfileView(store: environment.identityStore)
            }
            .tag(AppDestination.you)
            .tabItem { Label { Text("You") } icon: { AppIcons.image(for: AppIcons.userProfile) } }
        }
        .overlay(alignment: .bottom) {
            // The visible center ＋ rides over the invisible slot, centered
            // on the ~49pt bar (floats half above its top edge).
            hexCreateButton
                .padding(.bottom, 26)
        }
        .tint(BitOSTheme.accent)
        // R10: mark the selected destination as visited (first composition).
        .onChange(of: destination, initial: true) { _, selected in
            visitedDestinations.insert(selected)
        }
        // APP-018 functional setting: font size applies app-wide.
        .environment(\.dynamicTypeSize, fontTypeSize)
        // Web feedPreferences parity: the persisted protocol-notes opt-in
        // drives the feed gate live (Settings → Feed → Protocol notes).
        .onChange(of: settings.state.showProtocolNotes, initial: true) { _, enabled in
            environment.feedStore.setShowProtocolNotes(enabled)
        }
        // APP-018/APP-023 functional setting: the persisted theme applies
        // app-wide (SettingsStore also syncs BitOSTheme.modeOverride so the
        // dynamic palette tokens follow; system = follow the device).
        .preferredColorScheme(settings.state.themeMode.colorScheme)
        .environment(environment.identityStore)
        // One shared feed store backs Home and Bitz. Own its relay lifecycle
        // at the shell so switching tabs never closes and reopens the same
        // subscription.
        .task {
            await environment.feedStore.start()
        }
        // Audit R10: the signed-in shell renders immediately from the public
        // account registry; this task re-verifies the active entry against
        // its sealed slot off-main and replaces or clears the provisional
        // account (see `IdentityStore.restoreActiveSession`).
        .task {
            await environment.identityStore.restoreActiveSession()
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
            .preferredColorScheme(BitOSTheme.preferredScheme)
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
        // Prototype `openCreateSheet`: the center ＋ picker — the three
        // native creation entries (quick MEM arrives with the Bitz hub;
        // the sheet grows then).
        .sheet(isPresented: $showCreateSheet) {
            VStack(alignment: .leading, spacing: BitOSTheme.Spacing.base) {
                Text("Create")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(BitOSTheme.textTertiary)
                createRow(icon: "square.and.pencil", title: "New note",
                          subtitle: "text · poll · GIF · PoW") {
                    showCreateSheet = false
                    showCreateNote = true
                }
                createRow(icon: "camera.fill", title: "New Bitz",
                          subtitle: "camera → editor → publish") {
                    showCreateSheet = false
                    showCreateBitz = true
                }
                createRow(icon: "film.fill", title: "New story",
                          subtitle: "24 h · kind-30315 set") {
                    showCreateSheet = false
                    showStoryComposer = true
                }
                Spacer(minLength: 8)
            }
            .padding(.horizontal, BitOSTheme.Spacing.base)
            .padding(.top, 12)
            .presentationDetents([.height(300), .large])
            .preferredColorScheme(BitOSTheme.preferredScheme)
        }
        // Create sheet → New story: the APP-006 story composer (same
        // surface the stories rail's Create story card opens, HomeView
        // parity). A sheet keeps swipe-to-dismiss over the keyboard.
        .sheet(isPresented: $showStoryComposer) {
            StoryComposerSheet(
                onPublish: { text, imageUrls, background, altText, sensitive in
                    Task { await environment.notePublisher.publishStory(
                        text: text,
                        imageUrls: imageUrls,
                        background: background,
                        altText: altText,
                        sensitive: sensitive
                    ) }
                },
                onClose: { showStoryComposer = false }
            )
            .environment(environment.identityStore)
            .environment(environment)
            .presentationDetents([.large])
            .preferredColorScheme(BitOSTheme.preferredScheme)
        }
        // Create sheet → New note: the full-page composer (legacy parity).
        .fullScreenCover(isPresented: $showCreateNote) {
            ComposerScreen {
                environment.notePublisher.dismiss()
                showCreateNote = false
            }
        }
        // Create sheet → New Bitz: the capture hub (record / import).
        .fullScreenCover(isPresented: $showCreateBitz) {
            CreateView()
                .preferredColorScheme(BitOSTheme.preferredScheme)
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
                    .preferredColorScheme(BitOSTheme.preferredScheme)
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
        // Prototype tabdock: the combined badge covers BOTH inbox surfaces
        // — notifications + chats now that Chats lives inside Activity.
        let count = environment.inboxStore.unreadCount
            + environment.dmStore.unreadCount + environment.dmStore.requestCount
        guard count > 0 else { return nil }
        return Text(count > 9 ? "9+" : "\(count)")
    }

    /** Chats unread for the in-Activity chip (DM unread + pending requests). */
    private var dmUnreadCount: Int {
        environment.dmStore.unreadCount + environment.dmStore.requestCount
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
                // Prototype tabdock: the center ＋ is an action, not a
                // destination — open the Create sheet and keep the current
                // tab selected.
                if next == .createSlot {
                    showCreateSheet = true
                    return
                }
                if next == destination, next == .home || next == .bitz {
                    feedRetapTick += 1
                }
                destination = next
            }
        )
    }

    /// R10 deferred composition: unvisited tabs render nothing until first
    /// selected; visited tabs stay composed (state preservation).
    @ViewBuilder
    private func deferred(_ dest: AppDestination, @ViewBuilder content: () -> some View) -> some View {
        if dest == destination || visitedDestinations.contains(dest) {
            content()
        } else {
            Color.clear
        }
    }

    /// One Create-sheet row (prototype list-row parity).
    private func createRow(icon: String, title: String, subtitle: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12)
                        .fill(BitOSTheme.accent.opacity(0.14))
                        .frame(width: 40, height: 40)
                    Image(systemName: icon)
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(BitOSTheme.accent)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(BitOSTheme.textPrimary)
                    Text(subtitle)
                        .font(.caption2)
                        .foregroundStyle(BitOSTheme.textTertiary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(BitOSTheme.textTertiary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(BitOSTheme.surface, in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
    }
}
