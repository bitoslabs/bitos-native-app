import BusinessCore
import Foundation
import Observation

/// Notification kind mirror (shared `NotificationKind`; ordinals locked by
/// `BusinessCoreBridge.extractNotification` — 0 reply … 5 follow).
enum NotificationKind: Sendable, Equatable, CaseIterable {
    case reply, mention, reaction, repost, zap, follow

    /** Shared-enum name used for per-type mute persistence. */
    var name: String {
        switch self {
        case .reply: return "REPLY"
        case .mention: return "MENTION"
        case .reaction: return "REACTION"
        case .repost: return "REPOST"
        case .zap: return "ZAP"
        case .follow: return "FOLLOW"
        }
    }

    var ordinal: Int {
        switch self {
        case .reply: return 0
        case .mention: return 1
        case .reaction: return 2
        case .repost: return 3
        case .zap: return 4
        case .follow: return 5
        }
    }
}

/// One verified relay event targeting the account.
struct NotificationItem: Sendable, Equatable, Identifiable {
    let id: String
    let authorPubkey: String
    let kind: NotificationKind
    let targetEventId: String?
    let summary: String
    let createdAt: Int64
    /** Zap amount in msat (bolt11 HRP) for ZAP items, else nil. */
    let amountMsat: Int64?
}

/// Bounded origin-note preview (APP-012).
struct OriginNote: Sendable, Equatable {
    let id: String
    let authorPubkey: String
    let kind: Int
    let createdAt: Int64
    let excerpt: String
    let thumbUrl: String?
    /** Full bounded content for thread roots. */
    let content: String
    /** Media strip (APP-012): ≤4 image/video URLs. */
    var mediaUrls: [String] = []
    /** NIP-36 flag — the strip renders behind a sensitive cover. */
    var contentWarning: Bool = false
}

enum OriginNoteState: Sendable, Equatable {
    case loading
    case ready(OriginNote)
    case unavailable
}

/// Day-sectioned grouping decoded from the shared-core JSON (APP-012).
struct NotificationGroupRow: Sendable, Equatable, Identifiable {
    let id: String
    let kind: NotificationKind
    let actors: [String]
    let actorCount: Int
    let targetEventId: String?
    let summary: String
    let newestAt: Int64
    let itemIds: [String]
    /** Summed zap msat for ZAP groups, else 0. */
    let msat: Int64
}

struct NotificationDaySection: Sendable, Equatable, Identifiable {
    let epochDay: Int64
    let groups: [NotificationGroupRow]

    var id: Int64 { epochDay }
}

/// Primary tabs + activity chips (ordinals mirror the shared enums).
enum NotificationTab: Int, CaseIterable, Sendable {
    case all = 0, unread, mentions, replies

    var label: String {
        switch self {
        case .all: return "All"
        case .unread: return "Unread"
        case .mentions: return "Mentions"
        case .replies: return "Replies"
        }
    }
}

enum NotificationActivity: Int, CaseIterable, Sendable {
    case none = 0, zaps, likes, reposts, follows

    var label: String {
        switch self {
        case .none: return "All"
        case .zaps: return "Zaps"
        case .likes: return "Likes"
        case .reposts: return "Reposts"
        case .follows: return "Follows"
        }
    }
}

@MainActor
@Observable
final class InboxStore {
    private(set) var items: [NotificationItem] = []
    private(set) var loaded = false
    private(set) var hasAccount = false
    /** True after the head subscription's EOSE (web `connected` parity). */
    private(set) var connected = false
    /** True when the head EOSE deadline expired without a relay answer. */
    private(set) var offline = false
    /** Older-history paging (web `loadMore` parity). */
    private(set) var loadingMore = false
    private(set) var hasMore = true
    private(set) var readIds: Set<String> = []
    private(set) var rawEvents: [String: String] = [:]
    private(set) var origins: [String: OriginNoteState] = [:]
    /** Self-preview per mention/reply id (clean excerpt + media strip). */
    private(set) var previews: [String: OriginNote] = [:]
    /** Per-type mutes (kind names); muted kinds never reach items or counts. */
    private(set) var mutedKinds: Set<NotificationKind> = []
    /** Blocked authors (kind-10004 head) — rows evicted, badge-safe. */
    private(set) var blockedPubkeys: Set<String> = []

