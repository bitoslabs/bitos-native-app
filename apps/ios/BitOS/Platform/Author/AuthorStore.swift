import BusinessCore
import Foundation
import Observation

/// Author profile state: targeted profile + notes REQ for one pubkey.
/// Notes load five at a time — the first page arrives with the profile,
/// older pages page backward (`until` = oldest loaded note) on demand.
@MainActor
@Observable
final class AuthorStore {
    private(set) var pubkey: String?
    private(set) var profile: ProfileMetadata?
    private(set) var notes: [FeedNote] = []
    private(set) var isLoading = true
    /** False once a page returned fewer than a full page of new notes. */
    private(set) var canLoadMore = true
    private(set) var isLoadingMore = false

    static let pageSize = 5

    private let pool: RelayPool
    private let bridge: BusinessCoreBridge
    private let client: any BusinessCoreClient
    private var seen = Set<String>()
    private var page = 0
    private var framesTask: Task<Void, Never>?

    init(pool: RelayPool, client: any BusinessCoreClient, bridge: BusinessCoreBridge = BusinessCoreBridge()) {
        self.pool = pool
        self.client = client
        self.bridge = bridge
    }

    /// Subscribes this store to the relay fan-out. Must run once before any
    /// `open`; without it the REQ leaves but verified frames never arrive.
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

    func open(authorPubkey: String) {
        guard pubkey != authorPubkey else { return }
        pubkey = authorPubkey
        profile = nil
        notes = []
        seen = []
        isLoading = true
        canLoadMore = true
        isLoadingMore = false
        page = 0

        Task {
            await pool.start()
            start()
            if let request = (bridge.authorRequest(
                subscriptionId: "bitos-author",
                authorPubkey: authorPubkey,
                limit: Int32(Self.pageSize),
                untilSeconds: nil
            ) as String?) {
                await pool.broadcast(request)
            }
            // Settle: not-loading after a window even without results.
            try? await Task.sleep(for: .seconds(3))
            if isLoading, pubkey == authorPubkey {
                isLoading = false
            }
        }
    }

    /// Next older page (first 5 load with the profile; the rest on demand).
    func loadMoreNotes() {
        guard let target = pubkey, canLoadMore, !isLoadingMore, !notes.isEmpty else { return }
        isLoadingMore = true
        page += 1
        let until = notes.map(\.createdAt).min() ?? Int64(Date.now.timeIntervalSince1970)
        let before = notes.count
        Task {
            await pool.start()
            start()
            if let request = (bridge.authorRequest(
                subscriptionId: "bitos-author-\(page)",
                authorPubkey: target,
                limit: Int32(Self.pageSize),
                // Kotlin Long? boxes as KotlinLong across the bridge.
                untilSeconds: KotlinLong(value: until)
            ) as String?) {
                await pool.broadcast(request)
            }
            // Settle: a short page means the author's history ended.
            try? await Task.sleep(for: .seconds(3))
            guard pubkey == target else { return }
            isLoadingMore = false
            if notes.count - before < Self.pageSize {
                canLoadMore = false
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
        canLoadMore = true
        isLoadingMore = false
    }
}
