import BusinessCore
import Foundation
import Observation

/// Author profile state: targeted profile + notes REQ for one pubkey.
/// Pages settle on ALL-RELAY EOSE (falling back to a 4 s deadline so a
/// dead relay cannot stall the page — web `loadReels` parity) and every
/// page's REQ is CLOSED on completion so overlapping subscriptions never
/// re-stream old windows.
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

    /// A page carrying this many NEW notes keeps `canLoadMore` true.
    static let freshPageTarget = 5
    /// Hard page deadline (web REELS_PAGE_MAX_WAIT_MS parity).
    static let pageMaxWait: Duration = .seconds(4)
    /// Web loadReels parity: deep media window, shallow text window
    /// (Nostr `limit` is per relay per filter).
    static let mediaPageLimit = 60
    static let textPageLimit = 150

    private let pool: RelayPool
    private let bridge: BusinessCoreBridge
    private let client: any BusinessCoreClient
    private var seen = Set<String>()
    private var page = 0
    private var framesTask: Task<Void, Never>?

    /// One page batch: settles when every expected relay sent EOSE.
    private struct PageBatch {
        let subId: String
        let expectedRelays: Set<RelayURL>
        let startedAtCount: Int
        var eoseRelays: Set<RelayURL> = []
        var timeoutTask: Task<Void, Never>?
    }

    private var activePage: PageBatch?

    init(pool: RelayPool, client: any BusinessCoreClient, bridge: BusinessCoreBridge = BusinessCoreBridge()) {
        self.pool = pool
        self.client = client
        self.bridge = bridge
    }

    /// Subscribes this store to the relay fan-out. Must run once before any
    /// `open`; without it the REQ leaves but verified frames never arrive.
    /// Frames pass the protocol gate OFF the main actor (shared ingest pump);
    /// only verified events and EOSE ids hop to absorption.
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
        closeActivePage()

        Task {
            await pool.start()
            start()
            await requestPage(untilSeconds: nil)
        }
    }

    /// Next older page (`until` = oldest loaded note) on demand.
    func loadMoreNotes() {
        guard pubkey != nil, canLoadMore, !isLoadingMore, !notes.isEmpty else { return }
        isLoadingMore = true
        page += 1
        let until = notes.map(\.createdAt).min() ?? Int64(Date.now.timeIntervalSince1970)
        Task { await requestPage(untilSeconds: until) }
    }

    private func requestPage(untilSeconds: Int64?) async {
        guard let target = pubkey else { return }
        let subId = untilSeconds == nil ? "bitos-author" : "bitos-author-\(page)"
        closeActivePage()
        var batch = PageBatch(
            subId: subId,
            expectedRelays: await pool.connectedRelays(),
            startedAtCount: notes.count
        )
        batch.timeoutTask = Task { [weak self] in
            try? await Task.sleep(for: Self.pageMaxWait)
            guard !Task.isCancelled else { return }
            self?.completePage(subId: subId, timedOut: true)
        }
        activePage = batch
        if let request = (bridge.authorRequest(
            subscriptionId: subId,
            authorPubkey: target,
            mediaLimit: Int32(Self.mediaPageLimit),
            textLimit: Int32(Self.textPageLimit),
            untilSeconds: untilSeconds.map { KotlinLong(value: $0) }
        ) as String?) {
            await pool.broadcast(request)
        }
    }

    /// Head EOSE → connected; page EOSE → settle when all relays answered.
    private func absorbEose(subscriptionId subId: String, relay: RelayURL) {
        guard subId == activePage?.subId else { return }
        activePage?.eoseRelays.insert(relay)
        guard let batch = activePage,
              !batch.expectedRelays.isEmpty,
              batch.expectedRelays.isSubset(of: batch.eoseRelays) else { return }
        completePage(subId: subId, timedOut: false)
    }

    /// Settles one page: CLOSE the REQ, then judge the short-page rule.
    private func completePage(subId: String, timedOut: Bool) {
        guard let batch = activePage, batch.subId == subId else { return }
        batch.timeoutTask?.cancel()
        activePage = nil
        Task { [pool, client] in await pool.broadcast(client.close(subscriptionId: subId)) }
        let fresh = notes.count - batch.startedAtCount
        isLoading = false
        isLoadingMore = false
        // A full EOSE short page means the author's history ended; a
        // deadline page stays retryable (a slow relay is not proof of
        // exhaustion — same rule as the feed's older-walk).
        if !timedOut, fresh < Self.freshPageTarget {
            canLoadMore = false
        }
    }

    private func closeActivePage() {
        guard let batch = activePage else { return }
        batch.timeoutTask?.cancel()
        activePage = nil
        let subId = batch.subId
        Task { [pool, client] in await pool.broadcast(client.close(subscriptionId: subId)) }
    }

    func absorb(_ gated: GatedFrame) {
        switch gated {
        case .eose(let subId, let relay):
            absorbEose(subscriptionId: subId, relay: relay)
        case .event(let gatedEvent):
            guard let target = pubkey,
                  gatedEvent.event.pubkey == target else { return }
            let event = gatedEvent.event
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
    }

    func close() {
        pubkey = nil
        profile = nil
        notes = []
        isLoading = true
        canLoadMore = true
        isLoadingMore = false
        closeActivePage()
    }
}
