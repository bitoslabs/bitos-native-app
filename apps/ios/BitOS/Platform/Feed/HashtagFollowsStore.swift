import BusinessCore
import Foundation
import Observation

/**
 * NIP-51 followed hashtags (kind 30015 d=interest) — web `hashtag-follows`
 * parity: the account's interest-set head syncs from relays (newest
 * verified list wins); toggles flip optimistically and the caller publishes
 * the resulting set through the note publisher.
 */
@MainActor
@Observable
final class HashtagFollowsStore {
    private(set) var hashtags: Set<String> = []

    private let pool: RelayPool
    private var accountPubkey: String?
    private var headAt: Int64 = Int64.min
    private var framesTask: Task<Void, Never>?
    private var requested = false

    init(pool: RelayPool) {
        self.pool = pool
    }

    func start() {
        guard framesTask == nil else { return }
        framesTask = Task { [weak self] in
            let stream = await self?.pool.frames() ?? AsyncStream { $0.finish() }
            for await frame in stream {
                guard let self else { return }
                self.absorb(frame)
            }
        }
    }

    /// Account lifecycle: re-opens the interest-set head REQ.
    func setAccount(_ pubkey: String?) {
        accountPubkey = pubkey
        headAt = Int64.min
        requested = false
        hashtags = []
        subscribe()
    }

    /// Optimistic local flip; the caller publishes the returned set.
    func toggle(_ tag: String) -> [String] {
        let normalized = tag.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "#", with: "")
            .lowercased()
        guard normalized.count >= 2, normalized.count <= 60 else { return Array(hashtags) }
        if hashtags.contains(normalized) {
            hashtags.remove(normalized)
        } else {
            hashtags.insert(normalized)
        }
        return Array(hashtags)
    }

    func isFollowed(_ tag: String) -> Bool {
        hashtags.contains(tag.replacingOccurrences(of: "#", with: "").lowercased())
    }

    private func subscribe() {
        guard let accountPubkey, !requested else { return }
        requested = true
        Task {
            await pool.start()
            start()
            if let request = (BusinessCoreBridge().interestSetRequest(
                subscriptionId: "bitos-interest",
                accountPubkey: accountPubkey
            ) as String?) {
                await pool.broadcast(request)
            }
        }
    }

    private let bridge = BusinessCoreBridge()

    private func absorb(_ frame: RelayFrame) {
        guard let accountPubkey else { return }
        // The bridge verifies signature, kind, d coordinate and author.
        guard let hashtags = bridge.interestSetHashtags(
            message: frame.message, relayUrl: frame.relay.rawValue, accountPubkey: accountPubkey
        ) as? [String] else { return }
        // Replaceable head: only accept frames newer than the current head.
        // Kotlin Long? crosses the bridge as KotlinLong? — unwrap via intValue.
        guard let eventAt = (bridge.interestSetCreatedAt(
            message: frame.message, relayUrl: frame.relay.rawValue, accountPubkey: accountPubkey
        ) as KotlinLong?)?.int64Value, eventAt > headAt else { return }
        headAt = Int64(eventAt)
        self.hashtags = Set(hashtags)
    }
}
