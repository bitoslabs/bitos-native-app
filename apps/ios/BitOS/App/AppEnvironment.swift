import Foundation

struct AppEnvironment: Sendable {
    let businessCore: any BusinessCoreClient

    static func live() -> AppEnvironment {
        AppEnvironment(businessCore: ScaffoldBusinessCoreClient())
    }
}
