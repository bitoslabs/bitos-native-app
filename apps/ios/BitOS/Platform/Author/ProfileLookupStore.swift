import BusinessCore
import Foundation
import Observation

/**
 * One-shot multi-pubkey kind-0 lookup (legacy `SupportController` /
 * `ContributorsWidget` fetch parity): subscribes `kinds:[0] authors:[…]`
 * through the shared codec, keeps the NEWEST verified metadata per pubkey,
 * and settles after 8 s. Pure display projection; no cache, no persistence.
 */
@MainActor
@Observable
final class ProfileLookupStore {
    private(set) var profiles: [String: ProfileMetadata] = [:]
    private(set) var loading = false

    private let pool: RelayPool
    private let client: any BusinessCoreClient
    private var seenAt: [String: Int64] = [:]
    private var wanted: Set<String> = []
    private var settleTask: Task<Void, Never>?
    private var framesTask: Task<Void, Never>?

    init(pool: RelayPool, client: any BusinessCoreClient) {
        self.pool = pool
        self.client = client
    }

    func start() {
        guard framesTask == nil else { return }
        Task { [weak self, pool, client] in
            let stream = await pool.verifiedFrames(client: client)
            guard let self, !Task.isCancelled else { return }
            self.framesTask = FrameIngest.pump(
                gated: stream,
                isAlive: { [weak self] in self != nil }
            ) { [weak self] gated in
                await self?.absorb(gated)
            }
        }
    }

    private func absorb(_ gated: GatedFrame) {
        guard case .event(let gatedEvent) = gated,
              gatedEvent.event.kind == 0,
              wanted.contains(gatedEvent.event.pubkey) else { return }
        let decoded = gatedEvent.event
        let known = seenAt[decoded.pubkey] ?? Int64.min
        if decoded.createdAt < known { return }
        guard let metadata = client.profile(from: decoded) else { return }
        seenAt[decoded.pubkey] = decoded.createdAt
        profiles[decoded.pubkey] = metadata
    }

    /// Looks up one or more pubkeys; settles (loading=false) after 8 s.
    func lookup(pubkeys: [String]) {
        wanted = Set(pubkeys)
        seenAt = [:]
        profiles = [:]
        loading = true
        settleTask?.cancel()
        settleTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(8))
            self?.loading = false
        }
        let request = client.profileRequest(subscriptionId: "bitos-lookup", authors: pubkeys)
        guard !request.isEmpty else { return }
        Task { await pool.broadcast(request) }
    }

    func stop() {
        wanted = []
        loading = false
        settleTask?.cancel()
    }
}
