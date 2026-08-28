import BusinessCore
import Foundation
import Observation

/// Which Home timeline the user is viewing.
enum FeedTimeline: Hashable, Sendable {
    case forYou
    case following
}

/// Optimistic local interaction state, keyed by verified event id.
struct LocalActions: Sendable, Equatable {
    var liked: Set<String> = []
    var bookmarked: Set<String> = []

    mutating func toggleLike(_ id: String) { toggle(\.liked, id) }
    mutating func toggleBookmark(_ id: String) { toggle(\.bookmarked, id) }

    private mutating func toggle(_ keyPath: WritableKeyPath<LocalActions, Set<String>>, _ id: String) {
        var set = self[keyPath: keyPath]
        if !set.insert(id).inserted { set.remove(id) }
        self[keyPath: keyPath] = set
    }
}

/**
 * Native feature store for the Home surface. Owns UI state and platform
 * lifetime; every product rule (decoding, ID verification, normalization,
 * bounded aggregation, subscription encoding) executes once in the shared
 * BusinessCore through the client seam.
 */
@MainActor
@Observable
final class FeedStore {
    private(set) var timeline: FeedTimeline = .forYou
    private(set) var filterOrdinal: Int = 0
    private(set) var notes: [FeedNote] = []
    private(set) var pendingNotes: [FeedNote] = []
    /// APP-004 live tab counts: ALL-window size per timeline (mutes +
    /// protocol payload hidden) — what each tab shows under the All filter.
    private(set) var forYouCount = 0
    private(set) var followingCount = 0
    private(set) var profiles: [String: ProfileMetadata] = [:]
    private(set) var relayHealth = RelayHealth(connected: 0, total: 0)
    private(set) var isLoading = false
    private(set) var hasLoadedAnyEvent = false
    var localActions = LocalActions()
    private(set) var accountPubkey: String?
    private(set) var followingResolved = false
    private(set) var comments: [String: [FeedNote]] = [:]
    private(set) var following: Set<String> = []
    private(set) var bookmarkedIds: Set<String> = []
    private(set) var zapCounts: [String: Int] = [:]
    private(set) var muted: Set<String> = []

    private let pool: RelayPool
    private let client: any BusinessCoreClient
    private let eventStore: EventStore?
    private var window: (any FeedWindowing)?
    private var followingWindow: (any FeedWindowing)?
    private var followingAuthors: Set<String> = []
    private var followingSubscribed = false
    private var commentThreads: [String: [FeedNote]] = [:]
    private var bookmarked: [String] = []
    private var zapCountsBuffer: [String: Int] = [:]
    private var collectTask: Task<Void, Never>?
    private var healthTask: Task<Void, Never>?
    private var retryTask: Task<Void, Never>?
    private var retryAttempt = 0
    private var subscriptionCounter = 0
    private var profileQueue: [String] = []
    private var requestedProfiles: Set<String> = []
    private var profileTimestamps: [String: Int64] = [:]
    private var profileDrainTask: Task<Void, Never>?

    init(pool: RelayPool, client: any BusinessCoreClient, eventStore: EventStore? = nil) {
        muted = Set(UserDefaults.standard.stringArray(forKey: "bitos_mutes") ?? [])
        self.pool = pool
        self.client = client
        self.eventStore = eventStore
    }

