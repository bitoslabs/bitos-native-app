import SwiftUI

@main
struct BitOSApp: App {
    @State private var environment = AppEnvironment.live()
    @State private var settings = SettingsStore()

    var body: some Scene {
        WindowGroup {
            // Fast access (user decision 2026-08-28): the native launch
            // screen hands off straight into the product shell — the branded
            // BootSplashScreen is disabled at app entry (component retained,
            // APP-022).
            RootView()
                .environment(environment)
                .environment(settings)
                .environment(environment.relayManager)
                .environment(environment.algorithmStore)
                .environment(environment.privacyPrefs)
        }
    }
}
