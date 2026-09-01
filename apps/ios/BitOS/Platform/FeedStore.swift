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
    /** Video-bearing projection of [notes], derived ONCE per coalesced
     *  publication — Bitz surfaces must not re-filter the 200-note window
     *  on every body evaluation (audit §4). */
    private(set) var videoNotes: [FeedNote] = []
    /** Text-note projection for Home, derived once per coalesced publication. */
    private(set) var textNotes: [FeedNote] = []
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
    private(set) var paginationPrefetchThreshold: Int = 0
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
    /** APP-008 poll tallies per poll note id (latest-vote-per-pubkey rule). */
    private(set) var pollTallies: [String: PollTally] = [:]
    /** pollId → pubkey → latest vote (bounded: 16 polls × MAX_VOTERS). */
    private var pollVoters: [String: [String: PollVote]] = [:]
    private let pollVotes = PollVotes()
    /** Matches shared PollVotes.MAX_VOTERS (kind-1018 fan-in bound). */
    private let pollVotersMax = 200
    private var pollVotesRequested: Set<String> = []
    /** APP-014: verified embedded 9734 request ids per target (paid match). */
    private(set) var zapRequestIds: [String: Set<String>] = [:]
    /** APP-009 live per-note tallies (reactions/reposts/zaps+msat). */
    private(set) var tallies: [String: NoteTallyMirror] = [:]
    private(set) var muted: Set<String> = []
    /** My kind-7 reaction event id per liked note (web `myEventId` parity) —
     *  the kind-5 unlike target. */
    private(set) var myReactionEventIds: [String: String] = [:]
    /** Web feedPreferences parity: protocol-payload notes stay hidden until
     *  the reader opts in (Settings → Feed → Protocol notes). */
    private(set) var showProtocolNotes = false

    /// Blocked authors (NIP-51 kind-10004 head) — filtered like mutes.
    private(set) var blocked: Set<String> = []
    private var blockHeadAt: Int64?

    private let pool: RelayPool
    private let client: any BusinessCoreClient
    private let eventStore: EventStore?
    /** Local ranking signals (hide + author/tag demotions). */
    private let interaction: InteractionProfileStore
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
    private var subscriptionCounter = 0
    /// The persistent head first supplies a bounded snapshot; after EOSE (or
    /// deadline), its frames become pending arrivals rather than list inserts.
    private var headExpectedRelays: Set<RelayURL> = []
    private var headEoseRelays: Set<RelayURL> = []
    private var initialSnapshotComplete = false
    /// True while the head page is collecting its EOSE snapshot — its
    /// frames publish once as ONE batch when the page completes.
    private var headBatchPublishing = false
    private var headSnapshotDeadline: Task<Void, Never>?
    private var profileQueue: [String] = []
    private var requestedProfiles: Set<String> = []
    private var profileTimestamps: [String: Int64] = [:]
    private var profileDrainTask: Task<Void, Never>?
    private var profileFallbackTasks: [Task<Void, Never>] = []
    private var profileRequestCounter = 0
    private var richTokensCache: [String: String] = [:]
    // (replyCount, assembled) per rootId — skips bridge when thread size unchanged.
    private var assembledThreadsCache: [String: (Int, [ThreadDisplayItem])] = [:]

    init(pool: RelayPool, client: any BusinessCoreClient, eventStore: EventStore? = nil,
         interaction: InteractionProfileStore = InteractionProfileStore()) {
        muted = Set(UserDefaults.standard.stringArray(forKey: "bitos_mutes") ?? [])
        self.pool = pool
        self.client = client
        self.eventStore = eventStore
        self.interaction = interaction
        paginationPrefetchThreshold = client.bitzWalkPrefetchThreshold()
    }

    func start() async {
        guard collectTask == nil else { return }
        window = window ?? client.makeFeedWindow(maxItems: 200)
        followingWindow = followingWindow ?? client.makeFeedWindow(maxItems: 200)
        hydrateFromCache()
        await pool.start()
        subscribe()
        let stream = await pool.frames()
        // Ingest stage (audit R1/R2): protocol decode + ID/signature
        // verification run OFF the main actor — the Schnorr pair is the
        // burst hot path. The verified value then hops to main-actor
        // absorption; one sequential task preserves frame order, and the
        // shared verify-once cache dedupes the other frame collectors.
        collectTask = Task.detached(priority: .userInitiated) { [weak self, client] in
            for await frame in stream {
                guard let self else { return }
                await self.absorb(Self.ingest(frame, client: client), frame: frame)
            }
        }
        healthTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let health = await self.pool.health()
                self.relayHealth = health
                // Cold-start bootstrap: re-issue lost account heads while
                // unresolved (shared `AccountBootstrap` policy).
                self.reissueUnresolvedAccountHeads(connectedRelays: health.connected)
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
        cancelActiveOlderBatch()
        headSnapshotDeadline?.cancel()
        headSnapshotDeadline = nil
        // A stop mid-head-page must not strand the suppressed per-frame
        // publishes — drop the batch window and flush as final state.
        headBatchPublishing = false
        collectTask?.cancel()
        collectTask = nil
        publishTask?.cancel()
        publishTask = nil
        profileDrainTask?.cancel()
        profileDrainTask = nil
        profileFallbackTasks.forEach { $0.cancel() }
        profileFallbackTasks.removeAll()
        healthTask?.cancel()
        healthTask = nil
        retryTask?.cancel()
        retryTask = nil
        Task { await pool.broadcast(self.client.close(subscriptionId: self.currentSubscriptionId)) }
        // Flush any coalesced publication + persistence so stopped state is
        // final (a pending tick would otherwise be cancelled mid-flight).
        publishState()
        flushPendingPersist()
    }

    func selectTimeline(_ timeline: FeedTimeline) {
        self.timeline = timeline
        heldTimelines.remove(timeline)
        _ = drainPending(for: timeline)
        syncPaginationState()
        publishState()
    }

    // MARK: - APP-004: content filter + new-notes hold/reveal

    func selectFilter(_ ordinal: Int) {
        filterOrdinal = ordinal
        publishState()
    }

    /// While the reader is away from the head, preserve its scroll anchor.
    /// Returning to the head silently merges the bounded arrival buffer.
    func holdNewNotes(_ hold: Bool) {
        let changed: Bool
        let drained: Bool
        if hold {
            changed = heldTimelines.insert(timeline).inserted
            drained = false
        } else {
            changed = heldTimelines.remove(timeline) != nil
            drained = drainPending(for: timeline)
        }
        if changed || drained { publishState() }
    }

    private func drainPending(for timeline: FeedTimeline) -> Bool {
        if timeline == .following {
            guard !pendingFollowing.isEmpty else { return false }
            for note in pendingFollowing { followingWindow?.insert(note) }
            pendingFollowing.removeAll()
        } else {
            guard !pendingForYou.isEmpty else { return false }
            for note in pendingForYou { window?.insert(note) }
            pendingForYou.removeAll()
        }
        return true
    }

    /// APP-005: NIP-27 rich-content tokens (bridge seam; JSON shape locked
    /// by shared `Nip27Test`). Bounded by note immutability: content never
    /// changes, so the cache key is the content string itself.
    func richTokens(for content: String) -> String {
        if let cached = richTokensCache[content] { return cached }
        let result = bridgeFacade().richTokens(content: content)
        if richTokensCache.count > 300 { richTokensCache.removeValue(forKey: richTokensCache.keys.first!) }
        richTokensCache[content] = result
        return result
    }

    func refresh() {
        // Fresh subscription re-opens the timeline head; older pages may
        // exist again after new arrivals push the window deeper.
        isLoading = !hasLoadedAnyEvent
        resetOlderLanes()
        heldTimelines.remove(timeline)
        _ = drainPending(for: timeline)
        // Flush notes absorbed mid-walk whose per-note publish was
        // suppressed when the batch was cancelled — must precede
        // subscribe(), which re-opens the head snapshot. The persist flush
        // rides the same rule.
        publishState()
        flushPendingPersist()
        subscribe()
    }

    /// Local-only state changed (interaction profile, settings): republish
    /// projections without touching the relay subscriptions.
    func republish() {
        publishState()
    }

    /// APP-004: manual retry from the relay-error / empty state — resets the
    /// backoff so the next auto-retry is 2 s away, then re-issues the REQ.
    func retryNow() {
        retryAttempt = 0
        resetOlderLanes()
        // Same mid-walk flush as refresh(): cancelled batches must not
        // strand suppressed publishes.
        publishState()
        flushPendingPersist()
        subscribe()
    }

    /**
     * APP-004 pagination (Flutter `_fetchReels` parity, shared
     * `BitzTimelinePolicy` rules through the bridge): one "load more" is a
     * bounded backwards `until`-walk whose page budget counts only FRESH
     * playable notes — a relay page of duplicates/text must not strand the
     * surface at a short list. Pages that land ids but nothing playable
     * auto-continue (≤ 6 batches × 4 s each); two truly-empty pages or a
     * relay ignoring `until` exhaust the walk until the next refresh. The
     * Following tab walks its OWN window — the global cursor is not its
     * boundary (root cause of "load more does nothing" on that tab).
     */
    func loadOlder() {
        guard loadingOlderTimeline == nil else { return }
        var lane = olderLanes[timeline] ?? OlderLane()
        guard !lane.exhausted else { return }
        guard let source = olderSourceWindow(for: timeline) else { return }
        let snapshot = source.snapshot()
        guard !snapshot.isEmpty else { return }
        // A full window is NOT timeline exhaustion — the walk pages backward
        // through it (older pages evict at the head via insertOlder). The
        // cap-as-exhaustion check here permanently dead-ended Home as soon
        // as the window filled (cache hydrate + head page = 200), the
        // "cannot load more / You're all caught up" trap. True exhaustion
        // remains the two-empty-pages / relay-stall rules below.
        guard let oldest = snapshot.map(\.createdAt).min() else { return }
        if lane.anchorSeconds != oldest {
            lane.anchorSeconds = oldest
            lane.cursorSeconds = max(0, oldest - 1)
        }
        let cursor = lane.cursorSeconds ?? max(0, oldest - 1)
        olderLanes[timeline] = lane
        loadingOlderTimeline = timeline
        syncPaginationState()
        walkOlder(timeline: timeline, cursor: cursor, batches: 0,
                  budget: client.bitzWalkPageBudget(), emptyAttempts: 0)
    }

    /// Per-tab pagination source: Following walks the follows window.
    private func olderSourceWindow(for timeline: FeedTimeline) -> (any FeedWindowing)? {
        timeline == .following ? followingWindow : window
    }

    private struct OlderLane {
        var anchorSeconds: Int64?
        var cursorSeconds: Int64?
        var exhausted = false
    }

    /** Exact results for one REQ, merged and deduped across parallel relays. */
    private final class OlderBatch {
        let timeline: FeedTimeline
        let subId: String
        let cursor: Int64
        let batches: Int
        let budget: Int
        let emptyAttempts: Int
        let expectedRelays: Set<RelayURL>
        let knownBefore: Set<String>
        var returnedIds: Set<String> = []
        var freshIds: Set<String> = []
        var freshPlayableIds: Set<String> = []
        var eoseRelays: Set<RelayURL> = []
        var oldestInBatch: Int64?
        var timeoutTask: Task<Void, Never>?

        init(timeline: FeedTimeline, subId: String, cursor: Int64, batches: Int,
             budget: Int, emptyAttempts: Int, expectedRelays: Set<RelayURL>,
             knownBefore: Set<String>) {
            self.timeline = timeline
            self.subId = subId
            self.cursor = cursor
            self.batches = batches
            self.budget = budget
            self.emptyAttempts = emptyAttempts
            self.expectedRelays = expectedRelays
            self.knownBefore = knownBefore
        }
    }

    /// Walk bookkeeping is independent for For You and Following.
    private var olderLanes: [FeedTimeline: OlderLane] = [
        .forYou: OlderLane(),
        .following: OlderLane(),
    ]
    private var loadingOlderTimeline: FeedTimeline?
    private var activeOlderBatch: OlderBatch?

    private func walkOlder(timeline: FeedTimeline, cursor: Int64, batches: Int,
                           budget: Int, emptyAttempts: Int) {
        guard batches < Self.walkMaxBatches else {
            finishOlderWalk(timeline)
            return
        }
        olderCounter += 1
        let subId = "bitos-older-\(olderCounter)"
        // Freshness compares only against the CURRENT lane window (+ its
        // held arrivals): relays re-send ids the app has ever seen, and ids
        // evicted from the bounded window are gone from the reader's world.
        // The ever-growing knownNoteIds set would mark every re-fetch
        // "known" and the walk would strand as "load more does nothing".
        let knownBefore = Set(olderSourceWindow(for: timeline)?.snapshot().map(\.id) ?? [])
            .union(pendingForYou.map(\.id))
            .union(pendingFollowing.map(\.id))
        Task { [weak self, pool] in
            let expectedRelays = await pool.connectedRelays()
            guard let self, self.loadingOlderTimeline == timeline else { return }
            let batch = OlderBatch(
                timeline: timeline, subId: subId, cursor: cursor, batches: batches,
                budget: budget, emptyAttempts: emptyAttempts,
                expectedRelays: expectedRelays, knownBefore: knownBefore
            )
            self.activeOlderBatch = batch
            batch.timeoutTask = Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(Self.walkPageMaxWaitMs))
                guard !Task.isCancelled else { return }
                self?.completeOlderBatch(subscriptionId: subId)
            }
            let request = self.client.olderFeedRequest(subscriptionId: subId, until: cursor, limit: 60)
            await pool.broadcast(request)
        }
    }

    private func recordOlderEvent(subscriptionId: String?, event: VerifiedEvent, note: FeedNote) {
        guard let subscriptionId, let batch = activeOlderBatch,
              batch.subId == subscriptionId else { return }
        batch.returnedIds.insert(note.id)
        batch.oldestInBatch = min(batch.oldestInBatch ?? event.createdAt, event.createdAt)
        if !batch.knownBefore.contains(note.id), batch.freshIds.insert(note.id).inserted,
           note.video != nil,
           batch.timeline == .forYou || followingAuthors.contains(event.pubkey) {
            batch.freshPlayableIds.insert(note.id)
        }
    }

    private func recordOlderEose(subscriptionId: String, relay: RelayURL) {
        guard let batch = activeOlderBatch, batch.subId == subscriptionId else { return }
        batch.eoseRelays.insert(relay)
        if !batch.expectedRelays.isEmpty, batch.eoseRelays.isSuperset(of: batch.expectedRelays) {
            completeOlderBatch(subscriptionId: subscriptionId)
        }
    }

    /** Completes one exact subscription batch on all-EOSE or its hard timeout. */
    private func completeOlderBatch(subscriptionId: String) {
        guard let batch = activeOlderBatch, batch.subId == subscriptionId else { return }
        activeOlderBatch = nil
        batch.timeoutTask?.cancel()
        Task { [pool, client] in await pool.broadcast(client.close(subscriptionId: batch.subId)) }
        guard loadingOlderTimeline == batch.timeline else { return }

        let freshCount = batch.freshIds.count
        let confirmedEmpty = !batch.expectedRelays.isEmpty &&
            batch.eoseRelays.isSuperset(of: batch.expectedRelays)
        let stalled = !batch.returnedIds.isEmpty && freshCount == 0 &&
            (batch.oldestInBatch == nil || batch.oldestInBatch! >= batch.cursor)
        if stalled {
            var lane = olderLanes[batch.timeline] ?? OlderLane()
            lane.exhausted = true
            olderLanes[batch.timeline] = lane
            finishOlderWalk(batch.timeline)
            return
        }
        // A timeout means the relay is slow or unavailable, not that its
        // history ended. Keep the lane retryable for the next edge trigger;
        // only a completed all-relay EOSE empty counts toward exhaustion.
        if batch.returnedIds.isEmpty && !confirmedEmpty {
            finishOlderWalk(batch.timeline)
            return
        }
        let nextEmptyAttempts = batch.returnedIds.isEmpty ? batch.emptyAttempts + 1 : 0
        if nextEmptyAttempts >= 2 {
            var lane = olderLanes[batch.timeline] ?? OlderLane()
            lane.exhausted = true
            olderLanes[batch.timeline] = lane
            finishOlderWalk(batch.timeline)
            return
        }
        let oldestCursor = batch.oldestInBatch.map { max(0, $0 - 1) } ?? batch.cursor
        let nextCursor = min(batch.cursor, oldestCursor)
        var lane = olderLanes[batch.timeline] ?? OlderLane()
        lane.cursorSeconds = nextCursor
        olderLanes[batch.timeline] = lane
        let remaining = max(0, batch.budget - batch.freshPlayableIds.count)
        if remaining == 0 || batch.batches + 1 >= Self.walkMaxBatches {
            finishOlderWalk(batch.timeline)
            return
        }
        walkOlder(timeline: batch.timeline, cursor: nextCursor,
                  batches: batch.batches + 1, budget: remaining,
                  emptyAttempts: nextEmptyAttempts)
        // The walk continues, but this relay batch is DONE — publish it now
        // (Flutter appends each completed EOSE batch) instead of holding the
        // page hidden until the whole multi-batch walk ends.
        schedulePublish()
    }

    private func finishOlderWalk(_ timeline: FeedTimeline) {
        if loadingOlderTimeline == timeline { loadingOlderTimeline = nil }
        syncPaginationState()
        publishState()
    }

    private func resetOlderLanes() {
        cancelActiveOlderBatch()
        olderLanes = [.forYou: OlderLane(), .following: OlderLane()]
        loadingOlderTimeline = nil
        syncPaginationState()
    }

    private func cancelActiveOlderBatch() {
        guard let batch = activeOlderBatch else { return }
        activeOlderBatch = nil
        batch.timeoutTask?.cancel()
        Task { [pool, client] in await pool.broadcast(client.close(subscriptionId: batch.subId)) }
    }

    private func syncPaginationState() {
        isLoadingOlder = loadingOlderTimeline == timeline
        noMoreOlder = olderLanes[timeline]?.exhausted ?? false
    }

    // MARK: - Absorption
    //
    // Ingest runs off the main actor (see start()); absorption below runs
    // on the main actor with the already-verified event. Relay bursts are
    // coalesced: absorption mutates state per frame, the UI projection
    // (window snapshot, filter, ranking, thread assembly) publishes once
    // per [publishCoalesceMs] window (audit R3). User-intent paths still
    // publish synchronously through publishState().

    /// One relay frame after the protocol trust gate, decoded off-main.
    private enum IngestedFrame {
        case eose(subscriptionId: String)
        case event(VerifiedEvent)
        case ignored
    }

    /// Protocol gate for one frame: EOSE ids pass through; EVENT frames
    /// must decode and pass ID + BIP-340 verification (shared core, whose
    /// outcome cache dedupes verification across every store).
    private nonisolated static func ingest(_ frame: RelayFrame, client: any BusinessCoreClient) -> IngestedFrame {
        if let subId = client.relayEoseSubscriptionId(message: frame.message) {
            return .eose(subscriptionId: subId)
        }
        // Phase 0 signpost: the frame trust gate (JSON + SHA-256 ID +
        // BIP-340) off the main actor. Counters only — never content.
        let signpost = Perf.signposter.beginInterval(Perf.Interval.relayDecode)
        let event = client.decodeVerifiedEvent(message: frame.message, relay: frame.relay.rawValue)
        Perf.signposter.endInterval(Perf.Interval.relayDecode, signpost)
        if let event { return .event(event) }
        return .ignored
    }

    private func absorb(_ ingested: IngestedFrame, frame: RelayFrame) {
        switch ingested {
        case .eose(let subId):
            recordOlderEose(subscriptionId: subId, relay: frame.relay)
            recordHeadEose(subscriptionId: subId, relay: frame.relay)
            return
        case .ignored:
            return
        case .event(let event):
            absorbVerified(event, frame: frame)
        }
    }

    private func absorbVerified(_ event: VerifiedEvent, frame: RelayFrame) {
        // Older-page feed frames publish once per completed walk (batch
        // parity with Flutter appending the whole EOSE page at once); late
        // frames after the walk ended and every other kind still publish.
        var suppressPublish = false
        if event.kind == 7 || event.kind == 6 {
            // APP-009: live tallies per thread note (root or reply).
            let eTagged = event.tags.filter { $0.first == "e" }.compactMap { $0.dropFirst().first }
            if let target = eTagged.lazy.compactMap({ self.tallyTarget(for: $0) }).first {
                var tally = talliesBuffer[target] ?? NoteTallyMirror()
                if event.kind == 7 { tally.reactions += 1 }
                else { tally.reposts += 1 }
                talliesBuffer[target] = tally
            }
            // Web `myEventId` parity: remember MY reaction event per note so
            // an unlike can publish its kind-5 deletion.
            if event.kind == 7, event.pubkey == accountPubkey, let target = eTagged.first {
                myReactionEventIds[target] = event.id
            }
        } else if event.kind == 3 {
            absorbContactList(frame, event: event)
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
            absorbBookmarkList(frame, event: event)
        } else if event.kind == 10004 {
            absorbBlockList(frame, event: event)
        } else if client.isProfileKind(event.kind) {
            absorbProfile(event)
            // DAT-003: kind-0 heads persist so the You page (and every
            // author card) hydrates from cache on the next cold start.
            persist(event)
        } else if event.kind == 1111 {
            // NIP-22 comments (ADR-003): kind-1111 projects into the comment
            // thread only — never the feed windows.
            let note = client.feedNote(from: event)
            if let parent = note.replyTo, var thread = commentThreads[parent] {
                thread.append(note)
                if thread.count > 100 { thread.removeFirst(thread.count - 100) }
                commentThreads[parent] = thread
            } else if let root = note.threadRootId, var thread = commentThreads[root] {
                thread.append(note)
                if thread.count > 100 { thread.removeFirst(thread.count - 100) }
                commentThreads[root] = thread
            }
        } else if event.kind == 1018 {
            // APP-008 poll votes: kind-1018 tallies per poll note
            // (shared latest-vote-per-pubkey rule).
            absorbPollVote(event)
        } else if client.isFeedKind(event.kind) {
            let subscriptionId = bridgeFacade().relayEventSubscriptionId(message: frame.message)
            let fromOlderPage = subscriptionId?
                .hasPrefix("bitos-older-") == true
            let note = client.feedNote(from: event)
            recordOlderEvent(subscriptionId: subscriptionId, event: event, note: note)
            absorbNote(event, fromOlderPage: fromOlderPage)
            persist(event)
            // Relay PAGE boundaries own the publishes: older-page frames
            // publish once per completed walk; the INITIAL head page holds
            // its frames until the page EOSE / snapshot deadline (batch
            // parity with Flutter appending whole pages — [10, 10, …]).
            if (fromOlderPage && loadingOlderTimeline != nil) ||
                (!fromOlderPage && headBatchPublishing) {
                suppressPublish = true
            }
        }
        if !suppressPublish { schedulePublish() }
    }

    private func absorbNote(_ event: VerifiedEvent, fromOlderPage: Bool = false) {
        let note = client.feedNote(from: event)
        if !knownNoteIds.contains(note.id) {
            knownNoteIds.insert(note.id)
            if !fromOlderPage {
                headWatermarkSeconds = max(headWatermarkSeconds ?? event.createdAt, event.createdAt)
            }
            let holdLiveArrival = !fromOlderPage && initialSnapshotComplete &&
                (window?.count() ?? 0) > 0 && heldTimelines.contains(.forYou)
            if holdLiveArrival {
                pendingForYou.append(note)
                if pendingForYou.count > Self.pendingMax {
                    pendingForYou.removeFirst(pendingForYou.count - Self.pendingMax)
                }
            } else if fromOlderPage {
                // Older pages extend the window at its head boundary
                // (newest evicted) — tail eviction at a full window would
                // drop the just-landed older note itself.
                window?.insertOlder(note)
            } else {
                window?.insert(note)
            }
            if followingAuthors.contains(event.pubkey) {
                if holdLiveArrival && heldTimelines.contains(.following) && (followingWindow?.count() ?? 0) > 0 {
                    pendingFollowing.append(note)
                    if pendingFollowing.count > Self.pendingMax {
                        pendingFollowing.removeFirst(pendingFollowing.count - Self.pendingMax)
                    }
                } else if fromOlderPage {
                    followingWindow?.insertOlder(note)
                } else {
                    followingWindow?.insert(note)
                }
            }
        }
        // Arrival keys on the direct parent; a nested reply whose parent
        // comment has no open thread falls back to its root tag so the
        // thread the reader opened still receives it (NIP-10 + NIP-22).
        if let parent = note.replyTo, var thread = commentThreads[parent] {
            thread.append(note)
            if thread.count > 100 { thread.removeFirst(thread.count - 100) }
            commentThreads[parent] = thread
        } else if let root = note.threadRootId, var thread = commentThreads[root] {
            thread.append(note)
            if thread.count > 100 { thread.removeFirst(thread.count - 100) }
            commentThreads[root] = thread
        }
        enqueueProfile(event.pubkey)
        // Web parity: mentioned pubkeys' profiles resolve for @display.
        if let data = self.richTokens(for: note.content).data(using: .utf8),
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

    /**
     * APP-018a row 5 (legacy parity): wipes the session's DERIVED feed
     * state — profiles, thread/comment views, tallies, bookmark/block
     * projections and buffers. Live subscriptions re-fetch all of it; user
     * preferences (mutes) and the feed windows stay. The persisted event
     * cache equivalent (Android `EventCache.clearAllCache`) arrives with
     * DAT-003 on iOS.
     */
    func clearDerivedState() {
        profiles = [:]
        comments = [:]
        threads = [:]
        commentThreads = [:]
        tallies = [:]
        talliesBuffer = [:]
        tallyTargets = []
        zapCountsBuffer = [:]
        zapRequestIdsBuffer = [:]
        remixAncestorEvents = [:]
        bookmarked = []
        blocked = []
        blockHeadAt = nil
        // Heads must re-resolve: re-arm the bootstrap for them.
        accountHeadAttempts = [0, 0, 0, 0]
        bookmarkHeadReceived = false
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

    private func absorbBookmarkList(_ frame: RelayFrame, event: VerifiedEvent) {
        guard let account = accountPubkey, event.pubkey == account else { return }
        // The account's bookmark head resolved — the bootstrap stops re-asking.
        bookmarkHeadReceived = true
        let ids = (bridgeFacade().bookmarkIds(message: frame.message, relayUrl: frame.relay.rawValue) as? [String]) ?? []
        // Newer verified heads replace the local set (bounded by composer).
        if event.createdAt >= (bookmarkHeadAt ?? Int64.min) {
            bookmarked = ids
            bookmarkHeadAt = event.createdAt
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

    // MARK: - APP-008 poll votes (web `rebuildPoll` parity)

    /// One-shot REQ for a poll's kind-1018 votes (called when it renders).
    func loadPollVotes(targetEventId: String) {
        guard !pollVotesRequested.contains(targetEventId) else { return }
        pollVotesRequested.insert(targetEventId)
        if pollVoters[targetEventId] == nil { pollVoters[targetEventId] = [:] }
        if pollVoters.count > 16, let oldest = pollVoters.keys.first {
            pollVoters.removeValue(forKey: oldest)
        }
        if let request = (bridgeFacade().pollVotesRequest(
            subscriptionId: "bitos-pollvotes-" + String(targetEventId.prefix(44)),
            targetEventId: targetEventId
        ) as String?) {
            Task { await pool.broadcast(request) }
        }
        publishState()
    }

    /// Optimistic local vote before the kind-1018 relay echo lands.
    func applyOptimisticPollVote(pollId: String, optionIndex: Int) {
        guard let account = accountPubkey else { return }
        var voters = pollVoters[pollId] ?? [:]
        // Shared latest-vote-per-pubkey rule, applied inline (map bridge).
        voters[account] = PollVote(pubkey: account, optionIndex: Int32(optionIndex), at: Int64(Date.now.timeIntervalSince1970))
        pollVoters[pollId] = voters
        publishState()
    }

    private func absorbPollVote(_ event: VerifiedEvent) {
        guard let target = event.tags.first(where: { $0.first == "e" })?.dropFirst().first,
              let optionText = event.tags.first(where: { $0.first == "response" })?.dropFirst().first,
              let option = Int32(optionText), option >= 0, option <= 255 else { return }
        var voters = pollVoters[target] ?? [:]
        if voters.count >= pollVotersMax && voters[event.pubkey] == nil { return }
        let vote = PollVote(pubkey: event.pubkey, optionIndex: option, at: event.createdAt)
        if let prev = voters[event.pubkey], prev.at >= vote.at { return }
        voters[event.pubkey] = vote
        pollVoters[target] = voters
        if pollVoters.count > 16, let oldest = pollVoters.keys.first {
            pollVoters.removeValue(forKey: oldest)
        }
    }

    /// Muted authors are filtered from all feed windows (device-local).
    func setMuted(_ pubkeys: Set<String>) {
        muted = pubkeys
        publishState()
    }

    /// Web feedPreferences parity: reader opt-in that re-admits
    /// protocol-payload (channel roster) notes into every feed window.
    func setShowProtocolNotes(_ enabled: Bool) {
        guard showProtocolNotes != enabled else { return }
        showProtocolNotes = enabled
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
        // NIP-22 companion REQ: uppercase-`E` rooted comments are invisible
        // to the plain `#e` filter (relay tag filters are case-sensitive).
        if let rootRequest = (bridgeFacade().commentsRootRequest(
            subscriptionId: "bitos-comments-e-" + String(targetEventId.prefix(44)),
            targetEventId: targetEventId
        ) as String?) {
            Task { await pool.broadcast(rootRequest) }
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
        // Shared `AccountBootstrap`: a new account episode re-arms the head
        // re-issue budget and the received flags.
        accountHeadAttempts = [0, 0, 0, 0]
        bookmarkHeadReceived = false
        lastConnectedRelays = 0
        if let pubkey { requestAccountHeads(for: pubkey) }
        bookmarked.removeAll()
        blocked.removeAll()
        blockHeadAt = nil
        publishState()
    }

    // MARK: - Cold-start account bootstrap (shared `AccountBootstrap`)
    //
    // The account heads (own kind-0, kind-3 contacts, 30003 bookmarks,
    // 10004 blocks) are one-shot REQs; a cold start composes them before
    // any relay socket opened and the send is silently dropped. The health
    // poll drives a bounded re-issue while a head is unresolved.

    /// Indexes into `accountHeadAttempts`.
    private enum AccountHead { static let profile = 0; static let contacts = 1; static let bookmarks = 2; static let blocks = 3 }
    private var accountHeadAttempts: [Int] = [0, 0, 0, 0]
    private var bookmarkHeadReceived = false
    private var lastConnectedRelays = 0

    private func requestAccountHeads(for pubkey: String) {
        // The signed-in profile is not necessarily an author in the feed.
        // Request its kind-0 head explicitly so the You surface has a fresh
        // projection on a cold start and after account switching.
        requestProfile(pubkey, force: true)
        if let request = (bridgeFacade().contactListRequest(subscriptionId: "bitos-contacts", accountPubkey: pubkey) as String?) {
            Task { await pool.broadcast(request) }
        }
        if let request = (bridgeFacade().bookmarkListRequest(subscriptionId: "bitos-bookmarks", accountPubkey: pubkey) as String?) {
            Task { await pool.broadcast(request) }
        }
        if let request = (bridgeFacade().encodeBlockListRequest(subscriptionId: "bitos-blocks", accountPubkey: pubkey) as String?) {
            Task { await pool.broadcast(request) }
        }
    }

    /// Called from the health poll with the current connected-relay count.
    private func reissueUnresolvedAccountHeads(connectedRelays: Int) {
        if client.accountBootstrapShouldOpenEpisode(previousConnected: lastConnectedRelays, currentConnected: connectedRelays) {
            accountHeadAttempts = [0, 0, 0, 0]
        }
        lastConnectedRelays = connectedRelays
        guard let pubkey = accountPubkey else { return }
        func should(_ head: Int, resolved: Bool) -> Bool {
            client.accountBootstrapShouldReissue(resolved: resolved, attempts: accountHeadAttempts[head], connectedRelays: connectedRelays)
        }
        if should(AccountHead.profile, resolved: profiles[pubkey] != nil) {
            accountHeadAttempts[AccountHead.profile] += 1
            requestProfile(pubkey, force: true)
        }
        if should(AccountHead.contacts, resolved: followingResolved) {
            accountHeadAttempts[AccountHead.contacts] += 1
            if let request = (bridgeFacade().contactListRequest(subscriptionId: "bitos-contacts", accountPubkey: pubkey) as String?) {
                Task { await pool.broadcast(request) }
            }
        }
        if should(AccountHead.bookmarks, resolved: bookmarkHeadReceived) {
            accountHeadAttempts[AccountHead.bookmarks] += 1
            if let request = (bridgeFacade().bookmarkListRequest(subscriptionId: "bitos-bookmarks", accountPubkey: pubkey) as String?) {
                Task { await pool.broadcast(request) }
            }
        }
        if should(AccountHead.blocks, resolved: blockHeadAt != nil) {
            accountHeadAttempts[AccountHead.blocks] += 1
            if let request = (bridgeFacade().encodeBlockListRequest(subscriptionId: "bitos-blocks", accountPubkey: pubkey) as String?) {
                Task { await pool.broadcast(request) }
            }
        }
    }

    private func absorbContactList(_ frame: RelayFrame, event: VerifiedEvent) {
        guard let account = accountPubkey else { return }
        // Only the account's own contact list drives the timeline.
        // (Ingest verified the frame; the author check filters.)
        guard event.pubkey == account,
              let authors = (bridgeFacade().contactListAuthors(message: frame.message, relayUrl: frame.relay.rawValue) as? [String]) else { return }
        followingAuthors = Set(authors)
        followingAuthors.forEach { enqueueProfile($0) }
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

    /// First call resolves the framework bridge (following-capable when the
    /// framework client is present); later calls reuse it. Manual memo —
    /// `lazy` is not representable under the @Observable macro.
    private var cachedBridge: BusinessCoreBridge?
    private func bridgeFacade() -> BusinessCoreBridge {
        if let cachedBridge { return cachedBridge }
        let bridge = (client as? FrameworkBusinessCoreClient)?.bridgeForFollowing() ?? BusinessCoreBridge()
        cachedBridge = bridge
        return bridge
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

    /// Requests a profile head for a native surface outside the notes feed.
    func requestProfile(_ pubkey: String, force: Bool = false) {
        guard !pubkey.isEmpty else { return }
        if !force {
            enqueueProfile(pubkey)
            return
        }
        profileRequestCounter += 1
        let request = client.profileRequest(
            subscriptionId: "bitos-profile-head-\(profileRequestCounter)", authors: [pubkey]
        )
        Task { await pool.broadcast(request) }
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
        let subscriptionId = currentSubscriptionId
        initialSnapshotComplete = false
        // UX-UI batch paint: the head page is ONE relay page — its frames
        // publish ONCE at the page EOSE (or the snapshot deadline below),
        // so a 10-item page renders 10 tiles at once and later pages
        // append [10, 10, …] instead of a 1,2,3 drip.
        headBatchPublishing = true
        headExpectedRelays.removeAll()
        headEoseRelays.removeAll()
        headSnapshotDeadline?.cancel()
        headSnapshotDeadline = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(Self.headSnapshotMaxWaitMs))
            guard !Task.isCancelled else { return }
            self?.completeInitialSnapshot(subscriptionId: subscriptionId)
        }
        let request: String
        if let watermark = headWatermarkSeconds {
            // Nostr time is second-granular. Re-fetch the boundary second and
            // rely on canonical event-id de-duplication for a gap-free head.
            request = client.feedRequestSince(subscriptionId: subscriptionId, since: max(0, watermark - 1))
        } else {
            request = client.feedRequest(subscriptionId: subscriptionId)
        }
        Task { [weak self, pool] in
            let expectedRelays = await pool.connectedRelays()
            guard let self, self.currentSubscriptionId == subscriptionId else { return }
            self.headExpectedRelays = expectedRelays
            await pool.broadcast(request)
        }
    }

    private var currentSubscriptionId: String { "bitos-feed-\(subscriptionCounter)" }

    private func recordHeadEose(subscriptionId: String, relay: RelayURL) {
        guard subscriptionId == currentSubscriptionId else { return }
        headEoseRelays.insert(relay)
        if !headExpectedRelays.isEmpty && headEoseRelays.isSuperset(of: headExpectedRelays) {
            completeInitialSnapshot(subscriptionId: subscriptionId)
        }
    }

    private func completeInitialSnapshot(subscriptionId: String) {
        guard subscriptionId == currentSubscriptionId, !initialSnapshotComplete else { return }
        initialSnapshotComplete = true
        headSnapshotDeadline?.cancel()
        headSnapshotDeadline = nil
        // The head page is complete — flush it as ONE projection (the
        // frames' per-event publishes were suppressed for this window).
        // flushPendingPersist matters as much: the suppressed publishes
        // were ALSO the cache-flush driver — without this the page's
        // events never reach the local cache.
        if headBatchPublishing {
            headBatchPublishing = false
            publishState()
            flushPendingPersist()
        }
    }

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
        func stringSetJson(_ set: Set<String>) -> String {
            "[" + set.map { "\"\($0)\"" }.joined(separator: ",") + "]"
        }
        guard let ids = bridgeFacade().algorithmRankIds(
            notesJson: "[\(rows.joined(separator: ","))]",
            surfaceWire: "feed",
            snapshotJson: algoJson,
            followingJson: "[\(following.joined(separator: ","))]",
            zapCountsJson: "{\(zaps.joined(separator: ","))}",
            replyCountsJson: "{\(replies.joined(separator: ","))}",
            nowSeconds: Int64(Date.now.timeIntervalSince1970),
            dismissedNoteIdsJson: stringSetJson(interaction.dismissedNotes),
            mutedAuthorsJson: stringSetJson(interaction.demotedAuthors),
            mutedTagsJson: stringSetJson(interaction.demotedTags)
        ) as? [String], !ids.isEmpty else {
            return base
        }
        let byId = Dictionary(base.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return ids.compactMap { byId[$0] }
    }

    private func publishState() {
        // Phase 0 signpost: one coalesced UI projection (window snapshot →
        // filter → rank → thread assembly).
        let signpost = Perf.signposter.beginInterval(Perf.Interval.feedPublish)
        defer { Perf.signposter.endInterval(Perf.Interval.feedPublish, signpost) }
        let hiddenSet = muted.union(blocked)
        let filterOrdinal = filterOrdinal
        let ownPubkey = accountPubkey
        let liked = Array(localActions.liked)
        let protocolNotesVisible = showProtocolNotes
        // Dismissed notes (hide / not-interested) never surface anywhere.
        let dismissed = interaction.dismissedNotes
        let forYouBase = (window?.snapshot() ?? []).filter {
            !hiddenSet.contains($0.pubkey) && !dismissed.contains($0.id) && (protocolNotesVisible || !$0.isProtocolPayload)
        }
        let followingBase = (followingWindow?.snapshot() ?? []).filter {
            !hiddenSet.contains($0.pubkey) && !dismissed.contains($0.id) && (protocolNotesVisible || !$0.isProtocolPayload)
        }
        forYouCount = forYouBase.count
        followingCount = followingBase.count
        notes = (timeline == .following ? followingBase : rankedForYou(forYouBase)).filter {
            client.feedFilterMatches(note: $0, filterOrdinal: filterOrdinal, ownPubkeyHex: ownPubkey, likedIds: liked, showProtocolNotes: protocolNotesVisible)
        }
        videoNotes = notes.filter { $0.video != nil }
        textNotes = notes.filter { $0.video == nil }
        comments = commentThreads
        var assembled: [String: [ThreadDisplayItem]] = [:]
        for (rootId, replies) in commentThreads {
            let count = replies.count
            if let (cachedCount, cachedItems) = assembledThreadsCache[rootId], cachedCount == count {
                assembled[rootId] = cachedItems
            } else {
                let items = threadItems(rootId: rootId, replies: replies)
                assembledThreadsCache[rootId] = (count, items)
                assembled[rootId] = items
            }
        }
        assembledThreadsCache = assembledThreadsCache.filter { commentThreads[$0.key] != nil }
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
        pollTallies = pollVoters.mapValues { voters in
            pollVotes.tally(byPubkey: voters, myPubkey: accountPubkey)
        }
        zapRequestIds = zapRequestIdsBuffer
        tallies = talliesBuffer
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

    /// Buffered persistence: verified events accumulate here and flush as
    /// ONE transaction per coalesced publish tick (or [persistBatchMax]),
    /// keeping relay bursts off the per-event fsync path (audit R6).
    private var pendingPersist: [StoredEvent] = []

    private func persist(_ event: VerifiedEvent) {
        guard eventStore != nil else { return }
        let tagsJson = client.tagsToJson(event.tags)
        let stored = StoredEvent(
            id: event.id, pubkey: event.pubkey, createdAt: event.createdAt,
            kind: event.kind, tagsJson: tagsJson, content: event.content,
            signature: event.signature, relayUrl: event.relayUrl, firstSeenAt: Int64(Date.now.timeIntervalSince1970)
        )
        pendingPersist.append(stored)
        if pendingPersist.count >= Self.persistBatchMax {
            flushPendingPersist()
        }
    }

    /// Swaps the buffer and hands the batch to a utility task; cache
    /// failures never break display.
    private func flushPendingPersist() {
        guard let store = eventStore, !pendingPersist.isEmpty else { return }
        let batch = pendingPersist
        pendingPersist.removeAll()
        Task.detached(priority: .utility) { [store] in
            _ = try? store.insertBatch(batch)
        }
    }

    // MARK: - Coalesced UI publication (audit R3)

    private var publishTask: Task<Void, Never>?

    /// Absorption-heavy paths schedule one publication per
    /// [publishCoalesceMs]; a burst of N events pays one projection instead
    /// of N. Intent-driven paths call publishState() directly for immediate
    /// feedback.
    private func schedulePublish() {
        guard publishTask == nil else { return }
        publishTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(Self.publishCoalesceMs))
            guard let self else { return }
            self.publishTask = nil
            guard !Task.isCancelled else { return }
            self.publishState()
            self.flushPendingPersist()
        }
    }

    private static let profileBatchSize = 48
    private static let healthPollInterval: Duration = .seconds(2)
    private static let pendingMax = 50
    private static let headSnapshotMaxWaitMs = 2_500
    /// Relay-burst publication coalescing window (audit R3).
    private static let publishCoalesceMs = 150
    /// Verified events per one transactional cache flush (audit R6).
    private static let persistBatchMax = 64

    /// FED-004 walk bounds (shared `BitzTimelinePolicy` via the bridge):
    /// ≤ 6 batches per load-more, 4 s hard deadline per batch.
    private static let walkMaxBatches = 6
    private static let walkPageMaxWaitMs = 4_000
    private var knownNoteIds: Set<String> = []
    private var pendingForYou: [FeedNote] = []
    private var pendingFollowing: [FeedNote] = []
    private var heldTimelines: Set<FeedTimeline> = []
    private var headWatermarkSeconds: Int64?
}
