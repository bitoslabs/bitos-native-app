import BusinessCore
import Foundation
import Observation

/// APP-006 stories store (iOS): mirrors the Android repository through the bridge.
@MainActor
@Observable
final class StoriesStore {
    private(set) var authors: [StoryAuthorMirror] = []
    private(set) var publicAuthors: [StoryAuthorMirror] = []
    private(set) var seenIds: Set<String> = []
    private(set) var hasAccount = false
    /// Per-slide engagement for the viewer (slide id → snapshot).
    private(set) var interactions: [String: StoryInteractionMirror] = [:]

    /// The viewer target (tap a bar avatar to open).
    var viewerTarget: StoryAuthorMirror?

    private let pool: RelayPool
    private let bridge: BusinessCoreBridge
    private var accountPubkey: String?
    private var followingPubkeys: Set<String> = []
    private var watchTask: Task<Void, Never>?
    private var activityTask: Task<Void, Never>?
    private var requested = false
    private var slidesById: [String: StorySlideMirror] = [:]
    private var publicSlidesById: [String: StorySlideMirror] = [:]
    private var deletedIds: Set<String> = []

    // Engagement lanes (web `stories` parity): latest reaction per pubkey,
    // replies by event id, zap receipts by event id; the FIFO seen list
    // dedupes relay replays and is trimmed to stay bounded.
    private struct ReactionRow: Equatable {
        var emoji: String
        var at: Int64
        var eventId: String
    }

    private var reactionsBySlide: [String: [String: ReactionRow]] = [:]
    private var repliesBySlide: [String: [String: StoryActivityMirror]] = [:]
    private var zapsBySlide: [String: [String: Int64]] = [:]
    private var activitySeen: [String] = []
    private var activitySeenSet: Set<String> = []

    private static let seenKey = "bitos_story_seen_ids"

    /// One classified engagement event (web `StoryActivityEvent` parity).
    struct StoryActivityMirror: Equatable, Sendable {
        let type: String // like | view | reply | zap
        let pubkey: String
        let emoji: String
        let text: String
        let sats: Int64
        let at: Int64
        let eventId: String
    }

    /// Aggregated engagement for one slide (web `StoryInteraction` parity).
    struct StoryInteractionMirror: Equatable, Sendable {
        var likeCount = 0
        var viewCount = 0
        var replyCount = 0
        var zapCount = 0
        var zapSats: Int64 = 0
        var likedByMe = false
        var myLikeEventId: String?
        var likes: [StoryActivityMirror] = []
        var replies: [StoryActivityMirror] = []
    }

    struct StorySlideMirror: Identifiable, Equatable, Sendable {
        let id: String
        let pubkey: String
        let content: String
        let createdAt: Int64
        let expiresAt: Int64
        let d: String?
        let imageUrl: String?
        let imageUrls: [String]
        let videoUrl: String?
        let videoPoster: String?
        let videoDurationMs: Int64?
        let sensitive: Bool
        let gradient: String?
        let pow: Int?

        func isExpired(now: Int64) -> Bool { expiresAt <= now }
    }

    struct StoryAuthorMirror: Identifiable, Equatable, Sendable {
        let pubkey: String
        let slides: [StorySlideMirror]
        let isPublicDiscovery: Bool
        var id: String { pubkey }
        var latestAt: Int64 { slides.first?.createdAt ?? 0 }
        var hasUnseen: Bool { true } // computed at render with seenIds
    }

    init(pool: RelayPool, bridge: BusinessCoreBridge = BusinessCoreBridge()) {
        self.pool = pool
        self.bridge = bridge
        seenIds = Set(UserDefaults.standard.stringArray(forKey: Self.seenKey) ?? [])
    }

    func setAccount(_ pubkey: String?, following: Set<String>) {
        guard accountPubkey != pubkey || followingPubkeys != following else { return }
        accountPubkey = pubkey
        followingPubkeys = following
        requested = false
        slidesById.removeAll()
        publicSlidesById.removeAll()
        reactionsBySlide.removeAll()
        repliesBySlide.removeAll()
        zapsBySlide.removeAll()
        activitySeen.removeAll()
        activitySeenSet.removeAll()
        hasAccount = pubkey != nil
        watchTask?.cancel()
        watchTask = nil
        rebuild()
        Task { await start() }
    }

    func markSeen(_ slideId: String) {
        seenIds.insert(slideId)
        UserDefaults.standard.set(Array(seenIds.suffix(1000)), forKey: Self.seenKey)
    }

    func openViewer(_ author: StoryAuthorMirror?) {
        viewerTarget = author
    }