    private let pool: RelayPool
    private let bridge: BusinessCoreBridge
    private let defaults: UserDefaults
    private var accountPubkey: String?
    private var watchTask: Task<Void, Never>?
    private var offlineDeadline: Task<Void, Never>?
    private var originBatch = 0
    private var originTimeoutTasks: [String: Task<Void, Never>] = [:]
    private var seen = Set<String>()
    private var blockHeadAt: Int64?
    private var cursorSeconds: Int64 = -1

    /// One `until`-bounded REQ → EOSE/timeout page (web `loadMore`).
    private struct PageBatch {
        let subId: String
        let startedCount: Int
        let expectedRelays: Set<RelayURL>
        let timeoutTask: Task<Void, Never>
        var eoseRelays: Set<RelayURL> = []
    }

    private var activePage: PageBatch?
    private var pageCounter = 0
    private static let readIdsKey = "bitos_notification_read_ids"
    private static let mutedKindsKey = "bitos_notification_muted_kinds"
    private static let cursorKey = "bitos_notification_cursor"
    private static let maxItems = 200
    private static let pageLimit = 60
    private static let headSubId = "bitos-notifications"
    private static let pageTimeoutNanos: UInt64 = 10_000_000_000
    private static let offlineDeadlineNanos: UInt64 = 10_000_000_000
    private static let originTimeoutNanos: UInt64 = 8_000_000_000

    init(
        pool: RelayPool,
        bridge: BusinessCoreBridge = BusinessCoreBridge(),
        defaults: UserDefaults = .standard
    ) {
        self.pool = pool
        self.bridge = bridge
        self.defaults = defaults
    }

    func setAccount(_ pubkey: String?) {
        // Idempotent: the shell wires account changes from app start (badge
        // feed) and the screen re-calls on every appearance.
        if accountPubkey == pubkey, watchTask != nil { return }
        accountPubkey = pubkey
        items.removeAll()
        seen.removeAll()
        rawEvents.removeAll()
        origins.removeAll()
        previews.removeAll()
        originTimeoutTasks.values.forEach { $0.cancel() }
        originTimeoutTasks.removeAll()
        loaded = false
        connected = false
        offline = false
        loadingMore = false
        hasMore = true
        activePage?.timeoutTask.cancel()
        activePage = nil
        offlineDeadline?.cancel()
        offlineDeadline = nil
        hasAccount = pubkey != nil
        readIds = Set(defaults.stringArray(forKey: Self.readIdsKey) ?? [])
        blockedPubkeys = []
        blockHeadAt = nil
        cursorSeconds = defaults.object(forKey: Self.cursorKey) == nil ? -1 : Int64(defaults.integer(forKey: Self.cursorKey))
        mutedKinds = Set((defaults.stringArray(forKey: Self.mutedKindsKey) ?? []).compactMap(NotificationKind.init(name:)))
        watchTask?.cancel()
        watchTask = nil
        guard accountPubkey != nil else { return }
        Task { await start() }
    }

    /// Web `loadMore`: page older history with an `until` REQ (exact batch:
    /// close on all-relay EOSE or the hard timeout).
    func loadMore() {
        guard accountPubkey != nil, !loadingMore, hasMore, activePage == nil else { return }
        guard let oldest = items.map(\.createdAt).min() else { return }
        pageCounter += 1
        let subId = "\(Self.headSubId)-p\(pageCounter)"
        let startedCount = items.count
        loadingMore = true
        Task { [weak self] in
            guard let self else { return }
            let expected = await self.pool.connectedRelays()
            let timeout = Task { [weak self] in
                try? await Task.sleep(nanoseconds: Self.pageTimeoutNanos)
                guard !Task.isCancelled else { return }
                self?.completePage(subId: subId)
            }
            self.activePage = PageBatch(
                subId: subId,
                startedCount: startedCount,
                expectedRelays: expected,
                timeoutTask: timeout
            )
            if let account = self.accountPubkey,
               let request = self.bridge.notificationsRequest(
                   subscriptionId: subId,
                   accountPubkey: account,
                   untilSeconds: oldest - 1,
                   limit: Int32(Self.pageLimit)
               ) as String? {
                await self.pool.broadcast(request)
            }
        }
    }

