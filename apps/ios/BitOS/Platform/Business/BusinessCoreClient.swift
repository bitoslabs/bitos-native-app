import Foundation

protocol BusinessCoreClient: Sendable {
    func publishStatusLabel() async -> String
}

/// Temporary adapter used until the generated BusinessCore XCFramework is linked.
/// It keeps SwiftUI independent from Kotlin interop names and is replaced behind
/// this protocol, not inside feature views.
struct ScaffoldBusinessCoreClient: BusinessCoreClient {
    func publishStatusLabel() async -> String {
        "BusinessCore adapter ready"
    }
}
