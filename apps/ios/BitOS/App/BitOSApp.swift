import BusinessCore
import SwiftUI

@main
struct BitOSApp: App {
    @State private var environment = AppEnvironment.live()
    @State private var settings = SettingsStore()
    /** T16: the pending inbound nostr:/lightning: deep link. */
    @State private var deepLinkUri: String?

    var body: some Scene {
        WindowGroup {
            // Fast access (user decision 2026-08-28): the native launch
            // screen hands off straight into the product shell — the branded
            // BootSplashScreen is disabled at app entry (component retained,
            // APP-022).
            RootView(deepLinkUri: deepLinkUri, onDeepLinkConsumed: { deepLinkUri = nil })
                .environment(environment)
                .environment(settings)
                .environment(environment.relayManager)
                .environment(environment.algorithmStore)
                .environment(environment.privacyPrefs)
                .onOpenURL { url in
                    // Only classify: the shared rule runs in RootView so
                    // routing stays with the navigation owner.
                    let uri = url.absoluteString
                    if (deepLinkJson(uri) as String?) != nil {
                        deepLinkUri = uri
                    }
                }
        }
    }

    private func deepLinkJson(_ uri: String) -> String? {
        BusinessCoreBridge().deepLinkJson(uri: uri)
    }
}
