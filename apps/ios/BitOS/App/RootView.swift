import SwiftUI

enum AppDestination: Hashable {
    case home
    case discover
    case create
    case inbox
    case profile
}

struct RootView: View {
    let environment: AppEnvironment
    @State private var destination: AppDestination = .home

    var body: some View {
        TabView(selection: $destination) {
            HomeView()
                .tag(AppDestination.home)
                .tabItem { Label("Home", systemImage: "play.rectangle.fill") }

            DiscoverView()
                .tag(AppDestination.discover)
                .tabItem { Label("Discover", systemImage: "magnifyingglass") }

            CreateView(businessCore: environment.businessCore)
                .tag(AppDestination.create)
                .tabItem { Label("Create", systemImage: "plus.square.fill") }

            InboxView()
                .tag(AppDestination.inbox)
                .tabItem { Label("Inbox", systemImage: "bell.fill") }

            ProfileView()
                .tag(AppDestination.profile)
                .tabItem { Label("Profile", systemImage: "person.crop.circle") }
        }
        .tint(BitOSTheme.accent)
        .preferredColorScheme(.dark)
    }
}
