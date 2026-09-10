import BusinessCore
import Foundation
import Observation

/// Local Discover scopes; Bitz remains restricted to standard media kinds.
enum SearchScope {
    /// Discover's general search: web `DISCOVER_CONTENT_KINDS` parity —
    /// text + all four media kinds.
    case general
    /// Bitz search: standard media kinds (bridge `searchRelayRequest`).
    case bitzMedia
}

/// Discover search over normally delivered, verified relay events. Each
/// debounced query broadcasts one multi-filter REQ (NIP-50 `search` + NIP-01
/// `#t` + bounded recent-sample fallback — web discover parity); results
/// still pass `matchesSearch` locally, so search stays verify-first.
@MainActor
@Observable
final class SearchStore {
    private(set) var query = ""
    private(set) var results: [FeedNote] = []
    private(set) var profiles: [String: ProfileMetadata] = [:]
    private(set) var resolvedNpub: String?
    private(set) var isSearching = false
    private(set) var hasSearched = false

    private let pool: RelayPool
    private let bridge: BusinessCoreBridge
    private var client: any BusinessCoreClient
    private var watchTask: Task<Void, Never>?
    private var searchTask: Task<Void, Never>?
    private var settleTask: Task<Void, Never>?
    private var window: (any FeedWindowing)?
    private var seen = Set<String>()
    private var activeQuery: String?
    private var activeScope: SearchScope = .general
    private var subscriptionCounter = 0

    init(pool: RelayPool, client: any BusinessCoreClient, bridge: BusinessCoreBridge = BusinessCoreBridge()) {
        self.pool = pool
        self.client = client
        self.bridge = bridge
        window = client.makeFeedWindow(maxItems: 100)

        Task {
            await pool.start()
            let stream = await pool.verifiedFrames(client: client)
            watchTask = FrameIngest.pump(
                gated: stream,
                isAlive: { [weak self] in self != nil }
            ) { [weak self] gated in
                await self?.absorb(gated)
            }
        }
    }

    func search(_ text: String, scope: SearchScope = .general) {
        query = text
        searchTask?.cancel()
        settleTask?.cancel()
        activeQuery = nil
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            results = []
            profiles = [:]
            resolvedNpub = nil
            isSearching = false
            hasSearched = false
            return
        }
        // Do not label prior-query cards as results for the newly typed text
        // while the 400 ms debounce is pending.
        seen.removeAll()
        window = client.makeFeedWindow(maxItems: 100)
        results = []
        profiles = [:]
        resolvedNpub = nil
        isSearching = false
        hasSearched = false
        searchTask = Task { [weak self, scope] in
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            await self?.performSearch(trimmed, scope: scope)
        }
    }

    private func performSearch(_ query: String, scope: SearchScope) async {
        seen.removeAll()
        results = []
        profiles = [:]
        resolvedNpub = nil
        isSearching = true
        hasSearched = true

        let npub = query.hasPrefix("npub1") ? (bridge.resolveNpub(query: query) as? String) : nil
        resolvedNpub = npub

        // Web discover parity: one multi-filter REQ — the NIP-50 `search`
        // filter for relays that support it, the NIP-01 `#t` filter for
        // indexed hashtag recall (the only filter for `#tag` queries, since
        // NIP-50 leaves `#` undefined in search strings), and a bounded
        // recent sample so relays without NIP-50 still deliver matchable
        // events. Matching stays local + verified via `matchesSearch`.
        subscriptionCounter += 1
        if let request = (bridge.searchRelayRequest(
            subscriptionId: "bitos-search-\(subscriptionCounter)",
            query: query,
            kinds: scope.kinds.map { KotlinInt(value: Int32($0)) }
        ) as String?) {
            Task { await pool.broadcast(request) }
        }

        activeQuery = query
        activeScope = scope

        settleTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            guard let self, self.isSearching, self.query.trimmingCharacters(in: .whitespaces) == query else { return }
            self.isSearching = false
        }
    }

    private func absorb(_ gated: GatedFrame) {
        guard case .event(let gatedEvent) = gated else { return }
        let event = gatedEvent.event
        if client.isProfileKind(event.kind) {
            guard activeQuery != nil else { return }
            if let metadata = client.profile(from: event) {
                profiles[metadata.pubkey] = metadata
            }
        } else if client.isFeedKind(event.kind) {
            guard let query = activeQuery, activeScope.kinds.contains(event.kind),
                  client.matchesSearch(event: event, query: query) else { return }
            guard !seen.contains(event.id) else { return }
            seen.insert(event.id)
            let note = client.feedNote(from: event)
            if window?.insert(note) == true {
                results = window?.snapshot() ?? results
            }
        }
    }

}

private extension SearchScope {
    /// The queried kind set (Discover general vs Bitz media-only).
    var kinds: [Int] {
        switch self {
        case .general: [1, 20, 21, 22, 34235, 34236]
        case .bitzMedia: [20, 21, 22, 34235, 34236]
        }
    }
}