    /// Engagement REQ for the viewer's slides (web `loadActivity` parity).
    func loadActivity(_ slides: [StorySlideMirror]) {
        let ids = slides.map(\.id)
        let addresses = slides.compactMap { slide -> String? in
            slide.d.map { "30315:\(slide.pubkey):\($0)=\(slide.id)" }
        }
        guard let request = bridge.storyActivityRequest(
            subscriptionId: "bitos-story-activity", slideIds: ids, addresses: addresses
        ) as String? else { return }
        Task { await pool.broadcast(request) }
    }

    /// Drops a slide locally after publishing a kind-5 deletion of it.
    func removeSlide(_ slideId: String) {
        deletedIds.insert(slideId)
        slidesById.removeValue(forKey: slideId)
        publicSlidesById.removeValue(forKey: slideId)
        rebuild()
    }

    // MARK: - Internals

    private func start() async {
        guard watchTask == nil else { return }
        await pool.start()
        if !requested {
            requested = true
            let authors = Array(([accountPubkey].compactMap { $0 } + Array(followingPubkeys)).prefix(50))
            if !authors.isEmpty,
               let request = (bridge.storiesRequest(subscriptionId: "bitos-stories", authorPubkeys: authors) as String?) {
                Task { await pool.broadcast(request) }
            }
            if let request = bridge.publicStoriesRequest(subscriptionId: "bitos-public-stories") as String? {
                Task { await pool.broadcast(request) }
            }
        }
        let stream = await pool.verifiedFrames(client: FrameworkBusinessCoreClient())
        guard !Task.isCancelled else { return }
        let boxedBridge = StatelessBridge(bridge: bridge)
        watchTask = FrameIngest.pump(
            gated: stream,
            isAlive: { [weak self] in self != nil },
            ingest: Self.storyFromGated(boxedBridge)
        ) { [weak self] slide in
            await self?.absorbSlide(slide)
        }
        guard activityTask == nil else { return }
        // Engagement lane: low-volume kinds (7/1/9735) classify on the main
        // actor against the tracked slide set (web `ingestActivity` parity).
        let activityStream = await pool.verifiedFrames(client: FrameworkBusinessCoreClient())
        activityTask = FrameIngest.pump(
            gated: activityStream,
            isAlive: { [weak self] in self != nil }
        ) { [weak self] gated in
            guard let self, case .event(let gatedEvent) = gated else { return }
            await self.absorbActivity(gatedEvent)
        }
    }

    private func absorbActivity(_ gatedEvent: VerifiedEventFrame) {
        let bridgeEvent = gatedEvent.event.bridgeEvent(bridge: bridge)
        guard [7, 1, 9735].contains(bridgeEvent.kind) else { return }
        let tracked = Array(slidesById.values) + Array(publicSlidesById.values)
        let addresses = tracked.compactMap { slide -> String? in
            slide.d.map { "30315:\(slide.pubkey):\($0)=\(slide.id)" }
        }
        let trackedIds = tracked.map { (slide: StoriesStore.StorySlideMirror) in slide.id }
        guard let dict = bridge.storyInteractionFromEvent(
            event: bridgeEvent, slideIds: trackedIds, addresses: addresses
        ) as? [String: Any],
            let slideId = dict["slideId"] as? String,
            let type = dict["type"] as? String,
            let pubkey = dict["pubkey"] as? String,
            let eventId = dict["eventId"] as? String else { return }
        guard !activitySeenSet.contains(eventId) else { return }
        activitySeenSet.insert(eventId)
        activitySeen.append(eventId)
        if activitySeen.count > 4_000 {
            let evicted = activitySeen.removeFirst()
            activitySeenSet.remove(evicted)
        }
        let at = (dict["at"] as? NSNumber)?.int64Value ?? 0
        let activity = StoryActivityMirror(
            type: type,
            pubkey: pubkey,
            emoji: (dict["emoji"] as? String) ?? "",
            text: (dict["text"] as? String) ?? "",
            sats: (dict["sats"] as? NSNumber)?.int64Value ?? 0,
            at: at,
            eventId: eventId
        )
        switch type {
        case "like", "view":
            var lane = reactionsBySlide[slideId] ?? [:]
            if lane[pubkey] == nil || lane[pubkey]!.at <= at {
                lane[pubkey] = ReactionRow(emoji: activity.emoji, at: at, eventId: eventId)
            }
            reactionsBySlide[slideId] = lane
        case "reply":
            var lane = repliesBySlide[slideId] ?? [:]
            lane[eventId] = activity
            repliesBySlide[slideId] = lane
        case "zap":
            var lane = zapsBySlide[slideId] ?? [:]
            lane[eventId] = activity.sats
            zapsBySlide[slideId] = lane
        default:
            return
        }
        rebuildInteraction(slideId)
    }