    func start() async {
        guard collectTask == nil else { return }
        window = window ?? client.makeFeedWindow(maxItems: 200)
        followingWindow = followingWindow ?? client.makeFeedWindow(maxItems: 200)
        hydrateFromCache()
        await pool.start()
        subscribe()
        let stream = await pool.frames()
        collectTask = Task { [weak self] in
            for await frame in stream {
                self?.absorb(frame)
            }
        }
        healthTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                self.relayHealth = await self.pool.health()
                try? await Task.sleep(for: Self.healthPollInterval)
            }
        }
        // APP-004: empty-feed auto-retry (shared `EmptyFeedRetry` policy —
        // 2 s exponential backoff capped at 30 s, relay-connectivity-gated:
        // a REQ only re-fires while the window is empty AND a relay is
        // connected; the pool owns reconnection otherwise).
        retryTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let delayMs = self.client.emptyFeedRetryDelayMs(attempt: self.retryAttempt)
                try? await Task.sleep(for: .milliseconds(delayMs))
                guard !Task.isCancelled, let self else { return }
                if self.forYouCount > 0 {
                    self.retryAttempt = 0
                    continue
                }
                let health = await self.pool.health()
                guard health.connected > 0 else { continue }
                // Saturate well past where the shared delay caps; the
                // counter only needs to stay bounded.
                self.retryAttempt = min(self.retryAttempt + 1, 30)
                self.subscribe()
            }
        }
    }

    func stop() {
        collectTask?.cancel()
        collectTask = nil
        profileDrainTask?.cancel()
        profileDrainTask = nil
        healthTask?.cancel()
        healthTask = nil
        retryTask?.cancel()
        retryTask = nil
        Task { await pool.broadcast(self.client.close(subscriptionId: self.currentSubscriptionId)) }
    }

    func selectTimeline(_ timeline: FeedTimeline) {
        self.timeline = timeline
    }

    // MARK: - APP-004: content filter + new-notes hold/reveal

    func selectFilter(_ ordinal: Int) {
        filterOrdinal = ordinal
        publishState()
    }

    /// Hold = user is scrolled into the feed; setting false flushes
    /// pending arrivals (auto-reveal at top).
    func holdNewNotes(_ hold: Bool) {
        guard hold != holdingNewNotes else { return }
        holdingNewNotes = hold
        if !hold, !pendingNotes.isEmpty { revealPendingNotes() }
    }

    func revealPendingNotes() {
        for note in pendingNotes { window?.insert(note) }
        pendingNotes.removeAll()
        publishState()
    }

    /// Up to four distinct arrival authors for the pill avatar stack.
    var pendingAuthors: [String] {
        var seen = Set<String>()
        var authors: [String] = []
        for note in pendingNotes where seen.insert(note.pubkey).inserted {
            authors.append(note.pubkey)
            if authors.count == 4 { break }
        }
        return authors
    }

    /// APP-005: NIP-27 rich-content tokens (bridge seam; JSON shape locked
    /// by shared `Nip27Test`).
    func richTokens(for content: String) -> String {
        bridgeFacade().richTokens(content: content)
    }

    func refresh() {
        isLoading = !hasLoadedAnyEvent
        subscribe()
    }

    /// APP-004: manual retry from the relay-error / empty state — resets the
    /// backoff so the next auto-retry is 2 s away, then re-issues the REQ.
    func retryNow() {
        retryAttempt = 0
        subscribe()
    }

    // MARK: - Absorption
    //
    // Runs on the main actor (Task in a @MainActor context inherits the
    // isolation). Decode cost per frame is bounded by NostrLimits; if this
    // ever shows in instruments, decoding moves into the pool stream before
    // the main-actor hop.

    private func absorb(_ frame: RelayFrame) {
        guard let event = client.decodeVerifiedEvent(message: frame.message, relay: frame.relay.rawValue) else { return }

        if event.kind == 3 {
            absorbContactList(frame)
        } else if event.kind == 9735 {
            let target = event.tags.first { $0.first == "e" }?.dropFirst().first
            if let target {
                zapCountsBuffer[target, default: 0] += 1
                if zapCountsBuffer.count > 16 { zapCountsBuffer.removeValue(forKey: zapCountsBuffer.keys.first!) }
            }
        } else if event.kind == 30003 {
            absorbBookmarkList(frame)
        } else if client.isProfileKind(event.kind) {
            absorbProfile(event)
        } else if client.isFeedKind(event.kind) {
            absorbNote(event)
            persist(event)
        }
        publishState()
    }

    private func absorbNote(_ event: VerifiedEvent) {
        let note = client.feedNote(from: event)
        if !knownNoteIds.contains(note.id) {
            knownNoteIds.insert(note.id)
            // APP-004: arrivals are held for the "N new notes" pill while
            // the user is scrolled into the feed; tap/at-top reveals them.
            if holdingNewNotes, (window?.count() ?? 0) > 0 {
                pendingNotes.append(note)
                if pendingNotes.count > Self.pendingMax { pendingNotes.removeFirst(pendingNotes.count - Self.pendingMax) }
            } else {
                window?.insert(note)
            }
        }
        if followingAuthors.contains(event.pubkey) {
            followingWindow?.insert(note)
        }
        if let target = note.replyTo, var thread = commentThreads[target] {
            thread.append(note)
            if thread.count > 100 { thread.removeFirst(thread.count - 100) }
            commentThreads[target] = thread
        }
        enqueueProfile(event.pubkey)
    }

    /**
     * Optimistic follow change: updates the local set and Following window;
     * the caller publishes the resulting kind-3 and the relay echo
     * reconciles. Returns the new set, or nil without an account.
     */
    func applyFollowChange(author: String, add: Bool) -> [String]? {
        guard let account = accountPubkey, author != account else { return nil }
        if add {
            followingAuthors.insert(author)
        } else {
            followingAuthors.remove(author)
        }
        followingSubscribed = false
        subscribeFollowing()
        publishState()
        return Array(followingAuthors)
    }

    /** Optimistic bookmark change; returns the list for the publish. */
    func applyBookmarkChange(eventId: String, add: Bool) -> [String]? {
        guard accountPubkey != nil else { return nil }
        if add {
            if !bookmarked.contains(eventId) { bookmarked.append(eventId) }
        } else {
            bookmarked.removeAll { $0 == eventId }
        }
        if bookmarked.count > 500 { bookmarked.removeFirst(bookmarked.count - 500) }
        publishState()
        return bookmarked
    }

    private func absorbBookmarkList(_ frame: RelayFrame) {
        guard let account = accountPubkey else { return }
        guard let decoded = client.decodeVerifiedEvent(message: frame.message, relay: frame.relay.rawValue),
              decoded.pubkey == account else { return }
        let ids = (bridgeFacade().bookmarkIds(message: frame.message, relayUrl: frame.relay.rawValue) as? [String]) ?? []
        // Newer verified heads replace the local set (bounded by composer).
        if decoded.createdAt >= (bookmarkHeadAt ?? Int64.min) {
            bookmarked = ids
            bookmarkHeadAt = decoded.createdAt
        }
        publishState()
    }

    private var bookmarkHeadAt: Int64?

    /** Loads zap receipts for one note (NIP-01 tagged #e filter). */
    func loadZaps(targetEventId: String) {
        if let request = (bridgeFacade().zapReceiptsRequest(
            subscriptionId: "bitos-zaps-" + String(targetEventId.prefix(48)),
            targetEventId: targetEventId
        ) as String?) {
            Task { await pool.broadcast(request) }
        }
    }

    /// Muted authors are filtered from all feed windows (device-local).
    func setMuted(_ pubkeys: Set<String>) {
        muted = pubkeys
        publishState()
    }

    func toggleMute(_ pubkey: String) {
        if muted.contains(pubkey) {
            muted.remove(pubkey)
        } else {
            muted.insert(pubkey)
        }
        UserDefaults.standard.set(Array(muted), forKey: "bitos_mutes")
        publishState()
    }

    /// Loads the reply thread for one note (NIP-01 tagged #e filter).
    func loadComments(targetEventId: String) {
        guard commentThreads[targetEventId] == nil else {
            publishState()
            return
        }
        commentThreads[targetEventId] = []
        if commentThreads.count > 16 { commentThreads.removeValue(forKey: commentThreads.keys.first!) }
        if let request = (bridgeFacade().commentsRequest(
            subscriptionId: "bitos-comments-" + String(targetEventId.prefix(48)),
            targetEventId: targetEventId
        ) as String?) {
            Task { await pool.broadcast(request) }
        }
        publishState()
    }

    // MARK: - Following timeline (kind-3 contacts)

    /// Account lifecycle: non-null activates the Following timeline.
    func setAccount(_ pubkey: String?) {
        accountPubkey = pubkey
        followingAuthors.removeAll()
        followingSubscribed = false
        followingResolved = pubkey == nil
        if let pubkey, let request = (bridgeFacade().contactListRequest(subscriptionId: "bitos-contacts", accountPubkey: pubkey) as String?) {
            Task { await pool.broadcast(request) }
        }
        if let pubkey, let request = (bridgeFacade().bookmarkListRequest(subscriptionId: "bitos-bookmarks", accountPubkey: pubkey) as String?) {
            Task { await pool.broadcast(request) }
        }
        bookmarked.removeAll()
        publishState()
    }

    private func absorbContactList(_ frame: RelayFrame) {
        guard let account = accountPubkey else { return }
        guard let authors = (bridgeFacade().contactListAuthors(message: frame.message, relayUrl: frame.relay.rawValue) as? [String]) else { return }
        // Only the account's own contact list drives the timeline.
        // (The bridge verified the frame; the author check filters.)
        guard let decoded = client.decodeVerifiedEvent(message: frame.message, relay: frame.relay.rawValue), decoded.pubkey == account else { return }
        followingAuthors = Set(authors)
        followingSubscribed = false
        subscribeFollowing()
        followingResolved = true
        publishState()
    }

    private func subscribeFollowing() {
        guard !followingSubscribed, !followingAuthors.isEmpty else { return }
        followingSubscribed = true
        let authors = Array(followingAuthors)
        if let request = (bridgeFacade().followingRequest(subscriptionId: "bitos-following", authors: authors) as? String) {
            Task { await pool.broadcast(request) }
        }
    }

    private func bridgeFacade() -> BusinessCoreBridge {
        (client as? FrameworkBusinessCoreClient)?.bridgeForFollowing() ?? BusinessCoreBridge()
    }

    private func absorbProfile(_ event: VerifiedEvent) {
        guard let metadata = client.profile(from: event) else { return }
        guard event.createdAt >= (profileTimestamps[metadata.pubkey] ?? Int64.min) else { return }
        profiles[metadata.pubkey] = metadata
        profileTimestamps[metadata.pubkey] = event.createdAt
    }

    private func enqueueProfile(_ pubkey: String) {
        guard profiles[pubkey] == nil,
              !profileQueue.contains(pubkey),
              !requestedProfiles.contains(pubkey) else { return }
        profileQueue.append(pubkey)
        if profileQueue.count >= Self.profileBatchSize {
            profileDrainTask?.cancel()
            drainProfiles()
        } else {
            profileDrainTask?.cancel()
            profileDrainTask = Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(250))
                guard !Task.isCancelled else { return }
                self?.drainProfiles()
            }
        }
    }

    private func drainProfiles() {
        let batch = Array(profileQueue.prefix(Self.profileBatchSize))
        profileQueue.removeFirst(min(batch.count, profileQueue.count))
        guard !batch.isEmpty else { return }
        requestedProfiles.formUnion(batch)
        let request = client.profileRequest(
            subscriptionId: "bitos-profiles-\(requestedProfiles.count)", authors: batch
        )
        Task { await pool.broadcast(request) }
    }

    private func subscribe() {
        subscriptionCounter += 1
        let request = client.feedRequest(subscriptionId: currentSubscriptionId)
        Task { await pool.broadcast(request) }
    }

    private var currentSubscriptionId: String { "bitos-feed-\(subscriptionCounter)" }

    private func publishState() {
        let mutedSet = muted
        let filterOrdinal = filterOrdinal
        let ownPubkey = accountPubkey
        let liked = Array(localActions.liked)
        let forYouBase = (window?.snapshot() ?? []).filter { !mutedSet.contains($0.pubkey) && !$0.isProtocolPayload }
        let followingBase = (followingWindow?.snapshot() ?? []).filter { !mutedSet.contains($0.pubkey) && !$0.isProtocolPayload }
        forYouCount = forYouBase.count
        followingCount = followingBase.count
        notes = (timeline == .following ? followingBase : forYouBase).filter {
            client.feedFilterMatches(note: $0, filterOrdinal: filterOrdinal, ownPubkeyHex: ownPubkey, likedIds: liked)
        }
        comments = commentThreads
        following = followingAuthors
        bookmarkedIds = Set(bookmarked)
        zapCounts = zapCountsBuffer
        isLoading = false
        hasLoadedAnyEvent = true
    }

    // MARK: - Persistence (DAT-003)

    /// Cold-start hydration: newest cached verified events fill the window
    /// before relays connect; failures never block the live feed.
    private func hydrateFromCache() {
        guard let store = eventStore else { return }
        Task { [weak self] in
            let stored: [StoredEvent] = await Task.detached(priority: .utility) {
                (try? store.recentEvents(limit: 200)) ?? []
            }.value
            guard let self, !Task.isCancelled else { return }
            for event in stored {
                self.absorbStored(event)
            }
            if !stored.isEmpty {
                self.publishState()
            }
            await Task.detached(priority: .utility) { try? store.prune(maxRows: 500) }.value
        }
    }

    private func absorbStored(_ stored: StoredEvent) {
        guard let event = storedEventToVerified(stored) else { return }
        if client.isProfileKind(event.kind) {
            absorbProfile(event)
        } else if client.isFeedKind(event.kind) {
            let note = client.feedNote(from: event)
            window?.insert(note)
            knownNoteIds.insert(note.id)
        }
    }

    private func storedEventToVerified(_ stored: StoredEvent) -> VerifiedEvent? {
        guard let tags = client.tagsFromJson(stored.tagsJson) else { return nil }
        return VerifiedEvent(
            id: stored.id,
            pubkey: stored.pubkey,
            createdAt: stored.createdAt,
            kind: stored.kind,
            tags: tags,
            content: stored.content,
            relayUrl: stored.relayUrl,
            signature: stored.signature
        )
    }

    /// Fire-and-forget persistence of verified events off the main actor.
    private func persist(_ event: VerifiedEvent) {
        guard let store = eventStore else { return }
        let tagsJson = client.tagsToJson(event.tags)
        let stored = StoredEvent(
            id: event.id, pubkey: event.pubkey, createdAt: event.createdAt,
            kind: event.kind, tagsJson: tagsJson, content: event.content,
            signature: event.signature, relayUrl: event.relayUrl, firstSeenAt: Int64(Date.now.timeIntervalSince1970)
        )
        Task.detached { [store] in
            _ = try? store.insert(stored)
        }
    }

    private static let profileBatchSize = 48
    private static let healthPollInterval: Duration = .seconds(2)
    private static let pendingMax = 50
    private var holdingNewNotes = false
    private var knownNoteIds: Set<String> = []
}
