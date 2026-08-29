import BusinessCore
import Foundation
import Observation

/// Which Home timeline the user is viewing.
enum FeedTimeline: Hashable, Sendable {
    case forYou
    case following
}

/// APP-009 live per-note tallies (shared `NoteTally` mirror).
struct NoteTallyMirror: Sendable, Equatable {
    var reactions: Int = 0
    var reposts: Int = 0
    var zaps: Int = 0
    var zapMillisats: Int64 = 0
}

/// APP-009 X-style display item (shared `ThreadAssembly` through the
/// bridge): top-level at depth 0, descendants flattened behind indents.
struct ThreadDisplayItem: Sendable, Equatable, Identifiable {
    let id: String
    let depth: Int
    let parentId: String?
    let orphan: Bool
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
    /// APP-004 pagination: an older-notes REQ is in flight (footer spinner).
    private(set) var isLoadingOlder = false
    /// True once an older page made no progress — pauses until refresh.
    private(set) var noMoreOlder = false
    var localActions = LocalActions()
    private(set) var accountPubkey: String?
    private(set) var followingResolved = false
    private(set) var comments: [String: [FeedNote]] = [:]
    /** APP-009 X-style display list per thread (shared assembly rule). */
    private(set) var threads: [String: [ThreadDisplayItem]] = [:]
    private(set) var following: Set<String> = []
    private(set) var bookmarkedIds: Set<String> = []
    /// APP-015 saved notes (newest-saved first; window + by-id refetch).
    private(set) var bookmarkedNotes: [FeedNote] = []
    private(set) var zapCounts: [String: Int] = [:]
    /** APP-014: verified embedded 9734 request ids per target (paid match). */
    private(set) var zapRequestIds: [String: Set<String>] = [:]
    /** APP-009 live per-note tallies (reactions/reposts/zaps+msat). */
    private(set) var tallies: [String: NoteTallyMirror] = [:]
    private(set) var muted: Set<String> = []

