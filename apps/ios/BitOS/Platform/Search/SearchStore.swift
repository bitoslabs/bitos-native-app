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
/// debounced query broadcasts a REQ pair — a base REQ (NIP-01 `#t` + bounded
/// recent-sample fallback) plus the NIP-50 `search` filter as its own
/// subscription — so relays that reject `search` still deliver matchable
/// events; results still pass `matchesSearch` locally, so search stays
/// verify-first.
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

        let npub = query.hasPrefix("npub1") ? bridge.resolveNpub(query: query) : nil
        resolvedNpub = npub

        // Web discover parity, split for relay tolerance: a base REQ (`#t` +
        // bounded recent sample) every relay answers, plus the NIP-50
        // `search` filter as its own subscription — several major relays
        // reject a whole REQ when any filter carries `search`, which would
        // silently kill hashtag and sample recall too. Matching stays local
        // + verified via `matchesSearch`.
        subscriptionCounter += 1
        if let requests = (bridge.searchRelayRequests(
            subscriptionId: "bitos-search-\(subscriptionCounter)",
            query: query,
            kinds: scope.kinds.map { KotlinInt(value: Int32($0)) }
        ) as SearchResults.RelaySearchRequests?) {
            let base = requests.baseRequest
            let search = requests.searchRequest
            Task {
                await pool.broadcast(base)
                if let search { await pool.broadcast(search) }
            }
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