    /// Web reconnect: re-open the head subscription (fresh snapshot).
    func reconnect() {
        guard accountPubkey != nil else { return }
        if watchTask == nil {
            Task { await start() }
        } else {
            sendHeadRequests()
        }
    }

    private func completePage(subId: String) {
        guard let batch = activePage, batch.subId == subId else { return }
        activePage = nil
        batch.timeoutTask.cancel()
        let close = bridge.close(subscriptionId: subId)
        Task { await pool.broadcast(close) }
        let added = items.count - batch.startedCount
        hasMore = added > 0 && items.count < Self.maxItems
        loadingMore = false
    }

    func isRead(_ item: NotificationItem) -> Bool {
        // Shared cursor rule: explicit marks + everything at/below the
        // persisted cursor (redelivered history never re-rings).
        readIds.contains(item.id) ||
            bridge.notificationCursorIsRead(
                id: item.id,
                createdAtSeconds: item.createdAt,
                cursorSeconds: cursorSeconds,
                explicitlyRead: []
            )
    }

    var unreadCount: Int {
        items.filter { !isRead($0) }.count
    }

    /// Marks one notification (or one aggregated group's ids) read; the
    /// cursor advances to the newest marked item (spec §3.12).
    func markRead(_ ids: [String]) {
        guard !ids.isEmpty else { return }
        readIds.formUnion(ids)
        advanceCursor(to: items.filter { ids.contains($0.id) }.map(\.createdAt).max())
        persistReadIds()
    }

    func markAllRead() {
        advanceCursor(to: items.map(\.createdAt).max())
        markRead(items.map(\.id))
    }

    private func advanceCursor(to newestMarkedAt: Int64?) {
        guard let newestMarkedAt, newestMarkedAt > cursorSeconds else { return }
        cursorSeconds = newestMarkedAt
        defaults.set(Int(newestMarkedAt), forKey: Self.cursorKey)
    }

    /** Per-type mutes: muted kinds drop from items, counts and the badge. */
    func setMutedKinds(_ kinds: Set<NotificationKind>) {
        mutedKinds = kinds
        defaults.set(kinds.map(\.name), forKey: Self.mutedKindsKey)
        // Evict already-collected muted items and forget them so redelivery
        // after unmute re-inserts; read state stays.
        let evicted = items.filter { mutedKinds.contains($0.kind) }
        items.removeAll { mutedKinds.contains($0.kind) }
        seen.subtract(evicted.map(\.id))
    }

    private func persistReadIds() {
        // Bounded: keep the newest 500 read ids.
        let bounded = Array(readIds.suffix(500))
        defaults.set(bounded, forKey: Self.readIdsKey)
    }

    /// Filters items by the shared tab/chip/search rules, then groups them
    /// through the shared core into day sections (contract-locked shape).
    func sections(
        tab: NotificationTab,
        activity: NotificationActivity,
        query: String = "",
        authorNames: [String: String] = [:],
        blocked: Set<String> = []
    ) -> [NotificationDaySection] {
        let filtered = items.filter { item in
            !blocked.contains(item.authorPubkey) && // APP-012 blocked-author filtering (10004 head)
            (query.isEmpty || bridge.notificationQueryMatches(
                summary: item.summary,
                authorName: authorNames[item.authorPubkey],
                query: query
            )) &&
                bridge.notificationTabMatches(kindInt: Int32(item.kind.ordinal), tabOrdinal: Int32(tab.rawValue), isRead: isRead(item)) &&
                bridge.notificationActivityMatches(kindInt: Int32(item.kind.ordinal), activityOrdinal: Int32(activity.rawValue))
        }
        guard !filtered.isEmpty else { return [] }
        let payload = filtered.map { item in
            [
                "id": item.id,
                "authorPubkey": item.authorPubkey,
                "kind": item.kind.ordinal,
                "targetEventId": item.targetEventId ?? "",
                "summary": item.summary,
                "createdAt": item.createdAt,
                "amountMsat": item.amountMsat ?? -1,
            ] as [String: Any]
        }
        guard
            let data = try? JSONSerialization.data(withJSONObject: payload),
            let itemsJson = String(data: data, encoding: .utf8),
            let groupedJson = bridge.groupNotificationsJson(itemsJson: itemsJson, nowSeconds: Int64(Date.now.timeIntervalSince1970)) as String?,
            let groupedData = groupedJson.data(using: .utf8),
            let root = try? JSONSerialization.jsonObject(with: groupedData) as? [String: Any],
            let sectionList = root["sections"] as? [[String: Any]]
        else { return [] }

        return sectionList.compactMap { section in
            guard
                let day = (section["day"] as? NSNumber)?.int64Value,
                let groupList = section["groups"] as? [[String: Any]]
            else { return nil }
            let groups = groupList.compactMap { group -> NotificationGroupRow? in
                guard
                    let id = group["id"] as? String,
                    let kindInt = (group["kind"] as? NSNumber)?.intValue,
                    let kind = NotificationKind(ordinal: kindInt),
                    let actors = group["actors"] as? [String],
                    let actorCount = (group["actorCount"] as? NSNumber)?.intValue,
                    let summary = group["summary"] as? String,
                    let newest = (group["newest"] as? NSNumber)?.int64Value,
                    let itemIds = group["items"] as? [String]
                else { return nil }
                let target = group["target"] as? String
                let msat = (group["msat"] as? NSNumber)?.int64Value ?? 0
                return NotificationGroupRow(
                    id: id,
                    kind: kind,
                    actors: actors,
                    actorCount: actorCount,
                    targetEventId: (target?.isEmpty == true) ? nil : target,
                    summary: summary,
                    newestAt: newest,
                    itemIds: itemIds,
                    msat: msat
                )
            }
            return NotificationDaySection(epochDay: day, groups: groups)
        }
    }

