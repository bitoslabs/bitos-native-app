import BusinessCore
import Foundation
import Observation

/// Local Discover scopes; Bitz remains restricted to standard media kinds.
enum SearchScope {
    /// Discover's general search: text + native video kinds.
    case general
    /// Bitz search: standard media kinds (bridge `bitzSearchRequest`).
    case bitzMedia
}

/// Local Discover search over normally delivered, verified relay events.
/// This avoids relay-specific NIP-50 search subscriptions.
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
            guard let query = activeQuery, activeScope.includes(event.kind),
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
    func includes(_ kind: Int) -> Bool {
        switch self {
        case .general: [1, 21, 22].contains(kind)
        case .bitzMedia: [20, 21, 22, 34235, 34236].contains(kind)
        }
    }
}
