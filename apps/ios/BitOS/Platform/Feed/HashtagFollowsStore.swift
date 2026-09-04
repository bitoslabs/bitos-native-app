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
    private let bridge = BusinessCoreBridge()
    private var accountPubkey: String?
    private var headAt: Int64 = Int64.min
    private var framesTask: Task<Void, Never>?
    private var requested = false

    init(pool: RelayPool) {
        self.pool = pool
    }

    /// Sendable mirror of [accountPubkey]: the off-main ingest reads the
    /// live account without touching main-actor state (the bridge verifies
    /// signature, kind, d coordinate and author inside the extraction).
    private final class AccountBox: @unchecked Sendable {
        private let lock = NSLock()
        private var value: String?
        func set(_ pubkey: String?) { lock.lock(); value = pubkey; lock.unlock() }
        func get() -> String? { lock.lock(); defer { lock.unlock() }; return value }
    }

    private let accountBox = AccountBox()

    /// Newest-wins payload extracted off the main actor.
    private struct InterestHead: Sendable {
        let hashtags: [String]
        let createdAt: Int64
    }

    func start() {
        guard framesTask == nil else { return }
        let boxedBridge = StatelessBridge(bridge: bridge)
        Task { [weak self, pool] in
            let stream = await pool.verifiedFrames(client: FrameworkBusinessCoreClient())
            guard let self, !Task.isCancelled else { return }
            self.framesTask = FrameIngest.pump(
                gated: stream,
                isAlive: { [weak self] in self != nil },
                ingest: Self.headIngest(boxedBridge, accountBox: self.accountBox)
            ) { [weak self] head in
                await self?.absorbHead(head)
            }
        }
    }

    /// Account lifecycle: re-opens the interest-set head REQ.
    func setAccount(_ pubkey: String?) {
        accountPubkey = pubkey
        accountBox.set(pubkey)
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
            if let request = (bridge.interestSetRequest(
                subscriptionId: "bitos-interest",
                accountPubkey: accountPubkey ?? ""
            ) as String?) {
                await pool.broadcast(request)
            }
        }
    }

    /// Interest-set head projection from an ALREADY-VERIFIED event — runs
    /// OFF the main actor via the single event-based seam (Phase 2); the
    /// bridge checks kind and author inside the call.
    private nonisolated static func headIngest(
        _ boxedBridge: StatelessBridge,
        accountBox: AccountBox
    ) -> @Sendable (GatedFrame) -> InterestHead? {
        { gated in
            guard case .event(let gatedEvent) = gated,
                  let account = accountBox.get(),
                  let head = boxedBridge.bridge.interestSetFromEvent(
                      event: gatedEvent.event.bridgeEvent(bridge: boxedBridge.bridge),
                      accountPubkey: account
                  ) as? [String: Any],
                  let hashtags = head["hashtags"] as? [String],
                  let createdAt = (head["createdAt"] as? NSNumber)?.int64Value else { return nil }
            return InterestHead(hashtags: hashtags, createdAt: Int64(createdAt))
        }
    }

    /// Replaceable head: only accept heads newer than the current one.
    private func absorbHead(_ head: InterestHead) {
        guard head.createdAt > headAt else { return }
        headAt = head.createdAt
        hashtags = Set(head.hashtags)
    }
}