    /// Requests origin-note previews (batched ≤100 per REQ). Missing ids
    /// flip to `.unavailable` after the timeout so rows never spin forever.
    func requestOrigins(_ ids: [String]) {
        guard accountPubkey != nil else { return }
        let batch = ids.filter { origins[$0] == nil }.prefix(100)
        guard !batch.isEmpty else { return }
        for id in batch { origins[id] = .loading }
        if let request = (bridge.eventsByIdsRequest(subscriptionId: "bitos-origin-\(originBatch)", ids: Array(batch)) as String?) {
            originBatch += 1
            Task { await pool.broadcast(request) }
        }
        for id in batch {
            originTimeoutTasks[id] = Task { [weak self] in
                try? await Task.sleep(nanoseconds: Self.originTimeoutNanos)
                guard !Task.isCancelled else { return }
                if self?.origins[id] == .loading {
                    self?.origins[id] = .unavailable
                }
            }
        }
    }

    private func start() async {
        guard watchTask == nil else { return }
        await pool.start()
        sendHeadRequests()
        let stream = await pool.verifiedFrames(client: FrameworkBusinessCoreClient())
        guard !Task.isCancelled, let account = accountPubkey else { return }
        let boxedBridge = StatelessBridge(bridge: bridge)
        watchTask = FrameIngest.pump(
            gated: stream,
            isAlive: { [weak self] in self != nil },
            ingest: Self.notificationIngest(boxedBridge, account: account)
        ) { [weak self] frame in
            await self?.absorb(frame)
        }
    }

    /// Head REQ round (web `start`): notifications + the blocked-author set,
    /// with an offline deadline when no relay answers.
    private func sendHeadRequests() {
        guard let account = accountPubkey else { return }
        connected = false
        offline = false
        hasMore = true
        offlineDeadline?.cancel()
        offlineDeadline = Task { [weak self] in
            try? await Task.sleep(nanoseconds: Self.offlineDeadlineNanos)
            guard !Task.isCancelled, let self else { return }
            if !self.connected { self.offline = true }
        }
        if let request = bridge.notificationsRequest(
            subscriptionId: Self.headSubId,
            accountPubkey: account,
            untilSeconds: 0,
            limit: Int32(Self.pageLimit)
        ) as String? {
            Task { await pool.broadcast(request) }
        }
        // Blocked-author set (kind-10004 head) rides the same round.
        if let request = bridge.blockListRequest(subscriptionId: "bitos-blocks", accountPubkey: account) as String? {
            Task { await pool.broadcast(request) }
        }
    }

