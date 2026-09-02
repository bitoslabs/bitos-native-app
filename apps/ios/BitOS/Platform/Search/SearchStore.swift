import BusinessCore
import Foundation
import Observation

/// NIP-50 search scopes (Bitz discovery/query standard, web docs/SYSTEM.md):
/// Bitz searches the standard NIP-68/NIP-71 media kinds only — it never
/// discovers media by scanning kind-1 text notes. Discover keeps its
/// text+video kinds.
enum SearchScope {
    /// Discover's general search: text + native video kinds.
    case general
    /// Bitz search: standard media kinds (bridge `bitzSearchRequest`).
    case bitzMedia
}

/// NIP-50 search store (SOC-004): debounced queries, npub creator
/// resolution, verified results in a bounded window.
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
    private var window: (any FeedWindowing)?
    private var seen = Set<String>()
    private var subscriptionCounter = 0

    init(pool: RelayPool, client: any BusinessCoreClient, bridge: BusinessCoreBridge = BusinessCoreBridge()) {
        self.pool = pool
        self.client = client
        self.bridge = bridge
        window = client.makeFeedWindow(maxItems: 100)

        Task {
            await pool.start()
            let stream = await pool.frames()
            watchTask = Task { [weak self] in
                for await frame in stream {
                    guard let self, !Task.isCancelled else { return }
                    self.absorb(frame)
                }
            }
        }
    }

    func search(_ text: String, scope: SearchScope = .general) {
        query = text
        searchTask?.cancel()
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            results = []
            profiles = [:]
            resolvedNpub = nil
            hasSearched = false
            return
        }
        searchTask = Task { [weak self, scope] in
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            await self?.performSearch(trimmed, scope: scope)
        }
    }

    private func performSearch(_ query: String, scope: SearchScope) async {
        subscriptionCounter += 1
        seen.removeAll()
        results = []
        resolvedNpub = nil
        isSearching = true
        hasSearched = true

        let npub = query.hasPrefix("npub1") ? (bridge.resolveNpub(query: query) as? String) : nil
        resolvedNpub = npub

        // Bitz discovery/query standard: the Bitz scope queries the standard
        // NIP-68/NIP-71 media kinds only — never kind-1. Discover keeps its
        // general text+video set.
        let request: String? = {
            switch scope {
            case .bitzMedia:
                return bridge.bitzSearchRequest(
                    subscriptionId: "bitos-search-\(subscriptionCounter)",
                    query: query,
                    limit: 50
                ) as String?
            case .general:
                return bridge.searchRequest(
                    subscriptionId: "bitos-search-\(subscriptionCounter)",
                    query: query,
                    kinds: [1, 21, 22],
                    limit: 50
                ) as String?
            }
        }()
        if let request {
            Task { await pool.broadcast(request) }
        }
        if let npub {
            if let request = (bridge.profileRequest(
                subscriptionId: "bitos-search-profile-\(subscriptionCounter)",
                authors: [npub]
            ) as String?) {
                Task { await pool.broadcast(request) }
            }
        }

        Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            guard let self, self.isSearching, self.query.trimmingCharacters(in: .whitespaces) == query else { return }
            self.isSearching = false
        }
    }

    private func absorb(_ frame: RelayFrame) {
        guard let event = client.decodeVerifiedEvent(message: frame.message, relay: frame.relay.rawValue) else { return }
        if client.isProfileKind(event.kind) {
            if let metadata = client.profile(from: event) {
                profiles[metadata.pubkey] = metadata
            }
        } else if client.isFeedKind(event.kind) {
            guard !seen.contains(event.id) else { return }
            seen.insert(event.id)
            let note = client.feedNote(from: event)
            if window?.insert(note) == true {
                results = window?.snapshot() ?? results
            }
        }
        isSearching = false
    }
}
