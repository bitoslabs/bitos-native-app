import BusinessCore
import Foundation
import Observation

/// Author profile state: targeted profile + notes REQ for one pubkey.
@MainActor
@Observable
final class AuthorStore {
    private(set) var pubkey: String?
    private(set) var profile: ProfileMetadata?
    private(set) var notes: [FeedNote] = []
    private(set) var isLoading = true

    private let pool: RelayPool
    private let bridge: BusinessCoreBridge
    private let client: any BusinessCoreClient
    private var seen = Set<String>()

    init(pool: RelayPool, client: any BusinessCoreClient, bridge: BusinessCoreBridge = BusinessCoreBridge()) {
        self.pool = pool
        self.client = client
        self.bridge = bridge
    }

    func open(authorPubkey: String) {
        guard pubkey != authorPubkey else { return }
        pubkey = authorPubkey
        profile = nil
        notes = []
        seen = []
        isLoading = true

        Task {
            await pool.start()
            if let request = (bridge.authorRequest(subscriptionId: "bitos-author", authorPubkey: authorPubkey) as String?) {
                await pool.broadcast(request)
            }
            // Settle: not-loading after a window even without results.
            try? await Task.sleep(for: .seconds(3))
            if isLoading, pubkey == authorPubkey {
                isLoading = false
            }
        }
    }

    func absorb(_ frame: RelayFrame) {
        guard let target = pubkey,
              let event = client.decodeVerifiedEvent(message: frame.message, relay: frame.relay.rawValue),
              event.pubkey == target else { return }
        if client.isProfileKind(event.kind) {
            if let metadata = client.profile(from: event) {
                profile = metadata
                isLoading = false
            }
        } else if client.isFeedKind(event.kind) {
            guard !seen.contains(event.id) else { return }
            seen.insert(event.id)
            notes.append(client.feedNote(from: event))
            notes.sort { $0.createdAt > $1.createdAt }
            isLoading = false
        }
    }

    func close() {
        pubkey = nil
        profile = nil
        notes = []
        isLoading = true
    }
}