    /// Sendable outcome of the shared decode-once gate for one frame.
    private struct InboxFrame: Sendable {
        struct Row: Sendable {
            let id: String
            let authorPubkey: String
            let kindOrdinal: Int
            let targetEventId: String?
            let summary: String
            let createdAt: Int64
            let amountMsat: Int64
        }
        struct BlockHead: Sendable {
            let createdAt: Int64
            let pubkeys: [String]
        }
        let relay: RelayURL
        /// Raw frame kept for the main-actor origin fetches (mention/reply
        /// previews) — they are gated on main-actor state and stay there.
        let message: String
        let eoseSubId: String?
        let notification: Row?
        let blockHead: BlockHead?
    }

    /// Notification/block-head projection from ALREADY-VERIFIED events —
    /// runs OFF the main actor via the event-based bridge seams (Phase 2);
    /// no per-frame re-decode. The pump hops the typed rows back.
    private nonisolated static func notificationIngest(
        _ boxedBridge: StatelessBridge,
        account: String
    ) -> @Sendable (GatedFrame) -> InboxFrame? {
        { gated in
            switch gated {
            case .eose(let subId, let relay):
                return InboxFrame(
                    relay: relay, message: "",
                    eoseSubId: subId, notification: nil, blockHead: nil
                )
            case .event(let gatedEvent):
                let bridgeEvent = gatedEvent.event.bridgeEvent(bridge: boxedBridge.bridge)
                let blockHead: InboxFrame.BlockHead?
                if let list = boxedBridge.bridge.blockListFromEvent(event: bridgeEvent, accountPubkey: account) as? [String: Any] {
                    blockHead = InboxFrame.BlockHead(
                        createdAt: (list["createdAt"] as? NSNumber)?.int64Value ?? 0,
                        pubkeys: (list["pubkeys"] as? [String]) ?? []
                    )
                } else {
                    blockHead = nil
                }
                var row: InboxFrame.Row?
                if let notification = boxedBridge.bridge.extractNotificationFromEvent(event: bridgeEvent, accountPubkey: account) as? [String: Any] {
                    let kind = (notification["kind"] as? KotlinInt).flatMap { NotificationKind(ordinal: $0.intValue) }
                    row = InboxFrame.Row(
                        id: (notification["id"] as? String) ?? UUID().uuidString,
                        authorPubkey: (notification["authorPubkey"] as? String) ?? "",
                        kindOrdinal: kind?.ordinal ?? NotificationKind.mention.ordinal,
                        targetEventId: (notification["targetEventId"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                        summary: (notification["summary"] as? String) ?? "",
                        createdAt: (notification["createdAt"] as? NSNumber)?.int64Value ?? 0,
                        amountMsat: (notification["amountMsat"] as? NSNumber)?.int64Value ?? -1
                    )
                }
                if row == nil, blockHead == nil { return nil }
                return InboxFrame(
                    // The verified event carries its delivery relay URL.
                    relay: gatedEvent.event.relayUrl.flatMap(RelayURL.parse) ?? RelayURL(rawValue: "wss://unknown.relay"),
                    message: gatedEvent.message,
                    eoseSubId: nil,
                    notification: row,
                    blockHead: blockHead
                )
            }
        }
    }

    private func absorb(_ frame: InboxFrame) {
        guard accountPubkey != nil else { return }
        absorbEose(subscriptionId: frame.eoseSubId, relay: frame.relay)
        if let head = frame.blockHead {
            absorbBlockHead(head)
        }
        absorbOrigin(frame)
        guard let row = frame.notification else {
            loaded = true
            return
        }
        let kind = NotificationKind(ordinal: row.kindOrdinal) ?? .mention
        // Per-type mutes never reach items or counts.
        if mutedKinds.contains(kind) { return }
        // Blocked authors never reach items or counts.
        if blockedPubkeys.contains(row.authorPubkey) { return }
        let item = NotificationItem(
            id: row.id,
            authorPubkey: row.authorPubkey,
            kind: kind,
            targetEventId: row.targetEventId,
            summary: row.summary,
            createdAt: row.createdAt,
            amountMsat: row.amountMsat >= 0 ? row.amountMsat : nil
        )
        guard !seen.contains(item.id) else { return }
        // Republished kind-3 lists collapse per author (shared rule).
        if item.kind == .follow, items.contains(where: { $0.kind == .follow && $0.authorPubkey == item.authorPubkey }) {
            return
        }
        seen.insert(item.id)
        items.append(item)
        items.sort { $0.createdAt > $1.createdAt }
        if items.count > Self.maxItems { items.removeLast(items.count - Self.maxItems) }
        rawEvents[item.id] = frame.message
        loaded = true
        // Mention/reply rows render the note itself: cleaned excerpt + a
        // media strip behind the NIP-36 cover (web preview parity).
        if item.kind == .mention || item.kind == .reply {
            previews[item.id] = Self.decodeOriginNote(
                bridge.originNoteFromFrame(
                    message: frame.message,
                    relayUrl: frame.relay.rawValue,
                    wantedIds: [item.id]
                )
            )?.note
        }
    }

    /// Head EOSE → connected; page EOSE → close the batch when all relays answered.
    private func absorbEose(subscriptionId: String?, relay: RelayURL) {
        guard let subId = subscriptionId else { return }
        if subId == activePage?.subId {
            activePage?.eoseRelays.insert(relay)
            if let batch = activePage, !batch.expectedRelays.isEmpty, batch.expectedRelays.isSubset(of: batch.eoseRelays) {
                completePage(subId: subId)
            }
        } else if subId == Self.headSubId {
            connected = true
            offline = false
            offlineDeadline?.cancel()
            offlineDeadline = nil
        }
    }

    /// APP-012 blocked-author filter: newest verified kind-10004 head wins;
    /// arriving heads also evict already-collected rows.
    private func absorbBlockHead(_ head: InboxFrame.BlockHead) {
        guard head.createdAt >= (blockHeadAt ?? Int64.min) else { return }
        blockHeadAt = head.createdAt
        let next = Set(head.pubkeys)
        guard next != blockedPubkeys else { return }
        blockedPubkeys = next
        let evicted = items.filter { next.contains($0.authorPubkey) }
        items.removeAll { next.contains($0.authorPubkey) }
        seen.subtract(evicted.map(\.id))
    }

    /// Kotlin Long boxes as KotlinLong across the bridge; be tolerant.
    /// `nonisolated`: the off-main ingest uses it while boxing typed rows.
    nonisolated private static func int64(_ value: Any?) -> Int64 {
        if let long = value as? KotlinLong { return long.int64Value }
        if let int = value as? KotlinInt { return Int64(int.intValue) }
        if let number = value as? NSNumber { return number.int64Value }
        return 0
    }

    private func absorbOrigin(_ frame: InboxFrame) {
        let loadingIds = origins.filter { $0.value == .loading }.map(\.key)
        guard !loadingIds.isEmpty else { return }
        guard let decoded = Self.decodeOriginNote(
            bridge.originNoteFromFrame(
                message: frame.message,
                relayUrl: frame.relay.rawValue,
                wantedIds: loadingIds
            )
        ), origins[decoded.id] != nil else { return }
        origins[decoded.id] = .ready(decoded.note)
        originTimeoutTasks[decoded.id]?.cancel()
        originTimeoutTasks[decoded.id] = nil
        rawEvents[decoded.id] = frame.message
    }

    /// Bridge map → `OriginNote` (nil when the frame did not match).
    private static func decodeOriginNote(_ raw: Any?) -> (id: String, note: OriginNote)? {
        guard let note = raw as? [String: Any], let id = note["id"] as? String else { return nil }
        return (
            id,
            OriginNote(
                id: id,
                authorPubkey: (note["authorPubkey"] as? String) ?? "",
                kind: (note["kind"] as? KotlinInt)?.intValue ?? 0,
                createdAt: int64(note["createdAt"]),
                excerpt: (note["excerpt"] as? String) ?? "",
                thumbUrl: (note["thumbUrl"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                content: (note["content"] as? String) ?? "",
                mediaUrls: (note["mediaUrls"] as? [String]) ?? [],
                contentWarning: (note["contentWarning"] as? KotlinBoolean)?.boolValue ?? false
            )
        )
    }
}

private extension NotificationKind {
    init?(name: String) {
        switch name {
        case "REPLY": self = .reply
        case "MENTION": self = .mention
        case "REACTION": self = .reaction
        case "REPOST": self = .repost
        case "ZAP": self = .zap
        case "FOLLOW": self = .follow
        default: return nil
        }
    }

    init?(ordinal: Int) {
        switch ordinal {
        case 0: self = .reply
        case 1: self = .mention
        case 2: self = .reaction
        case 3: self = .repost
        case 4: self = .zap
        case 5: self = .follow
        default: return nil
        }
    }
}
