import Foundation
import Observation

/// Verified by-id lookup for the Create-hub "Remix a Bitz" picker
/// (MEM-005a, plan docs/native/create-hub-sound-remix-plan.md; Android
/// `RemixSourceLookup` parity). One bounded REQ per attempt; only an
/// ID- and signature-verified frame whose event id MATCHES the requested
/// target resolves (the shared `RemixPreflight` mismatch rule). A named
/// timeout keeps the "Checking this Bitz on relays…" stage honest;
/// `cancel()` cancels the in-flight fetch — Back/Cancel strands nothing.
/// One resolved remix source: the verified event (raw tags for the
/// shared preflight seam) plus its Swift note projection.
struct RemixSourceHit: Sendable, Equatable {
    let event: VerifiedEvent
    let note: FeedNote
}

@MainActor
@Observable
final class RemixSourceLookupStore {
    enum State: Equatable {
        case idle
        case looking(eventId: String)
        case found
        case timedOut(eventId: String)
    }

    private(set) var state: State = .idle

    private let pool: RelayPool
    private let client: FrameworkBusinessCoreClient
    private var framesTask: Task<Void, Never>?
    private var timeoutTask: Task<Void, Never>?
    private var requestedId: String?
    private var subscriptionCounter = 0
    private var continuation: CheckedContinuation<RemixSourceHit?, Never>?

    /// Lookup timeout (named "Checking this Bitz on relays…" stage bound).
    static let timeoutMs: Int64 = 8_000

    init(pool: RelayPool, client: FrameworkBusinessCoreClient = FrameworkBusinessCoreClient()) {
        self.pool = pool
        self.client = client
    }

    private func start() {
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

    /// Parses `raw` through the shared MEM-003a seam and fetches the
    /// target. The returned note is verified AND id-matching; nil = the
    /// paste was rejected (read `rejectionMessage(raw:)` for the copy) or
    /// the fetch timed out / was cancelled (check [state]).
    func resolve(raw: String) async -> RemixSourceHit? {
        guard let (id, _) = Self.parseTarget(raw, client: client) else { return nil }
        cancel()
        requestedId = id
        state = .looking(eventId: id)
        subscriptionCounter += 1
        let subId = "bitos-remix-lookup-\(subscriptionCounter)"
        start()
        let request = client.remixSourceLookupRequest(subscriptionId: subId, eventId: id)
        if !request.isEmpty {
            Task { [pool] in
                await pool.start()
                await pool.broadcast(request)
            }
        }
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            timeoutTask = Task { [weak self, id] in
                try? await Task.sleep(nanoseconds: UInt64(Self.timeoutMs) * 1_000_000)
                guard !Task.isCancelled else { return }
                await self?.timeoutHit(eventId: id, subId: subId)
            }
        }
    }

    private func timeoutHit(eventId: String, subId: String) {
        guard requestedId == eventId, let continuation else { return }
        self.continuation = nil
        state = .timedOut(eventId: eventId)
        continuation.resume(returning: nil)
        Task { [pool] in await pool.broadcast("[\"CLOSE\",\"\(subId)\"]") }
    }

    private func absorb(_ gated: GatedFrame) {
        guard case .event(let gatedEvent) = gated,
              let requestedId,
              gatedEvent.event.id == requestedId else { return }
        let note = client.feedNote(from: gatedEvent.event)
        // The shared mismatch guard: intent must match the delivery.
        guard note.id == requestedId else { return }
        guard let continuation else { return }
        self.continuation = nil
        timeoutTask?.cancel()
        state = .found
        let subId = "bitos-remix-lookup-\(subscriptionCounter)"
        Task { [pool] in await pool.broadcast("[\"CLOSE\",\"\(subId)\"]") }
        continuation.resume(returning: RemixSourceHit(event: gatedEvent.event, note: note))
    }