    /// Blocked authors (NIP-51 kind-10004 head) — filtered like mutes.
    private(set) var blocked: Set<String> = []
    private var blockHeadAt: Int64?

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
    private var zapRequestIdsBuffer: [String: Set<String>] = [:]
    private var talliesBuffer: [String: NoteTallyMirror] = [:]
    private var tallyTargets: Set<String> = []
    private var collectTask: Task<Void, Never>?
    private var healthTask: Task<Void, Never>?
    private var retryTask: Task<Void, Never>?
    private var retryAttempt = 0
    private var olderCounter = 0
    private var oldestLoadedAt: Int64?
    private var subscriptionCounter = 0
    private var profileQueue: [String] = []
    private var requestedProfiles: Set<String> = []
    private var profileTimestamps: [String: Int64] = [:]
    private var profileDrainTask: Task<Void, Never>?
    private var profileFallbackTasks: [Task<Void, Never>] = []
    private var profileRequestCounter = 0

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
                guard !Task.isCancelled else { return }
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
        profileFallbackTasks.forEach { $0.cancel() }
        profileFallbackTasks.removeAll()
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
        // Fresh subscription re-opens the timeline head; older pages may
        // exist again after new arrivals push the window deeper.
        isLoading = !hasLoadedAnyEvent
        noMoreOlder = false
        oldestLoadedAt = nil
        subscribe()
    }

    /// APP-004: manual retry from the relay-error / empty state — resets the
    /// backoff so the next auto-retry is 2 s away, then re-issues the REQ.
    func retryNow() {
        retryAttempt = 0
        noMoreOlder = false
        oldestLoadedAt = nil
        subscribe()
    }

    /**
     * APP-004 pagination: request one page older than the window's oldest
     * note (`until` exclusive). One in-flight REQ at a time; a watchdog
     * marks the feed exhausted (`noMoreOlder`) when a page brings nothing
     * new, pausing infinite scroll until the next refresh.
     */
    func loadOlder() {
        guard !isLoadingOlder, !noMoreOlder else { return }
        guard let window else { return }
        let snapshot = window.snapshot()
        guard !snapshot.isEmpty else { return }
        if snapshot.count >= Self.olderWindowMax {
            noMoreOlder = true
            return
        }
        guard let oldest = snapshot.map(\.createdAt).min() else { return }
        guard oldestLoadedAt != oldest else { return } // same page requested
        oldestLoadedAt = oldest
        isLoadingOlder = true
        olderCounter += 1
        let request = client.olderFeedRequest(
            subscriptionId: "bitos-older-\(olderCounter)", until: oldest, limit: 40
        )
        Task { [pool] in await pool.broadcast(request) }
        Task { [weak self, oldest] in
            try? await Task.sleep(for: .seconds(8))
            guard let self, !Task.isCancelled else { return }
            guard self.isLoadingOlder else { return }
            self.isLoadingOlder = false
            // No progress by deadline: exhausted until the next refresh.
            if (self.window?.snapshot() ?? []).map(\.createdAt).min() == oldest {
                self.noMoreOlder = true
            }
        }
    }

    // MARK: - Absorption
    //
    // Runs on the main actor (Task in a @MainActor context inherits the
    // isolation). Decode cost per frame is bounded by NostrLimits; if this
    // ever shows in instruments, decoding moves into the pool stream before
    // the main-actor hop.

    private func absorb(_ frame: RelayFrame) {
        guard let event = client.decodeVerifiedEvent(message: frame.message, relay: frame.relay.rawValue) else { return }

        if event.kind == 7 || event.kind == 6 {
            // APP-009: live tallies per thread note (root or reply).
            let eTagged = event.tags.filter { $0.first == "e" }.compactMap { $0.dropFirst().first }
            if let target = eTagged.lazy.compactMap({ self.tallyTarget(for: $0) }).first {
                var tally = talliesBuffer[target] ?? NoteTallyMirror()
                if event.kind == 7 { tally.reactions += 1 }
                else { tally.reposts += 1 }
                talliesBuffer[target] = tally
            }
        } else if event.kind == 3 {
            absorbContactList(frame)
        } else if event.kind == 9735 {
            let target = event.tags.first { $0.first == "e" }?.dropFirst().first
            if let target {
                zapCountsBuffer[target, default: 0] += 1
                // APP-009: live zap tally per thread note (+ summed msat).
                if let tallyId = tallyTarget(for: target) {
                    var tally = talliesBuffer[target] ?? NoteTallyMirror()
                    tally.zaps += 1
                    let invoice = event.tags.first { $0.first == "bolt11" }?.dropFirst().first
                    if let invoice, let msat = bridgeFacade().bolt11AmountMillisats(invoice: invoice) as? Int64 {
                        tally.zapMillisats += msat
                    }
                    talliesBuffer[target] = tally
                }
                if zapCountsBuffer.count > 16 { zapCountsBuffer.removeValue(forKey: zapCountsBuffer.keys.first!) }
                // APP-014: retain the embedded 9734 request id for exact
                // paid matching in the zap sheet.
                if let requestId = bridgeFacade().embeddedZapRequestId(
                    message: frame.message, relayUrl: frame.relay.rawValue
                ) as String? {
                    var ids = zapRequestIdsBuffer[target] ?? []
                    ids.insert(requestId)
                    if ids.count > 8 { ids = Set(ids.suffix(8)) }
                    zapRequestIdsBuffer[target] = ids
                }
            }
        } else if event.kind == 30003 {
            absorbBookmarkList(frame)
        } else if event.kind == 10004 {
            absorbBlockList(frame, event: event)
        } else if client.isProfileKind(event.kind) {
            absorbProfile(event)
        } else if client.isFeedKind(event.kind) {
            let fromOlderPage = bridgeFacade().relayEventSubscriptionId(message: frame.message)?
                .hasPrefix("bitos-older-") == true
            absorbNote(event, fromOlderPage: fromOlderPage)
            persist(event)
        }
        publishState()
    }

    private func absorbNote(_ event: VerifiedEvent, fromOlderPage: Bool = false) {
        let note = client.feedNote(from: event)
        if !knownNoteIds.contains(note.id) {
            knownNoteIds.insert(note.id)
            // APP-004: arrivals are held for the "N new notes" pill while
            // the user is scrolled into the feed; tap/at-top reveals them.
            if !fromOlderPage, holdingNewNotes, (window?.count() ?? 0) > 0 {
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
        // Web parity: mentioned pubkeys' profiles resolve for @display.
        if let data = (bridgeFacade().richTokens(content: note.content) as String?)?.data(using: .utf8),
           let tokens = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] {
            let mentioned = tokens.compactMap { token -> String? in
                guard token["k"] as? String == "n",
                      (token["e"] as? String) == "profile" else { return nil }
                return token["x"] as? String
            }
            if !mentioned.isEmpty { requestMentionProfiles(mentioned) }
        }
        // APP-007 Chain: by-id fetches for the remix ancestry land here too.
        remixAncestorEvents[note.id] = event
        if remixAncestorEvents.count > 48, let oldest = remixAncestorEvents.keys.first {
            remixAncestorEvents.removeValue(forKey: oldest)
        }
        // APP-015: keep saved-note bodies for the bookmarks page.
        if bookmarked.contains(note.id) {
            bookmarkedNoteBodies[note.id] = note
        }
    }

    // MARK: - Bookmarks page (APP-015)

    /// Saved-note bodies outside the live feed window (by-id re-fetch).
    private var bookmarkedNoteBodies: [String: FeedNote] = [:]

    /// Spec §3.15: re-fetch saved notes missing from the local map on open.
    func loadBookmarked() {
        let missing = bookmarked.filter { bookmarkedNoteBodies[$0] == nil }.suffix(100)
        if !missing.isEmpty,
           let request = (bridgeFacade().eventsByIdsRequest(
               subscriptionId: "bitos-saved-notes",
               ids: Array(missing)
           ) as String?) {
            Task { [pool] in await pool.broadcast(request) }
        }
        publishState()
    }

    // MARK: - Remix chain (APP-007, web remixChainOf parity)

    struct RemixChainStep: Equatable {
        let eventId: String
        let pubkey: String?
        let depth: Int
    }

    struct RemixChainUiState: Equatable {
        var isLoading = false
        var rootId: String?
        var steps: [RemixChainStep] = []
        var truncated = false
        var isCycle = false
        var isCompleted = false
    }

    private(set) var remixChainState = RemixChainUiState()
    private var remixAncestorEvents: [String: VerifiedEvent] = [:]
    private var chainCounter = 0
    private var chainTask: Task<Void, Never>?

    /** Walks the remix ancestry of [note] (cycle-safe, hex-validated, ≤32). */
    func loadRemixChain(note: FeedNote) {
        guard let sourceId = note.remixOfEventId else { return }
        let rootId = note.id
        remixChainState = RemixChainUiState(isLoading: true, rootId: rootId)
        chainTask?.cancel()
        chainTask = Task { [weak self] in
            await self?.runRemixChainWalk(rootId: rootId, sourceId: sourceId, sourcePubkey: note.remixOfPubkey)
        }
    }

    /** Ancestor note for sheet row tap-through (opens its thread). */
    func remixAncestorNote(id: String) -> FeedNote? {
        remixAncestorEvents[id].map { client.feedNote(from: $0) }
    }

    // MARK: - Mention profiles + note refs (web parity)

    /** Mentioned pubkeys (NIP-27 profile entities) — @display resolution. */
    func requestMentionProfiles(_ pubkeys: [String]) {
        pubkeys.prefix(48).forEach { enqueueProfile($0) }
    }

    /** In-place note-ref open: fetch the head, caller polls [refNote]. */
    func openNoteReference(raw: String) {
        guard let ref = (bridgeFacade().eventRefParse(bech32: raw) as? [String: Any]) else { return }
        let request: String?
        if ref["form"] as? String == "id", let id = ref["id"] as? String {
            request = bridgeFacade().threadRootRequestById(
                subscriptionId: "bitos-ref-open", eventId: id)
        } else if let kind = (ref["kind"] as? KotlinInt)?.intValue,
                  let pubkey = ref["pubkey"] as? String,
                  let d = ref["d"] as? String {
            request = bridgeFacade().threadRootRequestByCoordinate(
                subscriptionId: "bitos-ref-open", kind: Int32(truncatingIfNeeded: kind), pubkey: pubkey, d: d)
        } else {
            request = nil
        }
        if let request {
            Task { [pool] in await pool.broadcast(request) }
        }
    }

    /** Fetched head for the in-place ref open (null while in flight). */
    func refNote(raw: String) -> FeedNote? {
        guard let ref = (bridgeFacade().eventRefParse(bech32: raw) as? [String: Any]) else { return nil }
        if ref["form"] as? String == "id", let id = ref["id"] as? String {
            return remixAncestorEvents[id].map { client.feedNote(from: $0) }
        }
        if let kind = (ref["kind"] as? KotlinInt)?.intValue,
           let pubkey = ref["pubkey"] as? String {
            // Coordinate refs match the newest absorbed event of that shape
            // (the REQ is limit-1, relay-newest).
            return Array(remixAncestorEvents.values)
                .filter { $0.kind == kind && $0.pubkey == pubkey }
                .max { $0.createdAt < $1.createdAt }
                .map { client.feedNote(from: $0) }
        }
        return nil
    }

    private func runRemixChainWalk(rootId: String, sourceId: String, sourcePubkey: String?) async {
        var steps: [RemixChainStep] = []
        var visited: Set<String> = [rootId]
        var currentId: String? = sourceId
        var currentPubkey = sourcePubkey
        var truncated = false
        var isCycle = false
        while let id = currentId {
            guard Self.isLowercaseHex64(id) else { break }
            if visited.contains(id) {
                isCycle = true
                break
            }
            visited.insert(id)
            steps.append(RemixChainStep(eventId: id, pubkey: currentPubkey, depth: steps.count))
            if steps.count >= 32 {
                truncated = true
                break
            }
            guard let tags = await fetchRemixAncestorTags(id) else { break } // natural end
            if let data = try? JSONSerialization.data(withJSONObject: tags),
               let tagsJson = String(data: data, encoding: .utf8),
               let packed = (bridgeFacade().remixSourceOfTags(tagsJson: tagsJson) as String?) {
                let parts = packed.split(separator: "|", maxSplits: 1).map(String.init)
                currentId = parts.first
                currentPubkey = parts.count > 1 && !parts[1].isEmpty ? parts[1] : nil
            } else {
                currentId = nil
            }
        }
        remixChainState = RemixChainUiState(
            isLoading: false,
            rootId: rootId,
            steps: steps,
            truncated: truncated,
            isCycle: isCycle,
            isCompleted: true
        )
    }

    /// Single-id REQ + bounded wait (3 s); tags of the ancestor or nil.
    private func fetchRemixAncestorTags(_ id: String) async -> [[String]]? {
        chainCounter += 1
        let request = bridgeFacade().threadRootRequestById(
            subscriptionId: String("bitos-chain-\(chainCounter)".prefix(64)),
            eventId: id
        )
        Task { [pool] in await pool.broadcast(request) }
        for _ in 0..<20 {
            if let event = remixAncestorEvents[id] { return event.tags }
            try? await Task.sleep(nanoseconds: 150_000_000)
        }
        return nil
    }

    private static func isLowercaseHex64(_ value: String) -> Bool {
        value.count == 64 && value.allSatisfy { "0123456789abcdef".contains($0) }
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

    private func absorbBlockList(_ frame: RelayFrame, event: VerifiedEvent) {
        guard let account = accountPubkey, event.pubkey == account else { return }
        let ids = (bridgeFacade().blockListPubkeys(message: frame.message, relayUrl: frame.relay.rawValue) as? [String]) ?? []
        if event.createdAt >= (blockHeadAt ?? Int64.min) {
            blocked = Set(ids)
            blockHeadAt = event.createdAt
        }
    }

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
    /// APP-009: the tally target — the id when it names a known note in an
    /// open thread window (root or any reply), so reply rows carry deltas.
    private func tallyTarget(for id: String) -> String? {
        if tallyTargets.contains(id) { return id }
        for window in commentThreads.values where window.contains(where: { $0.id == id }) {
            return id
        }
        return nil
    }

    func loadComments(targetEventId: String) {
        // APP-009: reactions/reposts/zaps targeting this thread tally live.
        tallyTargets.insert(targetEventId)
        if tallyTargets.count > 32 { tallyTargets.removeFirst() }
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
        if let pubkey, let request = (bridgeFacade().encodeBlockListRequest(subscriptionId: "bitos-blocks", accountPubkey: pubkey) as String?) {
            Task { await pool.broadcast(request) }
        }
        bookmarked.removeAll()
        blocked.removeAll()
        blockHeadAt = nil
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

    // MARK: - APP-009 X-style threading (shared assembly via bridge)

    private func threadItems(rootId: String, replies: [FeedNote]) -> [ThreadDisplayItem] {
        let payload = replies.map { note in
            [
                "id": note.id,
                "createdAt": note.createdAt,
                "threadRootId": note.threadRootId ?? "",
                "threadParentId": note.threadParentId ?? "",
            ] as [String: Any]
        }
        guard
            let data = try? JSONSerialization.data(withJSONObject: payload),
            let itemsJson = String(data: data, encoding: .utf8),
            let json = bridgeFacade().threadItemsJson(rootId: rootId, itemsJson: itemsJson) as String?,
            let jsonData = json.data(using: .utf8),
            let array = try? JSONSerialization.jsonObject(with: jsonData) as? [[String: Any]]
        else {
            // Honest fallback: keep every reply visible, flat, when the
            // bridge round-trip fails.
            return replies.map { ThreadDisplayItem(id: $0.id, depth: 0, parentId: nil, orphan: true) }
        }
        return array.compactMap { obj in
            guard let id = obj["id"] as? String else { return nil }
            return ThreadDisplayItem(
                id: id,
                depth: (obj["depth"] as? NSNumber)?.intValue ?? 0,
                parentId: (obj["parent"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                orphan: (obj["orphan"] as? NSNumber)?.boolValue ?? false
            )
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
        Task { [weak self, batch] in
            guard let self else { return }
            let primary = await self.pool.primaryRelay()
            self.profileRequestCounter += 1
            let request = self.client.profileRequest(
                subscriptionId: "bitos-profiles-\(self.profileRequestCounter)", authors: batch
            )
            if let primary {
                await self.pool.broadcast(request, to: [primary])
            } else {
                await self.pool.broadcast(request)
            }
            self.scheduleProfileFallback(batch, excluding: primary)
        }
    }

    private func scheduleProfileFallback(_ batch: [String], excluding primary: RelayURL?) {
        let task = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(900))
            guard let self, !Task.isCancelled else { return }
            let unresolved = batch.filter { self.profiles[$0] == nil }
            let relays = await self.pool.fallbackRelays(excluding: primary)
            guard !unresolved.isEmpty, !relays.isEmpty else { return }
            self.profileRequestCounter += 1
            let request = self.client.profileRequest(
                subscriptionId: "bitos-profiles-fallback-\(self.profileRequestCounter)", authors: unresolved
            )
            await self.pool.broadcast(request, to: relays)
        }
        profileFallbackTasks.append(task)
    }

    private func subscribe() {
        subscriptionCounter += 1
        let request = client.feedRequest(subscriptionId: currentSubscriptionId)
        Task { await pool.broadcast(request) }
    }

    private var currentSubscriptionId: String { "bitos-feed-\(subscriptionCounter)" }

    // MARK: - Algorithm (APP-018 §3.18 — origin parity)

    /// Canonical algorithm wire; a disabled FEED surface (or nil) keeps
    /// For-You strictly chronological. Following is ALWAYS chronological.
    private var algorithmJson: String?

    func setAlgorithm(snapshotJson: String) {
        algorithmJson = snapshotJson
        publishState()
    }

    /// Orders the For-You window through the shared engine (bridge seam:
    /// minimal note rows in, ordered ids out).
    private func rankedForYou(_ base: [FeedNote]) -> [FeedNote] {
        guard let algoJson = algorithmJson,
              let decoded = try? JSONSerialization.jsonObject(with: Data(algoJson.utf8)) as? [String: Any],
              let surfaces = decoded["s"] as? [String: Any],
              let feedSurface = surfaces["feed"] as? [String: Any],
              (feedSurface["e"] as? String) == "1" || (feedSurface["e"] as? Int) == 1 else {
            return base
        }
        let rows = base.map { "{\"id\":\"\($0.id)\",\"pubkey\":\"\($0.pubkey)\",\"createdAt\":\($0.createdAt)}" }
        let following = followingAuthors.map { "\"\($0)\"" }
        let zaps = zapCountsBuffer.map { "\"\($0.key)\":\($0.value)" }
        let replies = commentThreads.map { "\"\($0.key)\":\($0.value.count)" }
        guard let ids = bridgeFacade().algorithmRankIds(
            notesJson: "[\(rows.joined(separator: ","))]",
            surfaceWire: "feed",
            snapshotJson: algoJson,
            followingJson: "[\(following.joined(separator: ","))]",
            zapCountsJson: "{\(zaps.joined(separator: ","))}",
            replyCountsJson: "{\(replies.joined(separator: ","))}",
            nowSeconds: Int64(Date.now.timeIntervalSince1970)
        ) as? [String], !ids.isEmpty else {
            return base
        }
        let byId = Dictionary(base.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return ids.compactMap { byId[$0] }
    }

    private func publishState() {
        let hiddenSet = muted.union(blocked)
        let filterOrdinal = filterOrdinal
        let ownPubkey = accountPubkey
        let liked = Array(localActions.liked)
        let forYouBase = (window?.snapshot() ?? []).filter { !hiddenSet.contains($0.pubkey) && !$0.isProtocolPayload }
        let followingBase = (followingWindow?.snapshot() ?? []).filter { !hiddenSet.contains($0.pubkey) && !$0.isProtocolPayload }
        forYouCount = forYouBase.count
        followingCount = followingBase.count
        notes = (timeline == .following ? followingBase : rankedForYou(forYouBase)).filter {
            client.feedFilterMatches(note: $0, filterOrdinal: filterOrdinal, ownPubkeyHex: ownPubkey, likedIds: liked)
        }
        comments = commentThreads
        var assembled: [String: [ThreadDisplayItem]] = [:]
        for (rootId, replies) in commentThreads {
            assembled[rootId] = threadItems(rootId: rootId, replies: replies)
        }
        threads = assembled
        following = followingAuthors
        bookmarkedIds = Set(bookmarked)
        // APP-015: newest-saved first; window notes fill ids not yet fetched.
        if bookmarked.isEmpty {
            bookmarkedNotes = []
        } else {
            let byId = (window?.snapshot() ?? []).reduce(into: [String: FeedNote]()) { map, note in
                map[note.id] = note
            }
            bookmarkedNotes = bookmarked.reversed().compactMap { bookmarkedNoteBodies[$0] ?? byId[$0] }
        }
        zapCounts = zapCountsBuffer
        zapRequestIds = zapRequestIdsBuffer
        tallies = talliesBuffer
        isLoading = false
        hasLoadedAnyEvent = true
        // Older page arrived when the window's oldest note moved back.
        if isLoadingOlder, let loadedAt = oldestLoadedAt,
           let oldestNow = window?.snapshot().map(\.createdAt).min(), oldestNow < loadedAt {
            isLoadingOlder = false
        }
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
    private static let olderWindowMax = 200
    private var holdingNewNotes = false
    private var knownNoteIds: Set<String> = []
}