    /// Web `buildInteraction` parity for one slide.
    private func rebuildInteraction(_ slideId: String) {
        let reactions = reactionsBySlide[slideId] ?? [:]
        let replyMap = repliesBySlide[slideId] ?? [:]
        let zapMap = zapsBySlide[slideId] ?? [:]
        let likeRows = reactions
            .filter { !Self.isViewEmoji($0.value.emoji) }
            .sorted { $0.value.at > $1.value.at }
        var viewers = Set(reactions.filter { Self.isViewEmoji($0.value.emoji) }.keys)
        let replies = replyMap.values.sorted { $0.at < $1.at }.prefix(100)
        for reply in replies { viewers.insert(reply.pubkey) }
        var snapshot = StoryInteractionMirror()
        snapshot.likeCount = likeRows.count
        snapshot.viewCount = viewers.count
        snapshot.replyCount = replyMap.count
        snapshot.zapCount = zapMap.count
        snapshot.zapSats = zapMap.values.reduce(0, +)
        snapshot.likes = likeRows.prefix(100).map {
            StoryActivityMirror(type: "like", pubkey: $0.key, emoji: $0.value.emoji, text: "", sats: 0, at: $0.value.at, eventId: $0.value.eventId)
        }
        snapshot.replies = Array(replies)
        if let me = accountPubkey, let mine = likeRows.first(where: { $0.key == me }) {
            snapshot.likedByMe = true
            snapshot.myLikeEventId = mine.value.eventId
        }
        interactions[slideId] = snapshot
    }

    private nonisolated static func isViewEmoji(_ emoji: String) -> Bool {
        ["👁️", "👁", "👀"].contains(emoji.trimmingCharacters(in: .whitespaces))
    }

    /// Story projection from an ALREADY-VERIFIED event — runs OFF the main
    /// actor via the event-based bridge seam; no frame re-decode (Phase 2).
    private nonisolated static func storyFromGated(
        _ boxedBridge: StatelessBridge
    ) -> @Sendable (GatedFrame) -> StorySlideMirror? {
        { gated in
            guard case .event(let gatedEvent) = gated else { return nil }
            let now = Int64(Date.now.timeIntervalSince1970)
            let bridgeEvent = gatedEvent.event.bridgeEvent(bridge: boxedBridge.bridge)
            guard let slide = boxedBridge.bridge.storyFromEvent(event: bridgeEvent, nowSeconds: now) as? [String: Any],
                  let id = slide["id"] as? String,
                  let pubkey = slide["pubkey"] as? String,
                  let content = slide["content"] as? String,
                  let createdAt = (slide["createdAt"] as? NSNumber)?.int64Value,
                  let expiresAt = (slide["expiresAt"] as? NSNumber)?.int64Value else { return nil }
            return StorySlideMirror(
                id: id, pubkey: pubkey, content: content,
                createdAt: createdAt, expiresAt: expiresAt,
                d: (slide["d"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                imageUrl: (slide["imageUrl"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                imageUrls: ((slide["imageUrls"] as? [Any]) as? [String])?.filter { !$0.isEmpty } ?? [],
                videoUrl: (slide["videoUrl"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                videoPoster: (slide["videoPoster"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                videoDurationMs: (slide["videoDurationMs"] as? NSNumber).flatMap { $0.int64Value > 0 ? $0.int64Value : nil },
                sensitive: (slide["sensitive"] as? Bool) ?? false,
                gradient: (slide["gradient"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                pow: (slide["pow"] as? NSNumber).flatMap { $0.intValue > 0 ? $0.intValue : nil }
            )
        }
    }

    private func absorbSlide(_ mirror: StorySlideMirror) {
        if deletedIds.contains(mirror.id) { return }
        if mirror.pubkey == accountPubkey || followingPubkeys.contains(mirror.pubkey) {
            slidesById[mirror.id] = mirror
        } else {
            publicSlidesById[mirror.id] = mirror
        }
        rebuild()
    }

    private func rebuild() {
        let now = Int64(Date.now.timeIntervalSince1970)
        func grouped(
            _ slides: [StorySlideMirror],
            publicDiscovery: Bool,
            ownFirst: Bool = false
        ) -> [StoryAuthorMirror] {
            Dictionary(grouping: slides, by: \.pubkey)
            .map { pubkey, slides in
                StoryAuthorMirror(
                    pubkey: pubkey,
                    slides: slides.sorted { $0.createdAt > $1.createdAt },
                    isPublicDiscovery: publicDiscovery
                )
            }
            .sorted { lhs, rhs in
                if ownFirst {
                    let lhsIsOwn = lhs.pubkey == accountPubkey
                    let rhsIsOwn = rhs.pubkey == accountPubkey
                    if lhsIsOwn != rhsIsOwn { return lhsIsOwn }
                }
                return lhs.latestAt > rhs.latestAt
            }
        }
        authors = grouped(
            slidesById.values.filter { !$0.isExpired(now: now) },
            publicDiscovery: false,
            ownFirst: true
        )
        publicAuthors = grouped(publicSlidesById.values.filter { !$0.isExpired(now: now) }, publicDiscovery: true)
    }
}
