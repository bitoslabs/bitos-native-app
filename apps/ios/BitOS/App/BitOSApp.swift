import SwiftUI

@main
struct BitOSApp: App {
    @State private var environment = AppEnvironment.live()
    @State private var settings = SettingsStore()
    /// Branded boot splash (legacy Flutter main.dart parity): holds while the
    /// feed/environment hydrate under it, then fades out to the product shell.
    @State private var bootDone = false

    var body: some Scene {
        WindowGroup {
            ZStack {
                RootView()
                    .environment(environment)
                    .environment(settings)
                if !bootDone {
                    BootSplashScreen { bootDone = true }
                        .zIndex(1)
                }
            }
        }
    }
}
