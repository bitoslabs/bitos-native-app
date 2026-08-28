import BusinessCore
import Foundation
import Observation

/**
 * APP-018a row 2 adapter (iOS): UserDefaults-backed interaction-gate store
 * executing the shared `PrivacyPrefsContract` through the bridge. Rules
 * live in business-core; this adapter persists and publishes.
 */
@MainActor
@Observable
final class PrivacyPrefsStore {
    static let storageKey = "privacy_prefs_v1"

    struct State: Equatable {
        var privateAccount = false
        var includeClientTag = true
        var activityVisible = true
        var readReceipts = true
        var sensitiveReason = true
        var storyShare = true
        var messagePermission = "everyone"   // followers | everyone | none
        var commentPermission = "everyone"   // everyone | followers | friends
    }

    private(set) var state = State()

    private let defaults: UserDefaults
    private let bridge = BusinessCoreBridge()

    init(defaults: UserDefaults? = UserDefaults(suiteName: "bitos.privacy")) {
        self.defaults = defaults ?? .standard
        reload()
    }

    func reload() {
        let wire = bridge.privacyPrefsDecode(json: defaults.string(forKey: Self.storageKey) ?? "")
        state = State(
            privateAccount: wire.privateAccount,
            includeClientTag: wire.includeClientTag,
            activityVisible: wire.activityVisible,
            readReceipts: wire.readReceipts,
            sensitiveReason: wire.sensitiveReason,
            storyShare: wire.storyShare,
            messagePermission: wire.messagePermission,
            commentPermission: wire.commentPermission
        )
    }

    func update(_ transform: (inout State) -> Void) {
        var next = state
        transform(&next)
        state = next
        persist()
    }

    private func persist() {
        let wire = BusinessCoreBridge.PrivacyPrefsWire(
            privateAccount: state.privateAccount,
            includeClientTag: state.includeClientTag,
            activityVisible: state.activityVisible,
            readReceipts: state.readReceipts,
            sensitiveReason: state.sensitiveReason,
            storyShare: state.storyShare,
            messagePermission: state.messagePermission,
            commentPermission: state.commentPermission
        )
        defaults.set(bridge.privacyPrefsEncode(wire: wire), forKey: Self.storageKey)
    }
}
