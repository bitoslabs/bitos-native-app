import BusinessCore
import OSLog
import SwiftUI

@main
struct BitOSApp: App {
    @Environment(\.scenePhase) private var scenePhase
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
                .onChange(of: scenePhase) { _, phase in
                    DebugActivityLog.scenePhaseChanged(to: phase)
                }
                .onOpenURL { url in
                    // Only classify: the shared rule runs in RootView so
                    // routing stays with the navigation owner.
                    let uri = url.absoluteString
                    if (deepLinkJson(uri) as String?) != nil {
                        deepLinkUri = uri
                        DebugActivityLog.event("accepted a supported deep link")
                    } else {
                        DebugActivityLog.event("ignored an unsupported deep link")
                    }
                }
        }
    }

    private func deepLinkJson(_ uri: String) -> String? {
        BusinessCoreBridge().deepLinkJson(uri: uri)
    }
}

private enum DebugActivityLog {
    #if DEBUG
    private static let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier ?? "space.bitos.app",
        category: "activity"
    )
    #endif

    static func scenePhaseChanged(to phase: ScenePhase) {
        switch phase {
        case .active:
            event("became active")
        case .inactive:
            event("became inactive")
        case .background:
            event("entered background")
        @unknown default:
            event("entered an unknown scene phase")
        }
    }

    static func event(_ message: String) {
        #if DEBUG
        logger.debug("\(message, privacy: .public)")
        #endif
    }
}
