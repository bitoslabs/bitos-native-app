import BusinessCore
import Foundation
import Observation

/// APP-006 stories store (iOS): mirrors the Android repository through the bridge.
@MainActor
@Observable
final class StoriesStore {
    private(set) var authors: [StoryAuthorMirror] = []
    private(set) var seenIds: Set<String> = []
    private(set) var hasAccount = false

    /// The viewer target (tap a bar avatar to open).
    var viewerTarget: StoryAuthorMirror?

    private let pool: RelayPool
    private let bridge: BusinessCoreBridge
    private var accountPubkey: String?
    private var followingPubkeys: Set<String> = []
    private var watchTask: Task<Void, Never>?
    private var requested = false
    private var slidesById: [String: StorySlideMirror] = [:]
    private var deletedIds: Set<String> = []

    private static let seenKey = "bitos_story_seen_ids"

    struct StorySlideMirror: Identifiable, Equatable, Sendable {
        let id: String
        let pubkey: String
        let content: String
        let createdAt: Int64
        let expiresAt: Int64
        let d: String?
        let imageUrl: String?
        let gradient: String?
        let pow: Int?

        func isExpired(now: Int64) -> Bool { expiresAt <= now }
    }

    struct StoryAuthorMirror: Identifiable, Equatable, Sendable {
        let pubkey: String
        let slides: [StorySlideMirror]
        var id: String { pubkey }
        var latestAt: Int64 { slides.last?.createdAt ?? 0 }
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
        hasAccount = pubkey != nil
        watchTask?.cancel()
        watchTask = nil
        rebuild()
        guard pubkey != nil else { return }
        Task { await start() }
    }

    func markSeen(_ slideId: String) {
        seenIds.insert(slideId)
        UserDefaults.standard.set(Array(seenIds.suffix(1000)), forKey: Self.seenKey)
    }

    func openViewer(_ author: StoryAuthorMirror?) {
        viewerTarget = author
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
                gradient: (slide["gradient"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                pow: (slide["pow"] as? NSNumber).flatMap { $0.intValue > 0 ? $0.intValue : nil }
            )
        }
    }

    private func absorbSlide(_ mirror: StorySlideMirror) {
        if deletedIds.contains(mirror.id) { return }
        slidesById[mirror.id] = mirror
        rebuild()
    }

    private func rebuild() {
        let now = Int64(Date.now.timeIntervalSince1970)
        let active = slidesById.values.filter { !$0.isExpired(now: now) }
        authors = Dictionary(grouping: active, by: \.pubkey)
            .map { pubkey, slides in
                StoryAuthorMirror(pubkey: pubkey, slides: slides.sorted { $0.createdAt > $1.createdAt })
            }
            .sorted { $0.latestAt > $1.latestAt }
    }
}
