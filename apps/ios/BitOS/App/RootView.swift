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
    @State private var destination: AppDestination = .home
    @State private var showDiscover = false
    @State private var showMore = false
    @Environment(AppEnvironment.self) private var environment
    @Environment(SettingsStore.self) private var settings

    var body: some View {
        TabView(selection: $destination) {
            HomeView(store: environment.feedStore,
                     onOpenDiscover: { showDiscover = true },
                     onOpenProfile: { destination = .you },
                     onOpenHub: { showMore = true })
                .tag(AppDestination.home)
                .tabItem { Label { Text("Home") } icon: { AppIcons.image(for: AppIcons.home) } }

            HomeView(store: environment.feedStore, videoOnly: true,
                     onOpenDiscover: { showDiscover = true },
                     onOpenProfile: { destination = .you },
                     onOpenHub: { showMore = true })
                .tag(AppDestination.bitz)
                .tabItem { Label { Text("Bitz") } icon: { AppIcons.image(for: AppIcons.bitz) } }

            ChatsView()
                .tag(AppDestination.chats)
                .tabItem { Label { Text("Chats") } icon: { AppIcons.image(for: AppIcons.chat) } }

            InboxView(store: environment.inboxStore)
                .tag(AppDestination.activity)
                .tabItem { Label { Text("Activity") } icon: { AppIcons.image(for: AppIcons.inbox) } }
                .badge(activityBadge)

            ProfileView(store: environment.identityStore)
                .tag(AppDestination.you)
                .tabItem { Label { Text("You") } icon: { AppIcons.image(for: AppIcons.userProfile) } }
        }
        .tint(BitOSTheme.accent)
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
        // subscription alive from app start, not only while the tab is open.
        .task(id: environment.identityStore.account?.pubkeyHex) {
            environment.feedStore.setAccount(environment.identityStore.account?.pubkeyHex)
            environment.inboxStore.setAccount(environment.identityStore.account?.pubkeyHex)
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
    }

    /** Unread badge for the Activity tab; "9+" cap keeps the bar tidy. */
    private var activityBadge: Text? {
        let count = environment.inboxStore.unreadCount
        guard count > 0 else { return nil }
        return Text(count > 9 ? "9+" : "\(count)")
    }
}