    /// Cancels any in-flight fetch (Back/Cancel/close).
    func cancel() {
        timeoutTask?.cancel()
        if let continuation {
            self.continuation = nil
            continuation.resume(returning: nil)
        }
        requestedId = nil
        state = .idle
        let subId = "bitos-remix-lookup-\(subscriptionCounter)"
        Task { [pool] in await pool.broadcast("[\"CLOSE\",\"\(subId)\"]") }
    }

    /// Shared MEM-003a parse seam: (event id, relay hints) or nil.
    static func parseTarget(
        _ raw: String, client: FrameworkBusinessCoreClient
    ) -> (id: String, relays: [String])? {
        guard let data = client.remixSourceTargetParse(raw).data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let id = root["id"] as? String, id.count == 64
        else { return nil }
        let relays = (root["relays"] as? [String]) ?? []
        return (id, relays)
    }

    /// Shared rejection copy for the paste field (nil when parseable).
    static func rejectionMessage(
        _ raw: String, client: FrameworkBusinessCoreClient
    ) -> String? {
        guard let data = client.remixSourceTargetParse(raw).data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let message = root["message"] as? String
        else { return nil }
        return message
    }
}

/// "Your recent Bitz" (MEM-005a): a bounded, account-scoped, verified
/// list of the signed-in key's own media notes — recency order, no
/// server-side search, no invented ranking (Android `RecentBitzStore`
/// parity). The REQ settles on a deadline; closing the picker closes it.
@MainActor
@Observable
final class RecentBitzStore {
    struct State: Equatable {
        var rows: [RemixSourceHit] = []
        var isLoading = false
        var timedOut = false
    }

    private(set) var state = State()

    /// Account-scoped window bound (plan: "bounded… recent Bitz").
    static let maxRows = 20

    private let pool: RelayPool
    private let client: FrameworkBusinessCoreClient
    private var framesTask: Task<Void, Never>?
    private var settleTask: Task<Void, Never>?
    private var authorPubkey: String?
    private var subscriptionCounter = 0

    init(pool: RelayPool, client: FrameworkBusinessCoreClient = FrameworkBusinessCoreClient()) {
        self.pool = pool
        self.client = client
    }

    func open(pubkey: String) {
        if let authorPubkey, authorPubkey != pubkey {
            state = State()
        }
        authorPubkey = pubkey
        state.isLoading = true
        state.timedOut = false
        subscriptionCounter += 1
        let subId = "bitos-recent-bitz-\(subscriptionCounter)"
        settleTask?.cancel()
        start()
        Task { [pool] in
            await pool.start()
            await pool.broadcast(
                #"["REQ","\#(subId)",{"kinds":[20,21,22],"authors":["\#(pubkey)"],"limit":\#(Self.maxRows)}]"#
            )
        }
        settleTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(6))
            guard !Task.isCancelled else { return }
            await self?.settle()
        }
    }

    private func settle() {
        guard state.isLoading else { return }
        state.isLoading = false
        state.timedOut = state.rows.isEmpty
        let subId = "bitos-recent-bitz-\(subscriptionCounter)"
        Task { [pool] in await pool.broadcast("[\"CLOSE\",\"\(subId)\"]") }
    }

    private func start() {
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
              let authorPubkey,
              gatedEvent.event.pubkey == authorPubkey,
              [20, 21, 22].contains(gatedEvent.event.kind) else { return }
        let note = client.feedNote(from: gatedEvent.event)
        guard !state.rows.contains(where: { $0.note.id == note.id }) else { return }
        state.rows.append(RemixSourceHit(event: gatedEvent.event, note: note))
        state.rows.sort { $0.note.createdAt > $1.note.createdAt }
        state.rows = Array(state.rows.prefix(Self.maxRows))
        state.isLoading = false
        state.timedOut = false
    }

    func close() {
        settleTask?.cancel()
        let subId = "bitos-recent-bitz-\(subscriptionCounter)"
        Task { [pool] in await pool.broadcast("[\"CLOSE\",\"\(subId)\"]") }
    }
}
